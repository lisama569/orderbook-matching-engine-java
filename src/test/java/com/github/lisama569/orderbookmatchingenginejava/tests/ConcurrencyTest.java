package com.github.lisama569.orderbookmatchingenginejava.tests;

import com.github.lisama569.orderbookmatchingenginejava.exception.RejectReason;
import com.github.lisama569.orderbookmatchingenginejava.exception.TradingException;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发场景：N 个买方线程与一个挂单卖方撮合、重复提交、并发撤单/改单。
 * 最终盘口与账户必须守恒：卖方持仓去向 = 买方持仓之和 + 簿中冻结；现金总量守恒。
 */
class ConcurrencyTest extends AbstractEngineTest {

    private static final int THREADS = 16;

    @Test
    @DisplayName("并发买入同一卖方盘口：成交/剩余/余额三方守恒，无超卖与重复成交")
    void concurrentBuysAgainstOneSellerAreConsistent() throws Exception {
        int sellerQty = THREADS * 10;
        engine.addAccount("SELLER", 0, 100_000);
        engine.submit("seller-wall", "SELLER", "BTC", Side.SELL,
                OrderType.LIMIT, 100, sellerQty);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Long> filled = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    // 每个买方都有充足现金，挂限价买 15@100：总共需求 240 > 卖方 160，
                    // 所以恰好成交 160，剩余的买单挂簿，不允许出现负持仓/超卖
                    engine.addAccount("BUYER-" + idx, 100_000, 0);
                    var o = engine.submit("buy-" + idx, "BUYER-" + idx, "BTC",
                            Side.BUY, OrderType.LIMIT, 100, 15);
                    filled.add(o.getFilledQuantity());
                } catch (TradingException ex) {
                    rejected.incrementAndGet();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        long totalFilled = filled.stream().mapToLong(Long::longValue).sum();
        log.info("[输入] 卖方墙 {}@100；{} 个买方各买 15@100 并发提交", sellerQty, THREADS);
        log.info("[撮合结果] 总成交={}, 被拒委托数={}, 各买方成交={}",
                totalFilled, rejected.get(), filled);

        // 守恒 1：总成交恰好等于卖方供给，没有超卖
        assertEquals(sellerQty, totalFilled);
        assertEquals(0, rejected.get());

        long buyerPosition = 0;
        long buyerFrozenCash = 0;
        long buyerSpent = 0;
        long restingBidQty = 0;
        for (int i = 0; i < THREADS; i++) {
            var a = engine.getAccount("BUYER-" + i);
            buyerPosition += a.getPositionAvailable();
            buyerFrozenCash += a.getCashFrozen();
            buyerSpent += 100_000 - a.getCashAvailable() - a.getCashFrozen();
        }
        for (long[] lvl : bidLevels()) {
            restingBidQty += lvl[1];
        }

        // 守恒 2：买方持仓之和 = 总成交；簿中买余量 = 总需求-总成交，对应现金全部冻结
        assertEquals(sellerQty, buyerPosition);
        assertEquals(THREADS * 15L - sellerQty, restingBidQty);
        assertEquals(restingBidQty * 100L, buyerFrozenCash);
        // 守恒 3：现金总量（买方花费）= 卖方收入
        assertEquals(sellerQty * 100L, buyerSpent);
        var seller = engine.getAccount("SELLER");
        assertEquals(sellerQty * 100L, seller.getCashAvailable());
        assertEquals(100_000 - sellerQty, seller.getPositionAvailable());
        assertEquals(0, seller.getPositionFrozen());

        log.info("[判定依据] 卖方持仓 {} 全部卖出且收入 {}；买方持仓合计 {}；买盘剩余 {} 股现金冻结 {}",
                sellerQty, seller.getCashAvailable(), buyerPosition,
                restingBidQty, buyerFrozenCash);
    }

    @Test
    @DisplayName("同一 clientOrderId 并发重复提交：恰好一单生效，其余拿到同一拒绝原因")
    void concurrentDuplicateSubmissions() throws Exception {
        engine.addAccount("SELLER", 0, 100);
        engine.submit("s", "SELLER", "X", Side.SELL, OrderType.LIMIT, 10, 50);
        // 买方账户由所有线程共用，必须在并发开始前只入账一次
        engine.addAccount("DUP-BUYER", 1_000_000, 0);

        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> reasons = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    engine.submit("same-client-id", "DUP-BUYER", "X",
                            Side.BUY, OrderType.LIMIT, 10, 10);
                    success.incrementAndGet();
                } catch (TradingException ex) {
                    reasons.add(ex.getReason().name());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        log.info("[输入] {} 个线程用同一 clientOrderId 并发下单 -> 成功 {}，拒绝原因集合 {}",
                n, success.get(), reasons);
        assertEquals(1, success.get(), "exactly one submission may take effect");
        assertEquals(Set.of(RejectReason.ORDER_DUPLICATE_CLIENT_ORDER_ID.name()), reasons);
        // 只有一单成交 10 股，卖方只收到 100 元，没有重复扣账
        var seller = engine.getAccount("SELLER");
        assertEquals(100, seller.getCashAvailable());
        var buyer = engine.getAccount("DUP-BUYER");
        assertEquals(1_000_000 - 100, buyer.getCashAvailable());
        assertEquals(10, buyer.getPositionAvailable());
    }

    @Test
    @DisplayName("并发撤单与改单作用于同一订单：只有一个成功，其余拿到可区分的终结态原因")
    void concurrentCancelAndModify() throws Exception {
        engine.addAccount("A", 1_000_000, 0);
        var order = engine.submit("target-order", "A", "X", Side.BUY,
                OrderType.LIMIT, 10, 10);

        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> reasons = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger modified = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    if (idx % 2 == 0) {
                        var o = engine.cancel("cancel-" + idx, "A", order.getOrderId());
                        if (o.getStatus() == OrderStatus.CANCELLED) {
                            cancelled.incrementAndGet();
                        }
                    } else {
                        var o = engine.modify("modify-" + idx, "A", order.getOrderId(), 11, 10);
                        if (o.getStatus() == OrderStatus.NEW
                                || o.getStatus() == OrderStatus.PARTIALLY_FILLED) {
                            modified.incrementAndGet();
                        }
                    }
                } catch (TradingException ex) {
                    reasons.add(ex.getReason().name());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        int effective = cancelled.get() + modified.get();
        log.info("[输入] {} 个线程并发对同一订单撤单/改单 -> 撤单生效 {}，改单生效 {}，失败原因 {}",
                n, cancelled.get(), modified.get(), reasons);
        assertEquals(1, effective, "cancel and modify are mutually exclusive");
        assertTrue(reasons.stream().allMatch(r ->
                r.equals(RejectReason.CANCEL_ORDER_NOT_ACTIVE.name())
                        || r.equals(RejectReason.MODIFY_ORDER_NOT_ACTIVE.name())
                        || r.equals(RejectReason.CANCEL_ORDER_NOT_FOUND.name())
                        || r.equals(RejectReason.MODIFY_ORDER_NOT_FOUND.name())),
                "failures must be distinguishable terminal-order reasons: " + reasons);

        var a = engine.getAccount("A");
        // 无论谁赢，账面都必须自洽：冻结 + 可用 = 初始 1_000_000（无成交）
        assertEquals(1_000_000, a.getCashAvailable() + a.getCashFrozen());
        log.info("[判定依据] 可用+冻结={} 守恒；失败原因集合={}",
                a.getCashAvailable() + a.getCashFrozen(), reasons);
    }

    @SuppressWarnings("unchecked")
    private List<long[]> bidLevels() {
        return (List<long[]>) engine.snapshot("BTC").get("bids");
    }
}
