package com.github.lisama569.orderbookmatchingenginejava.model;

/** 业务拒绝异常，携带可区分的 {@link RejectReason}。 */
public class RejectException extends RuntimeException {
    private final RejectReason reason;

    public RejectException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() { return reason; }
}
