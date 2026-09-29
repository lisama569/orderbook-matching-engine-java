package com.github.lisama569.orderbookmatchingenginejava.event;

/**
 * 事件日志中的一条不可变记录（JSON Lines 一行一条）。
 * seq 从 1 开始严格递增，是事件顺序与幂等判断的唯一依据。
 */
public record EventRecord(long seq, String type, String payloadJson, long createdAtMs) {
}
