# 封禁与长连接安全平面（V9 §26 / §27 / §48 / §49–§52）

本文描述「管理员封禁一个账号」到「该账号在全部宿主上的长连接被终止」的完整链路，
以及每一步落在哪个文件、由哪个门禁守住。

对应 V9 章节：§2.3（能力守恒）、§12（能力不丢）、§21（共享内核边界）、
§26（封禁事务）、§27（事件传播）、§28（5 秒预算）、§48–§52（本地 deny cache 与连接注册表）。

---

## 1. 一句话结论

封禁由**一个 MySQL 事务**负责「状态正确」，由**每个宿主自己的对账循环**负责
「传播到位」。事务内不触碰任何其它进程 —— 这是它能扛住「提交后立刻崩溃」的原因。

```
                       ┌─────────────────────────────────────────┐
管理员 PATCH /status ──▶│ 一个事务（UserAdminService.setUserStatus）│
                       │  ① sys_user.status 条件更新              │
                       │  ② 撤销全部 Refresh Session（MySQL）      │
                       │  ③ account_security_state 版本 + 游标     │
                       │  ④ security_event_outbox 落库             │
                       └─────────────────────────────────────────┘
                                        │ commit
                                        ▼
        ┌───────────────────────────────────────────────────────────┐
        │ 传播（事务外，跨进程，两条路径）                             │
        │  A. 快路径：outbox publisher 投递事件   ← 已实现（见 §3.5）  │
        │  B. 兜底：每个宿主按 change_seq 增量对账 ← 已实现            │
        └───────────────────────────────────────────────────────────┘
                                        │
                                        ▼
        ┌───────────────────────────────────────────────────────────┐
        │ 宿主本地：SecurityStateCache（O(1) 查询）+ ConnectionRegistry │
        │  手段 A：reconciler 命中 → closeAll 主动断开已建立的流       │
        │  手段 B：每一帧出站前（含心跳）O(1) 校验                     │
        └───────────────────────────────────────────────────────────┘
```

---

## 2. 事务边界：什么在事务里，什么不在

`UserAdminService.setUserStatus`（`identity/application`）与
`AccountSecurityStateService.applyState`（同包）在**同一个事务**内，
因为前者通过 Spring 代理调用后者的 `@Transactional` 方法（`REQUIRED` 传播）。

| 步骤 | 在事务内 | 载体 | 说明 |
|---|---|---|---|
| ① `sys_user.status` | 是 | MySQL | 条件更新，仅当状态确实变化时才写 |
| ② 撤销 Refresh Session | 是 | MySQL | `auth_refresh_session`，按索引一条 UPDATE |
| ③ `account_security_state` | 是 | MySQL | `security_version + 1`、`change_seq` 显式写入 |
| ④ `security_event_outbox` | 是 | MySQL | 事务性发件箱（§27） |
| ⑤ 推送 accountId 到各宿主 | **否** | 跨进程 | 只能事务后做，由 §27 的传播策略兜底 |
| ⑥ 关闭已建立的 SSE / WS | **否** | 跨进程 | 同上 |
| 遗留 Redis 撤销 | **否** | Redis | Redis 没有回滚，见下 |

### 遗留 Redis 撤销为什么故意留在事务外

`UserAdminService.revokeSessions` 删的是 `login:token:<jwt>` 与
`login:user_tokens:<userId>`。它**必须保留**到 Wave 2（§2.3 能力守恒）——
鉴权在 Wave 2 切换前仍以 Redis 为准，删掉它会让封禁在切换前完全失效。

它不在事务里，因此可能出现「MySQL 回滚但 Redis 已删」。这个方向是**安全**的：
只可能多撤销（用户被迫重新登录一次），不可能少撤销（封禁没生效）。
方向上的不对称才是关键，而不是原子性本身。

### 为什么 `change_seq` 必须显式写入

`account_security_state.change_seq` 用 `security_state_sequence` 单行游标
（`LAST_INSERT_ID(seq + 1)`）分配，而不是 `AUTO_INCREMENT`。

用 `AUTO_INCREMENT` 时，ODKU 的**更新路径不会推进该列**，于是
`change_seq > cursor` 的增量拉取会静默漏掉这次封禁 —— 而实例照样收敛到
「没有变化」，是一个**不会报错的失败**。这是本子系统里最危险的单点。

`security_version` 同理用 SQL 侧 `+ 1` 而不是 Java 侧读-改-写：两个管理员并发
操作同一账号时，读-改-写会让两次变更算出同一个版本号，于是任何「按版本号失效」
的缓存少失效一次。

---

## 3. 传播：为什么必须有兜底路径

`SecurityStateReconciler`（`identity/application`）是 `per_instance`，
1.5 秒一次增量拉取：

| 手段 | 位置 | 覆盖的窗口 |
|---|---|---|
| A 主动断开（主要） | `reconcile()` → `ConnectionRegistry.closeAll` | 事件已到宿主之后 |
| B 出站校验（兜底） | 8 条流各自的发送边界 | A 尚未扫到的 1~2 秒 |

**禁止给 reconciler 加全局 ShedLock**（V9 §69）。全局锁会让 N-1 个实例的本地
缓存永不刷新，而「不重复执行」的验收指标**仍然通过** —— 又是一个不会失败的失败。
门禁 `execution-scope-validation` 会拒绝 `per_instance` + 全局锁的组合。

### 5 秒预算从哪来（§28.3）

```
事件产生 → 到达宿主       <= 2 秒   ← reconcile interval 1.5 秒保证
宿主本地标记 denied       <= 0.1 秒
遍历并关闭该 account 连接  <= 1 秒
余量                      >= 1.9 秒
```

所以 reconcile interval **必须 <= 2 秒**；改它必须同步复核该 SLO。

---

## 3.5 快路径：`SecurityEventOutboxPublisher`（§27）

`identity/application/SecurityEventOutboxPublisher`，`global_once` +
`@SchedulerLock(name = "security-event-outbox-publisher")`，1 秒一轮，
领取 `status IN ('PENDING','FAILED') AND next_attempt_at <= NOW(3)` 的行。

### 为什么是 `global_once` 而不是 `per_instance`

本任务领取的是**共享的**发件箱行，投递成功后要把行标成 `PUBLISHED` ——
领取/标记状态是所有实例共用的，所以执行体只能有一个。否则 N 个实例各自领取同一批行、
各自标记，「已投递」变成 N 份互相矛盾的判断。

跨实例传播因此**不是调度器的事，而是投递目标的事**：Wave 0A 的投递目标是本实例的
安全平面，Wave 1 换成 Redis PubSub 广播，届时只换投递那一行，
领取/标记/退避的生命周期不变。

### ⚠️ Wave 0A 的能力边界（不得误报）

因为投递目标是「本实例」，所以**快路径目前只对抢到锁的那个实例生效**，
其余实例仍只靠各自的 reconciler 在 1.5 秒内收敛。这仍在 5 秒预算内，
**SLO 没有被违反**，但「快路径」这个词在多实例下暂时名不副实 ——
真正的多实例快路径要等 Wave 1 的 Redis PubSub。

本任务当前还承担另一个不可省略的职责：**让 `security_event_outbox` 有界**。
没有消费者时该表会无界增长。

### ⚠️ 两个「看起来更合理」的错误改法

**一、投递成功就推进 reconciler 的游标。** 这会制造一个永久漏封禁：
`change_seq` 在事务内分配，而发件箱行的可见性取决于**提交顺序** ——
两个并发封禁可能让 `change_seq=6` 先提交、`change_seq=5` 后提交。
此时 publisher 只看得见 6，若把游标推到 6，则 5 提交后会被
`pullChangesAfter` 永久跳过，那个账号的封禁**永远不会**到达本实例。

所以**游标只有一个所有者：`SecurityStateReconciler`**。
`SecurityEventOutboxPublisherTest#neverAdvancesTheReconcilerCursor` 守住这条。

**二、顺手刷新 `markSynced`。** §87 的 `securityStateAge` 可能被用于
fail-closed 判定，把它刷成「刚刚同步过」会**掩盖一个已经停摆的 reconciler**。
由 `#neverFakesTheSyncTimestamp` 守住。

### 失败处理与 `DEAD`

| 情形 | 处理 |
|---|---|
| 单条事件失败 | 记 `FAILED` + 指数退避（1s → 2s → … 封顶 60s），**不中断整批** |
| 第 8 次仍失败 | 记 `DEAD`，退出领取集合，不再自动重试 |
| 记录失败时数据库又失败 | 吞掉并继续下一批，否则一条事件的失败会推迟整批 |
| `markPublished` 失败 | 行留在 `PENDING`，下轮重复投递 → 投递端**必须幂等** |

**判死是安全的**，这正是 §27 选择事务性发件箱的原因：发件箱只负责**通知**，
不负责**状态**。`account_security_state` 才是 SOT，兜底路径仍会收敛 ——
一条 `DEAD` 事件不会让封禁失效，只会让快路径晚 1.5 秒。

⚠️ 但这条推理依赖「兜底路径同时存在」。一旦有人因为「快路径已经覆盖了」
而删掉 reconciler，`DEAD` 就从「无害的降级」变成「封禁被永久静默丢弃」，
而所有现有验收指标仍然通过。该风险登记在 `capability-baseline.yaml` 的
`admin-ban-outbox-is-optimization-not-guarantee`。

### 唯一的关闭路径

「标记本地缓存 + `closeAll`」抽成了 `SecurityStateApplier.apply`，
被 reconciler 与 publisher **共用**。两边各写一遍的话，Wave 1 换投递时
就会有人只改一边：结果是快路径能标记但不断连、或兜底路径能断连但漏标记 ——
两条路径都「看起来在工作」，缺陷只在多实例 + 已建立连接时暴露。

---

## 4. 长连接封禁边界：8 条流

词汇唯一来源是共享内核的 `com.xander.lab.common.security.ConnectionRegistry`：

```java
REASON_ACCOUNT_DISABLED       = "ACCOUNT_DISABLED"
WS_CLOSE_CODE_ACCOUNT_DISABLED = 4403
```

放在共享内核而不是 identity，是为了让 automation / studio 的 SSE 宿主不必为了
一个字符串常量反向依赖 identity（§21）。**任何宿主不得再写一份字面量**，
否则前端要处理的关闭码会漂移。

| 宿主 | 流数 | 句柄 | 关闭方式 |
|---|---|---|---|
| `agent/api/AgentConversationController` | 2 | `SseConnectionHandle` | `completeWithError(403)` |
| `blogagent/api/BlogAgentController` | 3 | `SseSubscriber` | `completeWithError(403)` |
| `automation/infrastructure/BlogPlanSseService` | 1 | `NotificationConnection` | `completeWithError(403)` |
| `studio/api/Img2ThreeController` | 1 | `Img2ThreeStream` | `completeWithError(403)` |
| `agent/api/AgentStreamWebSocketHandler` | 1 | `WsConnectionHandle` | `close(4403, ...)` |

两条实现细节值得单独记：

- **SSE 用 403、WS 用 4403**：SSE 没有应用层关闭码，能表达的只有 HTTP 状态。
- **`Img2ThreeController` 没有心跳调度器**：所以它的手段 B 实际不存在，唯一主要
  手段是 reconciler 主动断开。这就是 §52 必须把 `dinqor-core-host` 列进
  Security Local Cache 清单的原因 —— Wave 2 之后、Wave 3 之前，studio 的这条流
  仍由 core-host 承载。
- **img2three 的关闭只停推送，不取消后台任务**：任务持久化，取消会造成资产状态不一致。
- **WS 封禁后不回 pong**：否则客户端误判链路健康并继续重连。

`docs/stream-hosts.yaml` 人工登记每条流的边界实现在哪个文件，门禁
`stream-ban-boundary-coverage` 拿它与 `route-inventory.yaml` 比对：
未登记的流、不存在的文件、没有引用 `ConnectionRegistry` 的文件都会失败。
这是阻止「补了又丢」（§12）的机制。

---

## 5. 已知缺口

| 缺口 | 影响 | 状态 |
|---|---|---|
| **快路径只对抢到锁的实例生效** | Wave 0A 的投递目标是本实例，其余实例靠 reconciler 兜底（延迟 1.5 秒，未超 5 秒预算）。多实例快路径要等 Wave 1 的 Redis PubSub | 已知，Wave 1 |
| `shedlock.enabled` 默认 `false` | 单实例下无影响；**多实例前必须置 true 并校准 `lockAtMostFor`**（当前 7+1 个任务的取值是按任务间隔启发式给的，未按实测运行时长校准） | 切流前 |
| `next_attempt_at` 写端用 JVM 时钟 | `applyState` 插入时用 `LocalDateTime.now(clock)`，而领取条件用 `NOW(3)`（DB 时钟）。退避路径已改为 DB 时钟（`NOW(3) + INTERVAL ? MICROSECOND`），只有**首次**插入仍用 JVM 时钟；时钟偏差只会让事件早投或略晚投，不影响正确性 | 已知，低风险 |
| `SecurityStateCache` 无持久化 | 实例重启后靠 `fullReconcile`（5 分钟）重新收敛，重启后最坏 5 分钟窗口 | 设计取舍 |
| `contract-changes.yaml` 的 `security-plane-fail-closed` | §28.2 的 stale fail-closed 与 §103 互相否定（security plane 与 refresh 表同库），Wave 2 放行评审前必须二选一 | 待对齐 |

---

## 6. 怎么验证

```bash
# 封禁事务边界（§26 四步的顺序、自愈、审计字段边界）
java ... -Dtest=AccountSecurityStateServiceTest test

# 管理台封禁写路径（SOT + 能力守恒：遗留 Redis 撤销仍在）
java ... -Dtest=UserAdminServiceTest test

# 本地安全平面应用逻辑（标记 + 断连的组合顺序与幂等性）
java ... -Dtest=SecurityStateApplierTest test

# 快路径投递器（含 neverAdvancesTheReconcilerCursor / 判死边界 / 单条失败不阻塞整批）
java ... -Dtest=SecurityEventOutboxPublisherTest test

# 长连接封禁边界（入口拒绝 / 中途封禁 / reconciler 主动断开 / 注销）
java ... -Dtest=AgentStreamWebSocketHandlerTest test

# 机器门禁（含 stream-ban-boundary-coverage 与 inventory-integrity）
python scripts/migration/ci_gates.py
```

两个注解守卫，删掉注解时除它们之外**没有任何其它症状**：

- `AccountSecurityStateServiceTest#applyStateIsTransactional` 守 `@Transactional`（§26 的原子性）。
- `SecurityEventOutboxPublisherTest#publishDueEventsIsGloballyLocked` 守 `@Scheduled` +
  `@SchedulerLock`，并断言锁名等于 `scheduled-ownership.yaml` 里的 id（§68 的 `global_once`）。
