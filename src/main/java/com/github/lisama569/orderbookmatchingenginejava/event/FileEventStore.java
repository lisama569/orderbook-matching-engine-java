package com.github.lisama569.orderbookmatchingenginejava.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于本地 JSON Lines 文件的只追加事件存储。
 * 每行一条：{@code {"seq":1,"type":"TRADE","payload":{...},"createdAtMs":...}}。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code seq} 从 1 开始严格递增；reserve 分配号段，commit 落盘，
 *       调用方保证 reserve/commit 在同一业务临界区内成对出现；</li>
 *   <li>每次 commit 都通过独立文件句柄 APPEND 写入，写完即关闭，天然 flush，
 *       进程被 kill 也不丢已受理事件；</li>
 *   <li>文件缺失视为空日志（首次启动自动创建）。</li>
 * </ul>
 */
public class FileEventStore implements EventStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path logFile;
    private final Object appendLock = new Object();
    private long lastSeq = 0;
    /** 已 reserve 但尚未 commit 的序号；正常流程下至多有一个。 */
    private long reservedSeq = 0;

    public FileEventStore(String logFilePath) {
        this.logFile = Path.of(logFilePath);
        try {
            if (logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
            if (Files.notExists(logFile)) {
                Files.createFile(logFile);
            }
            try (BufferedReader r = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) {
                        lastSeq = Math.max(lastSeq, MAPPER.readTree(line).get("seq").asLong());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("init event log failed: " + logFile, e);
        }
    }

    @Override
    public long reserve() {
        synchronized (appendLock) {
            if (reservedSeq != 0) {
                throw new IllegalStateException("previous reserved seq not committed: " + reservedSeq);
            }
            reservedSeq = lastSeq + 1;
            return reservedSeq;
        }
    }

    @Override
    public void commit(long seq, EventType type, String payloadJson) {
        synchronized (appendLock) {
            if (seq != reservedSeq) {
                throw new IllegalStateException("commit seq " + seq
                        + " does not match reserved " + reservedSeq);
            }
            String line;
            try {
                var root = MAPPER.createObjectNode();
                root.put("seq", seq);
                root.put("type", type.name());
                root.set("payload", payloadJson == null
                        ? MAPPER.createObjectNode() : MAPPER.readTree(payloadJson));
                root.put("createdAtMs", System.currentTimeMillis());
                line = MAPPER.writeValueAsString(root);
            } catch (IOException e) {
                reservedSeq = 0;
                throw new UncheckedIOException("encode event failed", e);
            }
            try {
                Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                lastSeq = seq;
            } catch (IOException e) {
                throw new UncheckedIOException("commit event failed", e);
            } finally {
                reservedSeq = 0;
            }
        }
    }

    @Override
    public long lastSeq() {
        synchronized (appendLock) {
            return lastSeq;
        }
    }

    @Override
    public List<EventRecord> readAll() {
        List<EventRecord> records = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(line);
                long seq = node.get("seq").asLong();
                String type = node.get("type").asText();
                String payload = node.has("payload")
                        ? MAPPER.writeValueAsString(node.get("payload")) : "{}";
                long createdAt = node.has("createdAtMs") ? node.get("createdAtMs").asLong() : 0L;
                records.add(new EventRecord(seq, type, payload, createdAt));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("read event log failed", e);
        }
        return records;
    }

    @Override
    public void reset() {
        synchronized (appendLock) {
            try {
                Files.writeString(logFile, "", StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
                lastSeq = 0;
                reservedSeq = 0;
            } catch (IOException e) {
                throw new UncheckedIOException("reset event log failed", e);
            }
        }
    }
}
