package com.github.lisama569.orderbookmatchingenginejava.event;

/** 事件日志中记录的类型 */
public enum EventType {
    /** 账户入账（可用资金/持仓增加），重放时用于重建账户 */
    ACCOUNT_DEPOSIT,
    /** 委托被接受（进入系统，原始数量），其后可能跟随 0..n 条 TRADE */
    ORDER_ACCEPTED,
    /** 委托在风控/校验阶段被拒绝（审计用，不改变盘口与余额） */
    ORDER_REJECTED,
    /** 一笔成交 */
    TRADE,
    /** 撤单：source=USER 用户主动撤单；source=IOC 市价单剩余自动撤销 */
    ORDER_CANCELLED,
    /** 改单：旧单撤下（失去时间优先），新单以 newOrderId 重新排队并立即参与撮合 */
    ORDER_MODIFIED
}
