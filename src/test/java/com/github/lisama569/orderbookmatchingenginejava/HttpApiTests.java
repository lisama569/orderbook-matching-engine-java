package com.github.lisama569.orderbookmatchingenginejava;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层集成测试：业务拒绝返回 422 且 rejectReason 可区分；
 * 正常下单返回 orderId / status / fills。
 * 注意：本类共享磁盘事件日志（application.properties 配置的 ./data 路径），
 * 每个用例先调用 /api/admin/reset 隔离历史数据；种子账户来自 EngineConfig。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpApiTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void rejectReasonsAreDistinguishableOverHttp() throws Exception {
        mvc.perform(post("/api/admin/reset")).andExpect(status().isOk());

        // 价格非法 -> INVALID_PRICE
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientOrderId":"h1","account":"A1","symbol":"BTC-USD",
                                 "side":"BUY","type":"LIMIT","limitPrice":0,"qty":1}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.rejectReason").value("INVALID_PRICE"));

        // 未知合约 -> INVALID_SYMBOL
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientOrderId":"h2","account":"A1","symbol":"DOGE",
                                 "side":"BUY","type":"LIMIT","limitPrice":1,"qty":1}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectReason").value("INVALID_SYMBOL"));

        // 资金不足 -> INSUFFICIENT_CASH（A1 现金 1e9，这里挂个天价）
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientOrderId":"h3","account":"A1","symbol":"BTC-USD",
                                 "side":"BUY","type":"LIMIT","limitPrice":100000000000,"qty":1000}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectReason").value("INSUFFICIENT_CASH"));
    }

    @Test
    void submitQueryAndIdempotentFlow() throws Exception {
        mvc.perform(post("/api/admin/reset")).andExpect(status().isOk());

        // A2 卖，A1 买，撮合 2 张
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientOrderId":"ha-s","account":"A2","symbol":"BTC-USD",
                                 "side":"SELL","type":"LIMIT","limitPrice":100,"qty":2}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientOrderId":"ha-b","account":"A1","symbol":"BTC-USD",
                                 "side":"BUY","type":"LIMIT","limitPrice":100,"qty":5}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fills[0].qty").value(2))
                .andExpect(jsonPath("$.data.status").value("PARTIALLY_FILLED"));

        // 相同 clientOrderId 重发 -> idempotentReplay=true
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientOrderId":"ha-b","account":"A1","symbol":"BTC-USD",
                                 "side":"BUY","type":"LIMIT","limitPrice":100,"qty":5}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idempotentReplay").value(true));

        // 盘口买一剩余 3
        mvc.perform(get("/api/books/BTC-USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bids[0][0]").value(100))
                .andExpect(jsonPath("$.data.bids[0][1]").value(3));

        // 成交记录 1 笔，2 张
        mvc.perform(get("/api/fills/BTC-USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].qty").value(2));

        mvc.perform(post("/api/admin/reset")).andExpect(status().isOk());
    }
}
