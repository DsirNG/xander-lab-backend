# Legacy Redis 退场（V9 §56 / §57 / §58 / §59 / §60）

本文登记两个旧 Redis 键族的**真实语义**、它们为什么必须显式退场、
退场分几步、以及"退干净了"用什么判据证明。

对应 V9 章节：§2.3（能力守恒）、§12（能力不丢）、§24（Access Token 校验）、
§26（封禁事务）、§48（deny cache）、§56（缺陷）、§57（迁移）、§58（计数采集）、
§59（指标）、§60（验收）。

---

## 1. 一句话结论

**Wave 2 切换前，鉴权仍然以 Redis 为准 —— 所以这两个键族现在绝不能删；
但它们有一个会随时间恶化的缺陷，必须在切换时显式清掉。**

```
login:token:<jwt>          → userId        有 TTL（= Access JWT 的 7 天）
login:user_tokens:<userId> → Set<jwt>      无 TTL   ← 缺陷在这
```

⚠️ §57 特别强调：退场时**必须显式删除这两个键族**，
不能笼统写"旧 Redis key" —— 因为同一个 Redis 里还有 `login:code:`（邮箱验证码）
等其它 `login:` 前缀的键，清错会误删。

---

## 2. 两个键族的真实语义

唯一来源是 `common/Constants.java`：

```java
String REDIS_TOKEN_PREFIX       = "login:token:";        // L18
String REDIS_USER_TOKENS_PREFIX = "login:user_tokens:";  // L21
```

### 2.1 写入侧（`AuthService.generateTokenResponse`，L639）

```java
// L646-651：有 TTL
redisTemplate.opsForValue().set(
        Constants.REDIS_TOKEN_PREFIX + accessToken,
        userIdStr,
        jwtUtil.getAccessTokenExpire(),      // ← 604800000ms = 7 天
        TimeUnit.MILLISECONDS
);
// L653：无 TTL ← 缺陷
redisTemplate.opsForSet().add(Constants.REDIS_USER_TOKENS_PREFIX + userIdStr, accessToken);
```

**同一个方法里，两个键一个设了过期、一个没设。** 这不是笔误，
而是"这个 Set 是用户级索引，不该随单个 token 过期"的直觉 ——
但那个直觉漏掉了一件事：**没有任何机制把已过期 token 从 Set 里摘掉**。

### 2.2 校验侧（`AuthService.validateAccessToken`，L802）

```java
public boolean validateAccessToken(String token) {
    if (!jwtUtil.isValid(token) || jwtUtil.isRefreshToken(token)) {
        return false;
    }
    return Boolean.TRUE.equals(redisTemplate.hasKey(Constants.REDIS_TOKEN_PREFIX + token));
}
```

**JWT 签名有效 ≠ 登录有效**：还必须 `login:token:<jwt>` 这个键存在。
这是"能强制下线"能力的来源 —— 删键即失效，不用等 JWT 过期。

### 2.3 撤销侧（`UserAdminService.revokeSessions`，L160）

```java
private int revokeSessions(Long userId) {
    String setKey = Constants.REDIS_USER_TOKENS_PREFIX + userId;
    Set<String> tokens = redisTemplate.opsForSet().members(setKey);   // L162 ← O(N) 全量取
    if (tokens == null || tokens.isEmpty()) {
        redisTemplate.delete(setKey);
        return 0;
    }
    for (String token : tokens) {
        try {
            redisTemplate.delete(Constants.REDIS_TOKEN_PREFIX + token);  // L169 逐个删
        } catch (Exception e) {
            log.warn("[Admin] 撤销 token 失败 userId={}: {}", userId, e.getMessage());
        }
    }
    redisTemplate.delete(setKey);   // L174
    return tokens.size();
}
```

**封禁要遍历整个 Set，逐个 `DELETE`。**

### 2.4 登出侧（`AuthService.logout`，L586）

```java
redisTemplate.opsForSet().remove(Constants.REDIS_USER_TOKENS_PREFIX + userId, accessToken);  // L593
redisTemplate.delete(Constants.REDIS_TOKEN_PREFIX + accessToken);                            // L601
```

登出**会**从 Set 里摘掉自己。所以 Set 不是只增不减 —— 但它只在**显式登出**时减少。

---

## 3. 缺陷机制：封禁延迟为什么单调恶化（§56）

关键链条是"谁会把成员加进 Set"：

| 事件 | 是否新增 Set 成员 | 证据 |
|---|---|---|
| 登录 | 是 | `generateTokenResponse` L653 |
| **刷新 Access Token** | **是** | `refresh`（L557）→ `generateTokenResponse`（L568） |
| 登出 | **否（会移除一个）** | `logout` L593 |
| Access Token 自然过期（7 天） | **否（不移除）** | 无任何清理逻辑 |

于是：

```
Access JWT 有效期 7 天，Refresh JWT 有效期 30 天
→ 一个连续活跃的会话，每 7 天刷新一次 → 每次刷新都往 Set 里加一个新成员
→ 30 天内一个会话最多留下 4~5 个成员，其中大部分对应的 login:token: 早已过期
→ 用户不显式登出，这些成员就永远留在 Set 里
```

**用户越活跃、登录次数越多，Set 越大；而 Set 越大，`revokeSessions` 越慢。**
这就是 §56 的「封禁延迟随时间单调恶化」—— 它不是"Redis 慢"，
而是**封禁的耗时随用户历史登录次数线性增长**。

⚠️ 这个缺陷有个"不会失败"的特征：功能完全正常。
封禁仍然生效、用户仍然被踢下线、测试全绿。只有在大账号上
（或压测时）才会看到 `revoke_duration` 变大 —— 所以它由指标而不是由测试发现（见 §7）。

### 与 §48 deny cache 的关系

新模型下，封禁的新请求由每实例的 `LocalSecurityStateCache` 在 O(1) 内判定，
**不再依赖遍历 Redis Set**。所以这个缺陷的修法不是"给 Set 加 TTL"，
而是**让封禁不再走这条路** —— 也就是 §57 的退场。

---

## 4. 为什么现在不能删（§2.3 能力守恒）

`UserAdminService` 的类注释把这条写得很明确（L35–48）：

> 所以封禁必须**继续**走 `revokeSessions` —— 换成新 SOT 就删掉它，
> 会让封禁在 Wave 2 切换之前完全失效。

理由是**鉴权路径还没换**：`validateAccessToken`（L802）仍然要求
`login:token:<jwt>` 存在。只要这条还在，删掉 `revokeSessions` 就等于
"封禁后用户还能用 Access Token 直到 JWT 自然过期（7 天）"——
这正是 §28.4 明令禁止的退化：

```text
禁止退化为：
  等待 Access Token 15～30 分钟自然过期   ← 现在是 7 天，更糟
  只保护新请求、不管已建立流
```

### 两条路径并存的现状

Wave 0A 起，封禁同时走两条路（`setUserStatus` L118–137）：

```text
① sys_user.status 条件更新（L120-123）
② 遗留 Redis 撤销（L129）        ← 本文的主题，Wave 2 退场
③ 新安全平面：SOT + 事务性事件（L136-137）
```

**两条都幂等，重复执行不会互相干扰。** 这是能安全共存的原因 ——
不需要在切换时做"先关哪条"的排序。

⚠️ 但两者的事务语义不同（见
[`transaction-boundary.md`](./transaction-boundary.md) §3.3）：
② **不在事务里**（Redis 没有回滚），③ 在事务里。
这个不对称是**故意保留**的：② 只可能"多撤销"，不可能"少撤销"，
最坏结果是用户被迫重新登录一次，而不会出现"封禁没生效"。

---

## 5. 退场时间线（§57）

```text
T0  旧结构存在
    └─ 现状（Wave 0A）

T1  新登录只生成新 Token 模型
    不再向 login:user_tokens 新增成员          ← 增长在此停止
    Gateway 同时识别 legacy / new Access
    旧 refresh 成功一次后升级为新 Session

T2  主动 Session Upgrade
    压缩 30 天自然迁移窗口                      ← 不等自然过期，主动推

T3  必须显式删除：
    login:token:*
    login:user_tokens:*
```

**T1 是拐点**：从 T1 起 Set 不再增长，所以 §3 的"单调恶化"停止。
但**存量成员还在**，`revokeSessions` 仍然慢 —— 所以要靠 T2/T3 清掉。

### ⚠️ T1 之后"Redis independence"只对新用户成立

§57 原文：

```text
Legacy Access 用户普通请求仍依赖旧 Redis login:token:
所以 Redis independence 只对新 Token 用户立即成立
```

也就是说：T1~T3 之间，**Redis 挂了旧用户仍然登不上**。
任何"我们已经不依赖 Redis 了"的说法在这个窗口内都是错的 ——
它只在 T3 完成、且 §8 的三个判据同时满足后才成立。

---

## 6. 计数采集与退场判据（§58）

### 6.1 不要在业务高峰全量 SCAN

```text
identity:legacy_access_issued
identity:legacy_access_active_estimate
```

- `legacy_access_issued`：从 T1 起**不再增加** —— 它是"是否还有人在签发旧 token"的开关指标；
- `legacy_access_active_estimate`：低频 SCAN reconciliation **+** 签发/主动撤销事件计数。

推荐 SCAN 频率：**每 30～60 分钟**。§58 明确：只做迁移期指标，不做实时指标。

⚠️ "低频 SCAN + 事件计数"的组合是必要的：单靠 SCAN 有窗口误差，
单靠事件计数无法发现"T1 之前签发的存量"。两者相加才覆盖完整集合。

### 6.2 判定 `legacy token count = 0` 的三个条件

```text
1. 低频 SCAN 连续多个周期 = 0
2. legacy refresh endpoint 请求 = 0
3. 观察一个发布周期
```

**三者必须共同满足**，缺一不可：

- 只有 ①：可能是 SCAN 恰好没扫到，或窗口内没有活跃用户；
- 只有 ①+②：可能是"用户手上有 legacy Access，只是还没到刷新时间"——
  Access 有效期 7 天，所以至少要观察超过 7 天才排除；
- ③ 的作用是覆盖"一个完整发布周期"，排除"这个版本里还有代码在签发旧 token"。

---

## 7. 指标（§59）

```text
legacy_login_token_count
legacy_login_user_tokens_key_count
legacy_login_user_tokens_total_members
legacy_login_user_tokens_max_cardinality
legacy_revoke_duration
```

前四个是**存量**，最后一个是**性能**：

- `legacy_login_user_tokens_max_cardinality` 是 §3 缺陷的直接观测量 ——
  单个用户 Set 的最大成员数。它单调上升就说明退场没生效；
- `legacy_revoke_duration` 是封禁 SLO 的输入之一（§53 的
  `admin_ban_new_request_block_latency` 包含这段遍历）。

**禁止项（§59）：**

```text
不允许 login:user_tokens 永久遗留
不允许无 TTL JWT Set 成为长期 Session 模型
```

⚠️ 第二条是**设计禁令**，不只是迁移任务：它禁止"把无 TTL 的 JWT Set
当作正式会话模型"。新模型（§23/§24）用的是 MySQL Refresh Session + 短 Access JWT，
正是为了不重犯这条。

---

## 8. 验收（§60）

### 8.1 Wave 0A 可测 / 设计验证

```text
新 Access Validator 在测试模式下 Redis Down 仍能通过签名校验
Refresh Rotation / Grace / Reuse / Garbage-token 单元与集成测试
管理员封禁 deny cache 设计与 SLO 明确
Legacy migration 设计完成
```

§60 明确：**Wave 0A 不要求声称"全体线上用户已经不依赖 Redis"**。
这个限制很重要 —— 否则很容易把"设计完成"误报成"能力已达成"。

### 8.2 Wave 2 切流后验收

只有下面两条**同时成立**后，才允许声明
「普通 API 已完全脱离旧 Redis 白名单」：

```text
legacy token count = 0
legacy user_tokens key count = 0
```

注意第二条是 **key count**（键的数量），不是成员数 —— 一个空的
`login:user_tokens:<userId>` Set 在 Redis 里**键仍然存在**（Set 被删空后 Redis 会自动删键，
但 `SREM` 移除最后一个成员时确实会删键；而 T1 之后不再 `SADD`，
所以残留的空键只可能来自异常路径）。判据用 key count 更保守。

---

## 9. 机器门禁：`legacy-redis-reference-check`

`scripts/migration/ci_gates.py` L405 的门禁逻辑：

```python
allowed = {
    "src/main/java/com/xander/lab/common/Constants.java",
    "src/main/java/com/xander/lab/identity/application/AuthService.java",
    "src/main/java/com/xander/lab/identity/application/UserAdminService.java",
}
```

**只允许这三个文件引用旧键族**，其它任何文件出现
`REDIS_TOKEN_PREFIX` / `REDIS_USER_TOKENS_PREFIX` 即失败。

门禁注释解释了为什么是"允许清单"而不是"计数增长"：

> Wave 0A cannot remove them yet (they are the current session model),
> so the gate counts the call sites and fails only when the count grows.

⚠️ 这个门禁守的是**扩散**，不是**退场**。
它防止"新代码又去用旧键族"，但不会告诉你"旧键族什么时候能删" ——
后者要靠 §6.2 的三个判据（人工 + 指标）。

⚠️ 退场时（T3）必须**同时**更新这个 `allowed` 集合：删掉
`AuthService` / `UserAdminService` 两条之后，门禁才能证明"生产代码已无引用"。
若只删代码不改门禁，门禁会因为允许清单里的文件不再匹配而……仍然通过
（它是"允许清单"语义，不是"必须存在"语义）。所以 T3 的正确动作是
**把 `allowed` 收缩到只剩 `Constants.java`，然后再删常量**。

---

## 10. 已知缺口

| 缺口 | 影响 | 状态 |
|---|---|---|
| 五个指标全部未接入 | §3 的缺陷目前**无法被观测** —— 只能靠代码审查发现。`legacy_revoke_duration` 是封禁 SLO 的输入 | Wave 2 |
| 计数器 `identity:legacy_access_*` 未实现 | §6.1 的两个计数器不存在，退场判据无法量化 | T1 之前 |
| T3 的清理脚本未写 | §57 要求显式删除两个键族，脚本尚未编写（Wave 0A 不删是对的） | T3 |
| `allowed` 集合未随 T1/T3 收缩 | 见 §9 末尾 | T1 / T3 |
| 无门禁校验 Set 是否有 TTL | `login:user_tokens` 无 TTL 是**当前唯一写入点**造成的。若将来有人在别处 `SADD` 一个带 TTL 的变体，门禁不会发现 | 已知（允许清单已限制写入点） |
| `contract-changes.yaml` 的登记 | 遗留退场已登记在 `legacy-redis-user-tokens` 条目下，但 T1/T2/T3 的具体时点未登记 | T1 之前 |

---

## 11. 怎么验证

```bash
# 门禁：旧键族引用是否仍被限制在 3 个文件内
python scripts/migration/ci_gates.py

# 人工：确认写入点只有一处
grep -rn "REDIS_USER_TOKENS_PREFIX" src/main/java/
# 期望：Constants.java（定义）/ AuthService.java（写 + 登出移除）/ UserAdminService.java（遍历撤销）
```

⚠️ 本文档描述的 T1/T2/T3 **尚未开始**（Wave 0A 不改部署，也不改鉴权路径）。
当前唯一已落地的是 §9 的门禁。

**验证 §3 的缺陷是否仍然存在**（应当在 T1 之前一直存在）：

```text
1. 同一账号登录 N 次（不登出）
2. SCARD login:user_tokens:<userId>   → 期望 = N
3. 等待 Access JWT 过期（7 天）后重查 → 期望仍然 = N（成员不会被自动摘除）
4. TTL login:user_tokens:<userId>     → 期望 = -1（无过期）
```

第 3 步是缺陷的直接证据：**过期的 token 仍占着 Set 的位置，
而封禁要逐个遍历它们。**
