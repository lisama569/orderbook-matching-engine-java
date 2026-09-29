package com.github.lisama569.orderbookmatchingenginejava.tests;

import com.github.lisama569.orderbookmatchingenginejava.exception.RejectReason;
import com.github.lisama569.orderbookmatchingenginejava.exception.TradingException;
import com.github.lisama569.orderbookmatchingenginejava.model.Order;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 撤单与改单：冻结/释放对账，终态订单不可再操作，改单失败不留半更新 */
class CancelModifyTest extends AbstractEngineTest {

    @Test
    @DisplayName("撤单释放冻结，重复撤单/撤不存在的单给出不同原因")
    void cancelReleasesFreezeAndRejectsTerminalOrder() {
        engine.addAccount("A", 1_000_000, 0);
        Order o = engine.submit("o1", "A", "X", Side.BUY, OrderType.LIMIT, 10, 5);
        var a = engine.getAccount("A");
        assertEquals(1_000_000 - 50, a.getCashAvailable());
        assertEquals(50, a.getCashFrozen());

        Order cancelled = engine.cancel("cancel-o1", "A", o.getOrderId());
        assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());
        assertEquals(1_000_000, a.getCashAvailable());
        assertEquals(0, a.getCashFrozen());
        log.info("[输入] 挂 5@10 买后撤单 -> status={}, 现金全部退回可用", cancelled.getStatus());

        TradingException terminal = assertThrows(TradingException.class,
                () -> engine.cancel("cancel-o2", "A", o.getOrderId()));
        assertEquals(RejectReason.CANCEL_ORDER_NOT_ACTIVE, terminal.getReason());

        TradingException missing = assertThrows(TradingException.class,
                () -> engine.cancel("cancel-o3", "A", 999_999));
        assertEquals(RejectReason.CANCEL_ORDER_NOT_FOUND, missing.getReason());
        log.info("[判定依据] 再撤终结单 -> {}；撤不存在单 -> {}",
                terminal.getReason(), missing.getReason());
    }

    @Test
    @DisplayName("部分成交后撤单只释放剩余冻结，已成交部分不回滚")
    void cancelAfterPartialFill() {
        engine.addAccount("SELLER", 0, 3);
        engine.addAccount("BUYER", 1_000_000, 0);
        Order buy = engine.submit("big-buy", "BUYER", "X", Side.BUY, OrderType.LIMIT, 10, 10);
        // 卖方只提供 3，买剩余 7 挂簿
        engine.submit("small-sell", "SELLER", "X", Side.SELL, OrderType.LIMIT, 10, 3);

        var buyer = engine.getAccount("BUYER");
        // 成交 3 花 30，剩 7 股 * 10 = 70 仍冻结
        assertEquals(70, buyer.getCashFrozen());
        assertEquals(3, buyer.getPositionAvailable());

        engine.cancel("cancel-big", "BUYER", buy.getOrderId());
        assertEquals(0, buyer.getCashFrozen());
        assertEquals(1_000_000 - 30, buyer.getCashAvailable());
        assertEquals(3, buyer.getPositionAvailable());
        log.info("[输入] 买方 10@10 部分成交 3 后撤剩余 7 -> 冻结 0，只花掉 30");
    }

    @Test
    @DisplayName("改单失去时间优先位置并按新价立即重撮合")
    void modifyRequeuesAndMatches() {
        engine.addAccount("A", 1_000_000, 0);
        engine.addAccount("S", 0, 100);
        engine.submit("old-buy", "A", "X", Side.BUY, OrderType.LIMIT, 9, 10);
        engine.submit("sell-hit", "S", "X", Side.SELL, OrderType.LIMIT, 11, 4);
        assertEquals(0, engine.trades().size());

        Order neo = engine.modify("mod-1", "A", 1L, 11, 10);
        assertEquals(4, neo.getFilledQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, neo.getStatus());
        assertNotEquals(1L, neo.getOrderId());
        log.info("[输入] 10@9 改单为 10@11 -> 立即成交 4@11，剩 6 挂 11 档队尾");

        var a = engine.getAccount("A");
        assertEquals(6 * 11L, a.getCashFrozen());
        assertEquals(1_000_000 - 110L, a.getCashAvailable());
        var asks = engine.snapshot("X");
        log.info("[盘口] {}", asks);
    }

    @Test
    @DisplayName("改单风控失败时旧单原样保留、账面不变")
    void modifyRiskFailureKeepsOldOrder() {
        engine.addAccount("A", 100, 0);
        Order old = engine.submit("buy-1", "A", "X", Side.BUY, OrderType.LIMIT, 10, 5);
        // 可用余额已冻结 50；改成更贵的大单会资金不足
        TradingException ex = assertThrows(TradingException.class,
                () -> engine.modify("mod-bad", "A", old.getOrderId(), 20, 100));
        assertEquals(RejectReason.RISK_MODIFY_INSUFFICIENT_CASH, ex.getReason());

        Order still = engine.getOrder(old.getOrderId());
        assertEquals(OrderStatus.NEW, still.getStatus());
        var a = engine.getAccount("A");
        assertEquals(50, a.getCashAvailable());
        assertEquals(50, a.getCashFrozen());
        log.info("[输入] 5@10 改成 100@20 -> {}；旧单仍 NEW，冻结仍为 50", ex.getReason());
    }

    @Test
    @DisplayName("改单非法价量给出专属原因")
    void modifyInvalidArguments() {
        engine.addAccount("A", 1_000_000, 0);
        Order o = engine.submit("m-target", "A", "X", Side.BUY, OrderType.LIMIT, 10, 5);
        assertEquals(RejectReason.MODIFY_INVALID_PRICE, assertThrows(TradingException.class,
                () -> engine.modify("mp", "A", o.getOrderId(), 0, 5)).getReason());
        assertEquals(RejectReason.MODIFY_INVALID_QUANTITY, assertThrows(TradingException.class,
                () -> engine.modify("mq", "A", o.getOrderId(), 10, 0)).getReason());
        assertEquals(RejectReason.MODIFY_ORDER_NOT_FOUND, assertThrows(TradingException.class,
                () -> engine.modify("mn", "A", 404L, 10, 5)).getReason());
        log.info("[判定依据] 改单的坏价/坏量/不存在分别命中独立 reason");
    }

    @Test
    @DisplayName("改单释放的冻结可被新单复用：缩量后再放量，账面守恒")
    void modifyReusesReleasedFreeze() {
        engine.addAccount("A", 100, 0);
        Order first = engine.submit("m-buy", "A", "X", Side.BUY, OrderType.LIMIT, 10, 5);
        // 冻结 50，可用 50
        var smaller = engine.modify("m-shrink", "A", first.getOrderId(), 10, 3);
        var a = engine.getAccount("A");
        assertEquals(70, a.getCashAvailable());
        assertEquals(30, a.getCashFrozen());
        log.info("[输入] 5@10 缩量改成 3@10 -> 可用 {}，冻结 {}",
                a.getCashAvailable(), a.getCashFrozen());

        // 再放量成 6@10：需要 60，可用池 = 可用 70 + 旧冻结 30 = 100，足够
        var bigger = engine.modify("m-grow", "A", smaller.getOrderId(), 10, 6);
        assertEquals(6, bigger.getRemainingQuantity());
        assertEquals(40, a.getCashAvailable());
        assertEquals(60, a.getCashFrozen());
        assertEquals(100, a.getCashAvailable() + a.getCashFrozen());
        log.info("[判定依据] 再放量 6@10 成功：可用 40 + 冻结 60 = 100，总额守恒");
    }
}
