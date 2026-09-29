package com.github.lisama569.orderbookmatchingenginejava.tests;

import com.github.lisama569.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.orderbookmatchingenginejava.event.EventStore;
import com.github.lisama569.orderbookmatchingenginejava.risk.RiskValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 每个测试使用独立的临时事件日志文件，避免用例间互相污染。
 */
abstract class AbstractEngineTest {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected EventStore eventStore;
    protected MatchingEngine engine;
    private Path logFile;

    @BeforeEach
    void setUp() throws Exception {
        logFile = Files.createTempFile("event-log-", ".tsv");
        Files.deleteIfExists(logFile);
        eventStore = new EventStore(logFile.toString());
        engine = new MatchingEngine(new RiskValidator(), eventStore);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(logFile);
    }
}
