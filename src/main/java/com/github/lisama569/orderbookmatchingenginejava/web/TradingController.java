package com.github.lisama569.orderbookmatchingenginejava.web;

import com.github.lisama569.orderbookmatchingenginejava.service.TradingService;
import com.github.lisama569.orderbookmatchingenginejava.web.dto.CancelRequest;
import com.github.lisama569.orderbookmatchingenginejava.web.dto.ModifyRequest;
import com.github.lisama569.orderbookmatchingenginejava.web.dto.SubmitRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TradingController {

    private final TradingService tradingService;

    public TradingController(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    @PostMapping("/accounts/{accountId}/deposit")
    public Map<String, Object> deposit(@PathVariable String accountId,
                                       @RequestBody Map<String, Long> body) {
        tradingService.deposit(accountId, body.getOrDefault("cash", 0L),
                body.getOrDefault("position", 0L));
        return Map.of("ok", true, "accountId", accountId);
    }

    @PostMapping("/orders")
    public Map<String, Object> submit(@RequestBody SubmitRequest req) {
        return tradingService.submit(req.clientOrderId(), req.accountId(), req.symbol(),
                req.side(), req.type(), req.price(), req.quantity());
    }

    @PostMapping("/orders/cancel")
    public Map<String, Object> cancel(@RequestBody CancelRequest req) {
        return tradingService.cancel(req.clientOrderId(), req.accountId(), req.orderId());
    }

    @PostMapping("/orders/modify")
    public Map<String, Object> modify(@RequestBody ModifyRequest req) {
        return tradingService.modify(req.clientOrderId(), req.accountId(), req.orderId(),
                req.newPrice(), req.newQuantity());
    }

    @GetMapping("/orderbook/{symbol}")
    public Map<String, Object> orderBook(@PathVariable String symbol) {
        return tradingService.orderBook(symbol);
    }

    @GetMapping("/accounts/{accountId}")
    public Map<String, Object> account(@PathVariable String accountId) {
        var a = tradingService.account(accountId);
        return Map.of("accountId", a.getAccountId(),
                "cashAvailable", a.getCashAvailable(), "cashFrozen", a.getCashFrozen(),
                "positionAvailable", a.getPositionAvailable(),
                "positionFrozen", a.getPositionFrozen());
    }

    @GetMapping("/trades")
    public List<Map<String, Object>> trades() {
        return tradingService.trades();
    }

    @PostMapping("/admin/replay")
    public Map<String, Object> replay() {
        tradingService.restartAndReplay();
        return Map.of("ok", true);
    }
}
