package com.github.lisama569.orderbookmatchingenginejava.engine;

import com.github.lisama569.orderbookmatchingenginejava.event.Event;
import com.github.lisama569.orderbookmatchingenginejava.event.EventCodec;
import com.github.lisama569.orderbookmatchingenginejava.event.EventStore;
import com.github.lisama569.orderbookmatchingenginejava.event.EventType;
import com.github.lisama569.orderbookmatchingenginejava.exception.RejectReason;
import com.github.lisama569.orderbookmatchingenginejava.exception.TradingException;
import com.github.lisama569.orderbookmatchingenginejava.model.Account;
import com.github.lisama569.orderbookmatchingenginejava.model.Order;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;
import com.github.lisama569.orderbookmatchingenginejava.model.Trade;
import com.github.lisama569.orderbookmatchingenginejava.risk.RiskValidator;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 撮合引擎：系统内唯一的串行化点。
 *
 * <p>所有对盘口、账户、订单索引、幂等表、事件日志的变更都在同一把引擎锁内原子完成：
 * "校验 -> 冻结 -> 撮合 -> 逐笔结算记账 -> 写事件日志" 要么全部生效，要么全部不生效，
 * 失败时抛出 {@link TradingException} 且不会留下半更新的账面状态。
 *
 * <p>事件日志是唯一事实来源：进程重启后清空内存状态，重放日志即可得到同一份盘口与余额。
 */
@Component
public class MatchingEngine {

    private final RiskValidator riskValidator;
    private final EventStore eventStore;

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, OrderBook> books = new LinkedHashMap<>();
    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private final Map<String, Long> clientOrderIndex = new LinkedHashMap<>();
    /** 客户端请求号 -> 已执行结果（撤单/改单的并发幂等） */
    private final Map<String, Map<String, Object>> idempotentResults = new LinkedHashMap<>();
    private final List<Trade> trades = new ArrayList<>();

    private long orderIdSeq = 1;
    private long tradeIdSeq = 1;
    private long bookSequence = 1;

    public MatchingEngine(RiskValidator riskValidator, EventStore eventStore) {
        this.riskValidator = riskValidator;
        this.eventStore = eventStore;
    }

    // ------------------------------------------------------------------ 账户

    public synchronized void addAccount(String accountId, long cash, long position) {
        Account a = accounts.get(accountId);
        if (a == null) {
            a = new Account(accountId, 0, 0);
            accounts.put(accountId, a);
        }
        if (cash < 0 || position < 0) {
            throw new IllegalArgumentException("deposit amount must be non-negative");
        }
        a.setCashAvailable(a.getCashAvailable() + cash);
        a.setPositionAvailable(a.getPositionAvailable() + position);
        eventStore.append(EventType.ACCOUNT_DEPOSIT,
                EventCodec.encodeDeposit(accountId, cash, position), System.currentTimeMillis());
    }

    public synchronized Account getAccount(String accountId) {
        Account a = accounts.get(accountId);
        if (a == null) {
            throw new TradingException(RejectReason.ORDER_UNKNOWN_ACCOUNT,
                    "unknown account: " + accountId);
        }
        return a;
    }

    private OrderBook book(String symbol) {
        return books.computeIfAbsent(symbol, s -> new OrderBook());
    }

    // ------------------------------------------------------------------ 下单

    /**
     * 提交委托。成功返回终态订单（NEW/PARTIALLY_FILLED/FILLED/CANCELLED）；
     * 校验或风控失败抛 {@link TradingException} 并记录 ORDER_REJECTED 审计事件。
     */
    public synchronized Order submit(String clientOrderId, String accountId, String symbol,
                                     Side side, OrderType type, long price, long quantity) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new TradingException(RejectReason.ORDER_DUPLICATE_CLIENT_ORDER_ID,
                    "clientOrderId is required");
        }
        Long existing = clientOrderIndex.get(clientOrderId);
        if (existing != null) {
            // 相同 clientOrderId 重复提交：幂等返回原单，不重复撮合、不重复冻结
            throw new TradingException(RejectReason.ORDER_DUPLICATE_CLIENT_ORDER_ID,
                    "clientOrderId already used by orderId=" + existing);
        }

        boolean market = type == OrderType.MARKET;
        try {
            riskValidator.validateStatic(accountId, symbol, price, quantity, market,
                    Set.copyOf(accounts.keySet()));
        } catch (TradingException ex) {
            appendReject(clientOrderId, accountId, symbol, ex);
            throw ex;
        }
        Account account = accounts.get(accountId);

        // 冻结估算：限价单按整单 worst-case；市价单按当前对手盘可成交量估算（IOC）
        long freeze;
        if (!market) {
            freeze = side == Side.BUY ? price * quantity : quantity;
        } else {
            OrderBook b = book(symbol);
            long[] avail = side == Side.BUY ? b.marketableAsk(quantity, Long.MAX_VALUE)
                    : b.marketableBid(quantity, 0L);
            if (avail[1] == 0) {
                TradingException ex = new TradingException(RejectReason.MARKET_NO_LIQUIDITY,
                        "no opposing liquidity for market order");
                appendReject(clientOrderId, accountId, symbol, ex);
                throw ex;
            }
            freeze = side == Side.BUY ? avail[0] : avail[1];
        }

        try {
            if (side == Side.BUY) {
                riskValidator.checkBuyCash(account, freeze);
            } else {
                riskValidator.checkSellPosition(account, freeze);
            }
        } catch (TradingException ex) {
            appendReject(clientOrderId, accountId, symbol, ex);
            throw ex;
        }

        // 冻结落账（在锁内，异常不会发生在这之后的账面更新之外）
        if (side == Side.BUY) {
            account.setCashAvailable(account.getCashAvailable() - freeze);
            account.setCashFrozen(account.getCashFrozen() + freeze);
        } else {
            account.setPositionAvailable(account.getPositionAvailable() - freeze);
            account.setPositionFrozen(account.getPositionFrozen() + freeze);
        }

        long orderId = orderIdSeq++;
        Order order = new Order(orderId, clientOrderId, accountId, symbol, side, type,
                market ? 0L : price, quantity, 0L, OrderStatus.NEW, bookSequence++, freeze);
        orders.put(orderId, order);
        clientOrderIndex.put(clientOrderId, orderId);

        long now = System.currentTimeMillis();
        eventStore.append(EventType.ORDER_ACCEPTED, EventCodec.encodeOrder(order), now);

        // 撮合（同账户对手单跳过 = 自成交防止）
        book(symbol).match(order, market, maker -> {
            if (maker.getAccountId().equals(accountId)) {
                return 0L;
            }
            long qty = Math.min(order.getRemainingQuantity(), maker.getRemainingQuantity());
            long fillPrice = maker.getPrice();
            settle(order, maker, qty, fillPrice, now);
            return qty;
        });

        // 剩余处理
        if (order.getRemainingQuantity() > 0) {
            if (market) {
                // 市价单 IOC：剩余立即撤销，释放未用冻结
                releaseCancel(order);
                order.setStatus(OrderStatus.CANCELLED);
                eventStore.append(EventType.ORDER_CANCELLED,
                        EventCodec.encodeCancel(orderId, order.getRemainingQuantity(), "IOC"), now);
            } else {
                book(symbol).add(order);
                order.setStatus(order.getFilledQuantity() > 0
                        ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW);
            }
        } else {
            // 全部成交：成交价低于限价时冻结里会剩下滑点差额，必须退回可用，
            // 否则终结订单上会挂着无主冻结（卖单 worst-case 无差额，恒为 0）
            releaseResidualFrozen(order);
            order.setStatus(OrderStatus.FILLED);
        }
        return order;
    }

    /** 订单终结时，把冻结中剩余的滑点差额原路退回（买单现金；卖单差额恒为 0） */
    private void releaseResidualFrozen(Order order) {
        if (order.getFrozen() <= 0) {
            return;
        }
        Account a = accounts.get(order.getAccountId());
        if (order.getSide() == Side.BUY) {
            a.setCashFrozen(a.getCashFrozen() - order.getFrozen());
            a.setCashAvailable(a.getCashAvailable() + order.getFrozen());
        } else {
            a.setPositionFrozen(a.getPositionFrozen() - order.getFrozen());
            a.setPositionAvailable(a.getPositionAvailable() + order.getFrozen());
        }
        order.setFrozen(0);
    }

    /**
     * 单笔成交结算：taker 与 maker 同时、原子地更新冻结/可用/持仓与成交进度。
     * 成交价取 maker 挂单价（价格保护）。
     */
    private void settle(Order taker, Order maker, long qty, long fillPrice, long now) {
        Account takerAcc = accounts.get(taker.getAccountId());
        Account makerAcc = accounts.get(maker.getAccountId());

        if (taker.getSide() == Side.BUY) {
            // taker 买：现金由冻结划转（含滑点：实际成本低于限价，差额退回可用）
            long cost = qty * fillPrice;
            taker.setFrozen(taker.getFrozen() - cost);
            takerAcc.setCashFrozen(takerAcc.getCashFrozen() - cost);
            takerAcc.setPositionAvailable(takerAcc.getPositionAvailable() + qty);

            // maker 卖：持仓由冻结划转
            maker.setFrozen(maker.getFrozen() - qty);
            makerAcc.setPositionFrozen(makerAcc.getPositionFrozen() - qty);
            makerAcc.setCashAvailable(makerAcc.getCashAvailable() + cost);
        } else {
            // taker 卖：持仓由冻结划转
            taker.setFrozen(taker.getFrozen() - qty);
            takerAcc.setPositionFrozen(takerAcc.getPositionFrozen() - qty);
            takerAcc.setCashAvailable(takerAcc.getCashAvailable() + qty * fillPrice);

            // maker 买：现金由冻结划转（同样退回滑点差额）
            long cost = qty * fillPrice;
            maker.setFrozen(maker.getFrozen() - cost);
            makerAcc.setCashFrozen(makerAcc.getCashFrozen() - cost);
            makerAcc.setPositionAvailable(makerAcc.getPositionAvailable() + qty);
        }

        taker.setFilledQuantity(taker.getFilledQuantity() + qty);
        maker.setFilledQuantity(maker.getFilledQuantity() + qty);
        if (maker.getRemainingQuantity() == 0) {
            maker.setStatus(OrderStatus.FILLED);
        } else {
            maker.setStatus(OrderStatus.PARTIALLY_FILLED);
        }
        if (taker.getRemainingQuantity() == 0) {
            taker.setStatus(OrderStatus.FILLED);
        } else {
            taker.setStatus(OrderStatus.PARTIALLY_FILLED);
        }

        Trade trade = new Trade(tradeIdSeq++, taker.getOrderId(), maker.getOrderId(),
                taker.getSymbol(), fillPrice, qty, now);
        trades.add(trade);
        eventStore.append(EventType.TRADE, EventCodec.encodeTrade(trade), now);
    }

    /** 撤单/改单/IOC 剩余：把订单剩余冻结原路释放回可用 */
    private void releaseCancel(Order order) {
        Account a = accounts.get(order.getAccountId());
        if (order.getSide() == Side.BUY) {
            a.setCashFrozen(a.getCashFrozen() - order.getFrozen());
            a.setCashAvailable(a.getCashAvailable() + order.getFrozen());
        } else {
            a.setPositionFrozen(a.getPositionFrozen() - order.getFrozen());
            a.setPositionAvailable(a.getPositionAvailable() + order.getFrozen());
        }
        order.setFrozen(0);
    }

    private void appendReject(String clientOrderId, String accountId, String symbol,
                              TradingException ex) {
        eventStore.append(EventType.ORDER_REJECTED,
                EventCodec.encodeReject(clientOrderId, accountId, symbol,
                        ex.getReason().name(), ex.getMessage()),
                System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ 撤单

    /**
     * 用户撤单。clientOrderId 作为本次请求的幂等键：并发/重试的同一请求只生效一次。
     * 订单不存在 -> CANCEL_ORDER_NOT_FOUND；已终结 -> CANCEL_ORDER_NOT_ACTIVE。
     */
    public synchronized Order cancel(String clientOrderId, String accountId, long orderId) {
        Map<String, Object> memo = idempotentResults.get(clientOrderId);
        if (memo != null && "CANCEL".equals(memo.get("action"))) {
            return orders.get(memo.get("orderId"));
        }

        Order order = orders.get(orderId);
        if (order == null || (accountId != null && !accountId.isBlank()
                && !order.getAccountId().equals(accountId))) {
            throw new TradingException(RejectReason.CANCEL_ORDER_NOT_FOUND,
                    "active order not found: orderId=" + orderId);
        }
        if (!order.isActive()) {
            throw new TradingException(RejectReason.CANCEL_ORDER_NOT_ACTIVE,
                    "order already " + order.getStatus() + ": orderId=" + orderId);
        }

        Order removed = book(order.getSymbol()).remove(orderId);
        // 活动订单必定在簿中（市价单不会停留在活动状态），双保险
        if (removed == null) {
            throw new TradingException(RejectReason.CANCEL_ORDER_NOT_FOUND,
                    "order not resting in book: orderId=" + orderId);
        }
        long remaining = order.getRemainingQuantity();
        releaseCancel(order);
        order.setStatus(OrderStatus.CANCELLED);
        eventStore.append(EventType.ORDER_CANCELLED,
                EventCodec.encodeCancel(orderId, remaining, "USER"),
                System.currentTimeMillis());

        idempotentResults.put(clientOrderId,
                Map.of("action", "CANCEL", "orderId", orderId));
        return order;
    }

    // ------------------------------------------------------------------ 改单

    /**
     * 改单：在同一账户下把旧单剩余部分撤下（失去原时间优先位置），
     * 再按新价/新量做风控、冻结并立即参与撮合，未成交部分排到新档位队尾。
     * 风控不过则旧单原样保留，账面不变。
     */
    public synchronized Order modify(String clientOrderId, String accountId, long orderId,
                                     long newPrice, long newQuantity) {
        Map<String, Object> memo = idempotentResults.get(clientOrderId);
        if (memo != null && "MODIFY".equals(memo.get("action"))) {
            return orders.get(memo.get("newOrderId"));
        }

        Order old = orders.get(orderId);
        if (old == null || (accountId != null && !accountId.isBlank()
                && !old.getAccountId().equals(accountId))) {
            throw new TradingException(RejectReason.MODIFY_ORDER_NOT_FOUND,
                    "order not found: orderId=" + orderId);
        }
        if (!old.isActive()) {
            throw new TradingException(RejectReason.MODIFY_ORDER_NOT_ACTIVE,
                    "order already " + old.getStatus() + ": orderId=" + orderId);
        }
        if (old.getType() == OrderType.MARKET) {
            throw new TradingException(RejectReason.MODIFY_ORDER_NOT_ACTIVE,
                    "market order cannot be modified: orderId=" + orderId);
        }
        if (newPrice <= 0) {
            throw new TradingException(RejectReason.MODIFY_INVALID_PRICE,
                    "newPrice must be positive, got " + newPrice);
        }
        if (newQuantity <= 0) {
            throw new TradingException(RejectReason.MODIFY_INVALID_QUANTITY,
                    "newQuantity must be positive, got " + newQuantity);
        }

        Account account = accounts.get(old.getAccountId());
        // 新单可用池 = 当前可用 + 旧单剩余冻结（旧单即将被整体撤下并释放）。
        // 此时尚未动账：检查失败可直接返回，旧单与账面保持不变。
        long needFreeze = old.getSide() == Side.BUY ? newPrice * newQuantity : newQuantity;
        long cashPool = account.getCashAvailable() + old.getFrozen();
        long positionPool = account.getPositionAvailable() + old.getFrozen();
        if (old.getSide() == Side.BUY) {
            if (needFreeze > cashPool) {
                throw new TradingException(RejectReason.RISK_MODIFY_INSUFFICIENT_CASH,
                        "modify cash not enough: need=" + needFreeze
                                + ", availableAfterRelease=" + cashPool);
            }
        } else if (needFreeze > positionPool) {
            throw new TradingException(RejectReason.RISK_MODIFY_INSUFFICIENT_POSITION,
                    "modify position not enough: need=" + needFreeze
                            + ", availableAfterRelease=" + positionPool);
        }

        // 风控通过，执行"撤旧 -> 冻结新单"（同锁内，无失败路径）
        Order removed = book(old.getSymbol()).remove(orderId);
        if (removed == null) {
            throw new TradingException(RejectReason.MODIFY_ORDER_NOT_FOUND,
                    "order not resting in book: orderId=" + orderId);
        }
        long remaining = old.getRemainingQuantity();
        releaseCancel(old);
        old.setStatus(OrderStatus.CANCELLED);

        if (old.getSide() == Side.BUY) {
            account.setCashAvailable(account.getCashAvailable() - needFreeze);
            account.setCashFrozen(account.getCashFrozen() + needFreeze);
        } else {
            account.setPositionAvailable(account.getPositionAvailable() - needFreeze);
            account.setPositionFrozen(account.getPositionFrozen() + needFreeze);
        }

        long newOrderId = orderIdSeq++;
        long now = System.currentTimeMillis();
        Order neo = new Order(newOrderId, clientOrderId, old.getAccountId(),
                old.getSymbol(), old.getSide(), OrderType.LIMIT, newPrice, newQuantity,
                0L, OrderStatus.NEW, bookSequence++, needFreeze);
        orders.put(newOrderId, neo);

        eventStore.append(EventType.ORDER_MODIFIED,
                EventCodec.encodeModify(orderId, newPrice, newQuantity, newOrderId), now);
        eventStore.append(EventType.ORDER_ACCEPTED, EventCodec.encodeOrder(neo), now);

        book(old.getSymbol()).match(neo, false, maker -> {
            if (maker.getAccountId().equals(neo.getAccountId())) {
                return 0L;
            }
            long qty = Math.min(neo.getRemainingQuantity(), maker.getRemainingQuantity());
            settle(neo, maker, qty, maker.getPrice(), now);
            return qty;
        });

        if (neo.getRemainingQuantity() > 0) {
            book(old.getSymbol()).add(neo);
            neo.setStatus(neo.getFilledQuantity() > 0
                    ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW);
        } else {
            releaseResidualFrozen(neo);
            neo.setStatus(OrderStatus.FILLED);
        }

        idempotentResults.put(clientOrderId,
                Map.of("action", "MODIFY", "orderId", orderId, "newOrderId", newOrderId));
        return neo;
    }

    // ------------------------------------------------------------------ 查询

    public synchronized Order getOrder(long orderId) {
        Order o = orders.get(orderId);
        if (o == null) {
            throw new TradingException(RejectReason.CANCEL_ORDER_NOT_FOUND,
                    "order not found: orderId=" + orderId);
        }
        return o;
    }

    public synchronized List<Trade> trades() {
        return List.copyOf(trades);
    }

    public synchronized Map<String, Object> snapshot(String symbol) {
        OrderBook b = book(symbol);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", symbol);
        m.put("bids", b.bidLevels());
        m.put("asks", b.askLevels());
        m.put("bestBid", b.bestBid() == null ? null : b.bestBid().getPrice());
        m.put("bestAsk", b.bestAsk() == null ? null : b.bestAsk().getPrice());
        return m;
    }

    // ------------------------------------------------------- 重放（重建内存）

    /** 可信重启重放：先清空内存（保留日志文件），从内存日志逐条重建，再写回各计数器。 */
    public synchronized void replayFromLog() {
        List<Event> snapshot = eventStore.all();
        resetRuntimeState();
        applyEvents(snapshot, false);
    }

    /**
     * 外部事件流（如备份/对端重推）校验式重放：在隔离的空状态上应用，
     * 乱序、重复、冲突、迟到记录分别给出可区分结论，不污染当前运行态。
     * 返回报告：applied 条数、ignoredDuplicates、rejected（seq + 原因）。
     */
    public synchronized Map<String, Object> validateAndReplay(List<Event> incoming) {
        return IngestionValidator.run(this, incoming);
    }

    /**
     * 供外部事件流校验使用：在隔离的空运行态重放给定事件；
     * 无论成功还是失败，都恢复调用前的运行态，非法流绝不改变当前结果。
     */
    @SuppressWarnings("unchecked")
    synchronized void applyEventsIsolated(List<Event> events) {
        RuntimeSnapshot saved = snapshotRuntime();
        try {
            resetRuntimeState();
            applyEvents(events, true);
        } finally {
            restoreRuntime(saved);
        }
    }

    private record RuntimeSnapshot(
            LinkedHashMap<String, Account> accounts,
            LinkedHashMap<String, OrderBook> books,
            LinkedHashMap<Long, Order> orders,
            LinkedHashMap<String, Long> clientOrderIndex,
            LinkedHashMap<String, Map<String, Object>> idempotentResults,
            ArrayList<Trade> trades,
            long orderIdSeq, long tradeIdSeq, long bookSequence) {
    }

    private RuntimeSnapshot snapshotRuntime() {
        // 深拷贝可变对象（Order/Account），否则隔离重放会通过共享引用改花当前状态
        LinkedHashMap<String, Account> accCopy = new LinkedHashMap<>();
        for (var e : accounts.entrySet()) {
            Account src = e.getValue();
            Account cp = new Account(src.getAccountId(), 0, 0);
            cp.setCashAvailable(src.getCashAvailable());
            cp.setCashFrozen(src.getCashFrozen());
            cp.setPositionAvailable(src.getPositionAvailable());
            cp.setPositionFrozen(src.getPositionFrozen());
            accCopy.put(e.getKey(), cp);
        }
        LinkedHashMap<Long, Order> orderCopy = new LinkedHashMap<>();
        for (var e : orders.entrySet()) {
            Order src = e.getValue();
            Order cp = new Order(src.getOrderId(), src.getClientOrderId(), src.getAccountId(),
                    src.getSymbol(), src.getSide(), src.getType(), src.getPrice(),
                    src.getOriginalQuantity(), src.getFilledQuantity(), src.getStatus(),
                    src.getSequence(), src.getFrozen());
            orderCopy.put(e.getKey(), cp);
        }
        return new RuntimeSnapshot(
                accCopy,
                new LinkedHashMap<>(),   // 盘口重建（OrderBook 持 Order 引用，不可共享）
                orderCopy,
                new LinkedHashMap<>(clientOrderIndex),
                new LinkedHashMap<>(idempotentResults),
                new ArrayList<>(trades),
                orderIdSeq, tradeIdSeq, bookSequence);
    }

    /** 用深拷贝的订单重建盘口中的挂单 */
    private LinkedHashMap<String, OrderBook> rebuildBooksFromOrders(
            LinkedHashMap<Long, Order> orderCopy) {
        LinkedHashMap<String, OrderBook> booksCopy = new LinkedHashMap<>();
        for (Order o : orderCopy.values()) {
            if (o.isActive() && o.getType() == OrderType.LIMIT) {
                booksCopy.computeIfAbsent(o.getSymbol(), k -> new OrderBook()).add(o);
            }
        }
        return booksCopy;
    }

    private void restoreRuntime(RuntimeSnapshot s) {
        accounts.clear();
        accounts.putAll(s.accounts());
        books.clear();
        books.putAll(rebuildBooksFromOrders(s.orders()));
        orders.clear();
        orders.putAll(s.orders());
        clientOrderIndex.clear();
        clientOrderIndex.putAll(s.clientOrderIndex());
        idempotentResults.clear();
        idempotentResults.putAll(s.idempotentResults());
        trades.clear();
        trades.addAll(s.trades());
        orderIdSeq = s.orderIdSeq();
        tradeIdSeq = s.tradeIdSeq();
        bookSequence = s.bookSequence();
    }

    private void resetRuntimeState() {
        accounts.clear();
        books.clear();
        orders.clear();
        clientOrderIndex.clear();
        idempotentResults.clear();
        trades.clear();
        orderIdSeq = 1;
        tradeIdSeq = 1;
        bookSequence = 1;
    }

    /** 仅供本类与重放校验器调用的全量重置（含日志清空，主要给测试使用） */
    public synchronized void resetAll() {
        resetRuntimeState();
        eventStore.clearAll();
    }

    /**
     * 把一组事件应用到当前（已清空的）运行态。
     * strict=false 用于重启：seq 必须连续，时间戳必须单调不减（迟到拒绝）。
     * 事件以"命令组"为边界：ACCEPTED 后紧跟它的 TRADE，随后 CANCELLED/MODIFIED 结束该组。
     */
    void applyEvents(List<Event> events, boolean strict) {
        Deque<Order> pending = new ArrayDeque<>();
        long lastTs = Long.MIN_VALUE;

        for (Event e : events) {
            if (strict) {
                if (e.timestampMillis() < lastTs) {
                    throw new TradingException(RejectReason.EVENT_LATE,
                            "event timestamp regresses at seq=" + e.seq());
                }
            }
            lastTs = e.timestampMillis();
            Map<String, String> p = EventCodec.decode(e.payloadJson());

            switch (e.type()) {
                case ACCOUNT_DEPOSIT -> applyDeposit(p);
                case ORDER_ACCEPTED -> {
                    flushPending(pending);
                    pending.addLast(applyAccepted(p));
                }
                case TRADE -> applyTrade(p, pending);
                case ORDER_CANCELLED -> {
                    flushPending(pending);
                    applyCancelled(p);
                }
                case ORDER_MODIFIED -> {
                    flushPending(pending);
                    applyModified(p);
                }
                case ORDER_REJECTED -> {
                    flushPending(pending);
                    // 纯审计记录：重放时无状态可改
                }
            }
        }
        flushPending(pending);
    }

    private void applyDeposit(Map<String, String> p) {
        String id = EventCodec.str(p, "accountId");
        long cash = EventCodec.lng(p, "cash");
        long position = EventCodec.lng(p, "position");
        Account a = accounts.get(id);
        if (a == null) {
            accounts.put(id, new Account(id, cash, position));
        } else {
            a.setCashAvailable(a.getCashAvailable() + cash);
            a.setPositionAvailable(a.getPositionAvailable() + position);
        }
    }

    private Order applyAccepted(Map<String, String> p) {
        long orderId = EventCodec.lng(p, "orderId");
        Side side = EventCodec.side(p);
        OrderType type = EventCodec.type(p);
        long price = EventCodec.lng(p, "price");
        long qty = EventCodec.lng(p, "originalQuantity");
        String symbol = EventCodec.str(p, "symbol");
        String accountId = EventCodec.str(p, "accountId");

        // 重放时重建订单级冻结，必须与实时路径同一套规则：
        // 限价单按整单 worst-case；市价单按当时对手盘可成交量估算（IOC）。
        long frozen;
        if (type == OrderType.MARKET) {
            OrderBook b = book(symbol);
            frozen = side == Side.BUY
                    ? b.marketableAsk(qty, Long.MAX_VALUE)[0]
                    : b.marketableBid(qty, 0L)[1];
        } else {
            frozen = side == Side.BUY ? price * qty : qty;
        }

        Order o = new Order(orderId, EventCodec.str(p, "clientOrderId"), accountId, symbol,
                side, type, price, qty, EventCodec.lng(p, "filledQuantity"),
                EventCodec.status(p), EventCodec.lng(p, "sequence"), frozen);
        orders.put(orderId, o);
        clientOrderIndex.put(o.getClientOrderId(), orderId);
        orderIdSeq = Math.max(orderIdSeq, orderId + 1);
        bookSequence = Math.max(bookSequence, o.getSequence() + 1);

        // 建立冻结：可用 -> 冻结，使后续逐笔成交与撤单释放能与实时路径逐分钱对齐
        Account a = accounts.get(accountId);
        if (a == null) {
            throw new TradingException(RejectReason.EVENT_CANNOT_APPLY,
                    "order references unknown account: " + accountId);
        }
        if (side == Side.BUY) {
            a.setCashAvailable(a.getCashAvailable() - frozen);
            a.setCashFrozen(a.getCashFrozen() + frozen);
        } else {
            a.setPositionAvailable(a.getPositionAvailable() - frozen);
            a.setPositionFrozen(a.getPositionFrozen() + frozen);
        }
        return o;
    }

    /** 重放一笔成交：按 taker/maker 的方向重新记账（与实时结算同一套账面规则） */
    private void applyTrade(Map<String, String> p, Deque<Order> pending) {
        long takerId = EventCodec.lng(p, "takerOrderId");
        long makerId = EventCodec.lng(p, "makerOrderId");
        long qty = EventCodec.lng(p, "quantity");
        long px = EventCodec.lng(p, "price");
        Order taker = orders.get(takerId);
        Order maker = orders.get(makerId);
        if (taker == null || maker == null) {
            throw new TradingException(RejectReason.EVENT_CANNOT_APPLY,
                    "trade references unknown order: taker=" + takerId + ", maker=" + makerId);
        }

        Account ta = accounts.get(taker.getAccountId());
        Account ma = accounts.get(maker.getAccountId());
        long cost = qty * px;
        if (taker.getSide() == Side.BUY) {
            taker.setFrozen(taker.getFrozen() - cost);
            ta.setCashFrozen(ta.getCashFrozen() - cost);
            ta.setPositionAvailable(ta.getPositionAvailable() + qty);
            maker.setFrozen(maker.getFrozen() - qty);
            ma.setPositionFrozen(ma.getPositionFrozen() - qty);
            ma.setCashAvailable(ma.getCashAvailable() + cost);
        } else {
            taker.setFrozen(taker.getFrozen() - qty);
            ta.setPositionFrozen(ta.getPositionFrozen() - qty);
            ta.setCashAvailable(ta.getCashAvailable() + cost);
            maker.setFrozen(maker.getFrozen() - cost);
            ma.setCashFrozen(ma.getCashFrozen() - cost);
            ma.setPositionAvailable(ma.getPositionAvailable() + qty);
        }
        taker.setFilledQuantity(taker.getFilledQuantity() + qty);
        maker.setFilledQuantity(maker.getFilledQuantity() + qty);

        boolean takerDone = taker.getRemainingQuantity() == 0;
        boolean makerDone = maker.getRemainingQuantity() == 0;
        taker.setStatus(takerDone ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
        maker.setStatus(makerDone ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);

        // 与实时路径一致：终结订单的滑点残余冻结退回可用
        if (takerDone) {
            releaseResidualFrozen(taker);
        }
        if (makerDone) {
            releaseResidualFrozen(maker);
        }

        // 与实时撮合一致：全部成交的 maker 必须出簿；部分成交保留队首位置
        if (makerDone && maker.getType() == OrderType.LIMIT) {
            Order removed = book(maker.getSymbol()).remove(makerId);
            if (removed == null) {
                throw new TradingException(RejectReason.EVENT_CANNOT_APPLY,
                        "filled maker not resting in book: " + makerId);
            }
        }

        long tid = EventCodec.lng(p, "tradeId");
        trades.add(new Trade(tid, takerId, makerId, EventCodec.str(p, "symbol"),
                px, qty, 0L));
        tradeIdSeq = Math.max(tradeIdSeq, tid + 1);
    }

    /** 命令组结束：把仍有余量的限价单挂入盘口（成交时被填满的单不进簿） */
    private void flushPending(Deque<Order> pending) {
        while (!pending.isEmpty()) {
            Order o = pending.pollFirst();
            if (o.getRemainingQuantity() > 0 && o.getType() == OrderType.LIMIT) {
                book(o.getSymbol()).add(o);
                if (o.getStatus() != OrderStatus.PARTIALLY_FILLED
                        && o.getStatus() != OrderStatus.FILLED) {
                    o.setStatus(o.getFilledQuantity() > 0
                            ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW);
                }
            }
        }
    }

    private void applyCancelled(Map<String, String> p) {
        long orderId = EventCodec.lng(p, "orderId");
        Order o = orders.get(orderId);
        if (o == null) {
            throw new TradingException(RejectReason.EVENT_CANNOT_APPLY,
                    "cancel references unknown order: " + orderId);
        }
        if ("IOC".equals(EventCodec.str(p, "source"))) {
            // 市价单剩余：从未进簿，仅释放冻结并终结
            releaseCancel(o);
            o.setStatus(OrderStatus.CANCELLED);
            return;
        }
        Order removed = book(o.getSymbol()).remove(orderId);
        if (removed == null) {
            throw new TradingException(RejectReason.EVENT_CANNOT_APPLY,
                    "cancel order not resting in book: " + orderId);
        }
        releaseCancel(o);
        o.setStatus(OrderStatus.CANCELLED);
    }

    private void applyModified(Map<String, String> p) {
        long oldId = EventCodec.lng(p, "orderId");
        long newId = EventCodec.lng(p, "newOrderId");
        Order old = orders.get(oldId);
        if (old == null) {
            throw new TradingException(RejectReason.EVENT_CANNOT_APPLY,
                    "modify references unknown order: " + oldId);
        }
        Order removed = book(old.getSymbol()).remove(oldId);
        if (removed == null) {
            throw new TradingException(RejectReason.EVENT_CANNOT_APPLY,
                    "modify order not resting in book: " + oldId);
        }
        releaseCancel(old);
        old.setStatus(OrderStatus.CANCELLED);
        orderIdSeq = Math.max(orderIdSeq, newId + 1);
        // 新单的冻结账面由紧随其后的 ORDER_ACCEPTED + TRADE 序列重建
    }
}
