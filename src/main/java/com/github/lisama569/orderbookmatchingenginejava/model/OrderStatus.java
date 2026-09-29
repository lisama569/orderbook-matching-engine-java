package com.github.lisama569.orderbookmatchingenginejava.model;

/** 订单生命周期状态 */
public enum OrderStatus {
    /** 已报入，完全未成交，挂在簿中 */
    NEW,
    /** 部分成交，剩余仍挂在簿中 */
    PARTIALLY_FILLED,
    /** 全部成交 */
    FILLED,
    /** 已撤销（含市价单未成交完的剩余部分） */
    CANCELLED,
    /** 已拒绝（校验/风控未通过，从未进入盘口） */
    REJECTED
}
