package com.github.lisama569.orderbookmatchingenginejava.engine;

import com.github.lisama569.orderbookmatchingenginejava.event.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 进程启动时从本地事件日志自动重放：EventStore 构造阶段已把文件读入内存，
 * 这里在应用就绪后驱动撮合引擎重建盘口与账户，使重启后状态与停机前一致。
 */
@Component
public class StartupReplayer {

    private static final Logger log = LoggerFactory.getLogger(StartupReplayer.class);

    private final MatchingEngine engine;
    private final EventStore eventStore;

    public StartupReplayer(MatchingEngine engine, EventStore eventStore) {
        this.engine = engine;
        this.eventStore = eventStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void replayOnStartup() {
        long events = eventStore.lastSeq();
        if (events > 0) {
            log.info("detected {} persisted events, replaying to rebuild order book ...", events);
            engine.replayFromLog();
            log.info("replay finished: {} events applied", events);
        }
    }
}
