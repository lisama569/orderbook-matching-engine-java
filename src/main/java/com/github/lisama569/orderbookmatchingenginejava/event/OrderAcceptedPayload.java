package com.github.lisama569.orderbookmatchingenginejava.event;

import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;

/** 委托受理事件负载（冻结在受理时完成）。 */
public record OrderAcceptedPayload(long orderId, String clientOrderId, String account,
                                   String symbol, Side side, OrderType type,
                                   long limitPrice, long originalQty) {
}
