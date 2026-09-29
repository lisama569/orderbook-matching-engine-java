package com.github.lisama569.orderbookmatchingenginejava.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.orderbookmatchingenginejava.event.EventRecord;
import com.github.lisama569.orderbookmatchingenginejava.event.EventStore;
import com.github.lisama569.orderbookmatchingenginejava.event.EventType;
import com.github.lisama569.orderbookmatchingenginejava.event.OrderAcceptedPayload;
import com.github.lisama569.orderbookmatchingenginejava.event.OrderCancelledPayload;
import com.github.lisama569.orderbookmatchingenginejava.event.OrderModifiedPayload;
import com.github.lisama569.orderbookmatchingenginejava.event.ReplayReport;
import com.github.lisama569.orderbookmatchingenginejava.event.ReplayRejectException;
import com.github.lisama569.orderbookmatchingenginejava.event.ReplayRejectReason;
import com.github.lisama569.orderbookmatchingenginejava.event.TradePayload;
import com.github.lisama569.orderbookmatchingenginejava.model.Fill;
import com.github.lisama569.orderbookmatchingenginejava.model.Order;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.RejectException;
import com.github.lisama569.orderbookmatchingenginejava.model.RejectReason;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;
import com.github.lisama569.orderbookmatchingenginejava.risk.AccountLedger;
import com.github.lisama569.orderbookmatchingenginejava.risk.RiskValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 撮合引擎门面（Spring 单例）。
 *
 * <p>并发模型：一把可重入锁串行化所有“改状态”的请求（下单/撤单/改单/重置/重放）。
 * 锁内执行顺序固定：<em>参数与风控校验（冻结前） → 记账（冻结/结算/释放）
 * → 追加事件日志 → 更新订单簿与订单状态</em>。任一步抛异常都不会执行后续步骤，
 * 账户与盘口保持原状，因此不存在半更新账面。
 *
 * <p>事件溯源：订单号、成交号都直接使用事件序号（严格递增、重放确定）。
 * 重启后 {@link #replayFromLog()} 只重放“事实事件”即可得到同一份盘口与账户余额。
 */
public class MatchingEngine {

    /** 撤单原因（写入 ORDER_CANCELLED 事件）。 */
    public static final String CANCEL_USER = "USER_CANCEL";
    public static final String CANCEL_IOC_EXPIRED = "IOC_EXPIRED";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EventStore eventStore;
    private final AccountLedger ledger;
    private final RiskValidator riskValidator;
    private final Set<String> symbols;
    /** strict=乱序等记录直接抛异常中止重放；lenient=跳过并在 ReplayReport 中记录原因。 */
    private final String replayMode;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, OrderBook> books = new HashMap<>();
    private final Map<Long, Order> ordersById = new HashMap<>();
    /** clientOrderId -> 幂等记录（仅在受理成功时登记）。 */
    private final Map<String, IdempotentEntry> idempotency = new HashMap<>();
    /** 按 symbol 保存全部成交，供查询 / 测试断言。 */
    private final Map<String, List<Fill>> fillsBySymbol = new LinkedHashMap<>();
    private long eventSeq = 0;

    private record IdempotentEntry(String fingerprint, long orderId) {
    }

    public MatchingEngine(EventStore eventStore, AccountLedger ledger,
                          RiskValidator riskValidator, Set<String> symbols,
                          String replayMode) {
        this.eventStore = eventStore;
        this.ledger = ledger;
        this.riskValidator = riskValidator;
        this.symbols = symbols;
        this.replayMode = replayMode == null ? "strict" : replayMode;
        for (String s : symbols) {
            books.put(s, new OrderBook(s));
            fillsBySymbol.put(s, new ArrayList<>());
        }
    }

    // ============================== 下单 ==============================

    /**
     * 提交委托。
     *
     * @param clientOrderId 客户端订单号，非空且在已受理订单中唯一；
     *                      相同 clientOrderId + 完全相同请求视为幂等重发，返回原结果；
     *                      相同 clientOrderId 但请求内容不同，拒绝为 DUPLICATE_CLIENT_ORDER_ID
     */
    public SubmitResult submit(String clientOrderId, String account, String symbol,
                               Side side, OrderType type, Long limitPrice, long qty) {
        validateClientOrderId(clientOrderId);
        long price = type == OrderType.LIMIT
                ? requirePositive(limitPrice, RejectReason.INVALID_PRICE, "limitPrice")
                : 0L;

        lock.lock();
        try {
            IdempotentEntry seen = idempotency.get(clientOrderId);
            String fingerprint = fingerprint(account, symbol, side, type, price, qty);
            if (seen != null) {
                if (!seen.fingerprint().equals(fingerprint)) {
                    throw new RejectException(RejectReason.DUPLICATE_CLIENT_ORDER_ID,
                            "clientOrderId " + clientOrderId + " already used by a different order");
                }
                // 完全相同的请求：幂等返回已有结果（重放也能重建该记录，重启后依旧幂等）
                return buildResult(ordersById.get(seen.orderId()), true);
            }

            // 风控校验（市价单不做购买力预检，逐笔成交时检查）
            riskValidator.validateSubmit(account, symbol, side, type, price, qty);

            // 1) 先预留序号作为订单号，再把带正确 orderId 的事件落盘
            long orderId = eventStore.reserve();
            OrderAcceptedPayload accepted = new OrderAcceptedPayload(
                    orderId, clientOrderId, account, symbol, side, type, price, qty);
            commitEvent(orderId, EventType.ORDER_ACCEPTED, accepted);
            eventSeq = orderId;

            // 2) 内存建单 + 冻结
            Order order = new Order();
            order.setId(orderId);
            order.setClientOrderId(clientOrderId);
            order.setAccount(account);
            order.setSymbol(symbol);
            order.setSide(side);
            order.setType(type);
            order.setLimitPrice(price);
            order.setOriginalQty(qty);
            order.setFilledQty(0);
            order.setStatus(OrderStatus.NEW);
            order.setAcceptedAtSeq(orderId);
            order.setLastUpdatedSeq(orderId);
            ordersById.put(orderId, order);

            // 限价买冻结挂单金额；所有卖单（含市价）受理时冻结持仓，
            // 市价买金额未知无法提前冻结，购买力在逐笔成交时检查
            if (type == OrderType.LIMIT && side == Side.BUY) {
                ledger.freezeForBuy(account, price * qty);
            } else if (side == Side.SELL) {
                ledger.freezeForSell(account, symbol, qty);
            }

            // 3) 撮合
            List<Fill> fills = match(order);

            // 4) 剩余处理：限价挂簿，市价（IOC）未成交部分立即作废
            if (order.remainingQty() > 0) {
                if (type == OrderType.LIMIT) {
                    books.get(symbol).add(order);
                } else {
                    cancelInternal(order, CANCEL_IOC_EXPIRED);
                }
            }
            order.setStatus(resolveStatus(order));

            idempotency.put(clientOrderId, new IdempotentEntry(fingerprint, orderId));
            return buildResult(order, false);
        } finally {
            lock.unlock();
        }
    }

    /** 锁内撮合循环：价格优先、同价时间优先，允许部分成交。逐笔生成 TRADE 事件并结算。 */
    private List<Fill> match(Order taker) {
        List<Fill> fills = new ArrayList<>();
        OrderBook book = books.get(taker.getSymbol());
        String symbol = taker.getSymbol();

        while (taker.remainingQty() > 0) {
            Order maker = book.bestMaker(taker.getSide());
            if (maker == null) {
                break;
            }
            if (taker.getType() == OrderType.LIMIT) {
                // 买单成交价 <= 买限；卖单成交价 >= 卖限
                boolean crossed = taker.getSide() == Side.BUY
                        ? maker.getLimitPrice() <= taker.getLimitPrice()
                        : maker.getLimitPrice() >= taker.getLimitPrice();
                if (!crossed) {
                    break;
                }
            }

            long fillQty = Math.min(taker.remainingQty(), maker.remainingQty());
            long fillPrice = maker.getLimitPrice();

            // 市价买单按可用现金逐笔检查购买力
            if (taker.getSide() == Side.BUY && taker.getType() == OrderType.MARKET) {
                long available = ledger.get(taker.getAccount()).getAvailableCash();
                long maxAffordable = available / fillPrice;
                if (maxAffordable <= 0) {
                    break;
                }
                fillQty = Math.min(fillQty, maxAffordable);
            }

            // 买方冻结额：限价单按其自己挂单价解冻（可能优于成交价），市价单无冻结
            String buyAccount;
            String sellAccount;
            long buyOrderId;
            long sellOrderId;
            long buyerFrozenReduction;
            Side takerSide = taker.getSide();
            if (takerSide == Side.BUY) {
                buyAccount = taker.getAccount();
                sellAccount = maker.getAccount();
                buyOrderId = taker.getId();
                sellOrderId = maker.getId();
                buyerFrozenReduction = taker.getType() == OrderType.LIMIT
                        ? taker.getLimitPrice() * fillQty : 0L;
            } else {
                buyAccount = maker.getAccount();
                sellAccount = taker.getAccount();
                buyOrderId = maker.getId();
                sellOrderId = taker.getId();
                buyerFrozenReduction = maker.getLimitPrice() * fillQty;
            }

            long tradeSeq = eventStore.reserve();
            TradePayload payload = new TradePayload(tradeSeq, symbol, fillPrice, fillQty,
                    buyOrderId, sellOrderId, buyAccount, sellAccount, takerSide);
            commitEvent(tradeSeq, EventType.TRADE, payload);
            eventSeq = tradeSeq;
            long tradeId = tradeSeq;

            // 结算先于内存数量更新，结算抛异常则事件/内存均不会到达（此处买方资金已预检）
            ledger.settleFill(symbol, fillPrice, fillQty, buyAccount, sellAccount,
                    buyerFrozenReduction);

            taker.setFilledQty(taker.getFilledQty() + fillQty);
            maker.setFilledQty(maker.getFilledQty() + fillQty);
            taker.setLastUpdatedSeq(tradeSeq);
            maker.setLastUpdatedSeq(tradeSeq);

            Fill fill = new Fill(tradeId, symbol, fillPrice, fillQty,
                    buyOrderId, sellOrderId, buyAccount, sellAccount, takerSide, tradeSeq);
            fills.add(fill);
            fillsBySymbol.get(symbol).add(fill);

            if (maker.remainingQty() == 0) {
                maker.setStatus(OrderStatus.FILLED);
                book.remove(maker);
            } else {
                maker.setStatus(OrderStatus.PARTIALLY_FILLED);
            }
        }
        return fills;
    }

    // ============================== 撤单 / 改单（下一步实现） ==============================

    /**
     * 撤销委托（仅限价挂单可撤销）。
     * 原子完成：校验 -> ORDER_CANCELLED 事件 -> 移出簿子 -> 按剩余量释放冻结。
     */
    public CancelResult cancel(long orderId) {
        lock.lock();
        try {
            Order order = ordersById.get(orderId);
            if (order == null) {
                throw new RejectException(RejectReason.ORDER_NOT_FOUND,
                        "order not found: " + orderId);
            }
            if (order.isTerminal()) {
                throw new RejectException(RejectReason.ORDER_TERMINAL,
                        "order " + orderId + " already " + order.getStatus());
            }
            long cancelledQty = order.remainingQty();
            cancelInternal(order, CANCEL_USER);
            return new CancelResult(orderId, cancelledQty);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 修改限价单：可改价、改量（newQty 为新的目标累计数量，必须大于已成交量）。
     * 改单成功后订单回到同价位/新价位队列的队尾，主动放弃时间优先。
     * 冻结差额多退少补；市价单不可改。任一步失败原单保持原状。
     */
    public ModifyResult modify(long orderId, Long newLimitPrice, long newQty) {
        lock.lock();
        try {
            Order order = ordersById.get(orderId);
            if (order == null) {
                throw new RejectException(RejectReason.ORDER_NOT_FOUND,
                        "order not found: " + orderId);
            }
            // 市价单不可改的语义优先于终结判定，保证原因稳定可区分
            if (order.getType() != OrderType.LIMIT) {
                throw new RejectException(RejectReason.MARKET_ORDER_NOT_MODIFIABLE,
                        "market order cannot be modified: " + orderId);
            }
            if (order.isTerminal()) {
                throw new RejectException(RejectReason.ORDER_TERMINAL,
                        "order " + orderId + " already " + order.getStatus());
            }
            long targetPrice = newLimitPrice != null ? newLimitPrice : order.getLimitPrice();
            long filled = order.getFilledQty();
            var account = ledger.get(order.getAccount());
            long oldRemaining = order.remainingQty();

            if (order.getSide() == Side.BUY) {
                // 风控按“先释放旧剩余冻结、再按新剩余量全额冻结”的口径检查
                long oldFrozen = order.getLimitPrice() * oldRemaining;
                long newFrozen = targetPrice * (newQty - filled);
                long availableAfterRelease = account.getAvailableCash() + oldFrozen;
                if (targetPrice <= 0) {
                    throw new RejectException(RejectReason.INVALID_PRICE,
                            "new limit price must be positive, got " + targetPrice);
                }
                if (newQty <= 0) {
                    throw new RejectException(RejectReason.INVALID_QUANTITY,
                            "new quantity must be positive, got " + newQty);
                }
                if (newQty <= filled) {
                    throw new RejectException(RejectReason.INVALID_MODIFY_QUANTITY,
                            "new quantity " + newQty + " must be greater than filled " + filled);
                }
                if (availableAfterRelease < newFrozen) {
                    throw new RejectException(RejectReason.INSUFFICIENT_CASH,
                            "modify need " + newFrozen + " cash, available after release "
                                    + availableAfterRelease);
                }
            } else {
                long oldFrozenQty = oldRemaining;
                long newFrozenQty = newQty - filled;
                long availableAfterRelease =
                        account.getAvailablePosition().getOrDefault(order.getSymbol(), 0L)
                                + oldFrozenQty;
                if (newQty <= 0) {
                    throw new RejectException(RejectReason.INVALID_QUANTITY,
                            "new quantity must be positive, got " + newQty);
                }
                if (newQty <= filled) {
                    throw new RejectException(RejectReason.INVALID_MODIFY_QUANTITY,
                            "new quantity " + newQty + " must be greater than filled " + filled);
                }
                if (availableAfterRelease < newFrozenQty) {
                    throw new RejectException(RejectReason.INSUFFICIENT_POSITION,
                            "modify need " + newFrozenQty + " position, available after release "
                                    + availableAfterRelease);
                }
            }

            // 事件先落盘，再执行撤队列 + 冻结调整 + 重排队
            long seq = eventStore.reserve();
            commitEvent(seq, EventType.ORDER_MODIFIED,
                    new OrderModifiedPayload(orderId, targetPrice, newQty));
            eventSeq = seq;

            books.get(order.getSymbol()).remove(order);

            if (order.getSide() == Side.BUY) {
                long oldFrozen = order.getLimitPrice() * oldRemaining;
                long newFrozen = targetPrice * (newQty - filled);
                ledger.adjustBuyFreeze(order.getAccount(), oldFrozen, newFrozen);
            } else {
                ledger.adjustSellFreeze(order.getAccount(), order.getSymbol(),
                        oldRemaining, newQty - filled);
            }

            order.setLimitPrice(targetPrice);
            order.setOriginalQty(newQty);
            order.setStatus(filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW);
            order.setLastUpdatedSeq(seq);
            order.setRequeueSeq(seq);
            // 重排队尾：时间优先按最近一次修改重新计算
            books.get(order.getSymbol()).add(order);

            return new ModifyResult(orderId, targetPrice, newQty);
        } finally {
            lock.unlock();
        }
    }

    // ============================== 重放（下一步实现） ==============================

    /**
     * 从事件日志重放，重建订单簿、订单、成交与账户冻结/余额。
     * 重放只消费已持久化的“事实事件”，因此结果与当时在线执行完全一致。
     *
     * <p>记录必须满足：seq 从 1 开始、连续、严格递增且引用的订单已存在。
     * <ul>
     *   <li>strict 模式（默认）：任一记录违规即抛 {@link ReplayRejectException} 中止；</li>
     *   <li>lenient 模式：跳过违规记录，在报告中按 seq 记录可区分的
     *       {@link ReplayRejectReason}，且不会改变已有结果。</li>
     * </ul>
     * 注意：重放前内存必须为空（请用新实例或先 {@link #resetAll()}）。
     */
    public ReplayReport replayFromLog() {
        lock.lock();
        try {
            if (!ordersById.isEmpty()) {
                throw new IllegalStateException("replay requires empty in-memory state");
            }
            ReplayReport report = new ReplayReport();
            List<EventRecord> records = eventStore.readAll();
            long expected = 1L;
            long applied = 0L;
            long lastAppliedSeq = 0L;

            for (EventRecord rec : records) {
                try {
                    // 1) 序号纪律：
                    //    seq < expected 且已应用过 -> DUPLICATE_SEQUENCE；
                    //    seq < expected 但从未应用（缺口后混入的旧序号）-> LATE_EVENT；
                    //    seq > expected（跳号）-> OUT_OF_ORDER。
                    if (rec.seq() < expected) {
                        if (rec.seq() <= lastAppliedSeq) {
                            rejectRecord(report, rec.seq(), ReplayRejectReason.DUPLICATE_SEQUENCE,
                                    "duplicate seq " + rec.seq());
                        } else {
                            rejectRecord(report, rec.seq(), ReplayRejectReason.LATE_EVENT,
                                    "late seq " + rec.seq() + ", expected " + expected);
                        }
                        continue;
                    }
                    if (rec.seq() > expected) {
                        rejectRecord(report, rec.seq(), ReplayRejectReason.OUT_OF_ORDER,
                                "seq gap: expected " + expected + ", got " + rec.seq());
                        continue;
                    }

                    EventType eventType;
                    try {
                        eventType = EventType.valueOf(rec.type());
                    } catch (IllegalArgumentException badType) {
                        throw new PayloadCorruptException(
                                "unknown event type: " + rec.type(), badType);
                    }
                    switch (eventType) {
                        case ORDER_ACCEPTED -> applyAcceptedReplay(rec);
                        case TRADE -> applyTradeReplay(rec);
                        case ORDER_CANCELLED -> applyCancelledReplay(rec);
                        case ORDER_MODIFIED -> applyModifiedReplay(rec);
                    }
                    expected++;
                    applied++;
                    lastAppliedSeq = rec.seq();
                    report.setLastSeq(rec.seq());
                } catch (PayloadCorruptException corrupt) {
                    rejectRecord(report, rec.seq(), ReplayRejectReason.CORRUPT_PAYLOAD,
                            corrupt.getMessage());
                } catch (ReplayRejectException rre) {
                    rejectRecord(report, rec.seq(), rre.getReason(), rre.getMessage());
                }
            }
            // 收尾：按受理顺序（即 orderId/事件序号）把存活限价单挂入簿子。
            // 在线路径上吃单先成交后入簿、改单重排队，因此最后在簿的只有
            // “当前未终结、且最后一次有效变更后仍有剩余”的限价单；FIFO 由 orderId 升序还原。
            List<Order> resting = ordersById.values().stream()
                    .filter(o -> o.getType() == OrderType.LIMIT)
                    .filter(o -> o.getStatus() != OrderStatus.CANCELLED)
                    .filter(o -> o.remainingQty() > 0)
                    .sorted(java.util.Comparator.comparingLong(Order::getRequeueSeq)
                            .thenComparingLong(Order::getId))
                    .toList();
            for (Order o : resting) {
                if (o.getFilledQty() > 0) {
                    o.setStatus(OrderStatus.PARTIALLY_FILLED);
                } else {
                    o.setStatus(OrderStatus.NEW);
                }
                books.get(o.getSymbol()).add(o);
            }
            report.setAppliedCount(applied);
            eventSeq = report.getLastSeq();
            return report;
        } finally {
            lock.unlock();
        }
    }

    /** 严格模式直接抛；宽松模式登记跳过原因并继续。两种模式下违规记录都不会生效。 */
    private void rejectRecord(ReplayReport report, long seq, ReplayRejectReason reason,
                              String message) {
        if ("lenient".equalsIgnoreCase(replayMode)) {
            report.getSkipped().put(seq, reason);
        } else {
            throw new ReplayRejectException(seq, reason, message);
        }
    }

    /** 重放内部的损坏负载标记，统一映射为 CORRUPT_PAYLOAD。 */
    private static final class PayloadCorruptException extends RuntimeException {
        PayloadCorruptException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private <T> T parsePayload(EventRecord rec, Class<T> type) {
        try {
            return JSON.readValue(rec.payloadJson(), type);
        } catch (Exception e) {
            throw new PayloadCorruptException(
                    "unreadable " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private void applyAcceptedReplay(EventRecord rec) {
        OrderAcceptedPayload p = parsePayload(rec, OrderAcceptedPayload.class);
        if (p.orderId() != rec.seq()) {
            throw new PayloadCorruptException(
                    "orderId " + p.orderId() + " != seq " + rec.seq(), null);
        }
        if (!symbols.contains(p.symbol()) || ledger.get(p.account()) == null) {
            // 账户/合约在种子数据里不存在，日志与当前账本环境不兼容：视为损坏
            throw new PayloadCorruptException(
                    "unknown account/symbol in accepted event", null);
        }
        Order order = new Order();
        order.setId(p.orderId());
        order.setClientOrderId(p.clientOrderId());
        order.setAccount(p.account());
        order.setSymbol(p.symbol());
        order.setSide(p.side());
        order.setType(p.type());
        order.setLimitPrice(p.limitPrice());
        order.setOriginalQty(p.originalQty());
        order.setFilledQty(0);
        order.setStatus(OrderStatus.NEW);
        order.setAcceptedAtSeq(p.orderId());
        order.setLastUpdatedSeq(p.orderId());
        order.setRequeueSeq(p.orderId());
        ordersById.put(order.getId(), order);

        if (p.type() == OrderType.LIMIT && p.side() == Side.BUY) {
            ledger.freezeForBuy(p.account(), p.limitPrice() * p.originalQty());
        } else if (p.side() == Side.SELL) {
            ledger.freezeForSell(p.account(), p.symbol(), p.originalQty());
        }
        idempotency.put(p.clientOrderId(), new IdempotentEntry(
                fingerprint(p.account(), p.symbol(), p.side(), p.type(),
                        p.limitPrice(), p.originalQty()),
                p.orderId()));
        // 成交 / 撤销事件随后决定它最终在簿内还是已终结，此处先不挂簿
    }

    private void applyTradeReplay(EventRecord rec) {
        TradePayload p = parsePayload(rec, TradePayload.class);
        Order taker = ordersById.get(p.takerSide() == Side.BUY ? p.buyOrderId() : p.sellOrderId());
        Order maker = ordersById.get(p.takerSide() == Side.BUY ? p.sellOrderId() : p.buyOrderId());
        if (taker == null || maker == null) {
            throw new ReplayRejectException(rec.seq(), ReplayRejectReason.UNKNOWN_ORDER,
                    "trade references missing order(s)");
        }
        long buyerReduction = p.buyOrderId() == taker.getId()
                ? (taker.getType() == OrderType.LIMIT ? taker.getLimitPrice() * p.qty() : 0L)
                : maker.getLimitPrice() * p.qty();

        ledger.settleFill(p.symbol(), p.price(), p.qty(),
                p.buyAccount(), p.sellAccount(), buyerReduction);

        taker.setFilledQty(taker.getFilledQty() + p.qty());
        maker.setFilledQty(maker.getFilledQty() + p.qty());
        taker.setLastUpdatedSeq(rec.seq());
        maker.setLastUpdatedSeq(rec.seq());

        if (maker.remainingQty() == 0) {
            maker.setStatus(OrderStatus.FILLED);
        } else {
            maker.setStatus(OrderStatus.PARTIALLY_FILLED);
        }
        fillsBySymbol.get(p.symbol()).add(new Fill(p.tradeId(), p.symbol(), p.price(), p.qty(),
                p.buyOrderId(), p.sellOrderId(), p.buyAccount(), p.sellAccount(),
                p.takerSide(), rec.seq()));
    }

    private void applyCancelledReplay(EventRecord rec) {
        OrderCancelledPayload p = parsePayload(rec, OrderCancelledPayload.class);
        Order order = ordersById.get(p.orderId());
        if (order == null) {
            throw new ReplayRejectException(rec.seq(), ReplayRejectReason.UNKNOWN_ORDER,
                    "cancel references missing order " + p.orderId());
        }
        long remaining = order.remainingQty();
        if (order.getSide() == Side.SELL && remaining > 0) {
            // 限价卖与市价卖都冻结了持仓；重放期间订单统一在收尾阶段入簿，这里只释放冻结
            ledger.releaseSellFreeze(order.getAccount(), order.getSymbol(), remaining);
        } else if (order.getType() == OrderType.LIMIT && remaining > 0) {
            ledger.releaseBuyFreeze(order.getAccount(), order.getLimitPrice() * remaining);
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setLastUpdatedSeq(rec.seq());
    }

    private void applyModifiedReplay(EventRecord rec) {
        OrderModifiedPayload p = parsePayload(rec, OrderModifiedPayload.class);
        Order order = ordersById.get(p.orderId());
        if (order == null) {
            throw new ReplayRejectException(rec.seq(), ReplayRejectReason.UNKNOWN_ORDER,
                    "modify references missing order " + p.orderId());
        }
        long filled = order.getFilledQty();
        long oldRemaining = order.remainingQty();
        // 重放期间订单尚未入簿，只按在线路径调整冻结；最终入簿在重放收尾阶段完成
        if (order.getSide() == Side.BUY) {
            long oldFrozen = order.getLimitPrice() * oldRemaining;
            long newFrozen = p.newLimitPrice() * (p.newQty() - filled);
            ledger.adjustBuyFreeze(order.getAccount(), oldFrozen, newFrozen);
        } else {
            ledger.adjustSellFreeze(order.getAccount(), order.getSymbol(),
                    oldRemaining, p.newQty() - filled);
        }
        order.setLimitPrice(p.newLimitPrice());
        order.setOriginalQty(p.newQty());
        order.setStatus(filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW);
        order.setLastUpdatedSeq(rec.seq());
        order.setRequeueSeq(rec.seq());
    }

    // ============================== 查询 ==============================

    public Order getOrder(long orderId) {
        lock.lock();
        try {
            return ordersById.get(orderId);
        } finally {
            lock.unlock();
        }
    }

    public List<Fill> fillsOf(String symbol) {
        lock.lock();
        try {
            List<Fill> fills = fillsBySymbol.get(symbol);
            return fills == null ? List.of() : List.copyOf(fills);
        } finally {
            lock.unlock();
        }
    }

    public BookSnapshot bookSnapshot(String symbol) {
        lock.lock();
        try {
            OrderBook book = books.get(symbol);
            if (book == null) {
                throw new RejectException(RejectReason.INVALID_SYMBOL, "unknown symbol: " + symbol);
            }
            Map<Side, List<long[]>> snap = book.snapshot();
            return new BookSnapshot(symbol, snap.get(Side.BUY), snap.get(Side.SELL));
        } finally {
            lock.unlock();
        }
    }

    public AccountLedger getLedger() {
        return ledger;
    }

    public Set<String> getSymbols() {
        return Collections.unmodifiableSet(symbols);
    }

    /** 管理端 / 测试：重置事件日志与全部内存状态（账本账户由调用方重新登记）。 */
    public void resetAll() {
        lock.lock();
        try {
            eventStore.reset();
            books.clear();
            fillsBySymbol.clear();
            for (String s : symbols) {
                books.put(s, new OrderBook(s));
                fillsBySymbol.put(s, new ArrayList<>());
            }
            ordersById.clear();
            idempotency.clear();
            eventSeq = 0;
        } finally {
            lock.unlock();
        }
    }

    // ============================== 内部工具 ==============================

    /** 锁内撤单：从簿子移除并按剩余量释放冻结，记录 ORDER_CANCELLED 事件。 */
    private void cancelInternal(Order order, String reason) {
        long seq = eventStore.reserve();
        commitEvent(seq, EventType.ORDER_CANCELLED,
                new OrderCancelledPayload(order.getId(), reason));
        eventSeq = seq;
        long remaining = order.remainingQty();
        if (remaining > 0 && order.getType() == OrderType.LIMIT) {
            books.get(order.getSymbol()).remove(order);
        }
        if (remaining > 0 && order.getSide() == Side.BUY
                && order.getType() == OrderType.LIMIT) {
            ledger.releaseBuyFreeze(order.getAccount(), order.getLimitPrice() * remaining);
        } else if (remaining > 0 && order.getSide() == Side.SELL) {
            // 限价卖与市价卖都冻结了持仓，IOC 到期/撤单都必须释放剩余部分
            ledger.releaseSellFreeze(order.getAccount(), order.getSymbol(), remaining);
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setLastUpdatedSeq(seq);
    }

    private static OrderStatus resolveStatus(Order o) {
        if (o.getStatus() == OrderStatus.CANCELLED || o.getStatus() == OrderStatus.FILLED) {
            return o.getStatus();
        }
        return o.remainingQty() == 0 ? OrderStatus.FILLED
                : o.getFilledQty() > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW;
    }

    private SubmitResult buildResult(Order order, boolean idempotentReplay) {
        List<Fill> myFills = new ArrayList<>();
        for (Fill f : fillsBySymbol.getOrDefault(order.getSymbol(), List.of())) {
            long oid = order.getSide() == Side.BUY ? f.buyOrderId() : f.sellOrderId();
            if (oid == order.getId()) {
                myFills.add(f);
            }
        }
        return new SubmitResult(order.getId(), order.getStatus(),
                List.copyOf(myFills), idempotentReplay);
    }

    /** 将带正确序号的事件负载序列化并落盘（调用方已先 reserve）。 */
    private void commitEvent(long seq, EventType type, Object payload) {
        String json;
        try {
            json = JSON.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("encode payload failed: " + type, e);
        }
        eventStore.commit(seq, type, json);
    }

    private static String fingerprint(String account, String symbol, Side side,
                                      OrderType type, long price, long qty) {
        return account + '|' + symbol + '|' + side + '|' + type + '|' + price + '|' + qty;
    }

    private static void validateClientOrderId(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new RejectException(RejectReason.INVALID_CLIENT_ORDER_ID,
                    "clientOrderId is required");
        }
    }

    private static long requirePositive(Long value, RejectReason reason, String field) {
        if (value == null || value <= 0) {
            throw new RejectException(reason, field + " must be positive");
        }
        return value;
    }

}

