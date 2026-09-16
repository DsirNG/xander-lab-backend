# 长连接安全：宿主清单、本地 deny cache 与发送边界（V9 §49 / §50 / §51 / §52 / §53）

本文只讲**长连接这一侧**：一条 SSE / WS 建立之后，封禁如何终止它、在哪一行终止、
以及「5 秒内终止」这个承诺怎么被拆成可验证的三段。

封禁的**事务与传播**链路（谁写库、谁投递、谁对账）见
[`admin-ban-security-plane.md`](./admin-ban-security-plane.md)。两份文档的边界是：
那份回答「封禁状态怎么正确」；本文回答「已经连上的流怎么被切断」。

对应 V9 章节：§12（能力不丢）、§21（共享内核边界）、§28.2–§28.4（SLO 与 5 秒预算）、
§49（每实例连接注册表）、§50（SSE 发送边界）、§51（WS 推送边界）、
§52（宿主清单）、§53（SLO 验收）、§69（`per_instance` 不得用全局锁）。

---

## 1. 一句话结论

**长连接只在建连时鉴权一次，所以封禁必须能主动终止已建立的流。**

```
建连时鉴权一次  →  之后的整个流式过程不再校验身份
                   SSE: new SseEmitter(0L) 永不超时
                   WS : afterConnectionEstablished 取一次 userId 存进局部变量

所以只有两条路：
  手段 A（主要）  每实例 ConnectionRegistry + reconciler 命中后 closeAll
  手段 B（兜底）  每一帧出站前（含心跳）O(1) 查本地 SecurityStateCache
```

手段 A 与 B **不是二选一**。§28.2 原文列的是「C. 两者同时」，
§52 也把「都必须维护 Security Local Cache」写成判定规则。缺 A 则事件丢失时流不会断；
缺 B 则 reconciler 扫到之前（1~2 秒）那一帧仍会推出去。

---

## 2. §52 宿主清单：为什么必须写 `current_deployment_owner`

§52 明确「**不得只写 Gateway / Agent / Content-Automation**」。清单必须按
`logical_owner` + `current_deployment_owner` 两列写，与 §71 的 scheduled-ownership 同格式。
原因：Wave 1 里所有业务域仍在同一个进程（`dinqor-core-host`）中，
**逻辑归属与部署归属在这一阶段是分离的**。

| `logical_owner` | 承载的 SSE/WS | Wave 1 | Wave 2 | Wave 3+ |
|---|---|---|---|---|
| agent | 对话 SSE / WS | `dinqor-core-host` | `dinqor-agent` | `dinqor-agent` |
| automation | 自动化通知 SSE | `dinqor-core-host` | `dinqor-content-automation` | `dinqor-content-automation` |
| blogagent | BlogAgent SSE | `dinqor-core-host` | `dinqor-content-automation` | `dinqor-content` |
| studio | Img2Three SSE | `dinqor-core-host` | **`dinqor-core-host`** | `dinqor-studio` |
| identity（security plane） | 不承载业务流，承载 deny 判定 | `dinqor-gateway` | `dinqor-gateway` | `dinqor-gateway` |

**`dinqor-core-host` 必须实现 `LocalSecurityStateCache` 与连接注册表，
且必须持续到 Wave 3（studio 抽出）之后。** 最容易漏掉的一格就是 studio 那行 ——
Wave 2 已经把 agent / automation 拆走了，很容易顺手认为 core-host 不再需要安全平面，
但 studio 的 Img2Three SSE 此时**仍然**跑在 core-host 上。

### 判定规则（取代固定清单）

§52 给的是一条**规则**而不是一张表，这样后续 Wave 调整宿主时规则仍然成立：

> 任一当前部署单元，凡其承载的业务域中存在 SSE/WS，都必须维护 Security Local Cache，
> 并在 Wave 2 Staging 中验证封禁生效。

代码侧把这条规则写进了 `SecurityStateReconciler` 的类注释，
所以调整宿主时改的是注释里的清单，而不是某处隐藏的 if 分支。

### 机器能守什么、不能守什么

| 问题 | 谁能回答 |
|---|---|
| 哪些路由是流 | 机器（`route-inventory.yaml` 的 `stream` 字段） |
| 这条流的封禁边界实现在哪个文件 | **只能人工登记**（`docs/stream-hosts.yaml`） |

`stream-hosts.yaml` 登记了 8 条流、5 个宿主。门禁 `stream-ban-boundary-coverage` 做三件事：

1. 每条 `stream != none` 的路由都必须被某个 host 覆盖；
2. 每个 `boundary_files` 必须真实存在；
3. 每个 `boundary_files` 必须引用 `ConnectionRegistry`（即真的登记了连接）。

第 3 条是关键的：只要求"文件存在"的话，一个删掉了注册逻辑的空壳控制器也能过门禁。
**新增流端点时不登记就门禁失败 —— 这就是 §12「补了又丢」的阻止机制。**

⚠️ `routes` 里填的是 `route-inventory.yaml` 的 id，而 id 由路径推导（含路径变量名）。
因此改动扫描器的 id 生成规则时必须同步重跑门禁 —— id 变了但语义没变，
门禁会以"引用了不存在的路由"报错。这是**有意的**：宁可让人确认一次，也不要静默漂移。

---

## 3. 每个宿主的两份本地状态

§48 / §49 把宿主侧的封禁能力拆成两个只依赖 JDK 的类，都放在共享内核
`com.xander.lab.common.security`：

| 类 | 结构 | 回答什么 |
|---|---|---|
| `SecurityStateCache` | `ConcurrentMap<accountId, securityVersion>`（只在 disabled 时存在） | 这个账号现在还能不能收数据？ |
| `ConnectionRegistry` | `ConcurrentMap<accountId, Set<ConnectionHandle>>` | 这个账号在本实例上有哪些连接要关？ |

两者都在 `SecurityPlaneConfiguration` 里以 `@Bean` 暴露成**单例**（每实例一份），
所以宿主控制器直接注入即可，不需要自己持有状态。

### 3.1 为什么 `SecurityStateCache` 不做远程调用

被封禁账号集合通常很小，本地 Set 查询成本近似 O(1)。若改成每次请求调 Auth Service，
就把**每个请求的可用性绑在 identity 上** —— identity 抖动会让全站 5xx。
§48 因此明确要求「不做远程 Auth Service 调用」。

### 3.2 为什么不做中心化连接表（§49）

`ConnectionRegistry` 的类注释列了三条理由，都值得保留：

- 连接句柄不可跨进程迁移 —— 它包着 `SseEmitter` / `WebSocketSession`，
  这些都是本机对象，中心表里存不下可用的引用；
- 中心表会引入新的单点与一致性成本；
- 每实例本地注册表 + 事件广播 + reconcile 已足够收敛。

### 3.3 ⚠️ `per_instance` 任务不得使用全局 ShedLock（§69）

`SecurityStateCache` 的 `lastSeenSeq` 是**每实例自己的游标**，只被本实例的
`SecurityStateReconciler` 推进。两条约束：

- **游标必须在实例内部**（§27）：用全局单例游标时，先到的实例会把游标推高，
  其他实例就永久漏掉那段区间。
- **reconciler 不得加全局锁**（§69）：加锁会让 N-1 个实例的本地缓存永不刷新，
  而验收指标（"不重复执行"）**仍然通过** —— 这是一个不会失败的失败。

对应地，`scheduled-ownership.yaml` 里 `SecurityStateReconciler` 的
`execution_scope = per_instance`、`singleton_strategy = none`，
门禁 `scheduler-lock-scope-consistency` 校验锁名与 `execution_scope` 一致。

---

## 4. 手段 A 与手段 B 的逐宿主落地

### 4.1 统一的词汇与关闭方式

词汇唯一来源是 `ConnectionRegistry`，宿主**不得再写一份字面量**：

```java
REASON_ACCOUNT_DISABLED        = "ACCOUNT_DISABLED"
WS_CLOSE_CODE_ACCOUNT_DISABLED = 4403
```

放在共享内核而不是 identity，是为了让 automation / studio 的 SSE 宿主不必为了
一个字符串常量反向依赖 identity（§21）。否则前端要处理的关闭码会漂移。

- **SSE 用 403**：SSE 没有应用层关闭码，能表达的只有 HTTP 状态，
  所以句柄用 `emitter.completeWithError(new ResponseStatusException(FORBIDDEN, ...))`。
- **WS 用 4403**：落在 RFC 6455 的「应用私有」区间（3000–4999），
  客户端可以把它与网络故障区分开，从而「停止重连 + 提示已封禁」，
  而不是陷入重连风暴。该值是对外契约，登记在 `contract-changes.yaml` 的
  `ws-account-disabled-close` 条目下。

### 4.2 8 条流的边界实现位置

| 宿主文件 | 流数 | 句柄 | 入口侧校验 | 手段 B（出口） |
|---|---|---|---|---|
| `agent/api/AgentConversationController` | 2 | `SseConnectionHandle` | — | `send` L349 / `sendHeartbeat` L398 |
| `blogagent/api/BlogAgentController` | 3 | `SseSubscriber` | — | `send` L301 / `sendHeartbeat` L379 |
| `automation/infrastructure/BlogPlanSseService` | 1 | `NotificationConnection` | `subscribe` L80 | `emit` L147（心跳也走这里） |
| `studio/api/Img2ThreeController` | 1 | `Img2ThreeStream` | — | `send` L125 |
| `agent/api/AgentStreamWebSocketHandler` | 1 | `WsConnectionHandle` | `afterConnectionEstablished` L151 | `emit` L311 / `handleTextMessage` L181 / 心跳 L282 |

四个 SSE 宿主的出口校验形状完全一致：先查 `isOpen()`，再查
`securityStateCache.isDisabled(accountId)`，命中就 `close(REASON_ACCOUNT_DISABLED)` 并
**立即 return（不再发送这一帧）**。这是手段 B 的全部内容 —— 它必须是"每帧出站前"，
而不是"每个业务事件前"。

### 4.3 三个不显眼但必须保留的细节

- **`BlogPlanSseService` 的入口侧抛 403 而不是返回立刻关闭的 emitter**：
  后者会让客户端先收到 200 再断开，看起来像网络抖动，从而触发无意义的重连。
  这个差别只在客户端行为上可见，测试很难覆盖 —— 所以它写在代码注释里。
- **`Img2ThreeController` 的关闭只停推送，不取消后台重建任务**：
  任务本身是持久化的，关掉它会让用户的资产状态与数据库不一致。
  封禁是"停止向该账号推送"，不是"取消它已经付过费的作业"。
- **WS 封禁后不回 pong**：客户端保活 ping 到达时若发现已封禁，
  必须按 4403 关闭而不是照常回 pong —— 否则客户端会误判链路健康并继续重连。

---

## 5. 5 秒预算的分解（§28.3）

「封禁事件到达宿主服务后，最长残留推送 ≤ 5 秒」不是一个单点指标，而是三段之和：

| 段 | 预算 | 由什么保证 | 可观测吗 |
|---|---|---|---|
| 事件产生 → 到达宿主服务 | ≤ 2 秒 | reconciler 的 `fixedDelay`（默认 1500ms） | 是（`securityStateAge`） |
| 宿主本地标记 denied | ≤ 0.1 秒 | `SecurityStateCache.markDisabled`，一个 `ConcurrentHashMap.put` | 否（无 IO，无法单独采样） |
| 遍历并关闭该 account 连接 | ≤ 1 秒 | `ConnectionRegistry.closeAll`，单实例连接数有限 | 是（`admin_ban_stream_disconnect_latency`） |
| 余量 | ≥ 1.9 秒 | — | — |

因此两条硬约束：

- **`reconcile interval` 必须 ≤ 2 秒**。当前默认 1500ms
  （`auth.refresh.security-reconcile-interval-ms`）。
- **调整它必须同步复核本 SLO**。这不是"调优参数"，是预算的一部分。

⚠️ 第一段用的是 `fixedDelay` 而不是 `fixedRate`：上一次执行结束后再等 interval，
避免慢查询把调度器压垮。代价是最坏情况下间隔会超过 1500ms ——
这正是余量 1.9 秒存在的原因，也是**不把 interval 调到接近 2 秒**的原因。

### 第 3 段的分母是"本实例的连接数"

`closeAll` 是同步遍历本实例该账号的句柄集合。§49 已经把中心表排除掉了，
所以这个集合的上界就是单实例连接数，而不是全局连接数 ——
这也是"每实例注册表"在 SLO 上的额外好处：关闭动作的耗时**不随集群规模增长**。

---

## 6. 心跳与主要手段的关系（§28.4）

§50 规定「心跳也应触发检查」。四个 SSE 宿主与 WS 宿主都实现了这一条：
心跳路径**先**查 `isDisabled` **再**决定要不要发心跳帧。

但 §28.4 有一条必须写清的推论：

> 如果 heartbeat 间隔大于 5 秒，那么业务无事件时 **SLO 只能靠 A（主动断开）保证，
> B 只是兜底**。

实现者**不得认为「有心跳就够了」**。当前各宿主的心跳间隔：

| 宿主 | 心跳间隔 | 与 5 秒预算的关系 |
|---|---|---|
| `AgentConversationController` | 15s | > 5s，只能靠手段 A |
| `BlogAgentController` | 15s | > 5s，只能靠手段 A |
| `BlogPlanSseService` | 25s | > 5s，只能靠手段 A |
| `AgentStreamWebSocketHandler` | 20s | > 5s，只能靠手段 A |
| `Img2ThreeController` | **无心跳调度器** | 手段 B 实际不存在，**唯一**手段是 A |

所以这 8 条流里，**5 秒 SLO 完全由手段 A（reconciler 主动断开）承担**，
心跳校验只是"如果 reconciler 恰好还没扫到，也不会继续推"的兜底。

**没有一条流把心跳当主要手段**，因此 §28.4 要求的
「`heartbeat interval` 必须 ≤ 2 秒并写入 `contract-changes.yaml`」这条附加契约
**当前不适用**。将来若有人为了减少空转把心跳拉长、或反过来想用心跳替代 reconciler，
必须先改 `contract-changes.yaml`。

### ⚠️ `Img2ThreeController` 是最脆弱的一条

它没有心跳调度器，所以：

- 手段 B 只在**有业务事件推送时**才被执行；
- 若后台重建任务长时间不产生事件，这条流的断开**只依赖手段 A**；
- 而它 Wave 1/Wave 2 都跑在 `dinqor-core-host` 上（见 §2 的表）。

这就是 §52 把 core-host 明确列进清单的原因。

---

## 7. SLO 验收（§53）

§53 要求分**两类**观测，两者都要，不能只测一类：

```text
① Admin Ban → 新 HTTP / WS / SSE 请求被拒
② Admin Ban → 已建立 SSE / WS 被关闭
```

对应指标：

```text
admin_ban_new_request_block_latency
admin_ban_stream_disconnect_latency
```

每类都记录 `P95` / `P99` / `hard bound`，与 §28.1 / §28.2 的阈值对齐：

| 类别 | P95 | P99 | hard bound |
|---|---|---|---|
| 新请求 / 新连接 | ≤ 1 秒 | ≤ 2 秒 | ≤ 5 秒 |
| 已建立 SSE / WS | — | — | ≤ 5 秒（残留推送） |

⚠️ **两类必须分别测量**。只测第①类会掩盖一个典型缺陷：新连接被挡住、
但已建立的流因为手段 A 没实现而一直收数据 —— 这正好是 §12「补了又丢」
最容易发生的形态。

当前状态：两个指标**尚未接入**（Wave 2 的可观测性工作）。
本文给出的是可验证的预算分解，不是已上线的指标。

---

## 8. 已知缺口

| 缺口 | 影响 | 状态 |
|---|---|---|
| 两个 SLO 指标未接入 | §53 要求的两类观测目前只能靠 `ConnectionRegistry.totalClosed()` 与日志人工核对 | Wave 2 |
| 手段 A 的传播依赖 reconciler | 多实例下，非抢到锁的实例靠各自 reconciler 在 1.5 秒内收敛（仍在预算内，但"快路径"名不副实） | Wave 1（Redis PubSub） |
| `SecurityStateCache` 无持久化 | 实例重启后靠 `fullReconcile`（默认 5 分钟）重新收敛；重启窗口内已建立的流不会被主动断开，只能靠手段 B | 设计取舍 |
| `Img2ThreeController` 无心跳 | 见 §6，该流在无业务事件时完全依赖手段 A | 已知 |
| `contract-changes.yaml` 的 `security-plane-fail-closed` | §28.2 的 stale fail-closed 与 §103 互相否定（security plane 与 refresh 表同库），Wave 2 放行评审前必须二选一 | 待对齐 |

---

## 9. 怎么验证

```bash
# 8 条流的封禁边界（入口拒绝 / 中途封禁 / reconciler 主动断开 / 注销）
java ... -Dtest=AgentConversationControllerTest test
java ... -Dtest=BlogAgentControllerTest test
java ... -Dtest=BlogPlanSseServiceTest test
java ... -Dtest=AgentStreamWebSocketHandlerTest test

# 本地安全平面（标记 + 断连的组合顺序与幂等性）
java ... -Dtest=SecurityStateApplierTest test

# 机器门禁：stream-ban-boundary-coverage + scheduler-lock-scope-consistency
python scripts/migration/ci_gates.py
```

⚠️ `Img2ThreeController` **当前没有测试类**（`stream-hosts.yaml` 里该宿主的 `tests` 为空数组）。
它同时是唯一无心跳、且 Wave 1/Wave 2 都留在 core-host 的流 ——
这是清单里风险最高、覆盖最弱的一格，补测试应排在 Wave 2 Staging 之前。

`stream-hosts.yaml` 的 `unregistered_routes` 当前为空，表示 8 条流全部已登记。
**这个字段必须保持显式存在**：删掉它会让门禁无法区分"没有未登记的流"
和"这份清单还没写完"。
