package com.github.lisama569.orderbookmatchingenginejava.model;

/** 一笔成交：taker（主动单）与 maker（簿中挂单）之间 */
public record Trade(
        long tradeId,
        long takerOrderId,
        long makerOrderId,
        String symbol,
        long price,
        long quantity,
        long timestampMillis
) {
}
