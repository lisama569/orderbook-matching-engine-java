package com.github.lisama569.orderbookmatchingenginejava.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 本地内存账户账本。
 * 现金维度：cash = availableCash + frozenCash；
 * 持仓维度（按合约）：position = availablePosition + frozenPosition。
 */
public class Account {

    private final String accountId;
    private long availableCash;
    private long frozenCash;
    private final Map<String, Long> availablePosition = new HashMap<>();
    private final Map<String, Long> frozenPosition = new HashMap<>();

    public Account(String accountId, long cash, Map<String, Long> positions) {
        this.accountId = accountId;
        this.availableCash = cash;
        if (positions != null) {
            positions.forEach((s, q) -> {
                if (q > 0) availablePosition.put(s, q);
            });
        }
    }

    public String getAccountId() { return accountId; }
    public long getAvailableCash() { return availableCash; }
    public void setAvailableCash(long v) { this.availableCash = v; }
    public long getFrozenCash() { return frozenCash; }
    public void setFrozenCash(long v) { this.frozenCash = v; }
    public Map<String, Long> getAvailablePosition() { return availablePosition; }
    public Map<String, Long> getFrozenPosition() { return frozenPosition; }
}
