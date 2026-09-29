package com.github.lisama569.orderbookmatchingenginejava.event;

/**
 * 撤销事件负载。reason 为 USER_CANCEL 或 IOC_EXPIRED（市价单/可选择 IOC 未成交部分到期）。
 */
public record OrderCancelledPayload(long orderId, String reason) {
}
