package com.github.lisama569.orderbookmatchingenginejava.event;

/** 严格重放模式下，遇到乱序/重复/迟到/损坏记录时抛出。 */
public class ReplayRejectException extends RuntimeException {
    private final long seq;
    private final ReplayRejectReason reason;

    public ReplayRejectException(long seq, ReplayRejectReason reason, String message) {
        super("seq=" + seq + " " + reason + ": " + message);
        this.seq = seq;
        this.reason = reason;
    }

    public long getSeq() { return seq; }
    public ReplayRejectReason getReason() { return reason; }
}
