package com.github.lisama569.orderbookmatchingenginejava.engine;

import com.github.lisama569.orderbookmatchingenginejava.model.Order;

/** 撮合过程中计算出的一笔待结算成交（尚未落事件、尚未记账）。 */
public record PlannedFill(long price, long qty, Order maker) {
}
