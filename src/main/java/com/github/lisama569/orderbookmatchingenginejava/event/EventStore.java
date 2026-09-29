package com.github.lisama569.orderbookmatchingenginejava.event;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 append-only 事件日志：内存保存全量记录，同时逐行追加到本地文件；
 * 进程重启后从文件重载，再由引擎重放重建盘口与账户。
 *
 * <p>行格式（tab 分隔，payload 为不含 tab/换行的 JSON）：
 * {@code seq \t type \t timestampMillis \t payload}
 *
 * <p>正常写入（{@link #append}）由撮合引擎在单锁内调用，seq 严格递增；
 * 重载/重放只重建内存视图，不重复写文件。
 */
@Component
public class EventStore {

    private final List<Event> events = new ArrayList<>();
    private final Path file;

    public EventStore(@Value("${matching.event-log.file:data/event-log.tsv}") String filePath) {
        this.file = (filePath == null || filePath.isBlank()) ? null : Path.of(filePath);
        load();
    }

    /** 正常写入：seq 自动递增，并持久化一行 */
    public synchronized Event append(EventType type, String payloadJson, long timestampMillis) {
        long seq = events.size() + 1L;
        Event e = new Event(seq, type, timestampMillis, payloadJson);
        events.add(e);
        persistLine(e);
        return e;
    }

    public synchronized List<Event> all() {
        return List.copyOf(events);
    }

    public synchronized long lastSeq() {
        return events.size();
    }

    /** 模拟进程重启：清空内存状态并从日志文件重新加载（文件本身不变） */
    public synchronized void reloadFromFile() {
        events.clear();
        load();
    }

    public synchronized void clearAll() {
        events.clear();
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read event log: " + file, ex);
        }
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 4);
            if (parts.length != 4) {
                throw new IllegalStateException("corrupt event log line: " + line);
            }
            long seq = Long.parseLong(parts[0]);
            if (seq != events.size() + 1L) {
                throw new IllegalStateException("event log seq gap near: " + line);
            }
            events.add(new Event(seq, EventType.valueOf(parts[1]),
                    Long.parseLong(parts[2]), parts[3]));
        }
    }

    private void persistLine(Event e) {
        if (file == null) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String line = e.seq() + "\t" + e.type() + "\t" + e.timestampMillis()
                    + "\t" + e.payloadJson() + System.lineSeparator();
            Files.writeString(file, line, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to persist event: " + e, ex);
        }
    }
}
