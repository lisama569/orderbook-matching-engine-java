package com.github.lisama569.orderbookmatchingenginejava.engine;

import com.github.lisama569.orderbookmatchingenginejava.model.Fill;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderStatus;

import java.util.List;

/**
 * 下单结果。
 * idempotentReplay=true 表示该 clientOrderId 此前已受理，本次为相同请求重发，
 * 直接返回原始结果，不重复冻结、不重复撮合。
 */
public record SubmitResult(long orderId, OrderStatus status, List<Fill> fills,
                           boolean idempotentReplay) {
    public long totalFilledQty() {
        return fills.stream().mapToLong(Fill::qty).sum();
    }
}
