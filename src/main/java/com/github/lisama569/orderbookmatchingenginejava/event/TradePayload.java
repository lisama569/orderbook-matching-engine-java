package com.github.lisama569.orderbookmatchingenginejava.event;

import com.github.lisama569.orderbookmatchingenginejava.model.Side;

/** 成交事件负载：一笔成交即一次原子的双资金/双持仓结算。 */
public record TradePayload(long tradeId, String symbol, long price, long qty,
                           long buyOrderId, long sellOrderId,
                           String buyAccount, String sellAccount, Side takerSide) {
}
