package com.github.lisama569.orderbookmatchingenginejava.engine;

import com.github.lisama569.orderbookmatchingenginejava.model.Order;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 单品种盘口：价格优先、同价时间优先（FIFO）。
 * 买盘价格从高到低，卖盘价格从低到高；非线程安全，由 MatchingEngine 的单锁保护。
 */
final class OrderBook {

    /** 价格 -> 该价位按时间排列的订单队列 */
    private final NavigableMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(java.util.Collections.reverseOrder());
    private final NavigableMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();
    /** orderId -> 所在价格，便于 O(log n) 撤单 */
    private final java.util.HashMap<Long, Long> orderPrice = new java.util.HashMap<>();

    /** 撮合成交回调：返回与 maker 的成交量，0 表示跳过该 maker（如自成交防止，排队位置保持不变） */
    @FunctionalInterface
    interface MakerMatcher {
        long match(Order maker);
    }

    /** 把一笔剩余数量大于 0 的限价单加入簿中队尾 */
    void add(Order order) {
        (order.getSide() == Side.BUY ? bids : asks)
                .computeIfAbsent(order.getPrice(), p -> new ArrayDeque<>())
                .addLast(order);
        orderPrice.put(order.getOrderId(), order.getPrice());
    }

    /** 撤销，成功返回被移除的订单；簿中不存在返回 null */
    Order remove(long orderId) {
        Long price = orderPrice.remove(orderId);
        if (price == null) {
            return null;
        }
        NavigableMap<Long, ArrayDeque<Order>> side = bids.containsKey(price) ? bids : asks;
        ArrayDeque<Order> level = side.get(price);
        if (level == null) {
            return null;
        }
        Iterator<Order> it = level.iterator();
        while (it.hasNext()) {
            Order o = it.next();
            if (o.getOrderId() == orderId) {
                it.remove();
                if (level.isEmpty()) {
                    side.remove(price);
                }
                return o;
            }
        }
        return null;
    }

    boolean contains(long orderId) {
        return orderPrice.containsKey(orderId);
    }

    /**
     * 用 taker 顺序吃对手盘。价格限制：买单要求 makerPrice &lt;= limitPrice，
     * 卖单要求 makerPrice &gt;= limitPrice；市价单不设价格门槛。
     * maker 被全部成交时由本方法出队；部分成交则留在队首保持剩余的时间优先位置。
     */
    void match(Order taker, boolean marketOrder, MakerMatcher matcher) {
        boolean buy = taker.getSide() == Side.BUY;
        NavigableMap<Long, ArrayDeque<Order>> counter = buy ? asks : bids;

        List<Long> priceView = new ArrayList<>(counter.navigableKeySet());
        outer:
        for (Long price : priceView) {
            if (!marketOrder) {
                if (buy && price > taker.getPrice()) {
                    break;
                }
                if (!buy && price < taker.getPrice()) {
                    break;
                }
            }
            ArrayDeque<Order> level = counter.get(price);
            if (level == null || level.isEmpty()) {
                continue;
            }
            Iterator<Order> it = level.iterator();
            while (it.hasNext()) {
                if (taker.getRemainingQuantity() == 0) {
                    break outer;
                }
                Order maker = it.next();
                long traded = matcher.match(maker);
                if (traded <= 0) {
                    continue;
                }
                if (maker.getRemainingQuantity() == 0) {
                    it.remove();
                    orderPrice.remove(maker.getOrderId());
                }
            }
            if (level.isEmpty()) {
                counter.remove(price);
            }
        }
    }

    /**
     * 市价买单最差成本估算：从最低卖价起逐档累加，最多 needQty，
     * 返回 [需要冻结的现金额, 可成交数量]（无价格门槛）。
     */
    long[] marketableAsk(long needQty, long limitPrice) {
        long qty = 0;
        long cash = 0;
        for (var e : asks.entrySet()) {
            if (limitPrice != Long.MAX_VALUE && e.getKey() > limitPrice) {
                break;
            }
            for (Order o : e.getValue()) {
                long take = Math.min(needQty - qty, o.getRemainingQuantity());
                qty += take;
                cash += take * e.getKey();
                if (qty >= needQty) {
                    return new long[]{cash, qty};
                }
            }
        }
        return new long[]{cash, qty};
    }

    /** 市价卖单可成交持仓估算：从最高买价起逐档累加，最多 needQty，返回 [0, 可成交数量] */
    long[] marketableBid(long needQty, long limitPrice) {
        long qty = 0;
        for (var e : bids.entrySet()) {
            if (limitPrice != 0L && e.getKey() < limitPrice) {
                break;
            }
            for (Order o : e.getValue()) {
                long take = Math.min(needQty - qty, o.getRemainingQuantity());
                qty += take;
                if (qty >= needQty) {
                    return new long[]{0L, qty};
                }
            }
        }
        return new long[]{0L, qty};
    }

    Order bestBid() {
        while (!bids.isEmpty()) {
            var e = bids.firstEntry();
            if (!e.getValue().isEmpty()) {
                return e.getValue().peekFirst();
            }
            bids.remove(e.getKey());
        }
        return null;
    }

    Order bestAsk() {
        while (!asks.isEmpty()) {
            var e = asks.firstEntry();
            if (!e.getValue().isEmpty()) {
                return e.getValue().peekFirst();
            }
            asks.remove(e.getKey());
        }
        return null;
    }

    /** 买方档位摘要 [[price, aggregatedQuantity], ...]，从高到低 */
    List<long[]> bidLevels() {
        return levels(bids);
    }

    /** 卖方档位摘要 [[price, aggregatedQuantity], ...]，从低到高 */
    List<long[]> askLevels() {
        return levels(asks);
    }

    private List<long[]> levels(NavigableMap<Long, ArrayDeque<Order>> side) {
        List<long[]> result = new ArrayList<>();
        for (var e : side.entrySet()) {
            long qty = 0;
            for (Order o : e.getValue()) {
                qty += o.getRemainingQuantity();
            }
            if (qty > 0) {
                result.add(new long[]{e.getKey(), qty});
            }
        }
        return result;
    }
}
