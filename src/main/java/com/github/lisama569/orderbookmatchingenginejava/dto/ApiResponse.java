package com.github.lisama569.orderbookmatchingenginejava.dto;

import com.github.lisama569.orderbookmatchingenginejava.model.RejectReason;

/** 统一响应体；拒绝时 success=false 且带可区分的 rejectReason。 */
public record ApiResponse(boolean success, Object data, RejectReason rejectReason,
                          String message) {
    public static ApiResponse ok(Object data) {
        return new ApiResponse(true, data, null, null);
    }

    public static ApiResponse rejected(RejectReason reason, String message) {
        return new ApiResponse(false, null, reason, message);
    }
}
