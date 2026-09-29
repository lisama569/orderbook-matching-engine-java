package com.github.lisama569.orderbookmatchingenginejava;

import com.github.lisama569.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.orderbookmatchingenginejava.event.FileEventStore;
import com.github.lisama569.orderbookmatchingenginejava.model.Order;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 风控 / 拒绝原因区分测试。
 * 每个用例都打印：输入参数 -> 实际拒绝原因 -> 判定依据，方便人工核对。
 */
class RiskAndRejectTests {

    private static final Logger log = LoggerFactory.getLogger(RiskAndRejectTests.class);

    private MatchingEngine engine(AccountLedger ledger, Path tmp) {
        var store = new FileEventStore(tmp.resolve("events.log").toString());
        return new MatchingEngine(store, ledger,
                new RiskValidator(ledger, Set.of("BTC-USD")),
                Set.of("BTC-USD"), "strict");
    }

    private AccountLedger seed() {
        AccountLedger ledger = new AccountLedger();
        ledger.register("RICH", 1_000L, Map.of());
        ledger.register("HOLDER", 0L, Map.of("BTC-USD", 3L));
        return ledger;
    }

    private void assertReject(String input, MatchingEngine e, RejectReason expected,
                              Runnable action) {
        RejectException ex = assertThrows(RejectException.class, action::run);
        assertEquals(expected, ex.getReason(),
                "输入[" + input + "] 应拒绝为 " + expected + "，实际 " + ex.getReason());
        log.info("[REJECT] input='{}' -> reason={} | 判定依据: {}", input, ex.getReason(),
                ex.getMessage());
    }

    @Test
    void eachRejectReasonIsDistinguishable(@TempDir Path tmp) {
        AccountLedger ledger = seed();
        var e = engine(ledger, tmp);

        assertReject("未知合约", e, RejectReason.INVALID_SYMBOL,
                () -> e.submit("x1", "RICH", "DOGE", Side.BUY, OrderType.LIMIT, 1L, 1L));
        assertReject("限价<=0", e, RejectReason.INVALID_PRICE,
                () -> e.submit("x2", "RICH", "BTC-USD", Side.BUY, OrderType.LIMIT, 0L, 1L));
        assertReject("数量<=0", e, RejectReason.INVALID_QUANTITY,
                () -> e.submit("x3", "RICH", "BTC-USD", Side.BUY, OrderType.LIMIT, 1L, 0L));
        assertReject("缺失clientOrderId", e, RejectReason.INVALID_CLIENT_ORDER_ID,
                () -> e.submit("  ", "RICH", "BTC-USD", Side.BUY, OrderType.LIMIT, 1L, 1L));
        assertReject("未知账户", e, RejectReason.UNKNOWN_ACCOUNT,
                () -> e.submit("x4", "GHOST", "BTC-USD", Side.BUY, OrderType.LIMIT, 1L, 1L));
        assertReject("买资金不足(需2000有1000)", e, RejectReason.INSUFFICIENT_CASH,
                () -> e.submit("x5", "RICH", "BTC-USD", Side.BUY, OrderType.LIMIT, 100L, 20L));
        assertReject("卖持仓不足(需5有2)", e, RejectReason.INSUFFICIENT_POSITION,
                () -> e.submit("x6", "HOLDER", "BTC-USD", Side.SELL, OrderType.LIMIT, 1L, 5L));
        assertReject("市价卖持仓不足(需5有2)", e, RejectReason.INSUFFICIENT_POSITION,
                () -> e.submit("x7", "HOLDER", "BTC-USD", Side.SELL, OrderType.MARKET, null, 5L));

        // clientOrderId 重复：同号不同内容必须拒绝
        var first = e.submit("dup", "RICH", "BTC-USD", Side.BUY, OrderType.LIMIT, 10L, 1L);
        log.info("[ACCEPT] input='clientOrderId=dup price=10 qty=1' -> orderId={} status={}",
                first.orderId(), first.status());
        assertReject("同clientOrderId但内容不同", e, RejectReason.DUPLICATE_CLIENT_ORDER_ID,
                () -> e.submit("dup", "RICH", "BTC-USD", Side.BUY, OrderType.LIMIT, 11L, 1L));

        // 完全相同的重复请求：幂等返回，不二次冻结
        long frozenBefore = ledger.get("RICH").getFrozenCash();
        var replay = e.submit("dup", "RICH", "BTC-USD", Side.BUY, OrderType.LIMIT, 10L, 1L);
        assertTrue(replay.idempotentReplay(), "相同请求应识别为幂等重发");
        assertEquals(frozenBefore, ledger.get("RICH").getFrozenCash(), "幂等重发不得重复冻结");
        log.info("[IDEMPOTENT] input='相同请求重发' -> orderId={} idempotentReplay=true | "
                + "判定依据: 冻结额保持 {} 不变", replay.orderId(), frozenBefore);

        // 撤单 / 改单类原因
        assertReject("撤不存在的单", e, RejectReason.ORDER_NOT_FOUND,
                () -> e.cancel(999L));
        // 先挂一张存活卖单：既用于市价对手盘，成交后终结，也用于“撤已终结单”校验
        var sell = e.submit("hold-sell", "HOLDER", "BTC-USD", Side.SELL,
                OrderType.LIMIT, 10L, 1L);
        var market = e.submit("mkt", "RICH", "BTC-USD", Side.BUY, OrderType.MARKET, null, 1L);
        log.info("[MARKET] input='市价买1 吃卖一' -> orderId={} status={} fills={}",
                market.orderId(), market.status(), market.fills().size());
        assertReject("改市价单", e, RejectReason.MARKET_ORDER_NOT_MODIFIABLE,
                () -> e.modify(market.orderId(), 10L, 1L));
        // 卖单已被市价单吃完 -> 终结
        assertReject("撤已终结单", e, RejectReason.ORDER_TERMINAL,
                () -> e.cancel(sell.orderId()));

        var mod = e.submit("mod-me", "RICH", "BTC-USD", Side.BUY,
                OrderType.LIMIT, 10L, 2L);
        assertReject("改单数量<=已成交(0)但给0", e, RejectReason.INVALID_QUANTITY,
                () -> e.modify(mod.orderId(), 10L, 0L));
        assertReject("改单数量非法(<=filled，未成交时<=0已覆盖，这里用负)",
                e, RejectReason.INVALID_QUANTITY,
                () -> e.modify(mod.orderId(), 10L, -1L));

        ledger.assertInvariants();
    }

    @Test
    void modifyAdjustsFreezeAndRequeues(@TempDir Path tmp) {
        AccountLedger ledger = seed();
        ledger.register("HOLDER2", 0L, Map.of("BTC-USD", 5L));
        ledger.register("TAKER", 1_000L, Map.of());
        var e = engine(ledger, tmp);
        // 同价位两张卖单，验证改单后重排队尾（时间优先丢失）
        var first = e.submit("q1", "HOLDER2", "BTC-USD", Side.SELL, OrderType.LIMIT, 100L, 1L);
        var second = e.submit("q2", "HOLDER2", "BTC-USD", Side.SELL, OrderType.LIMIT, 100L, 1L);
        log.info("[MODIFY-INPUT] 卖一 orderId={} 卖二 orderId={}，同价100各1张",
                first.orderId(), second.orderId());

        // 把卖一数量从1改到2：需要额外冻结1张
        var result = e.modify(first.orderId(), 100L, 2L);
        Order modified = e.getOrder(first.orderId());
        assertEquals(2L, modified.remainingQty());
        assertEquals(3L, ledger.get("HOLDER2").getFrozenPosition().get("BTC-USD"));
        log.info("[MODIFY-RESULT] orderId={} newQty={} frozenPosition=3(q1改后2+q2的1) | 判定依据: 改单重排队尾",
                result.orderId(), result.newOriginalQty());

        // 买方只吃1张：应成交给“原本的卖二”（改单让卖一排到了队尾）
        var take = e.submit("take", "TAKER", "BTC-USD", Side.BUY, OrderType.LIMIT, 100L, 1L);
        assertEquals(1L, take.totalFilledQty());
        long makerId = take.fills().get(0).sellOrderId();
        assertEquals(second.orderId(), makerId, "改单后卖一必须排到卖二之后");
        log.info("[MODIFY-PRIORITY] 吃单成交卖方 orderId={} | 判定依据: 期望卖二 {}（改单丢时间优先）",
                makerId, second.orderId());

        // 改单价非法
        var bid = e.submit("bid-mod", "RICH", "BTC-USD", Side.BUY, OrderType.LIMIT, 90L, 5L);
        var badPrice = assertThrows(RejectException.class,
                () -> e.modify(bid.orderId(), 0L, 5L));
        assertEquals(RejectReason.INVALID_PRICE, badPrice.getReason());

        // 部分成交后，newQty 必须大于已成交
        e.submit("supply", "HOLDER2", "BTC-USD", Side.SELL, OrderType.LIMIT, 90L, 2L);
        Order partiallyFilled = e.getOrder(bid.orderId());
        // bid 在 supply 之前没有对手盘，supply 是后到的吃单方，bid 作为 maker 被动成交
        assertEquals(2L, partiallyFilled.getFilledQty(), "bid 应被动成交2张");
        var badQty = assertThrows(RejectException.class,
                () -> e.modify(bid.orderId(), 90L, 2L));
        assertEquals(RejectReason.INVALID_MODIFY_QUANTITY, badQty.getReason());
        log.info("[MODIFY-PARTIAL] orderId={} filled=2 newQty=2 -> {} | 判定依据: newQty 必须大于 filled",
                bid.orderId(), badQty.getReason());
        ledger.assertInvariants();
    }
}
