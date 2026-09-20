# Refresh 协议（V9 §33 ~ §47）

本文固定 Refresh Token 的轮换协议：正常轮换、幂等 grace、Reuse Detection 四分支、
有界 Replay History、密文清理语义，以及与前端 timeout / retry 的对齐关系。

前置：`auth-token-model.md`（Token 模型与权威源）。
清理任务的容量与调度细节见 `refresh-history-retention.md`。

当前后端已接入基础运行指标：`refresh_requests_total`、
`refresh_success_total`、`refresh_success_rate`、`refresh_grace_replay_total`、
`refresh_reuse_detected_total`、`refresh_family_revoked_total`、
`refresh_invalid_total`、`refresh_db_latency`、`refresh_db_error_total` 与
`logout_success_total`。Refresh QPS 由 `refresh_requests_total` 的时间窗口 rate
推导；客户端 retry 指标与跨 Tab 协调仍属于 Wave 2 联调范围，不能由服务端单独伪造。

---

## 1. 一句话结论

**轮换是默认路径，grace 内的重复提交是正常行为，grace 之外的重复提交才是盗用，
而"库里完全找不到的 token"永远不是盗用。**

```
正常轮换       RT(n) == session.current           → 发 RT(n+1)
grace 内重放   RT(n) 在 history 且 now <= grace   → 返回同一个 RT(n+1)，不撤销
grace 外重放   RT(n) 在 history 且仍在 retention  → 判定 theft，撤销整个 family
完全找不到     RT(n) 不在 current 也不在 history  → 无效，绝不撤销
```

第四行是**安全设计**，不是可选优化。理由见 §4.1。

---

## 2. 三条时间线

三个窗口在**写入时**确定，读取与清理都只是消费者。

```
写入时（轮换成功，RefreshTokenService.doRotate）
  ├─ grace_expires_at                 = now + 25s     → 幂等返回窗口
  ├─ retained_until                   = now + 24h     → Reuse 判定窗口（§40.2 必须显式写）
  └─ successor_ciphertext_expires_at  = grace_expires_at → 密文逻辑过期

读取时（RefreshTokenService.rotate）
  ├─ isWithinGrace(now)          → 幂等返回同一个 successor
  ├─ isCiphertextReadable(now)   → 解密前先查时间，过了就拒绝
  └─ isWithinRetention(now)      → 还能不能判 theft

清理时（两个 global_once 任务，只影响"字节还在不在"）
  ├─ AuthRefreshHistoryCleanup      删 retained_until 过期的行      (§42)
  └─ AuthRefreshCiphertextCleanup   清空已过期密文，不删行          (§43)
```

> ⚠️ **三个窗口独立，不得互相推导。** 只保留 grace 会导致 25 秒之后的真实盗用
> 无法被识别。详见 §5.5。

---

## 3. 正常轮换流程（§34）

客户端持有 `RT0`，服务端事务：

```
1.  解析 sid / tokenId / secret
2.  SELECT session FOR UPDATE          ← 锁住会话行，见 §8
3.  校验 ACTIVE / expires
4.  tokenId == current
5.  secret hash 匹配
6.  生成 AT1 / RT1
7.  RT0 写 history（含 successor_token_id / grace_expires_at /
                     retained_until / ciphertext）
8.  current 更新为 RT1，generation + 1
9.  commit
10. 返回 AT1 + RT1
```

实现位置：`RefreshTokenService.rotate` → `doRotate`。

### 3.1 ⚠️ 第 2 步的 `FOR UPDATE` 不是可选项

没有它，多标签页并发刷新会各自生成一个 successor —— **正常使用会被误判成 theft**。
这是"多标签页不误判"在服务端的落点；前端侧的配合见 §8。

---

## 4. Reuse Detection 判定表（§39）

**必须按四分支实现，缺一不可：**

| # | 条件 | 判定 | 动作 |
|---|---|---|---|
| 1 | `tokenId == session.current_token_id` 且 secret hash 匹配 | 正常 rotation | 生成 RT(n+1)，写 history，返回新对 |
| 2 | history hash 匹配 **且** `now <= grace_expires_at` | 幂等重放 | **返回同一个 successor**，不 revoke family |
| 3 | history hash 匹配 **且** `now > grace_expires_at` **且** 仍在 `retained_until` 内 | 真正的 Reuse | `REFRESH_TOKEN_REUSE` → **revoke 当前 family** → security event → `401 REFRESH_TOKEN_REUSED` |
| 4 | **不在 current，也不在 history**（随机垃圾 token） | 无效 token | `401 INVALID_REFRESH_TOKEN` → **不 revoke family** |

代码分支与测试方法一一对应：

```
分支 1  RefreshTokenService.rotate  第 226 行   RefreshTokenServiceTest#rotatesCurrentToken
分支 2  RefreshTokenService.rotate  第 249 行   RefreshTokenServiceTest#replaysIdempotentlyWithinGrace
分支 3  RefreshTokenService.rotate  第 269 行   RefreshTokenServiceTest#revokesFamilyOnReuseAfterGrace
分支 4  RefreshTokenService.rotate  第 237 行   RefreshTokenServiceTest#rejectsGarbageTokenWithoutRevokingFamily
```

### 4.1 ⚠️ 第 4 行的必要性（防 DoS）

`rt_<sid>.<tokenId>.<secret>` 把 `sid` 放在**明文前缀**（`auth-token-model.md` §6），
所以"知道 sid"**不是假设** —— 日志、Referer、客户端存储任一处泄漏即可获得。

如果实现者按"校验失败即视为可疑"处理，那么：

```
攻击者构造 rt_<已知sid>.<随机>.<随机> 反复提交
→ 该会话族被反复撤销
→ 用户被强制登出
```

因此第 4 行是**安全设计**，不是可选优化。

同一个原则在分支 1 内部也适用：`tokenId` 对但 **secret 错**，同样按垃圾 token 处理、
**不 revoke**（`RefreshTokenService.rotate` 第 227 行）。

### 4.2 第 3 行的时间边界（§39.2）

```
history hash 匹配
+ 超过 grace
+ 仍在 replay retention window（§5）
```

**三者同时满足才判 theft。**

超过 retention 的更老旧 token：

```
仍无效（401 INVALID_REFRESH_TOKEN）
但不再触发 family theft alarm
```

理由：把陈旧客户端的正常重试升级成安全事件，只会制造噪音。
实现见 `RefreshTokenService.rotate` 第 283 行，测试 `doesNotAlarmBeyondRetention`。

---

## 5. 有界 Replay History（§40）

**不保存 30 天完整 rotation 历史。**

### 5.1 保留策略

```
retention   = 24h
每 sid 最多 = 最近 48 代
```

配置：`auth.refresh.retention-hours` = `24`、`auth.refresh.max-generations-per-sid` = `48`。

### 5.2 `retained_until` 的写入规则（§40.2，必须显式实现）

```
写入时机：RT(n) 轮换成功、写 history 行时
写入值：  retained_until = now() + retention        # 即 now() + 24h
```

**不得留空、不得依赖后续任务回填。**

测试：`RefreshTokenServiceTest#retainedUntilIsWrittenExplicitly`。

### 5.3 每 sid 48 代上界的实现方式（§40.3）

`retention` 是**时间上界**，`48 代` 是**条数上界**，两者都必须生效：

```
轮换成功写 history 后，同一 sid 内执行：
  保留 generation 最大的 48 行
  其余行直接标记可删（retained_until = now()）
```

实现：`historyMapper.pruneGenerationsBeyondBound(sid, maxGenerationsPerSid, now)`。
测试：`RefreshTokenServiceTest#prunesByCount`。

**条数上界的必要性**：极端高频轮换（异常客户端循环刷新）下，
24 小时窗口仍可能堆积大量行。

### 5.4 容量上界算式（§40.4）

```
rows ≈ 活跃用户数 × 每用户每日轮换次数 × 1（仅保留 24h 窗口）

30m Access、10k 持续活跃用户：
  ≈ 10,000 × 48 × 1 = 480,000 rows

15m Access（更激进）：
  ≈ 10,000 × 96 × 1 = 960,000 rows
```

> ⚠️ **选择 15m Access 时容量上界翻倍，必须同步复核 §42 的清理吞吐。**
> 测试：`RefreshTokenServiceTest#historyCapacityBound`（钉住 30m → 480,000 的算式）。

### 5.5 三个窗口互不推导（§33 / §40.5）

| 窗口 | 量级 | 决定什么 |
|---|---|---|
| `grace_expires_at` | 秒级（25s） | 重放**是否算** theft |
| `retained_until` | 小时级（24h） | 还能**能不能识别**为 theft |
| `successor_ciphertext_expires_at` | 秒级（= grace） | 密文**能不能解密** |

**三者独立。** 只保留 grace 会让 25 秒之后的真实盗用无法被识别；
只保留 retention 会让 grace 内的正常重放被误判成 theft。

---

## 6. Grace 计算与前端对齐（§35 / §36）

### 6.1 公式

```
grace >= refresh request timeout
       + 最大 retry backoff
       + 单程网络/调度余量
       + clock/scheduler margin
```

### 6.2 当前取值推导

```
refresh timeout      = 15s
最大重试次数          = 1
backoff              = 500 ~ 1000ms + jitter
网络/调度余量         >= 5s
────────────────────────────────
grace                = 25s
```

### 6.3 前端侧的实际取值（必须与上表对齐）

```
src/api/http.js
  REFRESH_TIMEOUT          = 15000    ← ⚠️ 与全局超时解耦，见 §6.4
  REFRESH_MAX_RETRY        = 1        ← refreshFailurePolicy.js
  REFRESH_RETRY_BASE_DELAY = 800ms    ← refreshFailurePolicy.js（指数退避）
```

`refreshAccessToken` 走的是裸 `axios.post`（绕过通用 `httpPolicy`），
所以通用重试策略**不**参与 grace 下界计算 —— 参与的是 §9 的 refresh 专用重试。

> 测试：`RefreshTokenServiceTest#graceCoversFrontendTimeout` 会在 grace 被调到
> 覆盖不了 15s + 1 次退避 + 余量时失败。

### 6.4 ⚠️ 实施期发现的真实缺陷：refresh 超时不得跟随全局超时

**方案 §35 的前提在生产环境是错的。** 原文写：

> 当前前端 refresh 超时就是 15 秒 …… `refreshAccessToken` 走的是裸 `axios.post`，
> `DEFAULT_TIMEOUT = 15000`

`15000` 只是**代码默认值**（`Number(env.VITE_REQUEST_TIMEOUT) || 15000`）。实际环境变量：

```
.env.development   VITE_REQUEST_TIMEOUT = 15000
.env.production    VITE_REQUEST_TIMEOUT = 30000     ← 生产被调高了
```

而 `refreshAccessToken` 原本用的就是 `DEFAULT_TIMEOUT`，于是生产环境：

```
grace = 25s  <  refresh timeout = 30s        ← 不变量被破坏
```

**后果（可达的真实故障）**：

```
1. 客户端发起 refresh，服务端完成轮换（比如在 5 秒内），但响应在网络中丢失
2. 客户端一直等到 30 秒才超时；超时被 §45 归类为 transient → 触发 1 次重试
3. 重试带着同一个 RT0 到达服务端，此时距轮换已 > 25 秒（grace 已过）
4. 服务端按 §4 分支 3 判定为「真正的 Reuse」
5. → revoke 整个 family → 用户在所有设备上被强制登出
```

这正是 grace 机制存在的意义所要防住的场景，却在生产环境下必然发生。

**修复**：把 refresh 请求的超时**与全局超时解耦**，固定在 Contract 值：

```
src/api/http.js
  const REFRESH_TIMEOUT = 15000;   // 不跟随 VITE_REQUEST_TIMEOUT
```

这样开发与生产都满足 `grace(25s) >= 15 + 0.8 + 5`。

**为什么不是改 grace**：提高 grace 会同时扩大"被盗 token 重放仍返回同一 successor"的窗口
（25s → 40s 意味着多 15 秒的暴露）。而把 refresh 超时固定成 15s 是**恢复方案本来就声明的
Contract 值**（`contract-changes.yaml` → `refresh-grace-window.frontend_refresh_timeout_ms: 15000`），
既不扩大安全窗口，也不改变其它接口的行为。

**为什么不是改 `VITE_REQUEST_TIMEOUT`**：`DEFAULT_TIMEOUT` 同时是 axios 实例的默认超时，
生产调到 30000 是为了慢接口（上传、LLM 调用）。降回 15000 会波及全部接口，代价远大于收益。

### 6.5 这条不变量的守护方式

| 层 | 守护 |
|---|---|
| 前端 | `http.test.jsx` 的 Contract 用例：mock 的 `ENV_CONFIG.TIMEOUT` **刻意用生产值 30000**，断言 refresh 实际传入 `timeout: 15000` |
| 后端 | `RefreshTokenServiceTest#graceCoversFrontendTimeout`：断言 grace 覆盖 15 + 1 + 5 |
| 登记 | `docs/contract-changes.yaml` → `refresh-grace-window` 的 `notes` |

> 之所以前端 mock 要用 30000 而不是 15000：用 15000 时
> `REFRESH_TIMEOUT` 与 `DEFAULT_TIMEOUT` 数值相同，**测试无法区分两者**，
> 这条不变量就等于没测。已用注入验证（把 `REFRESH_TIMEOUT` 换回 `DEFAULT_TIMEOUT`
> → 该用例失败）。

### 6.4 ⚠️ Grace 参数属于 Contract（§36）

以下任何一项变化都必须同步复核并写入 `docs/contract-changes.yaml`：

```
VITE_REQUEST_TIMEOUT
refresh retry count
refresh backoff
server grace
```

**禁止单独改前端 timeout 而不复核 server grace。**

### 6.5 上线后的调参依据

```
refresh RTT P99
timeout rate
retry start delay
```

---

## 7. Ciphertext 清理语义：禁止提前清空（§38）

> `successor_token_ciphertext` 在**逻辑 grace window 内必须保持可恢复**。

规则分两层：

```
代码层：now > successor_ciphertext_expires_at 后禁止解密/返回
物理层：异步任务在 grace 结束后清空 ciphertext
```

因此不会出现"成功读取后提前清空导致第 3 次 replay 无法返回"的缺陷。

### 7.1 关键区分：**时间判断**才是安全边界

> 即使物理字段晚几十秒清空，业务代码也必须在 grace 到期后拒绝返回。
> 所以安全语义依赖**时间判断**，不是依赖清理任务刚好准点运行。

实现：`AuthRefreshTokenHistory.isCiphertextReadable(now)` 在 `cipher.decrypt` **之前**调用
（`RefreshTokenService.rotate` 第 250 行）。

### 7.2 密文窗口为什么等于 grace

`doRotate` 把 `successor_ciphertext_expires_at` 写成 `graceExpiresAt`（同一时刻）。
窗口**不得短于 grace**，否则第 N 次 grace 重放取不回同一个 successor。

测试：`RefreshTokenServiceTest#ciphertextWindowIsNotShorterThanGrace`。

### 7.3 密文不可用时的降级

若 grace 内重放但密文已不可读（逻辑过期或缺失），实现选择：

```
保守地按 INVALID 处理，不 revoke
```

理由与 §4.1 一致：**宁可放过一次无法判定的重放，也不要因为基础设施状态
把用户踢下线**。日志会记 warn 便于排查。

---

## 8. 多标签页并发协议（§44）

多标签页同时拿到 401 会各自去刷新，后到的那个拿的是已被轮换掉的旧 token ——
在服务端看就是分支 3（真实 Reuse），整个会话族被撤销。**用户什么都没做却被强制登出。**

前端 `src/api/authChannel.js` 用两层机制解决：

```
1. 广播（BroadcastChannel("dinqor-auth") → localStorage + storage 事件降级）
   事件：refresh-start / refresh-success / refresh-failed / logout
   收到 refresh-start 的 Tab 不再自己刷新，等结果后重读 localStorage 里的新 token

2. 跨 Tab 互斥量（navigator.locks → localStorage 租约降级）
   广播只解决"已经知道别人在刷新"；两个 Tab 在同一 tick 同时拿到 401 时
   谁都还没广播，必须靠真正的互斥量
```

服务端侧的配合是 §3.1 的 `SELECT ... FOR UPDATE`。**两层都需要**：
互斥量减少并发刷新，`FOR UPDATE` 保证即使并发也已经串行化。

`isRefreshing` 只保留为 **Tab 内锁**，不再假装能覆盖多 Tab。

---

## 9. Refresh 专用失败分类（§45 / §46）

Refresh 是 POST，但由于服务端提供**幂等 grace**，允许 **Refresh 专用的一次重试**。

### 9.1 分类规则

```
401 INVALID_REFRESH_TOKEN
401 REFRESH_TOKEN_REUSED
403 ACCOUNT_DISABLED
→ 会话不可恢复 → forceLoggedOut

503 AUTH_BACKEND_UNAVAILABLE
5xx
network error
timeout
→ 不清 Token → 不跳登录页 → backoff 后最多重试 1 次
```

### 9.2 重试再次失败

```
显示"暂时无法刷新会话"
保留本地凭据
当前需要认证的新请求失败
不把它伪装成"登录过期"
```

### 9.3 为什么走方案 A（§46）

现有通用策略 `src/api/httpPolicy.js`：

```
SAFE_RETRY_METHODS = {get, head, options}
MAX_RETRY = 2
```

Refresh 是 POST，所以默认不重试。两个方案：

| 方案 | 做法 | 结论 |
|---|---|---|
| **A（采用）** | 在 `refreshAccessToken` 内部显式重试一次 | ✅ |
| B | 给 refresh 请求打 `config._retryIdempotent = true` 复用通用策略 | ❌ |

选 A 的理由：通用策略的重试条件（网络错误 / 5xx）与 §45 的失败分类**并不等价**，
硬塞进去会让两套语义互相污染。`httpPolicy.js` 因此保持语义不变。

### 9.4 分类是按 HTTP 状态，不是按文案

服务端 `Result.code` **就是 HTTP 状态码本身**，线上没有独立的业务码字符串。
所以 §9.1 里的三个"不可恢复"名字在传输层**收敛为「4xx 鉴权失败」**。

**按 message 文案匹配等于把 i18n 当协议用**，已明确排除。

§45 未点名的状态一律保守归为 transient：误判为 transient 只是多一次重试、
用户仍是登录态；误判为 unrecoverable 是不可逆地清凭据踢下线。

---

## 10. 已知缺口

| 缺口 | 影响 | 归属 |
|---|---|---|
| `auth.refresh.enabled` 默认关闭 | `/api/auth/refresh` 已接入 `AuthService.refresh`，开关关闭时继续走旧 JWT；开启后才启用 MySQL Session rotation、grace 与 reuse 四分支 | Wave 2 切流 |
| `auth.refresh.enabled` 默认 `false` | 新协议不参与运行 | 设计如此 |
| `RefreshTokenProperties` 的 19 个键对门禁不可见 | Contract 参数无机器守护 | 见 `auth-token-model.md` §9 |
| 通用 `httpPolicy` 与 refresh 重试是两套逻辑 | 未来若有人给 refresh 加 `_retryIdempotent` 会双重重试 | 已用 §9.3 说明；建议加门禁 |

---

## 11. 怎么验证

```
# 后端：四分支 + 两个窗口 + 容量上界（全部在 RefreshTokenServiceTest）
mvn -q -Dtest=RefreshTokenServiceTest test

# 后端：grace 参数与前端 timeout 的对齐（同上一个类）
#   graceCoversFrontendTimeout

# 前端：失败分类（unrecoverable vs transient）
node --test src/api/refreshFailurePolicy.test.js

# 前端：跨 Tab 协调 + 只重试一次 + 并发 401 只刷新一次
npx vitest run src/api/http.test.jsx

# 前端：广播通道与互斥量本身
node --test src/api/authChannel.test.js
```

### 11.1 三个"无功能症状"的陷阱（已用注入验证）

```
503 被算进不可恢复        → 会静默把用户踢下线
登出广播不防 ping-pong    → 两个 Tab 互相弹登出事件成死循环
本 Tab 的锁挪到 await 后  → 同一 tick 的两个 401 都会去刷新
```
