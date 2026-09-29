package com.github.lisama569.orderbookmatchingenginejava.engine;

/** 撤单结果：cancelledQty 为本次实际撤销并释放冻结的剩余数量。 */
public record CancelResult(long orderId, long cancelledQty) {
}
