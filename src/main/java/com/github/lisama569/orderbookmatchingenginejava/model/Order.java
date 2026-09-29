package com.github.lisama569.orderbookmatchingenginejava.model;

/**
 * 限价 / 市价委托。价格与数量统一使用 long：
 * 价格以 PRICE_SCALE（默认 8）位小数换算的整数表示，避免浮点误差。
 */
public class Order {
    public static final int PRICE_SCALE = 8;

    private long id;
    private String clientOrderId;
    private String account;
    private String symbol;
    private Side side;
    private OrderType type;
    private long limitPrice;
    private long originalQty;
    private long filledQty;
    private OrderStatus status;
    private long acceptedAtSeq;
    private long lastUpdatedSeq;
    /** 最近一次进入队列的事件序号（受理 / 改单），用于还原同价位时间优先。 */
    private long requeueSeq;

    public Order() {
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getClientOrderId() { return clientOrderId; }
    public void setClientOrderId(String clientOrderId) { this.clientOrderId = clientOrderId; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public Side getSide() { return side; }
    public void setSide(Side side) { this.side = side; }
    public OrderType getType() { return type; }
    public void setType(OrderType type) { this.type = type; }
    public long getLimitPrice() { return limitPrice; }
    public void setLimitPrice(long limitPrice) { this.limitPrice = limitPrice; }
    public long getOriginalQty() { return originalQty; }
    public void setOriginalQty(long originalQty) { this.originalQty = originalQty; }
    public long getFilledQty() { return filledQty; }
    public void setFilledQty(long filledQty) { this.filledQty = filledQty; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public long getAcceptedAtSeq() { return acceptedAtSeq; }
    public void setAcceptedAtSeq(long acceptedAtSeq) { this.acceptedAtSeq = acceptedAtSeq; }
    public long getLastUpdatedSeq() { return lastUpdatedSeq; }
    public void setLastUpdatedSeq(long lastUpdatedSeq) { this.lastUpdatedSeq = lastUpdatedSeq; }
    public long getRequeueSeq() { return requeueSeq; }
    public void setRequeueSeq(long requeueSeq) { this.requeueSeq = requeueSeq; }

    public long remainingQty() { return originalQty - filledQty; }

    public boolean isTerminal() {
        return status == OrderStatus.FILLED || status == OrderStatus.CANCELLED
                || status == OrderStatus.REJECTED;
    }
}
