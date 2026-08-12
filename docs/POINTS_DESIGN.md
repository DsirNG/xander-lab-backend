# 积分（Points/Billing）功能设计

## 1. 背景与目标

Xander Lab 的多个功能依赖外部付费资源（大模型、图生 3D、邮件、CSDN 等），当前所有用户都能无限使用，成本不可控。接入积分系统后：

- 让用户的每次消耗可计量、可预期。
- 防止资源滥用和恶意刷接口。
- 为后续会员、付费、运营活动留出载体。

## 2. Non-goals

- 不做充值收银台（本期只建设积分账本与消耗链路）。
- 不做积分商城 / 兑换。
- 不改变现有 HTTP 路由、数据表和配置；全部通过新增迁移落地。

## 3. 消耗识别：哪些该扣积分

基于现有代码盘点，以下调用点会产生真实外部成本，应纳入扣费。计价方式分两类：

- **Token 计价**：LLM 调用（走 OpenAI Responses 兼容接口）可从响应 `usage` 摘取每次实际消耗，按 token × 单价扣费。
- **一口价**：无 token 计量能力的资源（配图、邮件、CSDN 发布、图生 3D 任务）按次一口价。

具体采集能力见 3.8，所有单价均为示例，实际走配置。

### 3.1 AI 文章生成（博客智能体）

位置：`blogagent.application.BlogAgentService` + `blogagent.infrastructure.BlogAgentModelClient`。

一次 `run` / `reviseStream` 会依次调用 `analyze` → `research` → `createArticle` / `createArticleStream` → 合规审查 `reviewCompliance`（→ `reviewArticle`），token 消耗最大；知识插图走 `BlogAgentImageClient.generate`，按张计。

| 场景 | 计价粒度 | 示例 |
| --- | --- | --- |
| 手动生成一篇文章（run / runStream） | 按 token 汇总所有阶段调用 | 输入 $0.15 / 输出 $0.60 每百万 token |
| 单轮改写（reviseStream） | 按 token（单次调用） | 同上 |
| 知识插图（illustration） | 按张一口价（images API 无 usage） | 5 分 |
| AI 选题（`generateTopics`，复用 `planTopicSeries`） | 按 token（单次调用） | 同上 |
| AI 生成内容计划（`BlogContentPlanAiGenerateRequest`） | 按 token（单次调用） | 同上 |

### 3.2 Agent 编排对话

位置：`agent.application.AgentConversationService` + `agent.infrastructure.AgentModelClient`。

注意：`POST /api/agent/conversations` 创建会话后会立即触发首轮模型调用（`startClaimedTurn` → `executeClaimedTurn`），因此"创建即消耗一轮"。

- 每轮（一个 `runVersion`）扣一次，按该轮内全部模型调用 token 汇总。
- 注意：一轮可能包含多次 `decideNextStep` 循环（决策→工具→决策），token 要累加。
- 工具调用不单独再扣，避免同一轮内重复扣费。

> 开放问题：当一轮对话调用了业务工具（如 `blog_generate`）时，生成的博客任务已经是独立计费项。建议对话轮次只按"纯编排模型调用"计费，业务工具结果按其自身单价计，避免同一份模型输出被重复收费。

### 3.3 图生 3D

位置：`studio.application.Img2ThreeService` + `studio.infrastructure.Img2ThreeModelClient`。

重建是全站单价最高的操作。内部也是 Responses API（vision 输入 + `max_output_tokens=16000`），可按 token 计费；但一次重建可能包含多次"重建 + 修复"循环。建议本期按"成功重建一次"一口价计费，同时把 token 明细写入流水备查。

### 3.4 定时发文（内容计划）

位置：`automation.application.BlogPlanRunDispatcher`（复用 `blogagent` 生成 + `publicationService` 发布）。

- 每个 `blog_plan_run` 成功生成逻辑扣一次生成费。
- 该 run 若还发布 CSDN，再扣一次 CSDN 配额。
- 失败 / 重跑不能重复扣：以 `runId` 作为扣费幂等键。

### 3.5 邮件定时提醒

位置：`automation.infrastructure.EmailReminderScheduler` → `integration.mail.MailService.sendReminderMail`。

每次实际发送扣一次，防止批量创建定时任务耗尽平台 SMTP 额度。

### 3.6 CSDN / 第三方发布

位置：`integration.mcp.csdn.CsdnPublisher` / `publishing.application.PublicationService.publish(command, "csdn")`。

平台承担 API 调用成本并消耗用户第三方账户配额，建议每次发文尝试扣少量积分防滥用。本站本地发布（`publish(command, "local")`）不扣。

### 3.7 OSS 存储 / 带宽（可选）

`integration.oss.OssService`：媒体上传有存储成本，但属于用户自有内容且与文章强绑定。建议本期不扣，预留为后续存储计费项。

### 3.8 模型单次消耗采集（前提能力）

三个模型客户端全部调用 OpenAI **Responses 兼容接口**（`POST /responses`），接口报文自带用量，当前代码未采集，需要先行改造：

| 客户端 | 调用形态 | 用量的位置 |
| --- | --- | --- |
| `BlogAgentModelClient` | 非流式 `postJson`（analyze/research/review/选题） | 响应体顶层 `usage` |
| `BlogAgentModelClient` | 流式 `streamOnce`（成文/改写） | SSE 终事件 `response.completed` 内的 `response.usage` |
| `AgentModelClient` | 流式 `decideNextStepStreamOnce`（对话决策） | 同上 |
| `Img2ThreeModelClient` | 非流式 `invokeResponsesApi`（重建/修复） | 响应体顶层 `usage` |

`usage` 结构（OpenAI 标准）：`input_tokens` / `output_tokens` / `total_tokens`，另有 `cached_tokens` 明细（`input_tokens_details.cached_tokens`）。

改造要求：

1. 每个 client 摘取 usage，随结果一并返回（返回类型从 `JsonNode` 扩展为"文本 + usage"记录）。
2. 流式调用在 `response.completed` 事件里读 `response.usage`；当前代码在该事件 `break` 退出，需补读后返回。
3. 只用"最终成功响应"的 usage 计费；`executeWithRetry` 重试前的失败尝试不参与，避免重复计费。
4. **兜底**：若生产网关不返回标准 `usage`，退回 3.1-3.6 的"一口价"计价，不能因此让扣费短路。
5. 配图（images API）、邮件、CSDN 发布、FlowCraft 无 usage 概念，天然按次计费。

## 4. 哪些明确不扣积分

| 操作 | 原因 |
| --- | --- |
| 所有读接口（列表 / 详情 / SSE 订阅 / 状态查询 / 事件回放） | 无外部成本 |
| `POST /api/blog-agent/tasks`（create 仅落库草稿） | 不触发模型调用 |
| 本地文章 CRUD、草稿、状态流转、软删除 | 纯数据库写入 |
| FlowCraft 项目 / 画布 / 快照 | 纯本地数据 |
| 登录、注册、验证码邮件 | 运营获客成本，扣分体验差 |
| 取消、失败、排队阶段 | 未产生有效产出则免扣或退款 |
| 浏览 / 阅读统计（`recordView`） | 公开行为 |

## 5. 扣费原则（与现有架构对齐）

1. 扣费发生在"实际调用付费资源"的后台工作线程中，不在 Controller 入口扣，避免请求失败导致误扣。
2. 幂等：扣费流水以业务唯一键（`taskId`、`conversationId + runVersion`、`planRunId`、`img2three taskId`）作为幂等键，数据库唯一约束保证不重复扣。
3. 失败处理：
   - 平台自身 / 供应商故障（502、超时等）失败 → 自动退款（冲正流水）。
   - 用户主动取消但工作已开展 → 不退款。
4. 余额不足：在真正发起付费调用前做余额预检查，不足时直接返回明确业务错误。
5. 竞态：余额扣减使用原子条件更新（`UPDATE ... SET balance = balance - ? WHERE balance >= ?`），与现有 conditional update 模式一致。
6. 计价方式：LLM 类按每次调用采集的 `usage` × 模型单价算分；无 `usage` 或网关不返回时退回一口价。单价用 `@ConfigurationProperties` 配置，不做死。
7. 计费基准：以"最终成功响应"的 token 数为准，重试的失败尝试不计费。
8. 注册赠送：新用户赠送初始积分（配置化，如 200 分）。

## 6. 数据模型

新增表（全部带中文 `COMMENT`，走 migration SQL）：

### 6.1 `sys_user_points` 积分账户

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `user_id` | BIGINT PK | 所属用户，关联 `sys_user.id` |
| `balance` | BIGINT | 当前余额（积分最小单位） |
| `created_at` | DATETIME | 创建时间 |
| `updated_at` | DATETIME | 更新时间（ON UPDATE CURRENT_TIMESTAMP） |

### 6.2 `points_ledger` 积分流水（审计）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | 流水主键 |
| `user_id` | BIGINT | 所属用户，关联 `sys_user.id` |
| `biz_type` | VARCHAR(32) | 业务类型：`grant` / `consume` / `refund` |
| `biz_key` | VARCHAR(128) | 业务幂等键，同 `biz_type` 下唯一 |
| `biz_ref` | VARCHAR(64) NULL | 业务引用：taskId / runId / conversationId+runVersion |
| `amount` | BIGINT | 金额（正数），方向由 `biz_type` 决定 |
| `balance_after` | BIGINT | 扣费后余额快照 |
| `unit_price` | BIGINT | 扣费时的单价快照（token 单价或一口价，价格会变） |
| `model` | VARCHAR(64) NULL | Token 计费用的模型名（一口价时为空） |
| `input_tokens` | BIGINT NULL | Token 计费用的输入 token 数 |
| `output_tokens` | BIGINT NULL | Token 计费用的输出 token 数 |
| `cached_tokens` | BIGINT NULL | Token 计费用的缓存命中 token 数（OpenAI `input_tokens_details.cached_tokens`） |
| `remark` | VARCHAR(500) NULL | 备注 |
| `created_at` | DATETIME | 创建时间 |

唯一约束：`uk_biz (biz_type, biz_key)`。

`biz_ref` 关联的业务实体（供对账）：
- 文章生成：`blog_agent_task.id`
- 改写：`blog_agent_task.id`
- 插图：`blog_agent_task.id`
- 选题：无 / 请求标识
- Agent 对话：`agent_conversation.id + runVersion`
- 定点 run：`blog_plan_run.id`
- 邮件：`email_reminder_task.id`
- 图生 3D：`img2three_task.id`
- CSDN 发布：`blog_publish_record.id`

## 7. 模块规划：`billing`

按 `AGENTS.md` 的模块边界，新增独立业务模块 `com.xander.lab.billing`：

- `billing.api`：Controller、DTO 校验。只做传输映射，不做余额逻辑。
- `billing.application`：`BalanceService`（原子扣减 / 退款 / 赠送）、对外端口 `{ 预检查 + 扣费 }`。
- `billing.domain`：`Balance`、`LedgerEntry`、价格模型（不可变类型，可用 record）。
- `billing.infrastructure`：mapper、价格 `@ConfigurationProperties`。
- 业务模块（blogagent / agent / studio / automation）通过 `billing.application` 接口调用，不依赖 billing mapper。
- 同时向 Controller 层暴露"只检查余额不扣费"的预检端口，用于前端提交前的双保险。

## 8. API 草案

| 路由 | 说明 |
| --- | --- |
| `GET /api/points` | 余额概览（余额、今日消耗等） |
| `GET /api/points/ledger` | 积分流水分页 |
| `GET /api/points/prices` | 价格表 |

写端不对外开新 API（扣费在后台任务发生）。错误形状保持 `{ code, message, data:null }`，余额不足映射为业务错误码并由 `GlobalExceptionHandler` 统一处理，不允许 HTTP 200 包业务错误。

## 9. 前端改动

- 工作台（`/workspace` 侧边栏）新增积分入口，展示余额、价格表、流水。
- 高成本操作（图生 3D、AI 生成、定时发文）在触发前调用余额预检，余额不足给出明确提示。
- 新增 i18n key 必须同步 `src/locales/*.js` 的 zh / en / fr / ja / ru / vi 六种语言（遵守前端规范）。

## 10. 安全与防滥用

- 扣费只发生在服务端后台任务，前端无法伪造。
- 原子扣减 + 唯一幂等键防止并发重复扣。
- 注册赠送与单价全部走环境变量 / 配置，禁止硬编码。
- 所有余额与流水查询按 `UserContext` 当前用户隔离，不泄露他人信息。

## 11. 开放问题（待决策）

- 生产 `BLOG_AGENT_BASE_URL` 指向的网关是否返回标准 `usage`？（决定 LLM 走 token 计费还是退回一口价）需要部署环境实际验证，3.8 已设计兜底。
- CSDN 发布与邮件发送是否纳入本期扣费，还是只先对 LLM / 图生 3D 计费？
- OSS 存储 / 带宽是否纳入？
- 免费额度策略：注册赠送 + 每日 / 每月低保？
- 是否预留积分兑换、会员等级体系？
- Agent 对话中调用业务工具时的计费口径（见 3.2 开放问题）。

## 12. 实施顺序（建议）

1. 改造 3 个模型客户端采集 `usage`（非流式响应体 + 流式 `response.completed`），返回"文本 + usage"，并补重试/网关兜底。
2. `billing` 模块骨架 + 数据模型（含 token 明细列）+ 迁移 SQL。
3. `BalanceService`：原子扣减 / 退款 / 赠送 + 幂等流水；token→积分换算与一口价并存。
4. 接入 `blogagent`（生成 / 改写 / 插图）与 `agent`（每轮）两个最大成本点。
5. 接入 `img2three`。
6. 接入 `automation`（选题、定时 run、邮件）。
7. 接入 CSDN 发布。
8. 前端积分页 + 运行前余额预检。
9. 测试：usage 采集解析、原子扣减并发、幂等去重、失败退款、余额不足、注册赠送、无 usage 兜底；最后 `mvn -q test` 通过。