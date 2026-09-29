package com.github.lisama569.orderbookmatchingenginejava.config;

import com.github.lisama569.orderbookmatchingenginejava.engine.MatchingEngine;
import com.github.lisama569.orderbookmatchingenginejava.event.EventStore;
import com.github.lisama569.orderbookmatchingenginejava.event.FileEventStore;
import com.github.lisama569.orderbookmatchingenginejava.risk.AccountLedger;
import com.github.lisama569.orderbookmatchingenginejava.risk.RiskValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 引擎装配：
 * <ul>
 *   <li>{@code engine.symbols}：可交易合约（逗号分隔，默认 BTC-USD,ETH-USD）；</li>
 *   <li>{@code engine.event-log}：JSON Lines 事件日志路径，重启重放的唯一数据源；</li>
 *   <li>{@code engine.replay-mode}：strict（默认）/ lenient；</li>
 *   <li>种子账户：engine.seed.accounts.* 配置初始现金与持仓。</li>
 * </ul>
 */
@Configuration
public class EngineConfig {

    @Value("${engine.symbols:BTC-USD,ETH-USD}")
    private String symbolsProperty;

    @Value("${engine.event-log:./data/orderbook-events.log}")
    private String eventLogPath;

    @Value("${engine.replay-mode:strict}")
    private String replayMode;

    @Bean
    public AccountLedger accountLedger() {
        AccountLedger ledger = new AccountLedger();
        // 默认种子账户，便于本地直接体验；生产应改为外部数据源
        ledger.register("A1", 1_000_000_000L, Map.of("BTC-USD", 100L, "ETH-USD", 1000L));
        ledger.register("A2", 1_000_000_000L, Map.of("BTC-USD", 100L, "ETH-USD", 1000L));
        ledger.register("TRADER", 1_000_000_000L, new LinkedHashMap<>());
        return ledger;
    }

    @Bean
    public EventStore eventStore() {
        return new FileEventStore(eventLogPath);
    }

    @Bean
    public MatchingEngine matchingEngine(EventStore eventStore, AccountLedger ledger) {
        Set<String> symbols = Set.of(symbolsProperty.split(","));
        RiskValidator riskValidator = new RiskValidator(ledger, symbols);
        MatchingEngine engine = new MatchingEngine(eventStore, ledger, riskValidator,
                symbols, replayMode);
        // 启动即重放：事件日志 -> 盘口 + 账户冻结/余额
        engine.replayFromLog();
        return engine;
    }
}
