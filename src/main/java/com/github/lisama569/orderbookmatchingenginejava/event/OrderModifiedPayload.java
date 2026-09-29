package com.github.lisama569.orderbookmatchingenginejava.event;

/**
 * 改单事件负载：改单一律重排队（丢失时间优先）。
 * newQty 为新的目标累计数量（必须 > 已成交数量）。
 */
public record OrderModifiedPayload(long orderId, long newLimitPrice, long newQty) {
}
