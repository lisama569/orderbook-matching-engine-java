package com.github.lisama569.orderbookmatchingenginejava.risk;

import com.github.lisama569.orderbookmatchingenginejava.exception.RejectReason;
import com.github.lisama569.orderbookmatchingenginejava.exception.TradingException;
import com.github.lisama569.orderbookmatchingenginejava.model.Account;
import org.springframework.stereotype.Component;

/**
 * 下单/改单前的静态参数校验与资金、持仓风控。
 * 只做纯函数式判断，不修改任何账面状态；任何修改账面的动作都在引擎单锁内完成。
 *
 * <p>价格、数量均为正整数（最小报价/数量单位的整数倍），从根上避免浮点误差。
 */
@Component
public class RiskValidator {

    /** 静态参数校验：账户、品种、客户端订单号、价格、数量；非法即抛带区分原因的异常 */
    public void validateStatic(String accountId, String symbol, long price, long quantity,
                               boolean marketOrder, java.util.Set<String> knownAccounts) {
        if (accountId == null || accountId.isBlank()) {
            throw new TradingException(RejectReason.ORDER_UNKNOWN_ACCOUNT,
                    "accountId is blank");
        }
        if (!knownAccounts.contains(accountId)) {
            throw new TradingException(RejectReason.ORDER_UNKNOWN_ACCOUNT,
                    "unknown account: " + accountId);
        }
        if (symbol == null || symbol.isBlank()) {
            throw new TradingException(RejectReason.ORDER_INVALID_SYMBOL,
                    "symbol is blank");
        }
        if (!marketOrder && price <= 0) {
            throw new TradingException(RejectReason.ORDER_INVALID_PRICE,
                    "limit price must be positive, got " + price);
        }
        if (marketOrder && price != 0) {
            throw new TradingException(RejectReason.ORDER_INVALID_PRICE,
                    "market order price must be 0, got " + price);
        }
        if (quantity <= 0) {
            throw new TradingException(RejectReason.ORDER_INVALID_QUANTITY,
                    "quantity must be positive, got " + quantity);
        }
    }

    /** 买入资金校验：可用现金必须足够覆盖整单冻结额（限价=price*qty，市价为最差成本估算） */
    public void checkBuyCash(Account account, long needCash) {
        if (needCash > account.getCashAvailable()) {
            throw new TradingException(RejectReason.RISK_INSUFFICIENT_CASH,
                    "cash not enough: need=" + needCash
                            + ", available=" + account.getCashAvailable());
        }
    }

    /** 卖出持仓校验：可用持仓必须足够覆盖整单冻结数量 */
    public void checkSellPosition(Account account, long needPosition) {
        if (needPosition > account.getPositionAvailable()) {
            throw new TradingException(RejectReason.RISK_INSUFFICIENT_POSITION,
                    "position not enough: need=" + needPosition
                            + ", available=" + account.getPositionAvailable());
        }
    }
}
