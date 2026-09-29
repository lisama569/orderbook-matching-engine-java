package com.github.lisama569.orderbookmatchingenginejava.event;

import java.util.LinkedHashMap;
import java.util.Map;

/** 重放结果：应用的事件数、最后序号，以及被忽略记录及可区分原因（lenient 模式）。 */
public class ReplayReport {
    private long appliedCount;
    private long lastSeq;
    private final Map<Long, ReplayRejectReason> skipped = new LinkedHashMap<>();

    public long getAppliedCount() { return appliedCount; }
    public void setAppliedCount(long appliedCount) { this.appliedCount = appliedCount; }
    public long getLastSeq() { return lastSeq; }
    public void setLastSeq(long lastSeq) { this.lastSeq = lastSeq; }
    public Map<Long, ReplayRejectReason> getSkipped() { return skipped; }
}
