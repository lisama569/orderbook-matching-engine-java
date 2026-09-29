# 限价订单撮合引擎（本地内存账本）

一个用 Spring Boot + 本地内存实现的简化交易所撮合与风控账本。支持限价单 / 市价单提交与撮合、
下单前资金与持仓风控、撤单 / 改单、订单与成交事件记录及按记录重放；目标是让**下单、撮合、撤单
在并发和重启场景下都得到一致且可解释的结果**。

价格与数量均使用 `long` 正整数（最小报价 / 数量单位的整数倍），从根上避免浮点误差。

---

## 1. 目录结构

```
src/main/java/com/github/lisama569/orderbookmatchingenginejava/
├── model/        Order / Trade / Account 与枚举 Side/OrderType/OrderStatus
├── risk/         RiskValidator：静态参数校验 + 资金/持仓风控（只判断、不改账）
├── engine/       OrderBook（盘口）、MatchingEngine（撮合/记账/重放，系统唯一串行化点）、
│                 IngestionValidator（外部事件流乱序/重复/迟到校验）、StartupReplayer
├── event/        Event / EventType / EventCodec（极简 JSON 编解码）/ EventStore（append-only 日志+文件持久化）
├── service/      TradingService：对外门面（入账、下单、撤单、改单、查询、重启重放）
└── web/          REST 控制器、DTO、全局异常处理（拒绝原因原样返回）
```

---

## 2. 撮合规则

- **价格优先，同价时间优先（FIFO）**：
  - 买盘按价格从高到低成交，卖盘从低到高成交。
  - 同一价位按进入盘口的逻辑序号 `sequence` 排队，先来先成交；部分成交的订单保留队首位置。
- **限价单（LIMIT）**：
  - 买单仅与 `makerPrice <= limitPrice` 的对手单成交；卖单仅与 `makerPrice >= limitPrice` 成交。
  - 允许部分成交；未成交余量挂入盘口。
  - 成交价取 **maker 挂单价**（对 taker 构成价格保护，可能产生滑点）。
- **市价单（MARKET，按 IOC 处理）**：
  - 不设价格门槛，立即与对手盘成交；**没有任何对手流动性时直接拒绝**（`MARKET_NO_LIQUIDITY`）。
  - 允许部分成交；未成交余量立即撤销（`ORDER_CANCELLED` 事件带 `source=IOC`），不进入盘口。
- **自成交防止**：taker 与 maker 属于同一账户时跳过该对手单（不成交、不消耗其排队位置）。

### 成交时的记账（每一笔都原子完成）

| 角色 / 方向 | 现金 | 持仓 |
| --- | --- | --- |
| taker 买入 | 冻结现金按 `成交价 × 量` 划转；低于限价的滑点差额退回可用 | 可用持仓 += 成交量 |
| maker 卖出 | 可用现金 += `成交价 × 量` | 冻结持仓按成交量划转 |
| taker 卖出 | 可用现金 += `成交价 × 量` | 冻结持仓按成交量划转 |
| maker 买入 | 冻结现金按 `成交价 × 量` 划转；滑点差额退回可用 | 可用持仓 += 成交量 |

订单被完全成交时，若冻结中仍有滑点残余（只可能出现在买单），残余冻结退回可用，
保证**终结订单上不挂任何无主冻结**。

---

## 3. 风控与冻结逻辑

账户的每一项资产都区分「可用」与「冻结」，恒等式始终成立：

```
现金总额   = cashAvailable     + cashFrozen
持仓总量   = positionAvailable + positionFrozen
```

### 下单前

1. **静态校验**（失败原因彼此可区分，且不产生任何账面变动）：
   - 账户不存在 / 为空 → `ORDER_UNKNOWN_ACCOUNT`
   - 品种为空 → `ORDER_INVALID_SYMBOL`
   - 限价单价格 ≤ 0 → `ORDER_INVALID_PRICE`；市价单价格 ≠ 0 → `ORDER_INVALID_PRICE`
   - 数量 ≤ 0 → `ORDER_INVALID_QUANTITY`
2. **客户端订单号去重**：同一 `clientOrderId` 第二次提交 →
   `ORDER_DUPLICATE_CLIENT_ORDER_ID`，幂等拒绝，**不会重复撮合、不会二次冻结**。
3. **资金 / 持仓风控**：
   - 限价买入：需要冻结 `price × quantity`，可用现金不足 → `RISK_INSUFFICIENT_CASH`。
   - 限价卖出：需要冻结 `quantity` 持仓，可用持仓不足 → `RISK_INSUFFICIENT_POSITION`。
   - 市价单：按**当前对手盘可成交量**估算最差成本 / 可成交持仓后再做风控；
     无对手流动性按上面的规则拒绝。
4. 风控通过后才在引擎单锁内「可用 → 冻结」落账，随后撮合。

### 撤单

- 仅活动订单（`NEW` / `PARTIALLY_FILLED`）可撤；不存在 → `CANCEL_ORDER_NOT_FOUND`，
  已终结 → `CANCEL_ORDER_NOT_ACTIVE`。
- 撤单把订单上**剩余数量对应的冻结原路释放**回可用；已成交部分不回滚。

### 改单

改单语义是「同账户撤下旧单剩余 → 按新价 / 新量重新风控、冻结并立即参与撮合，
未成交部分排到新档位队尾（**失去原时间优先位置**）」，新单获得新的 `orderId`。

- 先做新单的纯风控检查：资金不足 → `RISK_MODIFY_INSUFFICIENT_CASH`，
  持仓不足 → `RISK_MODIFY_INSUFFICIENT_POSITION`；新价 / 新量非法分别为
  `MODIFY_INVALID_PRICE` / `MODIFY_INVALID_QUANTITY`。
- **风控不过时旧单原样保留、账面不变**；风控通过后才在同一把锁内撤旧、冻结新单，
  因此部分成交、撤单、改单混在一起时不会重复扣减或漏加。
- 撤单 / 改单接口的 `clientOrderId` 同时是请求幂等键：并发或重试的同一请求只生效一次，
  重复请求返回首次结果。

### 原子性

所有「校验 → 冻结 → 撮合 → 逐笔结算记账 → 写事件日志」都在 `MatchingEngine`
的同一把 `synchronized` 锁内完成；账面更新之后的流程没有可抛出的失败路径，
因此任何一步被拒绝都不会留下半更新状态。

---

## 4. 事件记录与重放约定

事件日志（`EventStore`）是系统的**唯一事实来源**：

- 内存中保存全量事件，并逐行追加到本地文件（默认 `data/event-log.tsv`），
  路径由配置 `matching.event-log.file` 控制。
- 行格式为 tab 分隔：`seq \t type \t timestampMillis \t payload(json)`。
- 正常写入由引擎在单锁内完成，`seq` 从 1 开始严格递增。

### 事件类型与顺序约定

| 事件 | 含义 / 重放作用 |
| --- | --- |
| `ACCOUNT_DEPOSIT` | 入账，重放时重建账户初始可用现金 / 持仓 |
| `ORDER_ACCEPTED` | 委托被接受（原始数量），重放时重建订单并建立冻结 |
| `TRADE` | 一笔成交，紧跟在其 taker 的 ACCEPTED 之后，按 taker/maker 方向重新记账 |
| `ORDER_CANCELLED` | 撤单，`source=USER`（主动撤）/ `IOC`（市价剩余自动撤） |
| `ORDER_MODIFIED` | 改单：旧单出簿释放冻结；其后紧跟新单的 ACCEPTED（+可能的 TRADE） |
| `ORDER_REJECTED` | 校验 / 风控拒绝的审计记录，重放时无状态可改 |

重放以「命令组」为边界：`ACCEPTED` 与其后的 `TRADE` 同组，
遇到 `CANCELLED` / `MODIFIED` / 下一个 `ACCEPTED` 时把仍有余量的限价单挂入盘口，
从而与实时路径产生**同一份盘口、同一份账户余额、同一组成交序列**。

### 重启重放

- 进程启动时 `StartupReplayer` 检测到日志文件非空会自动重放；
  也可通过 `POST /api/admin/replay` 手动模拟「重新加载文件 + 清空内存 + 重放」。
- 成交事件的记账规则与实时结算完全相同（含滑点残余退回、完全成交的 maker 出簿、
  改单旧单释放冻结），`ReplayConsistencyTest` 对盘口档位、成交条数和三个账户的
  四项余额逐项断言相等。

### 外部事件流（备份 / 对端重推）的校验

`MatchingEngine.validateAndReplay(events)` 在**隔离的空运行态**上校验并重放外部事件，
无论结果如何都会恢复调用前状态，非法流绝不改变当前结果。结论可区分：

| 情况 | 结论 |
| --- | --- |
| seq 跳跃 / 缺号 | `EVENT_OUT_OF_ORDER`（拒绝，中止） |
| seq 重复且 type/payload/时间戳完全一致 | `EVENT_DUPLICATE_IGNORED`（安全忽略） |
| seq 重复但内容不一致 | `EVENT_DUPLICATE_CONFLICT`（拒绝，中止） |
| seq 正常但时间戳倒退（迟到） | `EVENT_LATE`（拒绝，中止） |
| 引用不存在的订单等业务不一致 | `EVENT_CANNOT_APPLY`（拒绝，中止） |

---

## 5. HTTP 接口（端口随机，见启动日志）

| 方法 / 路径 | 说明 |
| --- | --- |
| `POST /api/accounts/{id}/deposit` | 入账，body：`{"cash":1000000,"position":100}`（均可省略） |
| `POST /api/orders` | 下单，body 见下；成功返回订单终态，失败返回 400 + 可区分 `reason` |
| `POST /api/orders/cancel` | 撤单：`{"clientOrderId","accountId","orderId"}` |
| `POST /api/orders/modify` | 改单：`{"clientOrderId","accountId","orderId","newPrice","newQuantity"}` |
| `GET  /api/orderbook/{symbol}` | 盘口快照：bids/asks 档位、bestBid/bestAsk |
| `GET  /api/accounts/{id}` | 账户四项余额 |
| `GET  /api/trades` | 全部成交 |
| `POST /api/admin/replay` | 模拟重启：从日志文件重载并重放 |

下单请求示例：

```json
{
  "clientOrderId": "c-0001",
  "accountId": "BUYER",
  "symbol": "BTC",
  "side": "BUY",
  "type": "LIMIT",
  "price": 100,
  "quantity": 10
}
```

拒绝响应示例（全局异常处理器把原因原样返回，而不是笼统的 500）：

```json
{ "rejected": true, "reason": "RISK_INSUFFICIENT_CASH",
  "message": "cash not enough: need=1000, available=50" }
```

---

## 6. 本地验证方法

```bash
# 编译
mvn compile

# 全量测试（25 个用例）
mvn clean test

# 只跑某一组
mvn test -Dtest=MatchingEngineTest        # 撮合：价格/时间优先、部分成交、市价 IOC、自成交防止
mvn test -Dtest=RiskAndRejectTest         # 风控：各类拒绝原因可区分、失败零账面改动
mvn test -Dtest=CancelModifyTest          # 撤单 / 改单冻结释放对账
mvn test -Dtest=ReplayConsistencyTest     # 重启重放盘口与余额一致性
mvn test -Dtest=EventIngestionTest        # 乱序 / 重复 / 冲突 / 迟到事件分类
mvn test -Dtest=ConcurrencyTest           # 并发撮合守恒、重复提交与并发撤改单幂等
mvn test -Dtest=WebApiIntegrationTest     # 端到端 HTTP 冒烟
```

单测日志（`target/surefire-reports/TEST-*.xml` 的 system-out）会打印
**[输入] / [撮合结果] / [判定依据]**，例如并发场景的总成交量、买卖双方余额与守恒推导。

手工端到端：

```bash
mvn spring-boot:run
# 另开终端
curl -s -XPOST localhost:$(grep -o 'Tomcat started on port [0-9]*' /tmp/run.log | grep -o '[0-9]*')/api/accounts/ALICE/deposit \
  -H 'Content-Type: application/json' -d '{"cash":1000000}'
# 下单、撤单、改单后调用 /api/admin/replay，可观察到盘口与账户余额与重启前一致
```

---

## 7. 简化范围说明

- 单簿撮合为单品种 `OrderBook`，账户资金在多品种间共享（本地账本演示用）。
- 事件持久化是「逐行 append 文件 + 启动重放」的简化方案，没有做 WAL fsync / 分段归档。
- 没有引入行情集合竞价、手续费、保证金杠杆、撮合后清算交收周期等真实交易所概念。
