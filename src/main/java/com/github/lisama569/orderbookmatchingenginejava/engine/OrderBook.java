package com.github.lisama569.orderbookmatchingenginejava.engine;

import com.github.lisama569.orderbookmatchingenginejava.model.Order;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 单合约订单簿。
 *
 * <p>撮合规则：
 * <ul>
 *   <li>价格优先：买盘 {@code bids} 价格降序、卖盘 {@code asks} 价格升序；</li>
 *   <li>时间优先：同一价位用 FIFO {@link ArrayDeque}，队首为最早进入的挂单。
 *       撤单只移除订单不影响队列顺序；改单一律重新进队尾，主动放弃时间优先。</li>
 * </ul>
 *
 * 本类不做撮合与记账，只负责簿子的增删、最优价位访问与快照；
 * 调用方（MatchingEngine）保证在全局锁内使用。
 */
public class OrderBook {

    private final String symbol;
    private final NavigableMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(java.util.Collections.reverseOrder());
    private final NavigableMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    /** 订单进入簿子（限价单受理、部分成交后仍有剩余、或改单重排队）。 */
    public void add(Order order) {
        mapOf(order.getSide())
                .computeIfAbsent(order.getLimitPrice(), p -> new ArrayDeque<>())
                .addLast(order);
    }

    /** 从簿子移除整单（撤单、改单、完全成交后兜底清理）。 */
    public void remove(Order order) {
        NavigableMap<Long, ArrayDeque<Order>> side = mapOf(order.getSide());
        ArrayDeque<Order> level = side.get(order.getLimitPrice());
        if (level != null) {
            level.remove(order);
            if (level.isEmpty()) {
                side.remove(order.getLimitPrice());
            }
        }
    }

    /**
     * 对手方最优挂单：买方吃单看卖一（asks.firstEntry），卖方吃单看买一（bids.firstEntry）。
     * 实现顺手清理队首已终结 / 已无剩余数量的订单（它们在完全成交时应已被 remove，这里只是兜底）。
     */
    public Order bestMaker(Side takerSide) {
        NavigableMap<Long, ArrayDeque<Order>> counter =
                takerSide == Side.BUY ? asks : bids;
        while (!counter.isEmpty()) {
            ArrayDeque<Order> level = counter.firstEntry().getValue();
            while (!level.isEmpty()) {
                Order o = level.peekFirst();
                if (o.isTerminal() || o.remainingQty() <= 0) {
                    level.pollFirst();
                    continue;
                }
                return o;
            }
            counter.pollFirstEntry();
        }
        return null;
    }

    /**
     * 盘口快照：返回两组 {@code [price, aggregateRemainingQty]} 列表，
     * 买盘按价格降序、卖盘按价格升序，用于查询与测试断言。
     */
    public Map<Side, List<long[]>> snapshot() {
        Map<Side, List<long[]>> result = new LinkedHashMap<>();
        result.put(Side.BUY, aggregate(bids));
        result.put(Side.SELL, aggregate(asks));
        return result;
    }

    private List<long[]> aggregate(NavigableMap<Long, ArrayDeque<Order>> side) {
        List<long[]> levels = new ArrayList<>();
        for (Map.Entry<Long, ArrayDeque<Order>> e : side.entrySet()) {
            long total = 0;
            for (Order o : e.getValue()) {
                if (!o.isTerminal()) {
                    total += o.remainingQty();
                }
            }
            if (total > 0) {
                levels.add(new long[]{e.getKey(), total});
            }
        }
        return levels;
    }

    private NavigableMap<Long, ArrayDeque<Order>> mapOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }
}
