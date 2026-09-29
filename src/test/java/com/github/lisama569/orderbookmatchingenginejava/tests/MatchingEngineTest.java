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
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 基础撮合规则：价格优先、同价时间优先、部分成交、市价单 IOC、自成交防止 */
class MatchingEngineTest extends AbstractEngineTest {

    @Test
    @DisplayName("价格优先 + 同价时间优先 + 部分成交")
    void priceThenTimePriorityWithPartialFill() {
        engine.addAccount("A", 0, 300);
        engine.addAccount("B", 1_000_000, 0);
        // 卖一 100@10 先挂，卖二 50@11，再补一个 100@10 的晚到卖单
        engine.submit("c-s1", "A", "BTC", Side.SELL, OrderType.LIMIT, 10, 100);
        engine.submit("c-s2", "A", "BTC", Side.SELL, OrderType.LIMIT, 11, 50);
        // 同价 FIFO：第一档 100@10 只有一条，买单 120 会先吃完它再去 11 档

        // 买方限价 11 买入 120：先吃 10@10 的 100，再吃 11 的 20
        Order buy = engine.submit("c-b1", "B", "BTC", Side.BUY, OrderType.LIMIT, 11, 120);

        log.info("[输入] 卖单 100@10, 50@11；买单 120@11(限价)");
        log.info("[撮合结果] buy filled={}, remaining={}, status={}",
                buy.getFilledQuantity(), buy.getRemainingQuantity(), buy.getStatus());
        log.info("[判定依据] 期望成交 100@10 + 20@11，买方均价=(100*10+20*11)/120");

        assertEquals(120, buy.getFilledQuantity());
        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(2, engine.trades().size());
        assertEquals(10, engine.trades().get(0).price());
        assertEquals(100, engine.trades().get(0).quantity());
        assertEquals(11, engine.trades().get(1).price());
        assertEquals(20, engine.trades().get(1).quantity());

        // 账户对账：买方冻结 120*11=1320，实际花 1220，滑点差额 100 退回可用
        var b = engine.getAccount("B");
        assertEquals(1_000_000 - 1220, b.getCashAvailable());
        assertEquals(0, b.getCashFrozen());
        assertEquals(120, b.getPositionAvailable());
        // 卖方：120 持仓被买走，30 持仓仍冻结在 11 档
        var a = engine.getAccount("A");
        assertEquals(1220, a.getCashAvailable());
        assertEquals(30, a.getPositionFrozen());
        assertEquals(150, a.getPositionAvailable());
    }

    @Test
    @DisplayName("未成交限价单进入盘口，买高卖低排序")
    void restingOrdersBuildBook() {
        engine.addAccount("A", 1_000_000, 1_000_000);
        engine.submit("s1", "A", "X", Side.SELL, OrderType.LIMIT, 12, 5);
        engine.submit("s2", "A", "X", Side.SELL, OrderType.LIMIT, 10, 3);
        engine.submit("b1", "A", "X", Side.BUY, OrderType.LIMIT, 8, 7);

        var snap = engine.snapshot("X");
        log.info("[输入] SELL 5@12, SELL 3@10, BUY 7@8");
        log.info("[盘口] bids={}, asks={}", snap.get("bids"), snap.get("asks"));
        log.info("[判定依据] asks 应 3@10 在前；bids 为 7@8；无成交");

        assertEquals(0, engine.trades().size());
        @SuppressWarnings("unchecked")
        java.util.List<long[]> asks = (java.util.List<long[]>) snap.get("asks");
        @SuppressWarnings("unchecked")
        java.util.List<long[]> bids = (java.util.List<long[]>) snap.get("bids");
        assertEquals(10L, asks.get(0)[0]);
        assertEquals(8L, bids.get(0)[0]);
    }

    @Test
    @DisplayName("市价单按 IOC 撮合成交，剩余撤销并释放冻结")
    void marketOrderIoc() {
        engine.addAccount("A", 0, 100);
        engine.addAccount("B", 1_000_000, 0);
        engine.submit("s1", "A", "BTC", Side.SELL, OrderType.LIMIT, 10, 30);

        Order market = engine.submit("m1", "B", "BTC", Side.BUY, OrderType.MARKET, 0, 50);
        log.info("[输入] 卖 30@10 + 市价买 50");
        log.info("[撮合结果] filled={}, status={}", market.getFilledQuantity(), market.getStatus());
        log.info("[判定依据] 市价 IOC：成交 30，剩 20 撤销；买方只花 300，冻结不留尾");

        assertEquals(30, market.getFilledQuantity());
        assertEquals(OrderStatus.CANCELLED, market.getStatus());
        var b = engine.getAccount("B");
        assertEquals(1_000_000 - 300, b.getCashAvailable());
        assertEquals(0, b.getCashFrozen());
    }

    @Test
    @DisplayName("市价单无对手流动性必须被拒绝")
    void marketOrderWithoutLiquidityRejected() {
        engine.addAccount("B", 1_000_000, 0);
        TradingException ex = assertThrows(TradingException.class, () ->
                engine.submit("m2", "B", "BTC", Side.BUY, OrderType.MARKET, 0, 10));
        log.info("[输入] 空盘口市价买 10 -> reason={}", ex.getReason());
        assertEquals(RejectReason.MARKET_NO_LIQUIDITY, ex.getReason());
    }

    @Test
    @DisplayName("同账户对手单不成交（自成交防止），留在簿中")
    void selfTradePrevented() {
        engine.addAccount("A", 1_000_000, 1_000_000);
        engine.submit("self-s", "A", "X", Side.SELL, OrderType.LIMIT, 10, 5);
        Order selfBuy = engine.submit("self-b", "A", "X", Side.BUY, OrderType.LIMIT, 10, 5);
        log.info("[输入] 同一账户 A 先卖后买各 5@10 -> status={}, trades={}",
                selfBuy.getStatus(), engine.trades().size());
        assertEquals(OrderStatus.NEW, selfBuy.getStatus());
        assertEquals(0, engine.trades().size());
    }
}
