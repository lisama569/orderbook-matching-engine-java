package com.github.lisama569.orderbookmatchingenginejava.event;

import com.github.lisama569.orderbookmatchingenginejava.model.Order;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderStatus;
import com.github.lisama569.orderbookmatchingenginejava.model.OrderType;
import com.github.lisama569.orderbookmatchingenginejava.model.Side;
import com.github.lisama569.orderbookmatchingenginejava.model.Trade;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件与 JSON 字符串 payload 之间的极简编解码（无外部依赖）。
 * 字段集合小且固定，故手写 JSON 子集；解码为保留插入顺序的 map。
 */
public final class EventCodec {

    private EventCodec() {
    }

    public static String encodeOrder(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.getOrderId());
        m.put("clientOrderId", o.getClientOrderId());
        m.put("accountId", o.getAccountId());
        m.put("symbol", o.getSymbol());
        m.put("side", o.getSide().name());
        m.put("type", o.getType().name());
        m.put("price", o.getPrice());
        m.put("originalQuantity", o.getOriginalQuantity());
        m.put("filledQuantity", o.getFilledQuantity());
        m.put("status", o.getStatus().name());
        m.put("sequence", o.getSequence());
        return toJson(m);
    }

    public static String encodeDeposit(String accountId, long cash, long position) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", accountId);
        m.put("cash", cash);
        m.put("position", position);
        return toJson(m);
    }

    public static String encodeReject(String clientOrderId, String accountId, String symbol,
                                      String reason, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientOrderId", clientOrderId);
        m.put("accountId", accountId);
        m.put("symbol", symbol);
        m.put("reason", reason);
        m.put("message", message);
        return toJson(m);
    }

    public static String encodeTrade(Trade t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradeId", t.tradeId());
        m.put("takerOrderId", t.takerOrderId());
        m.put("makerOrderId", t.makerOrderId());
        m.put("symbol", t.symbol());
        m.put("price", t.price());
        m.put("quantity", t.quantity());
        return toJson(m);
    }

    public static String encodeCancel(long orderId, long remainingQuantity, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", orderId);
        m.put("remainingQuantity", remainingQuantity);
        m.put("source", source);
        return toJson(m);
    }

    public static String encodeModify(long orderId, long newPrice, long newQuantity,
                                      long newOrderId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", orderId);
        m.put("newPrice", newPrice);
        m.put("newQuantity", newQuantity);
        m.put("newOrderId", newOrderId);
        return toJson(m);
    }

    /** 解析 payload 为有序字段 map，值均为字符串，由调用方按需转换 */
    public static Map<String, String> decode(String payload) {
        Map<String, String> map = new LinkedHashMap<>();
        String body = payload.trim();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        int i = 0;
        int n = body.length();
        while (i < n) {
            // key: "..."
            while (i < n && body.charAt(i) != '"') i++;
            int ks = i + 1;
            int ke = readString(body, ks);
            String key = unescape(body.substring(ks, ke));
            i = ke + 1;
            while (i < n && (body.charAt(i) == ':' || Character.isWhitespace(body.charAt(i)))) i++;

            String value;
            if (i < n && body.charAt(i) == '"') {
                int vs = i + 1;
                int ve = readString(body, vs);
                value = unescape(body.substring(vs, ve));
                i = ve + 1;
            } else {
                int vs = i;
                while (i < n && body.charAt(i) != ',') i++;
                value = body.substring(vs, i).trim();
            }
            map.put(key, value);
            while (i < n && (body.charAt(i) == ',' || Character.isWhitespace(body.charAt(i)))) i++;
        }
        return map;
    }

    /** 从开引号后一位开始，返回闭引号位置（处理反斜杠转义） */
    private static int readString(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '\\') {
                i++;
            } else if (s.charAt(i) == '"') {
                return i;
            }
        }
        throw new IllegalArgumentException("malformed json payload: " + s);
    }

    private static String toJson(Map<String, ?> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    // ---- 重放时的类型化读取辅助 ----

    public static Side side(Map<String, String> m) {
        return Side.valueOf(m.get("side"));
    }

    public static OrderType type(Map<String, String> m) {
        return OrderType.valueOf(m.get("type"));
    }

    public static OrderStatus status(Map<String, String> m) {
        return OrderStatus.valueOf(m.get("status"));
    }

    public static long lng(Map<String, String> m, String key) {
        return Long.parseLong(m.get(key));
    }

    public static String str(Map<String, String> m, String key) {
        return m.get(key);
    }
}
