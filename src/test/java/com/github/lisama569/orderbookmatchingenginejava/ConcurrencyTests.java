package com.github.lisama569.orderbookmatchingenginejava;

import com.github.lisama569.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.orderbookmatchingenginejava.engine.SubmitResult;
import com.github.lisama569.orderbookmatchingenginejava.event.FileEventStore;
import com.github.lisama569.orderbookmatchingenginejava.model.Order;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.RejectException;
import com.github.lisama569.orderbookmatchingenginejava.model.RejectReason;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;
import com.github.lisama569.orderbookmatchingenginejava.risk.AccountLedger;
import com.github.lisama569.orderbookmatchingenginejava.risk.RiskValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发场景测试：
 * 1) 同一 clientOrderId 被多线程同时提交（内容相同）：只能受理一次、只冻结一次；
 * 2) 并发下单 + 并发撤单混合：盘口剩余量、冻结额、成交量三方对得上账；
 * 3) 撤单竞争（一张单被多个线程同时撤）：恰好一个成功，其余 ORDER_TERMINAL。
 */
class ConcurrencyTests {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTests.class);

    private MatchingEngine engine(AccountLedger ledger, Path tmp) {
        var store = new FileEventStore(tmp.resolve("events.log").toString());
        return new MatchingEngine(store, ledger,
                new RiskValidator(ledger, Set.of("BTC-USD")),
                Set.of("BTC-USD"), "strict");
    }

    /** 让 CountDownLatch.await 可在 Runnable lambda 中使用。 */
    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Test
    void concurrentDuplicateSubmissionsAcceptedOnce(@TempDir Path tmp) throws Exception {
        AccountLedger ledger = new AccountLedger();
        ledger.register("RICH", 100_000L, Map.of());
        var e = engine(ledger, tmp);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SubmitResult>> futures = new ArrayList<>();
        AtomicInteger idempotentHits = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                awaitUnchecked(start);
                SubmitResult r = e.submit("same-id", "RICH", "BTC-USD", Side.BUY,
                        OrderType.LIMIT, 100L, 2L);
                if (r.idempotentReplay()) {
                    idempotentHits.incrementAndGet();
                }
                return r;
            }));
        }
        start.countDown();

        Long orderId = null;
        for (Future<SubmitResult> f : futures) {
            SubmitResult r = f.get();
            if (orderId == null) {
                orderId = r.orderId();
            }
            assertEquals(orderId, r.orderId(), "并发重复提交必须返回同一个订单");
        }
        pool.shutdown();

        long expectedFrozen = 200L; // 2*100，只允许冻结一次
        assertEquals(expectedFrozen, ledger.get("RICH").getFrozenCash(),
                "16 个相同请求并发提交，冻结只能发生一次");
        assertEquals(threads - 1, idempotentHits.get(), "其余请求必须是幂等返回");
        log.info("[CONCURRENT-IDEMPOTENT] input='16线程同clientOrderId同内容' -> "
                + "唯一orderId={} 幂等返回次数={} 冻结={} | 判定依据: 冻结额=2*100 且只一张单",
                orderId, idempotentHits.get(), expectedFrozen);
        ledger.assertInvariants();
    }

    @Test
    void concurrentSubmitAndCancelKeepsBooksConsistent(@TempDir Path tmp) throws Exception {
        AccountLedger ledger = new AccountLedger();
        ledger.register("MARKET", 500_000L, Map.of());
        ledger.register("MM", 0L, Map.of("BTC-USD", 200L));
        var e = engine(ledger, tmp);

        int n = 40;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Long> makerIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long id = i;
            makerIds.add(-1L);
            futures.add(pool.submit(() -> {
                awaitUnchecked(start);
                var r = e.submit("mm-" + id, "MM", "BTC-USD", Side.SELL,
                        OrderType.LIMIT, 100L, 1L);
                makerIds.set((int) id, r.orderId());
                // 一半挂单提交后立刻撤单
                if (id % 2 == 0) {
                    try {
                        e.cancel(r.orderId());
                    } catch (RejectException ignored) {
                        // 已被买方成交则 ORDER_TERMINAL，属合法竞争结果
                    }
                }
            }));
        }
        // 买方并发扫货：总量 30，导致部分成交、部分撤单成功、部分撤单失败混合
        for (int i = 0; i < 6; i++) {
            final int k = i;
            futures.add(pool.submit(() -> {
                awaitUnchecked(start);
                e.submit("sweep-" + k, "MARKET", "BTC-USD", Side.BUY,
                        OrderType.LIMIT, 100L, 5L);
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        // ---- 判定依据：挂单量(40) = 成交量 + 簿内剩余量 + 成功撤销量 ----
        long traded = e.fillsOf("BTC-USD").stream().mapToLong(f -> f.qty()).sum();
        long bookQty = e.bookSnapshot("BTC-USD").asks().stream()
                .mapToLong(a -> a[1]).sum();
        long cancelled = 0;
        for (Long oid : makerIds) {
            Order o = e.getOrder(oid);
            if (o != null && o.getStatus() == OrderStatus.CANCELLED) {
                cancelled += o.remainingQty();
            }
        }
        log.info("[CONCURRENT-MIX] input='40张卖单(偶数随即撤)+买方并发30张' -> "
                + "成交={} 盘口剩余={} 撤单成功={} | 判定依据: 三者之和必须=40",
                traded, bookQty, cancelled);
        assertEquals(40L, traded + bookQty + cancelled,
                "成交量 + 盘口剩余 + 成功撤销量必须等于全部挂单量");

        // ---- 资金侧对账：MM 冻结持仓 + 已售持仓 + 可用持仓 = 初始 200 ----
        var mm = ledger.get("MM");
        long frozen = mm.getFrozenPosition().getOrDefault("BTC-USD", 0L);
        long available = mm.getAvailablePosition().getOrDefault("BTC-USD", 0L);
        long sold = traded; // MM 是唯一卖方
        assertEquals(200L, frozen + available + sold, "持仓总量守恒（冻结/可用/已售）");
        // MM 收到全部货款
        assertEquals(traded * 100L, mm.getAvailableCash(), "卖方货款必须逐笔到账");
        // MARKET：现金 = 初始 - 成交货款，持仓 = 成交量
        var mk = ledger.get("MARKET");
        assertEquals(500_000L - traded * 100L, mk.getAvailableCash() + mk.getFrozenCash(),
                "买方现金守恒");
        assertEquals(traded, mk.getAvailablePosition().getOrDefault("BTC-USD", 0L),
                "买方收到全部成交持仓");
        ledger.assertInvariants();
    }

    @Test
    void concurrentCancelsExactlyOneWins(@TempDir Path tmp) throws Exception {
        AccountLedger ledger = new AccountLedger();
        ledger.register("MM", 0L, Map.of("BTC-USD", 10L));
        var e = engine(ledger, tmp);
        var order = e.submit("only", "MM", "BTC-USD", Side.SELL,
                OrderType.LIMIT, 100L, 5L);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        ConcurrentLinkedQueue<RejectReason> reasons = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                awaitUnchecked(start);
                try {
                    e.cancel(order.orderId());
                    success.incrementAndGet();
                } catch (RejectException ex) {
                    reasons.add(ex.getReason());
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        log.info("[CONCURRENT-CANCEL] input='10线程同时撤同一张单 orderId={}' -> "
                + "成功={} 拒绝原因分布={} | 判定依据: 恰好1个成功，其余ORDER_TERMINAL，冻结全部释放",
                order.orderId(), success.get(), reasons);
        assertEquals(1, success.get(), "并发撤单必须恰好一个成功");
        assertTrue(reasons.stream().allMatch(r -> r == RejectReason.ORDER_TERMINAL),
                "竞争失败方必须都被识别为 ORDER_TERMINAL");
        assertEquals(0L, ledger.get("MM").getFrozenPosition().getOrDefault("BTC-USD", 0L),
                "冻结持仓必须一次性全部释放回可用");
        assertEquals(10L, ledger.get("MM").getAvailablePosition().get("BTC-USD"),
                "撤单后可用持仓恢复为初始值");
        ledger.assertInvariants();
    }
}
