package com.github.lisama569.orderbookmatchingenginejava.risk;

import com.github.lisama569.orderbookmatchingenginejava.model.Account;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.RejectException;
import com.github.lisama569.orderbookmatchingenginejava.model.RejectReason;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;

import java.util.Set;

/**
 * 下单 / 改单前的参数与资金风控校验。
 * 全部失败都以可区分的 {@link RejectReason} 抛出，绝不合并成笼统失败。
 *
 * <p>校验顺序固定，失败原因因此确定、可解释：
 * 合约 -> 账户 -> 价格 -> 数量 -> 资金/持仓。
 */
public class RiskValidator {

    private final AccountLedger ledger;
    private final Set<String> symbols;

    public RiskValidator(AccountLedger ledger, Set<String> symbols) {
        this.ledger = ledger;
        this.symbols = symbols;
    }

    /**
     * 下单前校验。
     * 限价单按 limitPrice*qty 全额冻结；市价单受理时金额未知，
     * 仅做参数校验，购买力在逐笔成交时按可用现金检查。
     */
    public void validateSubmit(String accountId, String symbol, Side side, OrderType type,
                               long limitPrice, long qty) {
        if (symbol == null || !symbols.contains(symbol)) {
            throw new RejectException(RejectReason.INVALID_SYMBOL,
                    "unknown symbol: " + symbol);
        }
        if (side == null) {
            throw new RejectException(RejectReason.INVALID_PRICE, "side is required");
        }
        Account account = ledger.get(accountId);
        if (account == null) {
            throw new RejectException(RejectReason.UNKNOWN_ACCOUNT,
                    "unknown account: " + accountId);
        }
        if (type == OrderType.LIMIT) {
            if (limitPrice <= 0) {
                throw new RejectException(RejectReason.INVALID_PRICE,
                        "limit price must be positive, got " + limitPrice);
            }
        }
        if (qty <= 0) {
            throw new RejectException(RejectReason.INVALID_QUANTITY,
                    "quantity must be positive, got " + qty);
        }

        // 卖单（含市价）受理时必须持有足够可用持仓，市价卖也不能卖空
        if (side == Side.SELL) {
            long have = account.getAvailablePosition().getOrDefault(symbol, 0L);
            if (have < qty) {
                throw new RejectException(RejectReason.INSUFFICIENT_POSITION,
                        "need " + qty + " of " + symbol + ", have " + have);
            }
        }
        // 限价买按挂单金额全额冻结；市价买金额未知，购买力在逐笔成交时检查
        if (type == OrderType.LIMIT && side == Side.BUY) {
            long need = limitPrice * qty;
            if (account.getAvailableCash() < need) {
                throw new RejectException(RejectReason.INSUFFICIENT_CASH,
                        "need " + need + " available cash, have " + account.getAvailableCash());
            }
        }
    }

    /** 买单改单校验：newQty 必须大于已成交数量；新加价金额必须买得起。 */
    public void validateModifyBuy(Account account, long newLimitPrice, long newQty,
                                  long filledQty) {
        if (newLimitPrice <= 0) {
            throw new RejectException(RejectReason.INVALID_PRICE,
                    "new limit price must be positive, got " + newLimitPrice);
        }
        if (newQty <= 0) {
            throw new RejectException(RejectReason.INVALID_QUANTITY,
                    "new quantity must be positive, got " + newQty);
        }
        if (newQty <= filledQty) {
            throw new RejectException(RejectReason.INVALID_MODIFY_QUANTITY,
                    "new quantity " + newQty + " must be greater than filled " + filledQty);
        }
        long need = newLimitPrice * (newQty - filledQty);
        // 改单时旧单剩余部分的冻结会先释放，等价于按全新剩余量重新冻结，
        // 因此这里直接检查全部新剩余量的资金需求
        if (account.getAvailableCash() + 0 < need) {
            throw new RejectException(RejectReason.INSUFFICIENT_CASH,
                    "modify need " + need + " available cash, have " + account.getAvailableCash());
        }
    }

    /** 卖单改单校验：新剩余冻结量必须由当前可用持仓覆盖（旧冻结先释放）。 */
    public void validateModifySell(Account account, String symbol, long newQty,
                                   long filledQty) {
        if (newQty <= 0) {
            throw new RejectException(RejectReason.INVALID_QUANTITY,
                    "new quantity must be positive, got " + newQty);
        }
        if (newQty <= filledQty) {
            throw new RejectException(RejectReason.INVALID_MODIFY_QUANTITY,
                    "new quantity " + newQty + " must be greater than filled " + filledQty);
        }
        long need = newQty - filledQty;
        long have = account.getAvailablePosition().getOrDefault(symbol, 0L);
        if (have < need) {
            throw new RejectException(RejectReason.INSUFFICIENT_POSITION,
                    "modify need " + need + " of " + symbol + ", have " + have);
        }
    }
}
