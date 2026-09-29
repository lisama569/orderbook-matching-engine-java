package com.github.lisama569.orderbookmatchingenginejava.web.dto;

public record CancelRequest(
        String clientOrderId,
        String accountId,
        long orderId
) {
}
