package com.github.lisama569.orderbookmatchingenginejava;

import com.github.lisama569.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.orderbookmatchingenginejava.event.FileEventStore;
import com.github.lisama569.orderbookmatchingenginejava.event.ReplayReport;
import com.github.lisama569.orderbookmatchingenginejava.event.ReplayRejectException;
import com.github.lisama569.orderbookmatchingenginejava.event.ReplayRejectReason;
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

class MarketOrderTests {

    private static final Logger log = LoggerFactory.getLogger(MarketOrderTests.class);

    private MatchingEngine engine(AccountLedger ledger, Path file, String mode) {
        var store = new FileEventStore(file.toString());
        return new MatchingEngine(store, ledger,
                new RiskValidator(ledger, Set.of("BTC-USD")),
                Set.of("BTC-USD"), mode);
    }

    @Test
    void marketOrderPartialFillByCashAndIocExpiry(@TempDir Path tmp) {
        AccountLedger ledger = new AccountLedger();
        ledger.register("B", 250L, Map.of());          // 只买得起 2 张 @100
        ledger.register("S", 0L, Map.of("BTC-USD", 10L));
        var e = engine(ledger, tmp.resolve("e.log"), "strict");

        e.submit("s1", "S", "BTC-USD", Side.SELL, OrderType.LIMIT, 100L, 4L);
        var r = e.submit("m1", "B", "BTC-USD", Side.BUY, OrderType.MARKET, null, 10L);

        log.info("[MARKET-PARTIAL] input='市价买10，现金250，卖盘4@100' -> 成交={} status={} | "
                        + "判定依据: floor(250/100)=2，只能成交2张，剩余IOC作废，现金账户不为负",
                r.totalFilledQty(), r.status());
        assertEquals(2L, r.totalFilledQty());
        assertEquals(50L, ledger.get("B").getAvailableCash());
        assertEquals(0L, ledger.get("B").getFrozenCash());
        assertEquals(2L, ledger.get("B").getAvailablePosition().get("BTC-USD"));
        // 卖方簿子还剩 2 张
        assertEquals(2L, e.bookSnapshot("BTC-USD").asks().get(0)[1]);
        ledger.assertInvariants();
    }

    @Test
    void marketSellFreezesPositionAndSettles(@TempDir Path tmp) {
        AccountLedger ledger = new AccountLedger();
        ledger.register("B", 1_000L, Map.of());
        ledger.register("S", 0L, Map.of("BTC-USD", 5L));
        var e = engine(ledger, tmp.resolve("ms.log"), "strict");

        // 买盘 3@100；卖方以市价卖 3 张全部成交
        e.submit("bid", "B", "BTC-USD", Side.BUY, OrderType.LIMIT, 100L, 3L);
        var r = e.submit("msell", "S", "BTC-USD", Side.SELL, OrderType.MARKET, null, 3L);

        log.info("[MARKET-SELL] input='市价卖3，买盘3@100' -> 成交={} status={} | "
                        + "判定依据: 卖方持仓先冻结3，成交后冻结清零、货款300到账，持仓总量减少3",
                r.totalFilledQty(), r.status());
        assertEquals(3L, r.totalFilledQty());
        assertEquals(300L, ledger.get("S").getAvailableCash());
        assertEquals(0L, ledger.get("S").getFrozenPosition().getOrDefault("BTC-USD", 0L));
        assertEquals(2L, ledger.get("S").getAvailablePosition().get("BTC-USD"));
        assertEquals(3L, ledger.get("B").getAvailablePosition().get("BTC-USD"));
        // 买方现金：冻结 300，按挂单价成交，解冻 300 支付 300，剩 700
        assertEquals(700L, ledger.get("B").getAvailableCash());
        ledger.assertInvariants();
    }

    @Test
    void lenientReplaySkipsBadRecordsWithReasons(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("e.log");
        AccountLedger ledger1 = new AccountLedger();
        ledger1.register("B", 1_000L, Map.of());
        ledger1.register("S", 0L, Map.of("BTC-USD", 5L));
        var e1 = engine(ledger1, file, "strict");
        e1.submit("s1", "S", "BTC-USD", Side.SELL, OrderType.LIMIT, 100L, 1L);
        e1.submit("b1", "B", "BTC-USD", Side.BUY, OrderType.LIMIT, 100L, 1L);

        List<String> lines = Files.readAllLines(file);
        // 制造：seq1, 重复seq1, seq3(跳号), 损坏payload的seq2
        String corrupt = lines.get(0).replace("\"seq\":1", "\"seq\":2")
                .replace("\"ORDER_ACCEPTED\"", "\"NOT_A_TYPE\"");
        Files.write(file, List.of(lines.get(0), lines.get(0), lines.get(1)
                .replace("\"seq\":2", "\"seq\":3"), corrupt));

        // strict 模式必须中止
        AccountLedger ledger2 = ledgerLike();
        assertThrows(ReplayRejectException.class,
                () -> engine(ledger2, file, "strict").replayFromLog());

        // lenient 模式：seq1 正常应用；dup seq1 -> DUPLICATE；seq3 -> OUT_OF_ORDER；
        // 损坏 seq2 -> CORRUPT_PAYLOAD（valueOf NOT_A_TYPE 失败）
        AccountLedger ledger3 = ledgerLike();
        ReplayReport report = engine(ledger3, file, "lenient").replayFromLog();
        log.info("[LENIENT-REPLAY] applied={} skipped={} | 判定依据: 每条坏记录原因互不相同且均未生效",
                report.getAppliedCount(), report.getSkipped());
        assertEquals(1L, report.getAppliedCount());
        assertEquals(ReplayRejectReason.DUPLICATE_SEQUENCE, report.getSkipped().get(1L));
        assertEquals(ReplayRejectReason.OUT_OF_ORDER, report.getSkipped().get(3L));
        assertEquals(ReplayRejectReason.CORRUPT_PAYLOAD, report.getSkipped().get(2L));
    }

    private AccountLedger ledgerLike() {
        AccountLedger ledger = new AccountLedger();
        ledger.register("B", 1_000L, Map.of());
        ledger.register("S", 0L, Map.of("BTC-USD", 5L));
        return ledger;
    }
}
