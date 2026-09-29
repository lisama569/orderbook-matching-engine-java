package com.github.lisama569.orderbookmatchingenginejava.model;

/**
 * 委托被拒绝的可区分原因（下单 / 改单 / 撤单共用）。
 * 每种原因都对应一条独立的业务规则，禁止合并成笼统的失败。
 */
public enum RejectReason {
    INVALID_SYMBOL,
    INVALID_PRICE,
    INVALID_QUANTITY,
    INVALID_CLIENT_ORDER_ID,
    UNKNOWN_ACCOUNT,
    DUPLICATE_CLIENT_ORDER_ID,
    INSUFFICIENT_CASH,
    INSUFFICIENT_POSITION,
    ORDER_NOT_FOUND,
    ORDER_TERMINAL,
    MARKET_ORDER_NOT_MODIFIABLE,
    INVALID_MODIFY_QUANTITY
}
