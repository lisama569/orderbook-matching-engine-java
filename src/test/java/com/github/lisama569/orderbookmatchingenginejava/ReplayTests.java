package com.github.lisama569.orderbookmatchingenginejava;

import com.github.lisama569.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.orderbookmatchingenginejava.event.FileEventStore;
import com.github.lisama569.orderbookmatchingenginejava.event.ReplayRejectException;
import com.github.lisama569.orderbookmatchingenginejava.event.ReplayRejectReason;
import com.github.lisama569.orderbookmatchingenginejava.model.Account;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;
import com.github.lisama569.orderbookmatchingenginejava.risk.AccountLedger;
import com.github.lisama569.orderbookmatchingenginejava.risk.RiskValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReplayTests {

    private static final Logger log = LoggerFactory.getLogger(ReplayTests.class);

    private MatchingEngine engine(AccountLedger ledger, Path file, String mode) {
        var store = new FileEventStore(file.toString());
        return new MatchingEngine(store, ledger,
                new RiskValidator(ledger, Set.of("BTC-USD")),
                Set.of("BTC-USD"), mode);
    }

    private AccountLedger seedLedger() {
        AccountLedger ledger = new AccountLedger();
        ledger.register("BUYER", 1_000_000L, Map.of());
        ledger.register("SELLER", 0L, Map.of("BTC-USD", 20L));
        return ledger;
    }

    @Test
    void replayAfterRestartReproducesSameBookAndBalances(@TempDir Path tmp) {
        Path file = tmp.resolve("events.log");

        // ---- 第一个“进程”：下单、部分成交、撤单、改单混合 ----
        AccountLedger ledger1 = seedLedger();
        var engine1 = engine(ledger1, file, "strict");
        engine1.submit("ask1", "SELLER", "BTC-USD", Side.SELL, OrderType.LIMIT, 100L, 3L);
        engine1.submit("ask2", "SELLER", "BTC-USD", Side.SELL, OrderType.LIMIT, 101L, 4L);
        var crossed = engine1.submit("bid1", "BUYER", "BTC-USD", Side.BUY,
                OrderType.LIMIT, 101L, 2L);
        assertEquals(2L, crossed.totalFilledQty());
        engine1.submit("bid-rest", "BUYER", "BTC-USD", Side.BUY, OrderType.LIMIT, 99L, 6L);
        // ask1 成交 2 张剩 1 张 -> 撤单；bid-rest 改价改量 -> 改单
        engine1.cancel(1L);
        engine1.modify(5L, 98L, 5L);
        // 再挂一档卖盘供市价单成交：SELLER 初始10张，ask2卖4未动，此处再挂 4@102
        engine1.submit("ask-mkt", "SELLER", "BTC-USD", Side.SELL, OrderType.LIMIT, 102L, 4L);
        // 市价买 2 张（逐笔按购买力成交）；市价卖 1 张吃 bid-rest(已改到98)
        engine1.submit("mkt-buy", "BUYER", "BTC-USD", Side.BUY, OrderType.MARKET, null, 2L);
        engine1.submit("mkt-sell", "SELLER", "BTC-USD", Side.SELL, OrderType.MARKET, null, 1L);

        BookSnapshotView before = snapshot(engine1);
        var buyerBefore = cash(ledger1, "BUYER");
        var sellerBefore = cash(ledger1, "SELLER");

        log.info("[RESTART-BEFORE] book={} buyerCash={} sellerCash={}",
                before, buyerBefore, sellerBefore);

        // ---- 第二个“进程”：空内存 + 同一份事件日志，启动重放 ----
        AccountLedger ledger2 = seedLedger();
        var store2 = new FileEventStore(file.toString());
        var engine2 = new MatchingEngine(store2, ledger2,
                new RiskValidator(ledger2, Set.of("BTC-USD")),
                Set.of("BTC-USD"), "strict");
        var report = engine2.replayFromLog();
        log.info("[REPLAY] appliedCount={} lastSeq={}", report.getAppliedCount(),
                report.getLastSeq());

        assertEquals(before, snapshot(engine2), "重放后盘口必须一致");
        assertEquals(buyerBefore, cash(ledger2, "BUYER"), "重放后买方现金必须一致");
        assertEquals(sellerBefore, cash(ledger2, "SELLER"), "重放后卖方现金必须一致");
        // 重放后新订单号必须延续，不复用旧序号
        var after = engine2.submit("after-restart", "BUYER", "BTC-USD", Side.BUY,
                OrderType.LIMIT, 90L, 1L);
        assertTrue(after.orderId() > report.getLastSeq());
        ledger2.assertInvariants();
    }

    @Test
    void strictReplayRejectsDuplicateAndOutOfOrder(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("events.log");
        AccountLedger ledger = seedLedger();
        var engine1 = engine(ledger, file, "strict");
        engine1.submit("ask1", "SELLER", "BTC-USD", Side.SELL, OrderType.LIMIT, 100L, 1L);
        engine1.submit("bid1", "BUYER", "BTC-USD", Side.BUY, OrderType.LIMIT, 100L, 1L);

        List<String> lines = Files.readAllLines(file);
        // 复制首行制造重复序号
        Files.write(file, List.of(lines.get(0), lines.get(0), lines.get(1)));

        AccountLedger ledger2 = seedLedger();
        var ex = assertThrows(ReplayRejectException.class,
                () -> engine(ledger2, file, "strict").replayFromLog());
        assertEquals(ReplayRejectReason.DUPLICATE_SEQUENCE, ex.getReason());
        log.info("[REPLAY-DUP] 拒绝重复记录 seq={} reason={}", ex.getSeq(), ex.getReason());

        // 制造跳号：1,3
        Files.write(file, List.of(lines.get(0), lines.get(1).replace("\"seq\":2", "\"seq\":3")));
        AccountLedger ledger3 = seedLedger();
        var gap = assertThrows(ReplayRejectException.class,
                () -> engine(ledger3, file, "strict").replayFromLog());
        assertEquals(ReplayRejectReason.OUT_OF_ORDER, gap.getReason());
        log.info("[REPLAY-GAP] 拒绝乱序记录 seq={} reason={}", gap.getSeq(), gap.getReason());
    }

    private record BookSnapshotView(List<List<Long>> bids, List<List<Long>> asks) {
    }

    private static List<List<Long>> normalize(List<long[]> levels) {
        return levels.stream().map(a -> List.of(a[0], a[1])).toList();
    }

    private BookSnapshotView snapshot(MatchingEngine e) {
        var snap = e.bookSnapshot("BTC-USD");
        return new BookSnapshotView(normalize(snap.bids()), normalize(snap.asks()));
    }

    private record CashView(long available, long frozen) {
    }

    private CashView cash(AccountLedger l, String id) {
        Account a = l.get(id);
        return new CashView(a.getAvailableCash(), a.getFrozenCash());
    }
}
