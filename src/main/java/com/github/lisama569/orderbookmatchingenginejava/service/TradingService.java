package com.github.lisama569.orderbookmatchingenginejava.service;

import com.github.lisama569.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.orderbookmatchingenginejava.event.EventStore;
import com.github.lisama569.orderbookmatchingenginejava.model.Account;
import com.github.lisama569.orderbookmatchingenginejava.model.Order;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;
import com.github.lisama569.orderbookmatchingenginejava.model.Trade;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易门面：入账、下单/撤单/改单、盘口与账户查询、模拟重启重放。
 * 负责 DTO 字符串到枚举的转换；所有一致性保证都在 {@link MatchingEngine} 单锁内。
 */
@Service
public class TradingService {

    private final MatchingEngine engine;
    private final EventStore eventStore;

    public TradingService(MatchingEngine engine, EventStore eventStore) {
        this.engine = engine;
        this.eventStore = eventStore;
    }

    public void deposit(String accountId, long cash, long position) {
        engine.addAccount(accountId, cash, position);
    }

    public Map<String, Object> submit(String clientOrderId, String accountId, String symbol,
                                      String side, String type, long price, long quantity) {
        Order o = engine.submit(clientOrderId, accountId, symbol, parseSide(side),
                parseType(type), price, quantity);
        return orderView(o);
    }

    public Map<String, Object> cancel(String clientOrderId, String accountId, long orderId) {
        return orderView(engine.cancel(clientOrderId, accountId, orderId));
    }

    public Map<String, Object> modify(String clientOrderId, String accountId, long orderId,
                                      long newPrice, long newQuantity) {
        return orderView(engine.modify(clientOrderId, accountId, orderId, newPrice, newQuantity));
    }

    public Map<String, Object> orderBook(String symbol) {
        return engine.snapshot(symbol);
    }

    public Account account(String accountId) {
        return engine.getAccount(accountId);
    }

    public List<Map<String, Object>> trades() {
        return engine.trades().stream().map(TradingService::tradeView).toList();
    }

    /**
     * 模拟进程重启：事件存储从本地日志文件重新加载，引擎清空内存运行态后重放，
     * 应得到与重启前同一份盘口和同一份账户余额。
     */
    public void restartAndReplay() {
        eventStore.reloadFromFile();
        engine.replayFromLog();
    }

    public static Map<String, Object> orderView(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.getOrderId());
        m.put("clientOrderId", o.getClientOrderId());
        m.put("accountId", o.getAccountId());
        m.put("symbol", o.getSymbol());
        m.put("side", o.getSide().name());
        m.put("type", o.getType().name());
        m.put("price", o.getPrice());
        m.put("originalQuantity", o.getOriginalQuantity());
        m.put("filledQuantity", o.getFilledQuantity());
        m.put("remainingQuantity", o.getRemainingQuantity());
        m.put("status", o.getStatus().name());
        m.put("frozen", o.getFrozen());
        return m;
    }

    public static Map<String, Object> tradeView(Trade t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradeId", t.tradeId());
        m.put("takerOrderId", t.takerOrderId());
        m.put("makerOrderId", t.makerOrderId());
        m.put("symbol", t.symbol());
        m.put("price", t.price());
        m.put("quantity", t.quantity());
        m.put("timestampMillis", t.timestampMillis());
        return m;
    }

    private Side parseSide(String side) {
        try {
            return Side.valueOf(side == null ? "" : side.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid side: " + side);
        }
    }

    private OrderType parseType(String type) {
        try {
            return OrderType.valueOf(type == null ? "" : type.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid order type: " + type);
        }
    }
}
