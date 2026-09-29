package com.github.lisama569.orderbookmatchingenginejava.event;

/**
 * 不可变事件记录，落本地内存日志，可按顺序重放重建盘口与账户。
 * seq 为日志内的严格递增序号（1 开始）。
 */
public record Event(
        long seq,
        EventType type,
        /** 事件发生时间戳，用于迟到判定（乱序检测以 seq 为准） */
        long timestampMillis,
        String payloadJson
) {
}
