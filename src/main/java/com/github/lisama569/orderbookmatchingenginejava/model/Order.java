package com.github.lisama569.orderbookmatchingenginejava.model;

/**
 * 委托单。filledQuantity / status / 订单级冻结字段在撮合中被引擎更新，
 * 所有更新都必须在引擎的单锁内完成。
 *
 * <p>订单级冻结：买单为冻结的现金额，卖单为冻结的持仓数。
 * 每成交一笔，先从冻结中划转，撤单/改单时把剩余冻结原路释放，
 * 使账户层面不会重复扣减或漏加。
 */
public final class Order {

    private final long orderId;
    private final String clientOrderId;
    private final String accountId;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    /** 限价；市价单为 0 */
    private final long price;
    private final long originalQuantity;

    private long filledQuantity;
    private OrderStatus status;
    /** 进入盘口的逻辑序号，同价时用于时间优先排序 */
    private final long sequence;

    /** 买单：仍冻结的现金（= 剩余数量对应所需现金）；卖单：仍冻结的持仓数 */
    private long frozen;

    public Order(long orderId, String clientOrderId, String accountId, String symbol,
                 Side side, OrderType type, long price, long originalQuantity,
                 long filledQuantity, OrderStatus status, long sequence, long frozen) {
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.originalQuantity = originalQuantity;
        this.filledQuantity = filledQuantity;
        this.status = status;
        this.sequence = sequence;
        this.frozen = frozen;
    }

    public long getOrderId() { return orderId; }
    public String getClientOrderId() { return clientOrderId; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public long getPrice() { return price; }
    public long getOriginalQuantity() { return originalQuantity; }
    public long getFilledQuantity() { return filledQuantity; }
    public long getSequence() { return sequence; }
    public OrderStatus getStatus() { return status; }
    public long getFrozen() { return frozen; }

    public void setFilledQuantity(long filledQuantity) { this.filledQuantity = filledQuantity; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public void setFrozen(long frozen) { this.frozen = frozen; }

    public long getRemainingQuantity() {
        return originalQuantity - filledQuantity;
    }

    public boolean isActive() {
        return status == OrderStatus.NEW || status == OrderStatus.PARTIALLY_FILLED;
    }
}
