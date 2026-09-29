package com.github.lisama569.orderbookmatchingenginejava.exception;

/** 业务拒绝异常：携带可区分的 {@link RejectReason}，不允许被笼统地包装成失败 */
public class TradingException extends RuntimeException {

    private final RejectReason reason;

    public TradingException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
