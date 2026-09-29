package com.github.lisama569.orderbookmatchingenginejava.web.dto;

public record ModifyRequest(
        String clientOrderId,
        String accountId,
        long orderId,
        long newPrice,
        long newQuantity
) {
}
