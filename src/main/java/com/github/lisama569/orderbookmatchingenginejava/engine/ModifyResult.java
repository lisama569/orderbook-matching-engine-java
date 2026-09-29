package com.github.lisama569.orderbookmatchingenginejava.engine;

/** 改单结果：改单成功即重排队，newOriginalQty 为新的目标累计数量。 */
public record ModifyResult(long orderId, long newLimitPrice, long newOriginalQty) {
}
