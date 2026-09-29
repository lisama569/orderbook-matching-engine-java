package com.github.lisama569.orderbookmatchingenginejava.web;

import com.github.lisama569.orderbookmatchingenginejava.dto.ApiResponse;
import com.github.lisama569.orderbookmatchingenginejava.dto.CancelRequest;
import com.github.lisama569.orderbookmatchingenginejava.dto.ModifyRequest;
import com.github.lisama569.orderbookmatchingenginejava.dto.SubmitRequest;
import com.github.lisama569.orderbookmatchingenginejava.engine.CancelResult;
import com.github.lisama569.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.orderbookmatchingenginejava.engine.ModifyResult;
import com.github.lisama569.orderbookmatchingenginejava.engine.SubmitResult;
import com.github.lisama569.orderbookmatchingenginejava.model.Account;
import com.github.lisama569.orderbookmatchingenginejava.model.Order;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.RejectException;
import com.github.lisama569.orderbookmatchingenginejava.model.RejectReason;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 交易与查询 HTTP 接口。业务拒绝统一返回 422 与可区分的 rejectReason。 */
@RestController
@RequestMapping("/api")
public class TradingController {

    private final MatchingEngine engine;

    public TradingController(MatchingEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/orders")
    public ResponseEntity<ApiResponse> submit(@RequestBody SubmitRequest req) {
        OrderType type = parseType(req.type());
        long qty = req.qty() == null ? 0L : req.qty();
        SubmitResult result = engine.submit(req.clientOrderId(), req.account(), req.symbol(),
                req.side(), type, req.limitPrice(), qty);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/orders/cancel")
    public ResponseEntity<ApiResponse> cancel(@RequestBody CancelRequest req) {
        if (req.orderId() == null) {
            throw new RejectException(RejectReason.ORDER_NOT_FOUND, "orderId is required");
        }
        CancelResult result = engine.cancel(req.orderId());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/orders/modify")
    public ResponseEntity<ApiResponse> modify(@RequestBody ModifyRequest req) {
        if (req.orderId() == null) {
            throw new RejectException(RejectReason.ORDER_NOT_FOUND, "orderId is required");
        }
        ModifyResult result = engine.modify(req.orderId(), req.newLimitPrice(),
                req.newQty() == null ? 0L : req.newQty());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<ApiResponse> getOrder(@PathVariable long id) {
        Order order = engine.getOrder(id);
        if (order == null) {
            throw new RejectException(RejectReason.ORDER_NOT_FOUND, "order not found: " + id);
        }
        return ResponseEntity.ok(ApiResponse.ok(toView(order)));
    }

    @GetMapping("/books/{symbol}")
    public ResponseEntity<ApiResponse> book(@PathVariable String symbol) {
        return ResponseEntity.ok(ApiResponse.ok(engine.bookSnapshot(symbol)));
    }

    @GetMapping("/fills/{symbol}")
    public ResponseEntity<ApiResponse> fills(@PathVariable String symbol) {
        return ResponseEntity.ok(ApiResponse.ok(engine.fillsOf(symbol)));
    }

    @GetMapping("/accounts/{id}")
    public ResponseEntity<ApiResponse> account(@PathVariable String id) {
        Account account = engine.getLedger().get(id);
        if (account == null) {
            throw new RejectException(RejectReason.UNKNOWN_ACCOUNT, "unknown account: " + id);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("accountId", account.getAccountId());
        view.put("availableCash", account.getAvailableCash());
        view.put("frozenCash", account.getFrozenCash());
        view.put("totalCash", account.getAvailableCash() + account.getFrozenCash());
        view.put("availablePosition", account.getAvailablePosition());
        view.put("frozenPosition", account.getFrozenPosition());
        return ResponseEntity.ok(ApiResponse.ok(view));
    }

    @PostMapping("/admin/reset")
    public ResponseEntity<ApiResponse> reset() {
        engine.resetAll();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("reset", true)));
    }

    private static OrderType parseType(String raw) {
        if (raw == null) {
            throw new RejectException(RejectReason.INVALID_PRICE, "type is required (LIMIT/MARKET)");
        }
        try {
            return OrderType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RejectException(RejectReason.INVALID_PRICE,
                    "unknown order type: " + raw);
        }
    }

    private static Map<String, Object> toView(Order o) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderId", o.getId());
        view.put("clientOrderId", o.getClientOrderId());
        view.put("account", o.getAccount());
        view.put("symbol", o.getSymbol());
        view.put("side", o.getSide());
        view.put("type", o.getType());
        view.put("limitPrice", o.getLimitPrice());
        view.put("originalQty", o.getOriginalQty());
        view.put("filledQty", o.getFilledQty());
        view.put("remainingQty", o.remainingQty());
        view.put("status", o.getStatus());
        return view;
    }

    @ExceptionHandler(RejectException.class)
    public ResponseEntity<ApiResponse> handleReject(RejectException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.rejected(ex.getReason(), ex.getMessage()));
    }
}
