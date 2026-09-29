package com.github.lisama569.orderbookmatchingenginejava.model;

/**
 * 账户：现金 + 单一品种持仓，各自区分可用/冻结。
 * 恒等式：cashTotal = cashAvailable + cashFrozen；
 *         positionTotal = positionAvailable + positionFrozen。
 */
public final class Account {

    private final String accountId;
    private long cashAvailable;
    private long cashFrozen;
    private long positionAvailable;
    private long positionFrozen;

    public Account(String accountId, long cashAvailable, long positionAvailable) {
        this.accountId = accountId;
        this.cashAvailable = cashAvailable;
        this.positionAvailable = positionAvailable;
    }

    public String getAccountId() { return accountId; }
    public long getCashAvailable() { return cashAvailable; }
    public long getCashFrozen() { return cashFrozen; }
    public long getPositionAvailable() { return positionAvailable; }
    public long getPositionFrozen() { return positionFrozen; }

    public void setCashAvailable(long v) { this.cashAvailable = v; }
    public void setCashFrozen(long v) { this.cashFrozen = v; }
    public void setPositionAvailable(long v) { this.positionAvailable = v; }
    public void setPositionFrozen(long v) { this.positionFrozen = v; }
}
