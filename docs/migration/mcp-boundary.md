# MCP 边界：入站与出站（V9 §76 / §77）

本文把 MCP 拆成**两个方向**分别登记 Owner、鉴权与迁移目标，
并登记 `agent_mcp_server` 这张表的**三阶段 Owner 切换**。

对应 V9 章节：§2.3（能力守恒）、§7（MCP 协议端点迁移）、§12（能力不丢）、
§21（共享内核边界）、§76（MCP 边界）、§77（Owner 切换）、
§89 / §90 PASS 42 / 43。

> PASS 42 用的词是「MCP 入站 / 出站边界**恢复**」——
> "恢复"而不是"建立"，说明这条边界在早前版本里丢过一次。
> 本文的首要目的就是把它写死到可被门禁检查的程度。

---

## 1. 一句话结论

**入站和出站是两个独立方向、两个 Owner、两套鉴权体系。**
把它们混成"一个 MCP 模块"是最容易犯的错。

```
出站：DinQor Agent → MCP Client → Remote MCP Server     归 dinqor-agent
入站：Claude / Cursor / Other Host → DinQor MCP Server   归 dinqor-platform-integration
```

| | 出站 MCP | 入站 MCP |
|---|---|---|
| 谁是客户端 | 我们（`dinqor-agent`） | 第三方宿主（Claude / Cursor） |
| 谁鉴权谁 | 我们带凭据去连远端 | 我们验对方的 OAuth token |
| 凭据形态 | `McpHeaderCipher` 加密的请求头 | OAuth 2.0 + PKCE + Scope |
| 当前包 | `integration/mcp/client` | `integration/mcp/{blog,csdn,dual,juejin}` + `integration/mcp/security` |
| 目标归属 | `dinqor-agent`（迁入 `agent/tool/mcp`） | `dinqor-platform-integration` |
| 表 | `agent_mcp_server` | `mcp_oauth_client` / `mcp_oauth_grant` / `mcp_oauth_token` |

⚠️ 注意出站方向的**包名与目标归属不一致**：它现在住在 `integration` 包里，
但归 `dinqor-agent`。这是 §76 明确要求的迁入动作
（`integration/mcp/client` → `agent/tool/mcp`），不是命名笔误。

---

## 2. 出站 MCP

```text
DinQor Agent
→ MCP Client
→ Remote MCP Server
```

**Owner：`dinqor-agent`。当前：`integration/mcp/client`。迁入：`agent/tool/mcp`。**

### 2.1 Transport 优先级（§76）

```text
P0  Streamable HTTP     ← 首选
P1  Legacy SSE
P2  STDIO（仅可信 Sandbox / Worker）
```

⚠️ `P2 STDIO` 被限制在"可信 Sandbox / Worker"是有原因的：STDIO transport
会在本机**启动一个子进程**。允许任意配置启进程等于把配置写权限变成命令执行权限。

### 2.2 安全边界：端点校验（`McpEndpointGuard`）

出站方向的核心风险是 **SSRF**：端点地址由配置者填写，而服务器会带着它主动发起
HTTP 请求。`McpEndpointGuard` 的类注释把这条写得很直接：

> 不做校验，就等于给任何能改这份配置的人一个探测内网的口子（SSRF）。

规则（`validate`，L31）：

```text
1. 必须是 https            ← 明文 HTTP 会让远端凭据和工具参数在链路上裸奔
2. 必须有主机名
3. 不得内嵌凭据（userinfo） ← 凭据请填在请求头里
4. 不得带 fragment
5. 域名解析出的【每一个】地址都必须是公网地址
```

第 5 条是"每一个"而不是"任意一个" —— 这是防 DNS 多记录绕过：
只检查第一条记录的话，攻击者可以让第一条返回公网、第二条返回内网。

⚠️ **已知残余风险（代码注释里显式记录）**：这是"先解析、后校验"，
而真正建连时 `java.net.http.HttpClient` 会**再解析一次**。
理论上存在 DNS rebinding —— 第一次解析返回公网骗过校验，建连时解析返回内网。
彻底堵死需要把校验出的 IP 钉进连接过程（自建 HttpClient 连接层）。
当前接受这个残余风险，理由是**改这份配置需要管理员或账号本人权限，不是匿名可达的攻击面**。

这个"接受残余风险 + 写清理由 + 写清彻底修法的代价"的形态值得保留 ——
它让下一个人能判断"现在要不要修"，而不是看到一个没有上下文的 TODO。

### 2.3 凭据存储：`McpHeaderCipher`

远端 MCP 的请求头凭据（如 `Authorization`）加密后存进 `agent_mcp_server`。
与 `WechatCredentialCipher`、`CsdnPlatformCredential` 同一模式：**每用户加密凭据**。

### 2.4 ⚠️ 路径前缀与真实 Owner 不一致（Wave 0A 已修）

`AgentMcpServerController` 在 `integration/mcp/client/api`，
但它的路由前缀是 `/api/agent/mcp-servers`：

| 路由 | handler 所在包 |
|---|---|
| `/api/agent/mcp-servers`（GET/POST） | `integration/mcp/client/api` |
| `/api/agent/mcp-servers/{id}`（PUT/DELETE） | `integration/mcp/client/api` |
| `/api/agent/mcp-servers/{id}/probe`（POST） | `integration/mcp/client/api` |

`docs/auth-policy.yaml` 已显式登记这条错配：

> URL 前缀是 `/api/agent`，实际属于 integration（AgentMcpServerController 在
> integration/mcp/client/api）。**Wave 0A 修复：原为 fail-open 公开读。**
> 路由必须按真实 Owner 切流。

⚠️ 两点后果：

1. **Wave 2 切流时不能按前缀切**。若 Gateway 按 `/api/agent/**` 转发到
   `dinqor-agent`，这五个端点会被打到错误的服务 —— 而它们归 integration。
   路由必须按 handler 的真实 Owner 切。
2. **"原为 fail-open 公开读"** 是一个已经修掉的安全缺陷。
   它同时说明：`auth-policy.yaml` 的覆盖率门禁
   （`auth-policy-coverage`）不是形式主义 —— 没有它，这类
   "路由存在但没被任何规则匹配"的读接口会默认放行。

### 2.5 事务缺陷

`AgentMcpServerService.probe` 在 `@Transactional` 方法内做外部 MCP HTTP 往返
（`connectionFactory.listTools`，L128）。这不是拆服务后才有的问题 ——
**当前就是错的**。详见 [`transaction-boundary.md`](./transaction-boundary.md) §4.3。

---

## 3. 入站 MCP

```text
Claude / Cursor / Other Host
→ DinQor MCP Server
```

**Owner：`dinqor-platform-integration`。**

### 3.1 五个端点

`docs/auth-policy.yaml` 的 `mcp_inbound:` 段（L619）是权威登记：

| id | 路径 | transport | auth | owner |
|---|---|---|---|---|
| `mcp-blog-server` | `/api/mcp` | streamable-http | oauth2-pkce | integration |
| `mcp-csdn-server` | `/api/mcp/csdn` | streamable-http | oauth2-pkce | integration |
| `mcp-juejin-server` | `/api/mcp/juejin` | streamable-http | oauth2-pkce | integration |
| `mcp-dual-publish-server` | `/api/mcp/dual` | streamable-http | oauth2-pkce | integration |
| `mcp-v2-endpoint` | `/mcp` | streamable-http | oauth2-pkce | integration |

`/mcp` 是 §7 要求的**协议端点迁移目标**（MCP 规范用根路径 `/mcp`），
当前已与博客 MCP 工具集接通；兼容期继续保留 `/api/mcp`，两者共享同一套 OAuth scope 语义。

四个业务模块的职责（`docs/MCP_MODULES.md`）：

```
integration.mcp.blog   → 本地博客授权与文章 CRUD
integration.mcp.csdn   → CSDN 扫码授权 + 已拥有文章的同步
integration.mcp.dual   → 组合式 publish_to_both（先本地创建发布，再让 CSDN 同步同一篇）
integration.mcp.juejin → 掘金账号状态与已拥有文章发布
```

`dual` 是**薄组合层**：它不自己读写 CSDN 的凭据，而是调用 CSDN 的
application service。`MCP_MODULES.md` 明确："Neither platform module reads
the other's credentials."

### 3.2 独立鉴权体系：不套业务 JWT

入站 MCP 走的是 **OAuth 2.0 + PKCE + Scope + Consent**，
**不复用业务 JWT**。这条由 `auth-policy.yaml` 的排除规则登记：

```yaml
- id: excluded-mcp-transport
  path_contains: /api/mcp
  note: "MCP 入站传输（Streamable HTTP），走 OAuth + PKCE + Scope，不套业务 JWT。
         含 /api/mcp、/api/mcp/csdn、/api/mcp/dual、/api/mcp/juejin。"
```

`/mcp` 另有独立的 `excluded-mcp-root-transport` 精确规则，避免根路径因不含
`/api` 前缀而落入业务 JWT 默认规则。

⚠️ 这是**有意的排除**，不是"漏配"。区分"有意的排除"与"漏配"的唯一办法
就是把它显式写进策略文件 —— 这也是 `auth-policy-token-location` 门禁存在的原因
（它要求每个流端点声明 `token_location`，让"token 从哪来"不能含糊）。

### 3.3 OAuth 端点（`McpOAuthController`）

| 端点 | 作用 |
|---|---|
| `/.well-known/oauth-protected-resource`（GET，L35） | RFC 9728 受保护资源元数据 |
| `/.well-known/oauth-authorization-server`（GET，L50） | RFC 8414 授权服务器元数据 |
| `/api/mcp/oauth/register`（POST，L66） | 动态客户端注册 |
| `/api/mcp/oauth/authorize`（GET，L78） | 授权入口 |
| `/api/mcp/oauth/authorize/requests/{requestId}`（GET，L94） | 查询授权请求状态 |
| `/api/mcp/oauth/authorize/requests/{requestId}/approve`（POST，L102） | 用户同意 |
| `/api/mcp/oauth/authorize/requests/{requestId}/deny`（POST，L114） | 用户拒绝 |
| `/api/mcp/oauth/token`（POST，L122） | 换 token |

两个 `.well-known` 端点必须**匿名可达**（客户端在拿到 token 之前就要发现元数据），
所以它们出现在 `route-inventory.yaml` 里而**不在**业务 JWT 覆盖范围内。

### 3.4 Scope 错误必须结构化

`McpScopeErrors`（L20）把 scope 不足映射成**结构化工具错误**：

```java
.structuredContent(Map.of("ok", false, "error", "INSUFFICIENT_SCOPE", "message", ...))
```

类注释解释了为什么：

> Maps a missing OAuth tool scope to a structured MCP tool error the client can act on,
> instead of ...（而不是一个客户端无法处理的通用失败）

⚠️ 这条对第三方宿主很关键：**宿主需要区分"权限不足"与"服务出错"**。
权限不足时宿主应当提示用户重新授权；服务出错时应当重试。
如果两者都返回同一个错误，宿主只能无脑重试或直接放弃 ——
前者制造无意义的重连，后者让用户看不到"请重新授权"。

---

## 4. `agent_mcp_server` 三阶段 Owner 切换（§77）

这张表**同时被出站 MCP 的两侧读写**，所以它的 Owner 不能一次切换：

```text
Stage A   integration：唯一写     agent：只读
Stage B   agent：唯一写           integration：只读
Stage C   数据校验完成 → REVOKE integration WRITE → agent 成为唯一 Owner
```

已登记在 `docs/table-ownership.yaml`：

```yaml
- table: agent_mcp_server
  logical_owner: agent
  target_deployment_unit: dinqor-agent
  owner_transition: stage-a-integration-write
```

### 4.1 为什么必须分三阶段

因为**迁移期间两侧都要读这张表**：

- 出站 MCP 归 `dinqor-agent`（§76），它需要读自己配置的远端服务器列表；
- 而这张表的写入当前在 `integration` 侧（管理员配置接口在
  `integration/mcp/client/api/AdminMcpServerController`）。

若一步切完 Owner，会出现"agent 已开始写、integration 还在写"的窗口 ——
这正是 §2.2 双 Owner 禁令要排除的状态。

### 4.2 Stage C 的四项校验（§77）

```text
row count
updated_at
business key
checksum
```

四项缺一不可，各自覆盖不同的失败模式：

| 校验 | 覆盖什么 |
|---|---|
| row count | 整体丢失 / 重复插入 |
| updated_at | 切换后是否还有旧侧写入（时间戳会继续前进） |
| business key | 唯一键是否漂移（同一台服务器出现两条） |
| checksum | 内容是否被静默改写（条数与键都对但字段变了） |

⚠️ **`updated_at` 这一项最容易被忽略**：它不验证数据一致性，
而是验证"旧侧确实已经停止写入"。前三项在"两侧都写、写的是同一份数据"时
**仍然全部通过** —— 只有 `updated_at` 能发现那个窗口还没关。

---

## 5. 为什么 PASS 42 用"恢复"这个词

§76 之所以要单独成章，是因为"入站 / 出站边界"在文档迭代中丢过一次。
它丢失的形态很典型：**只写"我们有 MCP"，不写方向与 Owner** ——
于是"出站 MCP 归谁"变成了一个每版都要重新讨论的问题。

本文的防丢失手段是把边界落到**机器可查的文件**里：

| 边界信息 | 落地位置 | 门禁 |
|---|---|---|
| 入站端点 + 鉴权方式 + Owner | `auth-policy.yaml` 的 `mcp_inbound:` | `auth-policy-coverage` |
| 入站端点不套业务 JWT | `auth-policy.yaml` 的 `excluded-mcp-transport` | 同上 |
| 每条路由的真实 handler | `route-inventory.yaml` | `route-inventory-drift` |
| `agent_mcp_server` 的 Owner 切换阶段 | `table-ownership.yaml` 的 `owner_transition` | `table-ownership-drift` |

⚠️ 但**出站 MCP 的 Owner 存在一处未解决的口径冲突**：
`route-inventory.yaml` 把这五个管理接口登记为
`logical_owner: integration` / `deployment_unit: dinqor-platform-integration`，
而 §76 说出站 MCP 客户端归 `dinqor-agent`。

两者可以同时成立（**管理接口留在 integration、客户端运行时搬到 agent**），
但也可以理解为路由登记需要跟着改。**当前没有任何文件把这两种解释区分开** ——
这是 Wave 2 切流前必须拍板的一处，见 §6。

---

## 6. 已知缺口

| 缺口 | 影响 | 状态 |
|---|---|---|
| 出站 MCP 的 Owner 口径冲突 | 见 §5 末尾：`route-inventory.yaml` 说管理接口归 `integration`，§76 说出站客户端归 `dinqor-agent`。两种解释都自洽，但没有任何文件区分它们 → Wave 2 切流时不知道这五个路由该发给谁 | **Wave 2 前必须拍板** |
| `integration/mcp/client` → `agent/tool/mcp` 未迁移 | §76 的迁入动作尚未执行。当前包名与目标归属不一致 | Wave 2 |
| `probe` 在事务内做 HTTP | 见 §2.5，当前就是错的 | 建议 Wave 0B 修 |
| `owner_transition` 无门禁校验阶段合法性 | `table-ownership.yaml` 登记了 `stage-a-integration-write`，但没有门禁校验这个取值合法、也没有门禁校验"Stage C 完成前不得 REVOKE integration WRITE" | Wave 0B |
| STDIO transport 的可信边界未定义 | §76 说"仅可信 Sandbox / Worker"，但"可信"如何判定（配置白名单？部署单元？）未定义。STDIO 会启动子进程，边界不清等于命令执行面 | Wave 2 设计 |
| DNS rebinding 残余风险 | 见 §2.2，已显式接受并记录理由 | 已接受 |

---

## 7. 怎么验证

```bash
# 入站 MCP 端点的覆盖率 + token_location 声明 + 排除规则完整性
python scripts/migration/ci_gates.py

# 人工：确认 mcp_inbound 段的五个端点仍与代码一致
grep -n -A 6 "mcp_inbound:" docs/auth-policy.yaml

# 人工：确认 /api/agent/mcp-servers 的 handler 仍在 integration 包内
grep -n "agent/mcp-servers" -A 5 docs/route-inventory.yaml
```

⚠️ 本文 §2.4 的错配（前缀 `/api/agent` 但归 integration）是
**Wave 2 切流的直接输入**：Gateway 路由表不能按前缀批量生成，
必须先读 `route-inventory.yaml` 的 `handler` 字段确认真实 Owner。
这一点在 `mcp-boundary.md` 之外没有第二处记录，所以不要在 Wave 2 时
只看前缀就写路由。
