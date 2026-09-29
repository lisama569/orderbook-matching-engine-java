package com.github.lisama569.orderbookmatchingenginejava;

import com.github.lisama569.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.orderbookmatchingenginejava.event.FileEventStore;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;
import com.github.lisama569.orderbookmatchingenginejava.risk.AccountLedger;
import com.github.lisama569.orderbookmatchingenginejava.risk.RiskValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SmokeTests {

    @Test
    void smokeMatchAndCancel(@TempDir Path tmp) {
        AccountLedger ledger = new AccountLedger();
        ledger.register("BUYER", 1_000_000L, Map.of());
        ledger.register("SELLER", 0L, Map.of("BTC-USD", 10L));
        var store = new FileEventStore(tmp.resolve("events.log").toString());
        var engine = new MatchingEngine(store, ledger,
                new RiskValidator(ledger, Set.of("BTC-USD")), Set.of("BTC-USD"), "strict");

        var sell = engine.submit("s1", "SELLER", "BTC-USD", Side.SELL,
                OrderType.LIMIT, 100L, 5L);
        assertEquals(0, sell.totalFilledQty());

        var buy = engine.submit("b1", "BUYER", "BTC-USD", Side.BUY,
                OrderType.LIMIT, 100L, 8L);
        assertEquals(5L, buy.totalFilledQty(), "partial fill expected");

        // BUYER: paid 500, frozen 300 remains for 3 unfilled
        assertEquals(999_200L, ledger.get("BUYER").getAvailableCash());
        assertEquals(300L, ledger.get("BUYER").getFrozenCash());
        // SELLER: 5 张全部成交，冻结持仓归零（条目被清除），可用持仓仍为未挂单的 5 张
        assertNull(ledger.get("SELLER").getFrozenPosition().get("BTC-USD"));
        assertEquals(5L, ledger.get("SELLER").getAvailablePosition().get("BTC-USD"));
        assertEquals(500L, ledger.get("SELLER").getAvailableCash());

        engine.cancel(buy.orderId());
        assertEquals(999_500L, ledger.get("BUYER").getAvailableCash());
        assertEquals(0L, ledger.get("BUYER").getFrozenCash());
        ledger.assertInvariants();
    }
}
