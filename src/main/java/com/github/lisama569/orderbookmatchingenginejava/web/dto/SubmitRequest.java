package com.github.lisama569.orderbookmatchingenginejava.web.dto;

/** 下单请求；市价单 price 可传 0 */
public record SubmitRequest(
        String clientOrderId,
        String accountId,
        String symbol,
        String side,
        String type,
        long price,
        long quantity
) {
}
