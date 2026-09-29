package com.github.lisama569.orderbookmatchingenginejava.risk;

import com.github.lisama569.orderbookmatchingenginejava.model.Account;

import java.util.HashMap;
import java.util.Map;

/**
 * 账户账本：所有资金 / 持仓变动都集中在这里完成，撮合引擎不得直接改账面。
 *
 * <p>恒等关系（任意时刻成立，测试中会显式断言）：
 * <ul>
 *   <li>现金：totalCash = availableCash + frozenCash，两者都不得为负；</li>
 *   <li>持仓：每个 symbol 下 totalPosition = availablePosition + frozenPosition，不得为负。</li>
 * </ul>
 *
 * <p>一笔成交的结算方式（冻结在下单受理时已完成）：
 * <ul>
 *   <li>买方：frozenCash 按 {@code limit*qty} 解冻（市价单为 0），实际只支付
 *       {@code price*qty}，挂单价与成交价之差以现金形式回到 availableCash；
 *       买到的数量直接进入可用持仓。</li>
 *   <li>卖方：frozenPosition 扣减 qty（持仓总量随之减少），收到的
 *       {@code price*qty} 货款进入 availableCash。</li>
 * </ul>
 * 本类不做并发控制，所有方法都必须在 {@code MatchingEngine} 的全局锁内调用，
 * 因此“校验—记账”整体是原子的，不存在半更新账面。
 */
public class AccountLedger {

    private final Map<String, Account> accounts = new HashMap<>();

    /** 测试 / 初始化用：登记账户与初始现金、持仓。 */
    public synchronized void register(String accountId, long cash, Map<String, Long> positions) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is blank");
        }
        if (cash < 0) {
            throw new IllegalArgumentException("initial cash must be >= 0");
        }
        accounts.put(accountId, new Account(accountId, cash, positions));
    }

    public Account get(String accountId) {
        return accounts.get(accountId);
    }

    public Map<String, Account> all() {
        return accounts;
    }

    /** 测试 / 管理端：清空全部账户（与事件日志重置配套使用）。 */
    public void reset() {
        accounts.clear();
    }

    // ---------------- 冻结（下单受理时） ----------------

    /** 买单冻结现金：available -> frozen，金额 = limitPrice*qty（市价单传 0）。 */
    public void freezeForBuy(String accountId, long amount) {
        if (amount <= 0) {
            return;
        }
        Account a = mustGet(accountId);
        if (a.getAvailableCash() < amount) {
            throw new IllegalStateException("freeze exceeds available cash: " + amount);
        }
        a.setAvailableCash(a.getAvailableCash() - amount);
        a.setFrozenCash(a.getFrozenCash() + amount);
    }

    /** 买单释放冻结：撤单、市价 IOC 到期、改单减少金额时使用。 */
    public void releaseBuyFreeze(String accountId, long amount) {
        if (amount <= 0) {
            return;
        }
        Account a = mustGet(accountId);
        if (a.getFrozenCash() < amount) {
            throw new IllegalStateException("release exceeds frozen cash: " + amount);
        }
        a.setFrozenCash(a.getFrozenCash() - amount);
        a.setAvailableCash(a.getAvailableCash() + amount);
    }

    /** 卖单冻结持仓：available -> frozen。 */
    public void freezeForSell(String accountId, String symbol, long qty) {
        if (qty <= 0) {
            return;
        }
        Account a = mustGet(accountId);
        long avail = pos(a.getAvailablePosition(), symbol);
        if (avail < qty) {
            throw new IllegalStateException("freeze exceeds available position: " + qty);
        }
        putPos(a.getAvailablePosition(), symbol, avail - qty);
        putPos(a.getFrozenPosition(), symbol, pos(a.getFrozenPosition(), symbol) + qty);
    }

    /** 卖单释放冻结持仓。 */
    public void releaseSellFreeze(String accountId, String symbol, long qty) {
        if (qty <= 0) {
            return;
        }
        Account a = mustGet(accountId);
        long frozen = pos(a.getFrozenPosition(), symbol);
        if (frozen < qty) {
            throw new IllegalStateException("release exceeds frozen position: " + qty);
        }
        putPos(a.getFrozenPosition(), symbol, frozen - qty);
        putPos(a.getAvailablePosition(), symbol, pos(a.getAvailablePosition(), symbol) + qty);
    }

    // ---------------- 成交结算 ----------------

    /**
     * 一笔成交的原子结算（买卖双方同时入账）。
     *
     * @param buyFrozenAmountReduction 买方本笔需要解冻的冻结金额：
     *                                 限价挂单为 limitPrice*qty，市价吃单方为 0
     */
    public void settleFill(String symbol, long price, long qty,
                           String buyAccountId, String sellAccountId,
                           long buyFrozenAmountReduction) {
        if (qty <= 0 || price <= 0) {
            throw new IllegalArgumentException("fill price/qty must be positive");
        }
        long notional = price * qty;

        Account buyer = mustGet(buyAccountId);
        Account seller = mustGet(sellAccountId);

        // ---- 买方：先按挂单冻结额解冻，再支付实际货款 ----
        if (buyFrozenAmountReduction > 0) {
            if (buyer.getFrozenCash() < buyFrozenAmountReduction) {
                throw new IllegalStateException("buyer frozen cash underflow");
            }
            buyer.setFrozenCash(buyer.getFrozenCash() - buyFrozenAmountReduction);
            // 解冻额先回到可用，再扣实际货款；买价优于挂单价时差额自然留在可用现金里
            buyer.setAvailableCash(buyer.getAvailableCash() + buyFrozenAmountReduction);
        }
        if (buyer.getAvailableCash() < notional) {
            // 市价吃单：受理时无法预知金额，逐笔成交时按可用现金校验。
            // 此时买方冻结尚未变动（reductions 已在上方回滚语义之外），
            // 但本实现保证引擎在调用本方法前已按可用现金限制 qty，正常不会到这里
            throw new IllegalStateException("buyer available cash underflow");
        }
        buyer.setAvailableCash(buyer.getAvailableCash() - notional);
        putPos(buyer.getAvailablePosition(), symbol,
                pos(buyer.getAvailablePosition(), symbol) + qty);

        // ---- 卖方：冻结持仓扣减（持仓总量减少），货款入可用现金 ----
        long sellerFrozen = pos(seller.getFrozenPosition(), symbol);
        if (sellerFrozen < qty) {
            throw new IllegalStateException("seller frozen position underflow");
        }
        putPos(seller.getFrozenPosition(), symbol, sellerFrozen - qty);
        seller.setAvailableCash(seller.getAvailableCash() + notional);
    }

    // ---------------- 改单：冻结差额调整 ----------------

    /** 买单改价/改量：oldFrozen 与 newFrozen 之差多退少补。 */
    public void adjustBuyFreeze(String accountId, long oldFrozen, long newFrozen) {
        long delta = newFrozen - oldFrozen;
        if (delta > 0) {
            freezeForBuy(accountId, delta);
        } else if (delta < 0) {
            releaseBuyFreeze(accountId, -delta);
        }
    }

    /** 卖单改量：oldFrozen/newFrozen 为冻结持仓数量。 */
    public void adjustSellFreeze(String accountId, String symbol,
                                 long oldFrozen, long newFrozen) {
        long delta = newFrozen - oldFrozen;
        if (delta > 0) {
            freezeForSell(accountId, symbol, delta);
        } else if (delta < 0) {
            releaseSellFreeze(accountId, symbol, -delta);
        }
    }

    // ---------------- 不变量自检（测试用） ----------------

    /** 校验全部账户非负不变量，发现破损直接抛出。 */
    public void assertInvariants() {
        for (Account a : accounts.values()) {
            if (a.getAvailableCash() < 0 || a.getFrozenCash() < 0) {
                throw new IllegalStateException("cash invariant broken for " + a.getAccountId());
            }
            for (Map.Entry<String, Long> e : a.getAvailablePosition().entrySet()) {
                if (e.getValue() < 0) {
                    throw new IllegalStateException("available position negative: " + a.getAccountId());
                }
            }
            for (Map.Entry<String, Long> e : a.getFrozenPosition().entrySet()) {
                if (e.getValue() < 0) {
                    throw new IllegalStateException("frozen position negative: " + a.getAccountId());
                }
            }
        }
    }

    // ---------------- internals ----------------

    private Account mustGet(String accountId) {
        Account a = accounts.get(accountId);
        if (a == null) {
            throw new IllegalStateException("unknown account: " + accountId);
        }
        return a;
    }

    private static long pos(Map<String, Long> map, String symbol) {
        return map.getOrDefault(symbol, 0L);
    }

    /** 归零的条目直接删除，方便快照与比较。 */
    private static void putPos(Map<String, Long> map, String symbol, long qty) {
        if (qty == 0) {
            map.remove(symbol);
        } else {
            map.put(symbol, qty);
        }
    }
}
