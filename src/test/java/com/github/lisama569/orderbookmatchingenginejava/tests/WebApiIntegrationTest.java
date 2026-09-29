package com.github.lisama569.orderbookmatchingenginejava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 HTTP 冒烟：入账 -> 挂单 -> 非法单被区分原因拒绝 -> 撮合成交 -> 重启重放后盘口一致。
 * 使用独立临时事件日志，避免污染默认 data/event-log.tsv。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "matching.event-log.file=target/test-data/web-api-event-log.tsv")
class WebApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    /** 非 clean 运行时清掉上次的持久化日志，保证用例可重复执行 */
    @BeforeAll
    static void cleanLog() throws Exception {
        Files.deleteIfExists(Path.of("target/test-data/web-api-event-log.tsv"));
    }

    @Test
    void endToEndOverHttp() throws Exception {
        // 入账：卖方 100 持仓，买方 1_000_000 现金
        mvc.perform(post("/api/accounts/SELLER/deposit")
                        .contentType("application/json")
                        .content("{\"position\":100}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/accounts/BUYER/deposit")
                        .contentType("application/json")
                        .content("{\"cash\":1000000}"))
                .andExpect(status().isOk());

        // 卖方挂 30@10
        mvc.perform(post("/api/orders").contentType("application/json")
                        .content("{\"clientOrderId\":\"w-s1\",\"accountId\":\"SELLER\","
                                + "\"symbol\":\"BTC\",\"side\":\"SELL\",\"type\":\"LIMIT\","
                                + "\"price\":10,\"quantity\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));

        // 非法价格 -> HTTP 400 + 可区分 reason，不是笼统失败
        mvc.perform(post("/api/orders").contentType("application/json")
                        .content("{\"clientOrderId\":\"w-bad\",\"accountId\":\"BUYER\","
                                + "\"symbol\":\"BTC\",\"side\":\"BUY\",\"type\":\"LIMIT\","
                                + "\"price\":0,\"quantity\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rejected").value(true))
                .andExpect(jsonPath("$.reason").value("ORDER_INVALID_PRICE"));

        // 买方市价买 50：只能成交 30，剩 20 IOC 撤销
        mvc.perform(post("/api/orders").contentType("application/json")
                        .content("{\"clientOrderId\":\"w-m1\",\"accountId\":\"BUYER\","
                                + "\"symbol\":\"BTC\",\"side\":\"BUY\",\"type\":\"MARKET\","
                                + "\"price\":0,\"quantity\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filledQuantity").value(30))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 卖方卖盘已被吃光
        mvc.perform(get("/api/orderbook/BTC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asks[0]").doesNotExist());

        // 买方账户：花了 300，无冻结尾
        mvc.perform(get("/api/accounts/BUYER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashAvailable").value(999700))
                .andExpect(jsonPath("$.cashFrozen").value(0))
                .andExpect(jsonPath("$.positionAvailable").value(30));

        // 再挂一笔留在簿中，重放后应仍在
        mvc.perform(post("/api/orders").contentType("application/json")
                        .content("{\"clientOrderId\":\"w-s2\",\"accountId\":\"SELLER\","
                                + "\"symbol\":\"BTC\",\"side\":\"SELL\",\"type\":\"LIMIT\","
                                + "\"price\":12,\"quantity\":40}"))
                .andExpect(status().isOk());

        // 模拟重启重放：账户与盘口不变
        mvc.perform(post("/api/admin/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mvc.perform(get("/api/accounts/BUYER"))
                .andExpect(jsonPath("$.cashAvailable").value(999700))
                .andExpect(jsonPath("$.positionAvailable").value(30));
        mvc.perform(get("/api/orderbook/BTC"))
                .andExpect(jsonPath("$.asks[0][0]").value(12))
                .andExpect(jsonPath("$.asks[0][1]").value(40));
    }
}
