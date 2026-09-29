# 限价订单撮合引擎（本地内存账本 / 事件溯源）

一个教学性质的简化交易所撮合引擎：Spring Boot 3 + Java 21，无外部数据库，
账户与盘口全部在本地内存，状态变更以 **JSON Lines 事件日志** 落盘；
进程重启后重放日志即可还原同一份盘口与同一份账户余额。

- 限价单 / 市价单（市价单按 IOC 处理），价格优先、同价时间优先，允许部分成交
- 下单前资金 / 持仓风控，冻结—释放严格对账，撤单 / 改单原子完成
- 只追加事件日志 + 启动重放；乱序 / 重复 / 迟到 / 损坏记录有可区分的拒绝原因
- 一把全局锁串行化所有写操作，并发下保证幂等与账面守恒

## 1. 目录结构

```
src/main/java/com/github/lisama569/orderbookmatchingenginejava
├── model/      Order / Fill / Account / Side / OrderType / OrderStatus
│               RejectReason（业务拒绝原因）RejectException
├── risk/       AccountLedger（账本：冻结/释放/结算） RiskValidator（下单改单风控）
├── engine/     MatchingEngine（门面+撮合主循环+重放） OrderBook（单合约簿子）
│               SubmitResult / CancelResult / ModifyResult / BookSnapshot
├── event/      EventStore / FileEventStore（JSON Lines）事件 payload record
│               ReplayRejectReason / ReplayRejectException / ReplayReport
├── web/        TradingController（REST）
├── dto/        请求与统一响应
└── config/     EngineConfig（Spring 装配、种子账户、启动重放）
```

## 2. 撮合规则

1. **价格优先**：买盘价格降序、卖盘价格升序，取对手方最优价成交。
2. **同价时间优先**：同一价位是 FIFO 队列；`cancel` 不改变队列顺序；
   **改单一律重新进队尾**，主动放弃时间优先（交易所通行做法）。
3. **限价单**：先与对手盘撮合。买单仅在 `maker价 <= 买限价` 时成交，
   卖单仅在 `maker价 >= 卖限价` 时成交；未成交部分挂簿。
4. **市价单（IOC）**：不校验价格、立即与对手盘成交；市价买不预冻结现金（逐笔按购买力成交），市价卖仍需预冻结持仓；未成交部分直接作废
   （产生一条 `IOC_EXPIRED` 的 `ORDER_CANCELLED` 事件），不会挂簿。
5. **允许部分成交**：每张挂单 / 吃单都记录累计成交量，状态为
   `NEW / PARTIALLY_FILLED / FILLED / CANCELLED`。
6. **成交价**：取挂单方（maker）价格。
7. 价格与数量一律用 `long` 表示，价格是 8 位小数定标的整数
   （`Order.PRICE_SCALE`），杜绝浮点误差；所有金额计算为整数乘加。

## 3. 风控与冻结逻辑

下单受理时（风控通过后）立即冻结，撤单 / 成交 / 改单时按下面规则解冻，
任何时刻都满足不变量：

- 现金：`totalCash = availableCash + frozenCash`，两栏都不得为负；
- 持仓（按合约）：`totalPosition = available + frozen`，不得为负。

| 场景 | 现金 / 持仓变动 |
|---|---|
| 限价买受理 | 按 `limitPrice * qty` 冻结现金（available → frozen） |
| 限价卖受理 | 按 `qty` 冻结持仓（available → frozen） |
| 一笔成交（买方） | 按买单挂单价 `limit*qty` 解冻冻结现金，实际只支付 `成交价*qty`；买得更便宜时差额自然留在 available；买到的币进入可用持仓 |
| 一笔成交（卖方） | 冻结持仓扣减 `qty`（持仓总量减少），货款 `成交价*qty` 进入 available 现金 |
| 市价买 | 受理时不冻结现金；逐笔成交前用 `floor(availableCash / maker价)` 限制本笔数量，现金绝不可能扣成负数 |
| 市价卖 | 受理时与限价卖一样**先冻结持仓**（卖空在风控阶段直接拒绝）；成交后扣冻结持仓、货款入账，IOC 剩余部分释放冻结 |
| 撤单 | 按**剩余数量**把冻结现金 / 持仓原路退回 available |
| 改单 | 先释放旧剩余量的冻结，再按新价 / 新剩余量重新冻结（多退少补）；资金或持仓不足则整体拒绝，订单与账面保持原状 |

所有冻结 / 结算都集中在 `AccountLedger`，引擎不直接改账面；
`AccountLedger.assertInvariants()` 在测试中用于自检。

### 可区分的拒绝原因（`RejectReason`，HTTP 422 返回）

`INVALID_SYMBOL`、`INVALID_PRICE`、`INVALID_QUANTITY`、`INVALID_CLIENT_ORDER_ID`、
`UNKNOWN_ACCOUNT`、`DUPLICATE_CLIENT_ORDER_ID`、`INSUFFICIENT_CASH`、
`INSUFFICIENT_POSITION`、`ORDER_NOT_FOUND`、`ORDER_TERMINAL`、
`MARKET_ORDER_NOT_MODIFIABLE`、`INVALID_MODIFY_QUANTITY`。

### 幂等约定

`clientOrderId` 非空且在“已受理订单”范围内唯一：

- 相同 `clientOrderId` 且**请求指纹完全一致**（账户/合约/方向/类型/价格/数量）：
  视为网络重发，直接返回原始结果（`idempotentReplay=true`），**不重复冻结、不重复撮合**；
  幂等表由受理事件重建，因此服务重启后仍然幂等。
- 相同 `clientOrderId` 但内容不同：拒绝为 `DUPLICATE_CLIENT_ORDER_ID`。

## 4. 事件与重放约定

### 事件类型（只记录“改变状态的事实”）

| 事件 | 负载要点 |
|---|---|
| `ORDER_ACCEPTED` | orderId(=seq)、clientOrderId、账户、合约、方向、类型、限价、原始数量 |
| `TRADE` | tradeId(=seq)、合约、成交价、数量、买卖订单号、买卖账户、主动方 |
| `ORDER_CANCELLED` | orderId、原因（`USER_CANCEL` / `IOC_EXPIRED`） |
| `ORDER_MODIFIED` | orderId、新限价、新目标累计数量 |

日志为 JSON Lines，默认路径 `./data/orderbook-events.log`，每行：

```json
{"seq":7,"type":"TRADE","payload":{...},"createdAtMs":1727000000000}
```

- `seq` 从 1 开始、严格递增；**订单号和成交号都直接使用事件 seq**，
  因此重放时 ID 完全确定。
- 写入流程是引擎锁内的 `reserve() → 构造带正确 ID 的 payload → commit()`：
  先把事件落盘，再让内存结果对外可见；commit 用独立文件句柄 APPEND，
  写完即关闭刷盘。
- 撤单 / 改单的顺序同样是“校验 → 落事件 → 记账 / 改簿”，任一步抛异常都不会
  执行后续步骤，账户与盘口维持原状（不会留下半更新）。

### 重放

`MatchingEngine.replayFromLog()`（Spring 启动时自动执行一次）：

1. 从空内存开始，逐行读日志，按 seq 应用 `ORDER_ACCEPTED → TRADE →
   ORDER_CANCELLED / ORDER_MODIFIED`，冻结、结算、释放都走与在线时相同的账本方法，
   因此得到同一份盘口、成交与账户余额；
2. FIFO 顺序通过订单上的 `requeueSeq`（受理或改单的事件序号）还原；
3. 存活限价单在全部事件应用后统一按 `requeueSeq` 挂入簿子——这与在线路径
   “吃单先成交、剩余再入簿 / 改单重排队”的最终状态等价。

序号纪律（逐条检查，坏记录绝不会被悄悄应用）：

| 情形 | 原因 `ReplayRejectReason` |
|---|---|
| seq 跳号（期望 2 来了 3） | `OUT_OF_ORDER` |
| seq 重复（已应用过的序号再次出现） | `DUPLICATE_SEQUENCE` |
| seq 倒退且是缺口后混入的旧记录（从未应用） | `LATE_EVENT` |
| 事件类型未知 / payload 无法解析 / 内部 ID 与 seq 不符 / 引用账户合约不存在 | `CORRUPT_PAYLOAD` |
| TRADE / 撤单 / 改单引用了不存在的订单 | `UNKNOWN_ORDER` |

两种模式（`engine.replay-mode`）：

- `strict`（默认）：遇到任一坏记录直接抛 `ReplayRejectException` 中止启动；
- `lenient`：跳过坏记录并在 `ReplayReport.skipped` 中按 `seq → 原因` 返回，
  被跳过的记录不改变任何状态。

## 5. HTTP 接口

| 方法 路径 | 说明 |
|---|---|
| `POST /api/orders` | 下单，body：clientOrderId/account/symbol/side(BUY,SELL)/type(LIMIT,MARKET)/limitPrice(市价省略)/qty |
| `POST /api/orders/cancel` | 撤单 `{orderId}` |
| `POST /api/orders/modify` | 改单 `{orderId,newLimitPrice,newQty}`（newQty 为新目标累计数量，必须 > 已成交） |
| `GET  /api/orders/{id}` | 订单详情（含 remainingQty/status） |
| `GET  /api/books/{symbol}` | 盘口快照 `{symbol,bids:[[价,量]...],asks:[[价,量]...]}` |
| `GET  /api/fills/{symbol}` | 成交列表 |
| `GET  /api/accounts/{id}` | 账户：available/frozen 现金与持仓 |
| `POST /api/admin/reset` | 清空事件日志与内存状态（测试 / 演示用） |

成功响应 `{success:true,data:{...}}`；业务拒绝为 HTTP 422：

```json
{"success":false,"data":null,"rejectReason":"INSUFFICIENT_CASH",
 "message":"need 2000 available cash, have 1000"}
```

## 6. 本地验证

前置：JDK 21、Maven 3.9+。

```bash
# 全量测试（含单元测试、并发测试、Spring 集成测试）
mvn test

# 只跑某一类
mvn test -Dtest=ReplayTests
mvn test -Dtest=ConcurrencyTests
mvn test -Dtest=RiskAndRejectTests

# 启动服务（默认端口 8080；事件日志写 ./data）
mvn spring-boot:run
```

手工体验：

```bash
# 卖方挂单
curl -s -XPOST localhost:8080/api/orders -H 'Content-Type: application/json' -d '{
  "clientOrderId":"demo-sell","account":"A2","symbol":"BTC-USD",
  "side":"SELL","type":"LIMIT","limitPrice":100,"qty":5}'

# 买方吃 2 张（部分成交，剩 3 张挂买一？本例为吃单，按对手卖盘成交后剩余挂簿）
curl -s -XPOST localhost:8080/api/orders -H 'Content-Type: application/json' -d '{
  "clientOrderId":"demo-buy","account":"A1","symbol":"BTC-USD",
  "side":"BUY","type":"LIMIT","limitPrice":100,"qty":2}'

curl -s localhost:8080/api/books/BTC-USD
curl -s localhost:8080/api/accounts/A1

# 重启进程后再查盘口与账户，结果与重启前一致
```

种子账户（`EngineConfig`，可改成外部数据源）：
`A1 / A2 / TRADER`，各有 10 亿现金；`A1`、`A2` 另持有 BTC-USD 100、ETH-USD 1000。

## 7. 测试与可解释性

测试日志统一打印 **输入 → 撮合结果 → 判定依据**，例如：

```
[CONCURRENT-IDEMPOTENT] input='16线程同clientOrderId同内容' -> 唯一orderId=1 幂等返回次数=15 冻结=200 | 判定依据: 冻结额=2*100 且只一张单
[CONCURRENT-MIX] input='40张卖单(偶数随即撤)+买方并发30张' -> 成交=23 盘口剩余=0 撤单成功=17 | 判定依据: 三者之和必须=40
[REPLAY-DUP] 拒绝重复记录 seq=1 reason=DUPLICATE_SEQUENCE
[REJECT] input='买资金不足(需2000有1000)' -> reason=INSUFFICIENT_CASH | 判定依据: ...
```

| 测试类 | 覆盖内容 |
|---|---|
| `SmokeTests` | 基本撮合、部分成交、撤单释放 |
| `RiskAndRejectTests` | 12 种拒绝原因逐一可区分、幂等重发不双冻、改单调冻结与重排队、部分成交后改单限制 |
| `MarketOrderTests` | 市价单按可用现金部分成交 + IOC 作废；lenient 重放跳过坏记录且原因可区分 |
| `ReplayTests` | 撤单/改单/部分成交混合后，重启重放得到同一份盘口与余额；strict 下重复/跳号中止 |
| `ConcurrencyTests` | 16 线程同号只受理一次；40 卖单 + 并发撤单 + 买方扫货的账实守恒；10 线程抢撤一单恰一个成功 |
| `HttpApiTests` | REST 422 + rejectReason、端到端下单/查询/幂等流程 |

## 8. 已知简化范围

- 单进程单实例、单把全局锁；未实现撮合分区与网络复制。
- 只有一档种子账户体系，没有充提、手续费、自成交预防、涨跌停与合约最小下单单位。
- 事件日志是普通本地文件（非批量 fsync 调优）；`/api/admin/reset` 仅供演示，生产需鉴权。
