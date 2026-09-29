package com.github.lisama569.orderbookmatchingenginejava.tests;

import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 重启重放：构造"部分成交 + 撤单 + 改单 + 滑点"混合场景，
 * 重放后盘口、成交序列、每个账户的四项余额必须与重启前逐项一致。
 */
class ReplayConsistencyTest extends AbstractEngineTest {

    @Test
    @DisplayName("混合场景重放得到同一份盘口与同一份账户余额")
    void replayRebuildsIdenticalBookAndBalances() {
        engine.addAccount("ALICE", 100_000, 0);
        engine.addAccount("BOB", 0, 1_000);
        engine.addAccount("CAROL", 100_000, 0);

        // BOB 挂 30@100 与 20@105
        engine.submit("bob-s1", "BOB", "BTC", Side.SELL, OrderType.LIMIT, 100, 30);
        engine.submit("bob-s2", "BOB", "BTC", Side.SELL, OrderType.LIMIT, 105, 20);

        // ALICE 买 50@110：吃 30@100 + 20@105，全部成交（滑点退回）
        engine.submit("alice-b1", "ALICE", "BTC", Side.BUY, OrderType.LIMIT, 110, 50);

        // CAROL 挂买单 40@99 后改单为 40@102（无对手卖单，留在簿中）
        var carolOrder = engine.submit("carol-b1", "CAROL", "BTC",
                Side.BUY, OrderType.LIMIT, 99, 40);
        engine.modify("carol-mod1", "CAROL", carolOrder.getOrderId(), 102, 40);

        // BOB 再挂 100@98 然后撤掉 60（部分冻结释放演示：无成交，全撤）
        var bobRest = engine.submit("bob-s3", "BOB", "BTC", Side.SELL, OrderType.LIMIT, 98, 100);
        engine.cancel("bob-cancel1", "BOB", bobRest.getOrderId());

        // CAROL 再挂 20@90（留在簿）
        engine.submit("carol-b2", "CAROL", "BTC", Side.BUY, OrderType.LIMIT, 90, 20);

        Map<String, Object> beforeBook = engine.snapshot("BTC");
        int tradeCountBefore = engine.trades().size();
        Map<String, Long> aliceBefore = snapshotAccount("ALICE");
        Map<String, Long> bobBefore = snapshotAccount("BOB");
        Map<String, Long> carolBefore = snapshotAccount("CAROL");

        log.info("[重启前盘口] {}", beforeBook);
        log.info("[重启前账户] ALICE={} BOB={} CAROL={}",
                aliceBefore, bobBefore, carolBefore);

        // ---- 模拟进程重启：事件存储从文件重载，引擎清空内存后重放 ----
        eventStore.reloadFromFile();
        engine.replayFromLog();

        Map<String, Object> afterBook = engine.snapshot("BTC");
        log.info("[重放后盘口] {}", afterBook);
        assertEquals(levels(beforeBook, "bids"), levels(afterBook, "bids"));
        assertEquals(levels(beforeBook, "asks"), levels(afterBook, "asks"));
        assertEquals(String.valueOf(beforeBook.get("bestBid")),
                String.valueOf(afterBook.get("bestBid")));
        assertEquals(String.valueOf(beforeBook.get("bestAsk")),
                String.valueOf(afterBook.get("bestAsk")));

        assertEquals(tradeCountBefore, engine.trades().size());
        assertAccountEquals(aliceBefore, snapshotAccount("ALICE"), "ALICE");
        assertAccountEquals(bobBefore, snapshotAccount("BOB"), "BOB");
        assertAccountEquals(carolBefore, snapshotAccount("CAROL"), "CAROL");

        log.info("[判定依据] 盘口四元组、成交条数、三个账户的四项余额逐项一致");
    }

    private Map<String, Long> snapshotAccount(String id) {
        var a = engine.getAccount(id);
        return Map.of(
                "cashAvailable", a.getCashAvailable(),
                "cashFrozen", a.getCashFrozen(),
                "positionAvailable", a.getPositionAvailable(),
                "positionFrozen", a.getPositionFrozen());
    }

    private void assertAccountEquals(Map<String, Long> expected, Map<String, Long> actual,
                                     String who) {
        assertEquals(expected, actual, "account " + who + " diverged after replay");
    }


    @SuppressWarnings("unchecked")
    private static String levels(Map<String, Object> book, String side) {
        java.util.List<long[]> levels = (java.util.List<long[]>) book.get(side);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < levels.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(java.util.Arrays.toString(levels.get(i)));
        }
        return sb.append("]").toString();
    }

}
