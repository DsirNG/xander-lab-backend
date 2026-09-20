# Token 模型（V9 §22 / §23 / §24 / §25 / §29 / §30）

本文固定 Token 的目标模型：Access 用什么、Refresh 用什么、权威源在哪、
以及 `sid` 到底承担什么职责。它是 `refresh-protocol.md` 的前置。

对应 V9 章节：§22（现状）、§23（目标模型）、§24（Access Token）、
§25（`sid` 的消费方与行为语义）、§29（Refresh Token 格式）、§30（MySQL SOT）。

---

## 1. 一句话结论

**短 Access JWT + MySQL Refresh Session + 轮换 + 幂等 Grace + 有界 Reuse Detection +
管理员封禁本地 Deny Cache。** 普通 API 请求不再依赖 Redis 白名单。

```
Access Token   JWT，TTL 30 分钟，无状态校验（签名 / exp / iss / aud / type / 本地 deny cache）
Refresh Token  Opaque 随机串 rt_<sid>.<tokenId>.<secret>，权威源 = MySQL auth_refresh_session
Redis          从"鉴权必经之路"退化为可选的加速/协调设施
```

---

## 2. 现状（改动前的真实行为）

以下都是当前代码的实际行为，不是设想。

### 2.1 Access Token

| 项 | 现状 | 出处 |
|---|---|---|
| 格式 | JWT | `JwtUtil.buildToken` |
| TTL | **7 天**（`604800000` ms） | `application.yml` → `jwt.access-token-expire` |
| Claims | 只有 `role` + `type=access`，**没有 `jti`，没有 `sid`** | `AuthService.generateTokenResponse`（第 641 行） |
| 校验 | 签名 + `login:token:<jwt>` 必须在 Redis 里存在 | 鉴权链路 |

### 2.2 Refresh Token

| 项 | 现状 | 出处 |
|---|---|---|
| 格式 | **JWT**（不是 opaque） | `JwtUtil.generateRefreshToken` |
| TTL | 30 天（`2592000000` ms） | `application.yml` → `jwt.refresh-token-expire` |
| 校验 | **只校验签名 + type + 用户状态**，不查 Redis、不查任何 Session | `AuthService.refresh`（第 557 行） |
| 撤销 | **做不到** | 同上 |

因此当前 logout：

```
不能阻止 refresh token 在 30 天内恢复会话
```

### 2.3 必须显式治理的 Redis 现状缺陷（§56）

```
login:token:<accessToken>       → userId       有 TTL（= accessTokenExpire）
login:user_tokens:<userId>      → Set<token>   ⚠️ 无 TTL，成员是完整 Access JWT
```

`login:user_tokens` 是无 TTL 的 Set，且成员是**完整 JWT**。它有两个后果：

1. 封禁时"遍历该 Set 逐个 `delete`"的成本随时间**单调恶化** —— 集合越大越慢，
   这正是 §26 要求把封禁改成按索引更新 + 事务的根本原因。
2. 集合本身长期驻留，包含已过期的 token 串。

退役计划见 `legacy-redis-retirement.md`。

---

## 3. 目标模型

> **短 Access JWT + MySQL Refresh Session + Refresh Rotation + 幂等 Grace +
> 有界 Reuse Detection + 管理员封禁本地 Deny Cache**

五个目标，每个都有明确的兑现机制：

| 目标 | 兑现机制 | 详见 |
|---|---|---|
| 普通 API 请求不依赖 Redis | Access 改为无状态校验 + 本地 deny cache | §4 |
| Refresh Session 有持久化权威源 | MySQL `auth_refresh_session` | §7 |
| 管理员封禁秒级生效（新请求 + 已建立长连接） | 事务 + outbox + 本地 deny cache + 主动断连 | `stream-security.md` |
| 多标签页并发不误判 theft | `SELECT ... FOR UPDATE` + 幂等 grace + 跨 Tab 协调 | `refresh-protocol.md` |
| Replay Detection 容量有上界 | 时间上界 24h + 每 sid 条数上界 48 | `refresh-protocol.md` |
| 随机垃圾 token 不造成用户 DoS | 分支 4 不 revoke family | `refresh-protocol.md` §4 |

---

## 4. Access Token

### 4.1 TTL

```
初始推荐：30 分钟
安全评审允许区间：15 ~ 30 分钟
```

配置键：`auth.refresh.access-ttl-seconds`，默认 `1800`。

> ⚠️ **选 15 分钟会使 history 容量上界翻倍**（480,000 → 960,000 行），
> 必须同步复核 §42 清理吞吐。见 `refresh-protocol.md` §5.4。

### 4.2 Claims

```json
{
  "sub":  "accountId",
  "type": "access",
  "role": "USER",
  "jti":  "...",
  "sid":  "...",
  "iat":  0,
  "exp":  0
}
```

### 4.3 校验方与校验内容

Gateway / 宿主服务校验：

```
签名
exp
issuer
audience
type
本地 security cache（account deny cache）
```

**不查 Refresh Session。** 这是"普通 API 不依赖 Redis"的落点。

---

## 5. `sid` 的行为语义与消费方（§25）

`sid` 保留，并且必须明确它**不是**什么。

### 5.1 Gateway 不通过 `sid` 查 MySQL Session

Gateway 会把下面四项写进可信内部身份 Context / 日志：

```
sid
accountId
role
jti
```

所以 `sid` 的定位是：

```
会话关联标识
```

**不是**"校验数据库存在性的 claim"。

### 5.2 消费方

| 消费方 | 用途 |
|---|---|
| 安全审计 | Access 请求关联到登录会话 |
| 日志排查 | `trace` / `account` / `sid` 联合定位 |
| 设备 / 会话管理 UI | 展示与管理登录设备 |
| Refresh Session Family 关联 | 轮换链归属 |
| 未来会话级 deny cache（如需要） | 见 §5.3 |

### 5.3 与 §48 的关系（重要）

`LocalSecurityStateCache` 按 **`accountId`** 建索引，所以**当前没有会话级 deny 的消费者**。

```
现阶段：sid = 审计与关联标识
未来若要做会话级撤销：再引入会话级缓存
        届时 sid 的语义变更必须走 §11 的 Contract 变更流程（它已被 §8 冻结）
```

---

## 6. Refresh Token 格式（§29）

### 6.1 使用 Opaque Random Token

```
rt_<sid>.<tokenId>.<secret>
```

| 组成 | 长度 | 是否秘密 |
|---|---|---|
| `sid` | 128 位随机（Base64URL，22 字符） | ❌ **不是秘密**（明文前缀） |
| `tokenId` | 128 位随机 | ❌ 不是秘密（要参与查表） |
| `secret` | **256 位**随机（Base64URL，43 字符） | ✅ 唯一的秘密 |

数据库长期只保存：

```
token_hash = SHA-256(tokenId + ":" + secret)
```

不长期存明文 Refresh Token。

### 6.2 ⚠️ `sid` 不是秘密 —— 这条事实决定了判定表

`sid` 位于**明文前缀**。任何拿到过一次 token 的地方（日志、Referer、客户端存储）
都会泄漏 `sid`。

> 这条事实直接决定了 §39 的判定表**必须**包含"垃圾 token 不撤销会话族"的分支。
> 否则攻击者构造 `rt_<已知sid>.<随机>.<随机>` 反复提交即可让用户被反复踢下线。
>
> 详见 `refresh-protocol.md` §4 分支 4。

### 6.3 为什么哈希要带上 `tokenId`

`token_hash = SHA-256(tokenId + ":" + secret)`，而不是只哈希 `secret`。

`tokenId` 本身不是秘密（它要参与查表），加上它做**域分隔**可以避免
"同一 secret 出现在不同 tokenId 下"时被误判为同一 token。
256 位随机 secret 让碰撞在实际中不可能发生，域分隔是防御性写法。

---

## 7. Refresh Session Source of Truth：MySQL（§30）

### 7.1 拍板结论

```
MySQL
```

### 7.2 理由只采用这五条

```
事务
持久性
按用户批量撤销
审计
现有数据库能力可复用
```

### 7.3 ⚠️ 明确不用的一条论证

**不再用"Redis 是单实例，而 MySQL 不是"作为论证。**

当前 MySQL 同样是单实例。因此必须明确：

> 选择 MySQL 是**数据模型与持久性语义判断**，不代表当前 MySQL 已经高可用。

这条区分很重要：它把"为什么选 MySQL"（语义）和"MySQL 够不够可靠"（运维）
拆成了两个独立问题。后者由 §31 的备份 / 恢复演练回答，见 `flyway-strategy.md`。

### 7.4 表结构

两张表，字段与**完整索引**（不得省略）：

`auth_refresh_session`

```
PRIMARY KEY(sid)
UNIQUE KEY uk_refresh_current_token(current_token_id)
KEY idx_refresh_account_status(account_id, status)
KEY idx_refresh_family(family_id)
KEY idx_refresh_expires(expires_at)
```

`auth_refresh_token_history`

```
PRIMARY KEY(token_id)
KEY idx_refresh_history_sid_generation(sid, generation)
KEY idx_refresh_history_retained_until(retained_until)
KEY idx_refresh_history_ciphertext_expires(successor_ciphertext_expires_at)
```

DDL 位置：`migrations/versioned/V2026091501__auth_refresh_session.sql`、
`migrations/versioned/V2026091502__auth_refresh_token_history.sql`。

> `idx_refresh_history_retained_until` 与 `idx_refresh_history_ciphertext_expires`
> 不是"顺手加的索引"，而是 §42 / §43 两个清理任务各自的驱动索引：
> 少了任一个，对应清理都会退化成全表扫描。见 `refresh-history-retention.md`。

---

## 8. `jti` / `sid` 行为语义（PASS 项 23）

| Claim | 存在意义 | 谁写 | 谁读 |
|---|---|---|---|
| `jti` | Access Token 的唯一标识，便于日志与未来黑名单 | 签发时生成 | 日志 / 审计 |
| `sid` | 会话关联标识（见 §5） | 签发时取自 Refresh Session | 日志 / 审计 / 会话管理 UI |

**两者都不是校验数据库存在性的依据**：

```
Gateway 不因为 jti / sid 在库里查不到就拒绝请求
Access 的合法性由 签名 + exp + deny cache 决定
```

`sub`（accountId）是唯一被真正用于授权的身份字段。

---

## 9. 开关与切流

新模型由 `auth.refresh.enabled` 控制，**Wave 0A 默认 `false`**：

```
enabled = false  → 旧的无状态 refresh JWT 行为完全不变（实现与单测先落地）
enabled = true   → 新协议生效（切流是 Wave 2 的事，§94）
```

当前接入状态：`AuthService` 已把登录签发、`/api/auth/refresh` 轮换、logout 的
当前 Session 撤销，以及 Core Host 的“JWT + 本地 deny cache”Access 校验接到这个开关；
默认值仍为 `false`。开启前必须完成客户端错误分类、
多标签页协调、Access TTL 与数据库恢复演练，不能把“代码已接入”当成“生产已切流”。

配置类：`RefreshTokenProperties`（`@ConfigurationProperties(prefix = "auth.refresh")`）。

> ⚠️ `RefreshTokenProperties` 的 19 个键目前**只以 Java 默认值存在**，
> `application.yml` 里一个都没有，因此对 `config-ownership-drift` 等门禁**不可见**，
> 尽管该类自己的 Javadoc 写着这些值"全部属于 Contract"（§36）。
> 补法（给 `scan_config()` 加 `@ConfigurationProperties` 扫描）见 `config-ownership.yaml` 的已知缺口。

### 9.1 Grace 参数属于 Contract（§36）

以下任何一项变化都必须同步复核：

```
VITE_REQUEST_TIMEOUT
refresh retry count
refresh backoff
server grace
```

并写入 `docs/contract-changes.yaml`。

**禁止单独改前端 timeout 而不复核 server grace。**

---

## 10. 已知缺口

| 缺口 | 影响 | 归属 |
|---|---|---|
| `RefreshTokenService.rotate()` 已接上 HTTP 端点，但开关默认关闭 | 生产默认仍走旧实现；开启新协议后由 MySQL Session、轮换、grace 与 reuse 分类接管 | Wave 2 切流 |
| `login:user_tokens` 无 TTL 且存完整 JWT | 封禁成本随时间恶化；需退役 | `legacy-redis-retirement.md` |
| 旧模式 Access Token 尚未携带新协议 `sid` | 新协议开启时 Access 会携带 `sid`，JWT 统一生成 `jti`；旧模式保持兼容 | Wave 2 |
| MySQL 备份 / RPO / RTO 未演练 | §31 的可靠性承诺无观测量兑现 | `flyway-strategy.md` |

---

## 11. 怎么验证

```
# 四分支判定（含"垃圾 token 不 revoke family"防 DoS 分支）+ grace 幂等 + retention 边界
RefreshTokenServiceTest
  ├─ rotatesCurrentToken                            分支 1
  ├─ replaysIdempotentlyWithinGrace                 分支 2
  ├─ revokesFamilyOnReuseAfterGrace                 分支 3
  ├─ rejectsGarbageTokenWithoutRevokingFamily       分支 4【防 DoS】
  ├─ rejectsMalformedToken / rejectsWrongSecretOnCurrentToken
  ├─ rejectsTokenFromAnotherSession
  └─ doesNotAlarmBeyondRetention                    §39.2 时间边界

# 两个窗口的写入规则与容量上界
RefreshTokenServiceTest
  ├─ retainedUntilIsWrittenExplicitly               §40.2 写 history 时显式写 now()+24h
  ├─ ciphertextWindowIsNotShorterThanGrace          §38 密文窗口不得短于 grace
  ├─ graceCoversFrontendTimeout                     §35 grace > 15s timeout + 重试 + 余量
  ├─ historyCapacityBound                           §40.4 30min Access → 480,000 行
  └─ prunesByCount                                  §40.3 条数上界独立生效

# 开关默认关闭（Wave 0A 不得改变现有行为）
RefreshTokenServiceTest#disabledByDefault

# 密文"过了逻辑窗口必须拒绝"（读路径的时间判断）
AuthRefreshCiphertextCleanupTest   ← 覆盖 AuthRefreshTokenHistory.isCiphertextReadable

# 旧路径仍然正确（切流前生产走的就是它）
AuthServiceTest / JwtUtilTest / AuthControllerTest
```

`RefreshTokenFormat`（格式 / 哈希 / 解析）与 `SuccessorTokenCipher`（加解密）
没有独立的测试类，它们的覆盖在 `RefreshTokenServiceTest` 内部完成。
