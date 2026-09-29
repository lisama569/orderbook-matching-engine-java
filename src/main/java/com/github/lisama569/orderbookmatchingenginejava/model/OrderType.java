package com.github.lisama569.orderbookmatchingenginejava.model;

/** 委托类型：限价单 / 市价单（市价单按 IOC 处理，未成交部分立即作废）。 */
public enum OrderType {
    LIMIT, MARKET
}
