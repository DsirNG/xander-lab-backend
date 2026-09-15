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
        │  A. 快路径：outbox publisher 投递事件   ← ⚠ 尚未实现        │
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
| **outbox publisher 未实现** | §27 的「快路径」不存在，封禁只靠 reconcile 兜底（延迟仍是 1.5 秒，未超 5 秒预算）；`security_event_outbox` 会无界增长 | 待做 |
| `SecurityStateCache` 无持久化 | 实例重启后靠 `fullReconcile`（5 分钟）重新收敛，重启后最坏 5 分钟窗口 | 设计取舍 |
| `contract-changes.yaml` 的 `security-plane-fail-closed` | §28.2 的 stale fail-closed 与 §103 互相否定（security plane 与 refresh 表同库），Wave 2 放行评审前必须二选一 | 待对齐 |

---

## 6. 怎么验证

```bash
# 封禁事务边界（§26 四步的顺序、自愈、审计字段边界）
java ... -Dtest=AccountSecurityStateServiceTest test

# 管理台封禁写路径（SOT + 能力守恒：遗留 Redis 撤销仍在）
java ... -Dtest=UserAdminServiceTest test

# 长连接封禁边界（入口拒绝 / 中途封禁 / reconciler 主动断开 / 注销）
java ... -Dtest=AgentStreamWebSocketHandlerTest test

# 机器门禁（含 stream-ban-boundary-coverage 与 inventory-integrity）
python scripts/migration/ci_gates.py
```

`AccountSecurityStateServiceTest#applyStateIsTransactional` 用注解反射守住
`@Transactional` —— 这个注解被删掉时，除了这条测试没有任何其它症状。
