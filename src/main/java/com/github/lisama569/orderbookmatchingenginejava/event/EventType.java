package com.github.lisama569.orderbookmatchingenginejava.event;

/**
 * 事件日志中只记录“真正改变状态”的事实：
 * 委托受理、成交、委托撤销（含市价 IOC 到期）、委托修改。
 */
public enum EventType {
    ORDER_ACCEPTED,
    TRADE,
    ORDER_CANCELLED,
    ORDER_MODIFIED
}
