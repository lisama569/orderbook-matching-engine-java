package com.github.lisama569.orderbookmatchingenginejava.engine;

import com.github.lisama569.orderbookmatchingenginejava.event.Event;
import com.github.lisama569.orderbookmatchingenginejava.exception.RejectReason;
import com.github.lisama569.orderbookmatchingenginejava.exception.TradingException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部事件流（备份恢复、对端重推）的校验式重放。
 *
 * <p>逐条检查并给出可区分的结论，绝不悄悄改变已有结果：
 * <ul>
 *   <li>seq 跳跃（缺号）-> EVENT_OUT_OF_ORDER，中止；</li>
 *   <li>seq 重复且 type/payload/时间戳完全一致 -> EVENT_DUPLICATE_IGNORED，安全忽略；</li>
 *   <li>seq 重复但内容不一致 -> EVENT_DUPLICATE_CONFLICT，拒绝并中止；</li>
 *   <li>seq 正常但时间戳倒退（迟到）-> EVENT_LATE，拒绝并中止；</li>
 *   <li>业务上无法应用（引用不存在的订单等）-> EVENT_CANNOT_APPLY，拒绝并中止。</li>
 * </ul>
 * 全部校验通过后，在隔离的空运行态上一次性重放成功，才视为这份日志可用。
 */
final class IngestionValidator {

    private IngestionValidator() {
    }

    static Map<String, Object> run(MatchingEngine engine, List<Event> incoming) {
        List<Map<String, Object>> ignored = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();

        long expectSeq = 1L;
        long lastTs = Long.MIN_VALUE;
        List<Event> accepted = new ArrayList<>();

        for (Event e : incoming) {
            if (e.seq() < expectSeq) {
                // seq 重复：必须在已接受集合里找到同一条
                Event prior = findBySeq(accepted, e.seq());
                if (prior == null) {
                    reject(rejected, e, RejectReason.EVENT_OUT_OF_ORDER,
                            "seq repeats before any accepted record");
                    break;
                }
                if (sameRecord(prior, e)) {
                    ignored.add(Map.of("seq", e.seq(),
                            "reason", RejectReason.EVENT_DUPLICATE_IGNORED.name()));
                    continue;
                }
                reject(rejected, e, RejectReason.EVENT_DUPLICATE_CONFLICT,
                        "same seq " + e.seq() + " carries different content");
                break;
            }
            if (e.seq() > expectSeq) {
                reject(rejected, e, RejectReason.EVENT_OUT_OF_ORDER,
                        "seq gap: expected " + expectSeq + " but got " + e.seq());
                break;
            }
            if (e.timestampMillis() < lastTs) {
                reject(rejected, e, RejectReason.EVENT_LATE,
                        "timestamp regresses: previous=" + lastTs
                                + ", current=" + e.timestampMillis());
                break;
            }
            lastTs = e.timestampMillis();
            accepted.add(e);
            expectSeq++;
        }

        if (rejected.isEmpty()) {
            // 业务层面应用：在引擎的隔离空状态上重放；任何引用不一致都会在这里暴露
            try {
                engine.applyEventsIsolated(accepted);
            } catch (TradingException ex) {
                reject(rejected, accepted.isEmpty() ? null
                                : accepted.get(accepted.size() - 1),
                        ex.getReason(), ex.getMessage());
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("applied", rejected.isEmpty() ? accepted.size() : 0);
        report.put("ignoredDuplicates", ignored);
        report.put("rejected", rejected);
        report.put("valid", rejected.isEmpty());
        return report;
    }

    private static void reject(List<Map<String, Object>> rejected, Event e,
                               RejectReason reason, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e == null ? null : e.seq());
        m.put("reason", reason.name());
        m.put("message", message);
        rejected.add(m);
    }

    private static Event findBySeq(List<Event> events, long seq) {
        for (Event e : events) {
            if (e.seq() == seq) {
                return e;
            }
        }
        return null;
    }

    private static boolean sameRecord(Event a, Event b) {
        return a.type() == b.type()
                && a.timestampMillis() == b.timestampMillis()
                && a.payloadJson().equals(b.payloadJson());
    }
}
