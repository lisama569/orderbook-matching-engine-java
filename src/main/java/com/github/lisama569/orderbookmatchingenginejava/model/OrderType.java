package com.github.lisama569.orderbookmatchingenginejava.model;

/** 委托类型：限价单 / 市价单（市价单按 IOC 处理，剩余立即撤销） */
public enum OrderType {
    LIMIT,
    MARKET
}
