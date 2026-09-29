package com.github.lisama569.orderbookmatchingenginejava.engine;

import java.util.List;

/** 盘口快照：每个元素为 [price, aggregateRemainingQty]；bids 价降序、asks 价升序。 */
public record BookSnapshot(String symbol, List<long[]> bids, List<long[]> asks) {
}
