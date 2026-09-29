package com.github.lisama569.orderbookmatchingenginejava.event;

import java.util.List;

/**
 * 只追加（append-only）事件存储。
 */
public interface EventStore {

    /**
     * 预留并分配下一个严格递增序号（首条从 1 开始），但暂不落盘。
     * 调用方随后必须调用 {@link #commit(long, EventType, String)} 完成落盘；
     * 两步必须在同一把业务锁内、且中间不能插入其他 reserve。
     */
    long reserve();

    /** 把已预留序号的事件原子落盘。 */
    void commit(long seq, EventType type, String payloadJson);

    /** 便捷方法：reserve + commit，返回事件序号。 */
    default long append(EventType type, String payloadJson) {
        long seq = reserve();
        commit(seq, type, payloadJson);
        return seq;
    }

    /** 按序号顺序读取全部历史记录，用于启动重放。 */
    List<EventRecord> readAll();

    /** 当前已持久化的最大序号（空日志为 0）。 */
    long lastSeq();

    /** 清空日志（仅测试 / 管理端重置使用）。 */
    void reset();
}
