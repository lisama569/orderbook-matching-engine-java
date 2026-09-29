package com.github.lisama569.orderbookmatchingenginejava.tests;

import com.github.lisama569.orderbookmatchingenginejava.event.Event;
import com.github.lisama569.orderbookmatchingenginejava.event.EventType;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部事件流重放校验：乱序、重复一致（忽略）、重复冲突（拒绝）、迟到，
 * 结论必须可区分，且非法流不得改变运行态。
 */
class EventIngestionTest extends AbstractEngineTest {

    private List<Event> buildBaseLog() {
        engine.addAccount("ALICE", 100_000, 0);
        engine.addAccount("BOB", 0, 100);
        engine.submit("s1", "BOB", "BTC", Side.SELL, OrderType.LIMIT, 100, 10);
        engine.submit("b1", "ALICE", "BTC", Side.BUY, OrderType.LIMIT, 100, 5);
        return eventStore.all();
    }

    @Test
    @DisplayName("正常日志校验通过并可在隔离态重放")
    void cleanLogIsValid() {
        var events = buildBaseLog();
        var report = engine.validateAndReplay(events);
        log.info("[判定依据] 正常日志 applied={}, valid={}",
                report.get("applied"), report.get("valid"));
        assertEquals(true, report.get("valid"));
        assertEquals(events.size(), report.get("applied"));
    }

    @Test
    @DisplayName("seq 跳跃 => EVENT_OUT_OF_ORDER")
    void gapIsOutOfOrder() {
        var events = new java.util.ArrayList<>(buildBaseLog());
        Event last = events.remove(events.size() - 1);
        events.add(new Event(last.seq() + 5, last.type(), last.timestampMillis(),
                last.payloadJson()));
        var report = engine.validateAndReplay(events);
        log.info("[输入] 删除末条后插入 seq+5 的记录 -> {}", report.get("rejected"));
        assertFalse((Boolean) report.get("valid"));
        assertEquals("EVENT_OUT_OF_ORDER", firstReason(report));
    }

    @Test
    @DisplayName("重复且内容完全一致 => EVENT_DUPLICATE_IGNORED，安全忽略")
    void identicalDuplicateIsIgnored() {
        var events = new java.util.ArrayList<>(buildBaseLog());
        Event dup = events.get(2);
        events.add(3, dup);
        var report = engine.validateAndReplay(events);
        log.info("[输入] 把 seq=3 原样再投递一次 -> ignored={}", report.get("ignoredDuplicates"));
        assertTrue((Boolean) report.get("valid"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ignored = (List<Map<String, Object>>)
                report.get("ignoredDuplicates");
        assertEquals(1, ignored.size());
        assertEquals("EVENT_DUPLICATE_IGNORED", ignored.get(0).get("reason"));
    }

    @Test
    @DisplayName("同 seq 不同内容 => EVENT_DUPLICATE_CONFLICT，拒绝")
    void conflictingDuplicateIsRejected() {
        var events = new java.util.ArrayList<>(buildBaseLog());
        Event original = events.get(2);
        events.add(3, new Event(original.seq(), original.type(),
                original.timestampMillis(), "{\"tampered\":true}"));
        var report = engine.validateAndReplay(events);
        log.info("[输入] seq=3 内容被篡改后重投 -> {}", report.get("rejected"));
        assertFalse((Boolean) report.get("valid"));
        assertEquals("EVENT_DUPLICATE_CONFLICT", firstReason(report));
    }

    @Test
    @DisplayName("时间戳倒退 => EVENT_LATE，拒绝")
    void lateEventIsRejected() {
        var events = new java.util.ArrayList<>(buildBaseLog());
        Event last = events.remove(events.size() - 1);
        events.add(new Event(last.seq(), last.type(), 1L, last.payloadJson()));
        var report = engine.validateAndReplay(events);
        log.info("[输入] 末条时间戳改成 1（倒退） -> {}", report.get("rejected"));
        assertFalse((Boolean) report.get("valid"));
        assertEquals("EVENT_LATE", firstReason(report));
    }

    @Test
    @DisplayName("引用不存在订单的成交 => EVENT_CANNOT_APPLY，拒绝且运行态不变")
    void unapplicableEventRejected() {
        var events = new java.util.ArrayList<>(buildBaseLog());
        long badTradeId = events.stream().filter(e -> e.type() == EventType.TRADE).count() + 100;
        events.add(new Event(events.size() + 1L, EventType.TRADE, System.currentTimeMillis(),
                "{\"tradeId\":" + badTradeId + ",\"takerOrderId\":999,\"makerOrderId\":888,"
                        + "\"symbol\":\"BTC\",\"price\":1,\"quantity\":1}"));
        var report = engine.validateAndReplay(events);
        log.info("[输入] 追加引用不存在订单的 TRADE -> {}", report.get("rejected"));
        assertFalse((Boolean) report.get("valid"));
        assertEquals("EVENT_CANNOT_APPLY", firstReason(report));
        // 校验是隔离进行的，当前运行态依然可用
        assertEquals(5, engine.getOrder(2).getFilledQuantity());
    }

    @SuppressWarnings("unchecked")
    private String firstReason(Map<String, Object> report) {
        List<Map<String, Object>> rejected = (List<Map<String, Object>>) report.get("rejected");
        return String.valueOf(rejected.get(0).get("reason"));
    }
}
