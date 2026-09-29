package com.github.lisama569.orderbookmatchingenginejava.web;

import com.github.lisama569.orderbookmatchingenginejava.exception.TradingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 把可区分的拒绝原因原样返回给调用方，而不是笼统的 500 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TradingException.class)
    public ResponseEntity<Map<String, Object>> handleTrading(TradingException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "rejected", true,
                "reason", ex.getReason().name(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "rejected", true,
                "reason", "BAD_REQUEST",
                "message", String.valueOf(ex.getMessage())
        ));
    }
}
