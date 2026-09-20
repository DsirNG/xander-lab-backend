# 刷新历史保留策略（V9 §40 / §42 / §43）

本文描述 `auth_refresh_token_history` 的容量与清理：谁负责让表<b>有界</b>、
哪个窗口由哪个机制兑现、以及两个清理任务为什么必须慢。

对应 V9 章节：§33（三个独立窗口）、§40（有界 Replay History）、
§41（Auth Maintenance Jobs）、§42（History Cleanup）、§43（Ciphertext Cleanup）、
§68（`global_once` 与 ShedLock）。

---

## 1. 一句话结论

**保留窗口由写入时的值决定，不由清理任务决定。** 清理任务只是把已经失效的行搬走；
即使它停摆，安全语义（能不能识别盗用、能不能返回同一个 successor）都不会改变。

```
写入时（轮换成功，RefreshTokenService）
  ├─ grace_expires_at                 = now + 25s      → 幂等返回窗口
  ├─ retained_until                   = now + 24h      → Reuse 判定窗口（§40.2 必须显式写）
  └─ successor_ciphertext_expires_at  = now + <密文窗口> → 密文逻辑过期

读取时（重放，RefreshTokenService）
  ├─ isWithinGrace(now)          → 幂等返回同一个 successor
  ├─ isCiphertextReadable(now)   → 解密前先查时间，过了就拒绝
  └─ isWithinRetention(now)      → 还能判 theft

清理时（两个 global_once 任务，只影响"字节还在不在"）
  ├─ AuthRefreshHistoryCleanup      删 retained_until 过期的行       (§42)
  └─ AuthRefreshCiphertextCleanup   清空已过期的密文字段，不删行      (§43)
```

---

## 2. 三个窗口互不推导（§33）

| 窗口 | 默认值 | 决定什么 | 载体 |
|---|---|---|---|
| `grace_expires_at` | 25s | 重放算不算 theft | history 行 |
| `retained_until` | 24h | 还能不能识别 theft | history 行 |
| `successor_ciphertext_expires_at` | 秒级 | 密文还能不能解密 | history 行 |

**只保留 grace 会让 25 秒之后的真实盗用无法被识别**（§40.5）。三个窗口必须同时存在，
且清理任务只能碰后两个已经过期的那部分。

---

## 3. 两个任务

| | `AuthRefreshHistoryCleanup` | `AuthRefreshCiphertextCleanup` |
|---|---|---|
| 章节 | §42 | §43 |
| SQL | `DELETE ... WHERE retained_until < now ORDER BY retained_until LIMIT ?` | `UPDATE ... SET successor_token_ciphertext = NULL WHERE successor_ciphertext_expires_at < now AND ... IS NOT NULL ORDER BY ... LIMIT ?` |
| 间隔 | 5 分钟 | 30 秒 |
| 单批 | 500 | 500 |
| 单轮批数上界 | 20 | 20 |
| 依赖索引 | `idx_refresh_history_retained_until` | `idx_refresh_history_ciphertext_expires` |
| `execution_scope` | `global_once` | `global_once` |
| `lockAtMostFor` | `PT5M`（启发式，未按实测校准） | `PT2M`（同上） |

### 为什么是 `global_once`

删除/清空的是**共享**表行。多实例同时处理同一段范围只会互相锁等待，换不来任何吞吐。
这与 §69 里 `per_instance` 的 reconciler 正好相反：那种任务各自维护本地状态，
加全局锁才是错的。判据是"这份状态是共享的还是每实例的"，不是"任务重不重要"。

### 为什么必须慢、必须小批

§42 原文：「全天低强度，而不是大批量集中 DELETE」。原因是 DELETE 持有索引范围锁，
而 `auth_refresh_token_history` 同时被轮换写入 —— 一次 10,000 行的集中 DELETE
会让正常的 refresh 请求排队等锁。容量上界是 480,000 行（§40.4），
所以清理的**吞吐**只要跟得上写入即可，不需要快。

批间停顿是 `sleepMs + [0, sleepMs]` 的随机值。jitter 不是为了"更好看"：
固定间隔会让清理批次与业务写入形成同频共振，把锁等待反复放大。

---

## 4. ⚠️ 事务边界：这是本子系统最容易写错的地方

§42 要求「**每批独立事务**」。事务边界由 `AuthRefreshRetentionService` 的两个
`@Transactional` 方法提供，循环所在的调度方法**刻意不带** `@Transactional`。

**在循环外面加 `@Transactional` 会发生什么**（这是最容易犯、后果最重的错）：

- 20 批 × 500 行 = 10,000 行删在**一个**事务里；
- 批间 `sleep` 期间**一直持锁**（4~8 秒），并发的 refresh 写入全部撞上锁等待；
- undo log 与主从复制延迟一起膨胀。

**它没有任何功能性症状** —— 测试全绿、功能正常、清理也确实在跑，
只在生产高峰暴露。所以它由两条注解反射测试守着：

- `AuthRefreshRetentionServiceTest#bothBatchMethodsAreTransactional` —— 每批方法必须带 `@Transactional`；
- `AuthRefreshHistoryCleanupTest#schedulerMethodItselfIsNotTransactional` —— 调度方法必须**不带**。

---

## 5. ⚠️ 这些任务不是安全边界

§43 原文：「即使物理字段晚几十秒清空，业务代码也必须在 grace 到期后拒绝返回。
所以安全语义依赖**时间判断**，不是依赖清理任务刚好准点运行。」

代码侧已经按这个要求实现：重放路径在 `cipher.decrypt(...)` **之前**先问
`history.isCiphertextReadable(now)`，它同时检查密文非空**和**
`now <= successorCiphertextExpiresAt`。

因此：**清理任务停摆、甚至被误删，都不会让过期密文重新可用。**

这条区分必须留在代码里，否则下一次修订很容易把它"优化"成安全边界 ——
一旦有人把重放路径改成"密文为空就当作已过期"，任务停摆就变成了一个可利用的窗口。

同理，`AuthRefreshHistoryCleanup` 删的行 `retained_until` 已过期，
既超出 Reuse 判定窗口也超出幂等返回窗口，删掉不会让任何**仍可被识别**的盗用变成不可识别。

---

## 6. 数据库负载高时自动暂停（§42）

实现形式是**单批耗时阈值**（`cleanupSlowBatchMs`，默认 2000ms）：
某一批超过阈值就主动结束本轮，剩余行留给下一轮。

**已知局限**：这是启发式代理信号，不是真正的负载检测 —— 读不到 `Threads_running`
之类的指标，也分不清"慢是因为本任务自己"还是"因为别的高峰流量"。
真正的负载感知暂停属于 Wave 2 的可观测性工作。

选择"退让"而不是"重试"的理由：保留策略没有任何时效性，晚一轮清理完全无害；
而在主库吃紧时继续打满 20 批会显著加剧争用。

---

## 7. 已知缺口

| 缺口 | 影响 | 状态 |
|---|---|---|
| `lockAtMostFor` 未按实测校准 | 取值是从任务间隔启发式推的（`PT5M` / `PT2M`），不是量出来的。设得过小会在多实例下出现锁提前释放 → 重复执行（幂等，无害）；设得过大则故障实例会长时间挡住其他实例 | 切流前 |
| 真实负载感知暂停 | 见 §6，目前只有单批耗时这一个代理信号 | Wave 2 |
| Primary 绑定未实现 | §42 要求 DELETE 必须走 Primary。当前单库无此问题；将来读写分离时本任务必须显式绑定主库数据源，**不得跟随只读路由**（在 Replica 上 DELETE 会直接失败） | 读写分离前 |
| `auth.refresh.*` 配置键不在机器清单里 | 已由 `scan.py` 扫描 `@ConfigurationProperties` 字段；Java 初始化表达式只记录为安全占位符，不落盘原文。`configuration-properties-coverage` 会阻止源代码键从清单中静默消失 | ✅ 已完成；后续仍需把 `@Scheduled` 占位符默认值与配置类默认值收敛为单一真源 |
| `auth.refresh.enabled` 默认 `false` | 整个新 Token 模型未生效，所以这两个任务当前是空转（表为空）。切流是 Wave 2（§94） | 切流前 |

---

## 8. 怎么验证

```bash
# 批次循环语义（排空 / 批数上界 / 整轮时间边界一致 / 中断恢复 / 慢批退让 / 失败不抛出）
java ... -Dtest=RetentionBatchLoopTest test

# 两个任务：参数接线、注解守卫（含"调度方法不得是事务性的"）
java ... -Dtest=AuthRefreshHistoryCleanupTest test
java ... -Dtest=AuthRefreshCiphertextCleanupTest test

# 每批独立事务的注解守卫
java ... -Dtest=AuthRefreshRetentionServiceTest test

# 机器门禁（scheduler-lock-scope-consistency 校验锁名与 execution_scope）
python scripts/migration/ci_gates.py

# 配置 Contract 的源代码覆盖门禁
python scripts/migration/ci_gates.py configuration-properties-coverage
```
