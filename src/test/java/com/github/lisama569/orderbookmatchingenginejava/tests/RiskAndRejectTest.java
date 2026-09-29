package com.github.lisama569.orderbookmatchingenginejava.tests;

import com.github.lisama569.orderbookmatchingenginejava.exception.RejectReason;
import com.github.lisama569.orderbookmatchingenginejava.exception.TradingException;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 风控与拒绝原因可区分性：每种非法输入/风控失败都必须落到专属 RejectReason，
 * 且失败时账面零改动（不留半更新状态）。
 */
class RiskAndRejectTest extends AbstractEngineTest {

    @Test
    @DisplayName("非法价格 / 非法数量 / 未知账户 / 未知品种 各自原因可区分")
    void distinctStaticValidationReasons() {
        engine.addAccount("A", 1_000_000, 1_000_000);

        assertReason("limit price 0",
                () -> engine.submit("c1", "A", "X", Side.BUY, OrderType.LIMIT, 0, 10),
                RejectReason.ORDER_INVALID_PRICE);
        assertReason("limit price negative",
                () -> engine.submit("c2", "A", "X", Side.BUY, OrderType.LIMIT, -1, 10),
                RejectReason.ORDER_INVALID_PRICE);
        assertReason("qty 0",
                () -> engine.submit("c3", "A", "X", Side.BUY, OrderType.LIMIT, 10, 0),
                RejectReason.ORDER_INVALID_QUANTITY);
        assertReason("qty negative",
                () -> engine.submit("c4", "A", "X", Side.BUY, OrderType.LIMIT, 10, -5),
                RejectReason.ORDER_INVALID_QUANTITY);
        assertReason("unknown account",
                () -> engine.submit("c5", "NOPE", "X", Side.BUY, OrderType.LIMIT, 10, 1),
                RejectReason.ORDER_UNKNOWN_ACCOUNT);
        assertReason("market with non-zero price",
                () -> engine.submit("c6", "A", "X", Side.BUY, OrderType.MARKET, 3, 1),
                RejectReason.ORDER_INVALID_PRICE);
        assertReason("blank symbol",
                () -> engine.submit("c7", "A", "  ", Side.BUY, OrderType.LIMIT, 10, 1),
                RejectReason.ORDER_INVALID_SYMBOL);

        // 全部拒绝后账户分文未动
        var a = engine.getAccount("A");
        assertEquals(1_000_000, a.getCashAvailable());
        assertEquals(0, a.getCashFrozen());
        assertEquals(1_000_000, a.getPositionAvailable());
        assertEquals(0, a.getPositionFrozen());
        log.info("[判定依据] 7 种非法输入分别命中 4 类不同 reason，账户四项余额零变化");
    }

    @Test
    @DisplayName("资金不足与持仓不足是两种不同原因，且拒绝不冻结任何资产")
    void insufficientCashVsPosition() {
        engine.addAccount("POOR_CASH", 50, 100);
        engine.addAccount("POOR_POS", 1_000_000, 3);

        TradingException e1 = assertThrows(TradingException.class, () ->
                engine.submit("cash-x", "POOR_CASH", "X", Side.BUY, OrderType.LIMIT, 10, 100));
        assertEquals(RejectReason.RISK_INSUFFICIENT_CASH, e1.getReason());

        TradingException e2 = assertThrows(TradingException.class, () ->
                engine.submit("pos-x", "POOR_POS", "X", Side.SELL, OrderType.LIMIT, 10, 10));
        assertEquals(RejectReason.RISK_INSUFFICIENT_POSITION, e2.getReason());

        var poorCash = engine.getAccount("POOR_CASH");
        var poorPos = engine.getAccount("POOR_POS");
        assertEquals(50, poorCash.getCashAvailable());
        assertEquals(0, poorCash.getCashFrozen());
        assertEquals(3, poorPos.getPositionAvailable());
        assertEquals(0, poorPos.getPositionFrozen());
        log.info("[输入] 现金 50 想买 100@10 -> {}; 持仓 3 想卖 10 -> {}",
                e1.getReason(), e2.getReason());
        log.info("[判定依据] 两种风控原因独立，拒绝后可用/冻结均未改变");
    }

    @Test
    @DisplayName("clientOrderId 重复提交被专属原因拒绝，且不会二次撮合/冻结")
    void duplicateClientOrderId() {
        engine.addAccount("A", 0, 100);
        engine.addAccount("B", 1_000_000, 0);
        engine.submit("dup-1", "A", "X", Side.SELL, OrderType.LIMIT, 10, 5);

        var first = engine.submit("same-id", "B", "X", Side.BUY, OrderType.LIMIT, 10, 5);
        long tradesAfterFirst = engine.trades().size();
        TradingException ex = assertThrows(TradingException.class, () ->
                engine.submit("same-id", "B", "X", Side.BUY, OrderType.LIMIT, 10, 5));
        assertEquals(RejectReason.ORDER_DUPLICATE_CLIENT_ORDER_ID, ex.getReason());
        assertEquals(tradesAfterFirst, engine.trades().size());
        var b = engine.getAccount("B");
        assertEquals(1_000_000 - 50, b.getCashAvailable());
        assertEquals(first.getOrderId(), 2);
        log.info("[输入] 同一 clientOrderId=same-id 提交两次；第二次 reason={}", ex.getReason());
        log.info("[判定依据] 成交数不增加、买方余额不二次扣减");
    }

    private void assertReason(String label, Runnable r, RejectReason expected) {
        TradingException ex = assertThrows(TradingException.class, r::run, label);
        assertEquals(expected, ex.getReason(), label);
        log.info("[输入:{}] -> reason={}", label, ex.getReason());
    }
}
