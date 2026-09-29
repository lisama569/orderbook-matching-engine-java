package com.github.lisama569.orderbookmatchingenginejava.dto;

/** 改单请求：newQty 为新的目标累计数量；限价单可同时改价。 */
public record ModifyRequest(Long orderId, Long newLimitPrice, Long newQty) {
}
