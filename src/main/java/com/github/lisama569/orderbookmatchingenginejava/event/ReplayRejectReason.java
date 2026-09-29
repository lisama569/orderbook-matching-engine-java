package com.github.lisama569.orderbookmatchingenginejava.event;

/** 事件重放时，乱序 / 重复 / 迟到 / 损坏记录的可区分原因。 */
public enum ReplayRejectReason {
    OUT_OF_ORDER,
    DUPLICATE_SEQUENCE,
    LATE_EVENT,
    UNKNOWN_ORDER,
    CORRUPT_PAYLOAD
}
