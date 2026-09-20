# 共享库与契约模块抽取（V9 §18 / §19 / §20 / §21 / §80 / §91）

本文记录 Wave 0B「抽纯净技术库 + 建业务 Contract」的**实际进度与判断依据**。

对应 V9 章节：§18（Monorepo 目标布局）、§19（禁止超级 common.jar）、
§20（各 lib 边界）、§21（ArchUnit 门禁）、§80（MyBatis 配置分片）、§91（Wave 0B）。

⚠️ 本文只描述**已经落地的事实**与**必须留痕的判断**。计划里的目标清单在 §2 表里，
不要把它当成"已完成"读。

---

## 1. 一句话结论

`common` 原来是一个 12 个类的"什么都有"包，其中 5 个是纯的、4 个是 Web MVC、
1 个是 MyBatis、2 个是运维型 Redis。**WebFlux Gateway 一旦依赖它就会启动失败**，
所以 §19 必须拆。

Wave 0B 已抽出 **6 个 lib**（`xander-model`、`xander-mybatis-support`、`xander-auth-core`、
`xander-webmvc-support`、`xander-observability-core`、`xander-test-support`）
与 **8 个契约模块**（`content-api`、`integration-api`、`core-api`、`payment-api`、
`agent-api`、`automation-api`、`recitation-api`、`blogagent-api`），`common` 从 12 类降到 6 类，
另有 2 个类从**业务域包** `identity.security` 迁出、4 个类从 `publishing.domain` 迁出。
2 个契约模块（`knowledge-api`、`studio-api`）仍按 V9 后续拆分节奏保留。

⚠️ **"8 个契约模块"数的是模块，不是刀次。** 已经有模块被扩展过多轮：
`core-api` 加过 `UserLookupPort`（§3septies），`automation-api` 加过 `NotificationPort`（§3duodecies），
`recitation-api` 加过 `RecitationQuizPort`（§3quaterdecies），
`blogagent-api` 加过 `BlogAgentImagePort`（§3sexdecies），
`integration-api` 加过 `CsdnBlogSyncPort` / `JuejinBlogSyncPort` + `BlogSyncResult`（§3novemdecies）。
扩展是**零构建接线**的 —— 不新增模块，四处构建接线一处都不用改。
`recitation-api` 是**第十刀**（§3terdecies），也是第一个"建新模块"的刀自 `automation-api` 之后；
它的测验能力扩展是**第十一刀**（§3quaterdecies），又回到零接线。
`blogagent-api` 是**第十二刀**（§3quinquiesdecies）**建模块**，看点是把参数顺序归一到 `(userId, ...)`，
以及随之暴露的"守卫只朝一个方向看"（第三次实例）；
它的图片能力扩展是**第十三刀**（§3sexdecies），是**第一个不收 `userId` 的端口**。
`core-api` 是**第十四刀**（§3septdecies），加 `AccessTokenLookupPort` ——
这是**第一个"产出"调用方身份**的端口（所以守卫是反的），也是第一个消费方在
`api/` 层（WebSocket 握手拦截器）而不是工具/服务里的刀。
**第十五刀**（§3octodecies）是把同一个端口接给**第二个消费方** `McpOAuthService` ——
零构建接线（契约模块与 POM 一行没改），而收益是消费方**少了一个协作者**
（它原来自己复制了一遍身份解析，`JwtUtil` 只为那一行而存在）；
⚠️ 顺带把 `dinqor-core-host` 送出了 Wave 2 强连通分量 —— 锁住它的只有这一条 1 语句的边。
**第十六刀**（§3novemdecies）把 `agent -> integration` 的 2 条语句抽成
`CsdnBlogSyncPort` / `JuejinBlogSyncPort`（**两个**端口，不是一个带 `platform` 参数的端口）+
一个等价 DTO `BlogSyncResult` —— `agent` 的出边**全部归零**，
⭐ **`dinqor-agent` 在实现层与 api 层都只剩 0 条边，彻底离开跨域边表**，
它的抽取不再需要任何契约工作。
**第十七刀**（§3vicies）是**第一次不靠 Java 改动收掉一条边**：`studio → blogagent` 的
唯一那条 import 是 `Img2ThreeModelClient` 读 blogagent 的 `@ConfigurationProperties`
（"img2three 没配就借 blogagent 的凭证"），把回落从**代码**搬到 `application.yml` 的
**嵌套占位符**就收掉了 —— 无新契约、无新端口、`contracts/` 一个字没改，
⭐ **`studio` 作为来源域也一条出边都没有了**。⚠️ 代价是单元测试再也构造不出那条路径，
必须新增一个**加载真实交付 YAML** 的测试来守（§3vicies.5）。

⚠️ **刀次口径有两个，引用时写清是哪个。** 本文用**契约刀次**（第十七刀）；
`D:\blog\.workbuddy-ai\memory\notes\v9-wave0a.md` 用 **Wave 0B 总刀次**（第二十四刀）。
两者都对，只是分母不同。

⚠️ **Wave 0 不全仓搬目录**（§18 原话）。目录形态之所以变了，是 Maven 的硬约束，
不是审美 —— 见 §5。

---

## 2. 目标清单与进度

### 2.1 共享库（§20）

| lib（§20） | 状态 | 内容 |
|---|---|---|
| `xander-model` | **已完成** | `Constants` `Result` `ModelCallResult` `ModelUsage` |
| `xander-mybatis-support` | **已完成** | `MybatisPlusConfig` `PageData` |
| `xander-auth-core` | **已完成** | `UserContext`（可信身份模型）`JwtUtil`（Token 校验器） |
| `xander-webmvc-support` | **已完成** | `GlobalExceptionHandler` `ResultStatusResponseBodyAdvice` `AuthInterceptor`（§20 的 "MVC 认证适配器"）`WebConfig`。包是 `com.xander.lab.webmvc`，**不是** `identity.security`（§3.4.2） | 见 §3.4 |
| `xander-observability-core` | **已完成（基础层）** | `CorrelationContext` + `CorrelationHeaders`：安全的 traceId/requestId 生成、校验与传播名称；不含 Servlet/WebFlux、MDC 生命周期或 exporter |
| `xander-test-support` | **已完成（骨架）** | 独立的 test-facing 模块与包边界；只有跨服务复用的测试契约才允许继续加入 |

### 2.2 业务契约（§18 `contracts/`，§91）

| 契约模块 | 状态 | 内容 | 跨部署单元实现依赖的收益 |
|---|---|---|---|
| `content-api` | **已完成** | 第一刀：`BlogPublisher`（发布 SPI）`PublishCommand` `PublishResult` `PublishingLimits`。第二刀：`MediaAssetPort` `MediaAssetRef` `StoredImage`。第三刀：`ContentPostPort` `PublicationPort` `ContentPost` `ContentPostPage` `ContentPostInput` `ContentPostQuery` `ContentPostStatus` | 第一刀：`integration → publishing` 22 → 5；总计 −18 条跨单元 import。第二刀：`publishing ↔ blogagent` 环拆成单向；跨单元 −8。第三刀：`integration → publishing` 5 → 0（api 11 → 0）、`agent → publishing` 3 → 0、`profile → publishing` 1 → 0 —— **`publishing` 跨单元入边归零**；总计 **−9**（跨单元 −9） |
| `integration-api` | **已完成**（两刀） | 第二刀（§3quater）：`OssPort`（对象存储端口）`UploadResponse`。**第十六刀（§3novemdecies）再加 `CsdnBlogSyncPort` / `JuejinBlogSyncPort`（站外同步，各 2 个方法）+ `BlogSyncResult`（`PublishResult` 的等价 DTO）**。⚠️ 是**两个**端口而不是一个带 `platform` 参数的端口（§3novemdecies.2）；⚠️ 掘金那两个参数跨域**今天一律传 `null`** 但留在端口上（§3novemdecies.3） | 第二刀：`knowledgebase/publishing/recitation → integration` 各 2 → **0（边归零）**；`agent → integration` 4 → 2；总计 **−8**。第十六刀：`agent → integration` 2 → **0（边整条消失）**，`automation → integration` 6 → 5；总计再 **−3**（跨单元 −3）。⚠️ `api_statements` / `api_edges` **一条都没动**（这一刀全是 impl 语句）。图形状：**`dinqor-agent` 在实现层与 api 层都只剩 0 条边**，彻底离开跨域边表 |
| `core-api` | **已完成** | `ModelGateway`（运行时模型路由 SPI）`ModelGateway.ModelEndpoint` `FeatureModelNotConfiguredException`；**`UserLookupPort`（用户展示名端口）**；**`AccessTokenLookupPort`（访问令牌身份端口，第十四刀加；第十五刀接入第二个消费方）** | `agent/blogagent/recitation/studio → admin` 四条边**全部归零**（10 → 0）；`publishing/components → identity` **两条边归零**（4 → 0）；`agent → identity` **两条边归零**（2 → 0，第十四刀）；`integration → identity` **归零**（1 → 0，第十五刀）；跨部署单元累计 **−11**；总计 **−17** |
| `agent-api` | **已完成** | `RemoteToolCatalog`（远端工具目录端口）`RemoteTool`（一个被贡献出来的工具）`RemoteToolCall`（调用期回传通道）。包是 `com.xander.lab.agent.spi`，**不是** `.agent.api`（§3decies.2） | `integration → agent` 5 → **0（边整条消失）**；总计 **−5**（跨单元 −5）。图形状：`agent ↔ platform-integration` **不再是互依对**，`deployment-unit-coupling` 从 4 对降到 3 对 |
| `automation-api` | **已完成** | `EmailReminderPort`（定时邮件提醒端口）+ `EmailReminderView` / `EmailReminderDraft`；`ContentPlanPort`（定时发文计划端口）+ `ContentPlanView` / `ContentPlanDraft` / `ContentPlanEdit`。**第九刀再加 `NotificationPort`（写一条持久化用户通知）+ 两个从实体搬来的常量**。包是 `com.xander.lab.automation.spi`，**不是** `.automation.api`（§3decies.2 同因：那个包是 20 个前端 HTTP 文件的家）。**收窄而非镜像**：11/12/11/10/8 个分量，对应域 VO 是 20/12/24/10/15 | 第八刀：`agent → automation` 6 → **0（边整条消失）**；总计 **−6**（跨单元 −6）。另外 `api_statements` 25 → 16（−9）：6 个工具原本 import 的 9 个 `automation.api.*` 类型全换成 `automation.spi.*`，而契约包在 `SHARED_KERNEL` 里**不计任何指标**。图形状：`dinqor-agent` **脱离 Wave 2 强连通分量**（只剩出边），成为可单独抽取的单元。**第九刀（零接线扩展，§3duodecies）**：`knowledgebase → automation` 2 → **0（边整条消失）**，总计再 **−2**（跨单元 −2）；`api_statements` **16 → 16 不变**（消费方 import 的是 `.application` / `.domain`，从没碰过 `.api`）。图形状：`knowledgebase` 变成**纯汇点**（整个域从清单里消失）；`automation` 扇入只剩 `admin`（SSE，要框架类型）与 `integration`（模板真环） |
| `recitation-api` | **已完成**（两刀） | 第十刀（§3terdecies）：`RecitationMaterialPort`（背诵材料能力端口，4 个方法）+ `MaterialView`（收窄的读模型，9 分量）/ `MaterialDraft`（4）/ `MaterialFilter`（4）。第十一刀（§3quaterdecies）：`RecitationQuizPort`（测验能力端口，2 个方法 + 2 个常量）+ `QuizSessionView`（15，全带）/ `QuizAttemptView`（收窄，6，`items` 走**文本** `itemsJson`）/ `QuizOutcomeView`（收窄，10）/ `GradedItemDraft`（4）。包是 `com.xander.lab.recitation.spi`，**不是** `.recitation.api`（同因：那个包是 6 个前端 HTTP 文件的家）。⚠️ 第十一刀**删掉了两个域内 record**（`QuizSession` / `QuizOutcome`，同域生产消费方为零）而不是搬走 | 材料刀：`automation → recitation` 2 → **0（边整条消失）**、`agent → recitation` 4 → 2；总计 **−4**（跨单元 −4），`api_statements` 16 → 13（−3）。测验刀：`agent → recitation` 2 → **0（边整条消失）**，总计 **−2**（跨单元 −2），`api_statements` 13 → 12（−1）。图形状：**`content-automation ↔ core-host` 不再是互依对**（互依对 3 → 2，`MUTUAL_UNIT_PAIRS` 删掉那对，门禁主动报出过期条目）；`recitation` 成为**零跨域消费方**的域 |
| `blogagent-api` | **已完成**（两刀） | 第十二刀（§3quinquiesdecies）：`BlogAgentPort`（博客生成能力端口，5 个方法）+ `BlogAgentTaskDraft`（2）/ `BlogAgentTaskView`（4）/ `BlogAgentPostView`（5）。包是 `com.xander.lab.blogagent.spi`，**不是** `.blogagent.api`（同因：那个包是 5 个前端 HTTP 文件的家）。⚠️ 第十二刀**归一了参数顺序**（提供方是 `(taskId, userId)`，端口统一 `(userId, taskId)`）—— 并因此发现既有的"首参必须是调用方 id"守卫**只断言类型**，对 `publish(Long taskId, Long userId)` 是**绿的**。第十三刀（§3sexdecies）：`BlogAgentImagePort`（图片生成能力端口，2 个方法）+ `BlogAgentImage`（6）/ `BlogAgentImageUsage`（3 long）。⚠️ 这是**第一个不收 `userId` 的端口**，理由是提供方是无状态的模型调用；守卫因此**反过来写**（断言"没有参数叫 userId"） | 第十二刀：`agent → blogagent` 4/2 → **2/0**（剩 `ImageGenerationTool`）；**`agent → publishing`（0/1）整条边消失**（`PublishPostTool` 是 `BlogPostVO` 的唯一跨域读者）；总计 **−2**（跨单元 −2），`api_statements` 12 → 9（−3）。⚠️ **两条边数指标一个都没动**（16 / 9）。第十三刀：`agent → blogagent` 2/0 → **0/0（边整条消失）**，总计再 **−2**（跨单元 −2）；`implementation_edges` 16 → **15**、`cross_deployment_unit_edges` 9 → **8**；`api_statements` **9 → 9 不变**（这一刀全是 impl 语句）。⚠️ 顺带修了一处缺陷：素材 meta 里的 `model` 记的是配置里的首选模型而不是实际端点模型 |
| `knowledge-api` | 未开始 | — | — |
| `studio-api` | 未开始 | — | — |
| `payment-api` | **已完成** | `BillingBiz`（`points_ledger.biz_type` 词表）；**`BillingPort`（积分账本端口）+ `TokenUsage`（用量 DTO）** | 词表那一刀：`agent 6→3`、`integration 4→2`、`automation 2→1`、`blogagent 2→1`、`studio 2→1`；总计 **−8**（跨单元 −7）。端口那一刀：`agent 3→0`、`integration 2→0`、`automation 1→0`、`blogagent 1→0`、`identity 1→0`、`profile 1→0`、`studio 1→0` —— **7 条边全部归零**，总计再 **−10**（跨单元 −7）。两刀合计 **−18**（跨单元 −14） |

`integration` 域**不是一次性搬完的**：它被 7 个域引用的 7 个类型里，只有 2 个是"能直接搬"的。
剩下 5 个（CSDN / 掘金 的凭证与同步服务、`MailService`）都是**具体实现而非接口**，
搬它们要先做端口抽取；其中 `MailService` 还 import 了
`automation.domain.EmailReminderMailTemplates`（一个业务域类型），
在解开那处依赖之前**根本搬不动**。详见 §3quater.5。

⚠️ **契约模块不是"共享库"**，但它的包名必须登记在 `SHARED_KERNEL` 里，
理由见 §6 与 §10.3：否则把类型搬进契约之后，跨域基线**一点都不会动**，
这次抽取在唯一的进度指标上完全隐形。

`common` / `config` / `filter` 三个包**当前剩余 8 个类**，全部还在应用模块里；
运维告警调度器已单独归到 `operations.redishealth`。
（⚠️ 2026-09-20 实测订正：原文写"11 个类"，那还是把 `config` 里已搬走的 3 个算在内；
而且列表本身还漏了 `common/security/SecurityPlaneReadiness.java`。8 是当前源码清单，
不是从这张表加出来的）：

```text
common/HealthController.java              ← 运维端点，见 §4
common/RedisHealthProbe.java              ← 运维型 Redis 探针，见 §4
common/security/ConnectionRegistry.java   ← 连接注册表（§49）
common/security/SecurityPlaneConfiguration.java
common/security/SecurityPlaneReadiness.java
common/security/SecurityStateCache.java
config/ShedLockConfiguration.java
filter/RequestLoggingFilter.java          ← 目标是 xander-observability-core
operations/redishealth/RedisHealthMonitor.java ← 运维告警调度器，见 §4
```

`config/GlobalExceptionHandler.java` / `config/ResultStatusResponseBodyAdvice.java` /
`config/WebConfig.java` 已于本刀搬进 `libs/xander-webmvc-support`（见 §3.4）。

---

## 3. 六个已抽出的 lib

### 3.1 `xander-model`

只依赖 `jackson-databind` + `lombok`。**无 Spring MVC / WebFlux / MyBatis / Redis / 业务域**
（§20 原文）。Gateway 可以依赖它。

包名保持 `com.xander.lab.common` 不变 —— 移动类但改包名会让 800+ 个文件的 diff 里
混进 import 变更，rename 检测就废了，评审也就看不出"到底改了什么"。

### 3.2 `xander-mybatis-support`

依赖 `mybatis-plus-extension` + `spring-context` + `lombok`。

版本号走父 POM 的 `${mybatis.plus.version}`（当前 `3.5.5`），**不要在子 POM 里内联写死**：
库和应用落在两个不同 MyBatis-Plus 版本上时，classpath 上会出现两份 MyBatis-Plus 类，
报错现场离原因很远。

### 3.3 `xander-auth-core`

依赖 `spring-context`（`@Component` / `@Value`）+ `jakarta.annotation-api`（`@PostConstruct`）
+ jjwt（`jjwt-api` 编译期、`jjwt-impl` / `jjwt-jackson` 运行期）+ `spring-boot-starter-test`（test）。
版本走父 POM 的 `${jjwt.version}`（当前 `0.12.6`），理由同 §3.2。

**这个库是 Wave 1 的 Gateway 与业务服务共享的认证面**，所以 §20 的禁令（`HandlerInterceptor` /
`WebMvcConfigurer` / Gateway Filter / `RedisHealthMonitor`）必须真的守住 —— 一旦混进 MVC
实现，Gateway 又会变成"依赖了它就没法启动"。

⚠️ **库自己拥有自己的测试**：`UserContextTest` / `JwtUtilTest` 跟着类一起进了
`libs/xander-auth-core/src/test/java/`，而不是留在应用模块的测试树里。
理由：测试留在应用模块等于"应用拥有它已经不再拥有的类的测试"，那不叫抽出来了。

### 3.4 `xander-webmvc-support`（ADR §26，Wave 0B 第 4 个共享库）

依赖 `xander-model`（`Result` / `Constants`）+ `xander-auth-core`（`UserContext` / `JwtUtil`）
+ `core-api`（**契约**）+ `spring-boot-starter-web`（`provided`）+ `spring-boot-starter-data-redis`
（`provided`）+ `lombok`（optional）+ `spring-boot-starter-test`（test）。

搬进来的是 §20 点名的 4 个：`GlobalExceptionHandler` / `ResultStatusResponseBodyAdvice` /
`AuthInterceptor`（§20 的 "MVC 认证适配器"）/ `WebConfig`；`GlobalExceptionHandlerTest` 与
`AuthInterceptorTest`（33 个方法）跟着走，另加 2 个新方法（§3.4.3）。留在应用的是
`config/ShedLockConfiguration`（调度接线，不是 MVC）与 `identity/security/PasswordEncoderConfig`
（BCrypt bean，identity 域配置；§20 没列它）。

#### 3.4.1 三个必须先说清楚的事实

1. **这 4 个类一个 Java 消费者都没有。** 它们是 Spring 发现的（组件扫描、
   `@RestControllerAdvice`、`WebMvcConfigurer`），所以搬错了**不会编译失败**：编译通过、
   测试全绿，而异常映射或拦截器注册在运行时**静默失效**。这一刀因此不能拿"构建绿"当验收。
2. **`AuthInterceptor` 不是"搬"，是"收窄"。** 它原来注入 `identity.application.AuthService`，
   只用了两个动作（`validateAccessToken(token)` + `jwtUtil.getSubject(token)`）——
   合起来正是 `core.api.AccessTokenLookupPort.resolveUserId(String)`，即第十四刀**为这个能力
   建的**端口。不改成端口就搬不动：库一旦依赖业务域，它就不是"每个 MVC 服务都能用的支撑"，
   而是 identity 域的一部分（ADR-2：跨域走**消费方拥有的契约**）。
3. **`GlobalExceptionHandler` 依赖 `core-api`，这是本仓库唯一一个带契约依赖的库。**
   它把 `FeatureModelNotConfiguredException` 映射成 400，而 §20 是**整类**列的
   `GlobalExceptionHandler`，所以整类搬走、依赖记在 POM 里。另一条路是拆成两个 advice，
   那会把 Spring 的"跨 advice 取最具体 handler"排序放到**每一条错误响应**的关键路径上 ——
   否掉，并在 POM 里写明这是逃生口而不是当前方案。

#### 3.4.2 包名为什么是 `com.xander.lab.webmvc`

`AuthInterceptor` 原来住在 `com.xander.lab.identity.security`。**库不能发一个落在业务域命名空间里的
包**：`identity` 是扫描器 `BUSINESS_DOMAINS` 的条目，模块会被判成那个域的一部分。先例是
`xander-auth-core` 把 `UserContext` / `JwtUtil` 从 `identity.security` 挪到 `auth.core`。

⚠️ 顺带**真的修掉了一条共享内核反向依赖**：`WebConfig` 原来是
`com.xander.lab.config.WebConfig`，第 3 行 `import com.xander.lab.identity.security.AuthInterceptor;`
—— 那就是 `ci_gates.py` 允许清单里 `("config", "identity")` 这一条的全部内容。
包一换、import 一没，`shared-kernel-reverse-dependency` 的违规从 2 条降到 1 条，
允许清单里那一项按判据自己的注释删掉了（判据会主动报"已修复，请从清单移除"）。
**允许清单没被清空**：`("common", "integration")` 还在，注入验证专门确认了它仍然会红（§3.4.4 C3）。

#### 3.4.3 ⚠️ 一处**行为变化**：subject 非数字时 500 → 401

旧写法 `Long.parseLong(jwtUtil.getSubject(token))` 在 subject 不是数字时抛
`NumberFormatException` ⇒ **500**；现在由提供方解析，解析不出来返回 `null` ⇒ **401**。
两种都拒绝请求，但 401 才是这条路径该有的答案，而且不再往日志里丢一条 500 堆栈。

这是这一刀唯一的行为变化，方向是 **fail-closed**，所以**必须**有行为测试守着 ——
新增两条：`protectedRouteRejectsTokenThePortCannotResolve`（受保护路径 401、且
`verify(jwtUtil, never()).parseToken(anyString())` 钉住"令牌有效性只走端口"）与
`publicRouteTreatsUnresolvableTokenAsAnonymous`（公开路径按匿名放行、200、`userId` 为 null）。
`AuthInterceptorTest` 从 31 个方法变成 33 个。

#### 3.4.4 收益与注入验证

**六个指标一个都没动**：17 / 7 / 0 / 0 / 9 / 6。这一刀不搬跨域边 —— 那 4 个类本来就
没有 Java 消费者，所以没有任何 import 边可减。**这正是它危险的地方**：
"指标不变"与"什么都没发生"在这一刀上是同一个数，所以验收必须靠注入。

| 编号 | 注入 | 期望 | 实测 |
|---|---|---|---|
| D0 | 不注入（对照） | 真跑且全绿 | `Tests run: 33, Failures: 0, Errors: 0` ✓ |
| C1 | `scan.py` 去掉 `SHARED_KERNEL` 的 `webmvc` | `shared-kernel-classification` 红 | FAIL，指名 `webmvc (libs/xander-webmvc-support)` ✓ |
| C2 | 库里的类 `import com.xander.lab.identity.application.AuthService` | `shared-kernel-reverse-dependency` 红 | FAIL，`[('webmvc','identity')]` ✓ |
| C3 | 允许清单清空 | 同上红 | FAIL，`[('common','integration')]` ✓ |
| C4 | Dockerfile 注释掉 `COPY libs/xander-webmvc-support/pom.xml` | `dockerfile-module-coverage` 红 | **修复前 PASS（假绿）**，修复后 FAIL ✓ |
| C0 | 根 POM 不动（对照） | core-host 编译成功 | BUILD SUCCESS ✓ |
| C5 | 根 POM 注释掉 `<module>` | core-host 编译失败 | FAIL：`Could not find artifact com.xander:xander-webmvc-support` ✓ |
| D1 | `requireAuth` 改成 fail-open | 新测试红 | `protectedRouteRejectsTokenThePortCannotResolve` 红（"Expecting value to be false but was true"）✓ |
| D2 | `trySetUserContext` 拿不到身份时抛异常 | 新测试红 | `publicRouteTreatsUnresolvableTokenAsAnonymous` 红（`IllegalStateException`）✓ |
| D3 | `requireAuth` 多调一次 `jwtUtil.parseToken` | 新测试红 | 同一方法红（Mockito `never()` 消息），另 1 条连带 error ✓ |

⚠️ **C4 是这一刀真正的收获：门禁原来会假绿。** `dockerfile-module-coverage` 的第一条判据是
`if f"{module}/pom.xml" not in docker` —— **全文子串匹配**，于是把 COPY 行**注释掉**仍然通过，
而"注释掉一行"正是有人要禁用某行时会写的形状。修法是匹配前逐行丢掉注释行
（`ci_gates.py` 里那 3 行）。判据自己的 docstring 记了这次实测。

⚠️ **还剩一处同类形状未修**：`stream-ban-boundary-coverage`（第 1176 行，
`"ConnectionRegistry" not in text` 读 Java 文件）仍只做子串匹配，注释同样能满足 ——
记为新待办，先要想清楚"注释里出现类名算不算注册"。Gateway 这一处已随 Wave 1 落点
改为解析 `infrastructure/nacos/dinqor-gateway.yaml` 的结构化路由与 stream-proxy 字段。

#### 3.4.5 文档与允许清单动了哪些，以及为什么不新增契约变更条目

六个指标不动、没有端口形状变化、没有契约字段增删 —— `AuthInterceptor` 换依赖是**收窄**
（从域服务收窄到已有契约），不是新缝。按判据（"镜像可证纯重构，不进；收窄是新缝，要进"）
这里**既不是镜像也不是收窄**：端口本来就存在，这一刀只是把一个消费方接到它上面。
唯一进文档的是 `contract-changes.yaml` 里 `shared-kernel-boundary` 那条的
`current_violations`：`config/WebConfig` 那一项移进新的 `resolved_violations`。

---

## 3bis. 第一个业务契约：`contracts/content-api`

### 3bis.1 抽的是哪 4 个类，为什么是它们

`publishing.domain` 里的 `BlogPublisher` / `PublishCommand` / `PublishResult` /
`PublishingLimits` 迁到 `contracts/content-api`，包名 `com.xander.lab.content.api`。

判断依据不是"看起来像契约"，而是**它们的实现跨部署单元**：
`BlogPublisher` 的实现有 `LocalBlogPublisher`（内容域）与 `CsdnPublisher` /
`JuejinPublisher`（integration 域）。拆服务之后，integration 就变成"在实现一个内容域
拥有的接口" —— **依赖方向反了**，所以端口和它的数据必须搬到双方都能依赖的中立模块。

### 3bis.2 为什么 `BlogPostDTO` / `BlogPostVO` **不**跟着搬

它们在 `publishing.api`，已经是 api 层，扫描器**本来就**把它们算作契约面而不是待还的债
（`api_statements` 38 条，不计入 `implementation_statements`）。搬它们：

- **不改变任何一个被度量的数字**（§3bis.4 的数字全部来自 domain 层那 4 个类）；
- 却要改 26 个文件（含 `agent` / `blogagent` 的测试），把 diff 撑大。

更重要的是它们**受众不同**：`BlogPostDTO` / `BlogPostVO` 是**前端**（Web / 小程序）的
HTTP 契约，服务间契约是另一件事。

⚠️ 而且 `BlogPostVO` 现在**同时**是 `BlogPostMapper` 的投影目标
（`@JsonIgnore private String tagsRaw` 承接 `GROUP_CONCAT` 结果）。把它搬进契约模块之前，
必须先把"上线的形状"与"持久化投影"分开 —— 否则契约模块会带着一个只对 MyBatis 有意义的字段。
**搬它们的触发条件是内容域的 api 层自己拆分（Wave 2）**，不是"有空就搬"。

### 3bis.3 硬约束：零依赖

`contracts/content-api` 的 `<dependencies>` 里**没有任何编译期或运行期依赖**，
只有 test-scope 的 `junit-jupiter`。

这不是洁癖：契约要被**每个**服务依赖，包括 §19 里那个"被拖进 spring-webmvc 就启动失败"
的 WebFlux Gateway。一个长胖了的契约就是 §19 禁止的超级 common.jar 换了个名字。
新增编译期依赖不会有任何编译错误、所有测试照样绿，所以做成门禁
`contract-dependency-boundary`（两条判据，都注入验证过）：

1. 契约源码只允许 import JDK 与自己的包（`com.xander.lab.<其他>` 也不许 ——
   那意味着契约反过来指回业务域）；
2. 契约 POM 里除 test scope 外不得有依赖。

### 3bis.4 收益（重新生成基线后实测，与事前预测完全一致）

| 指标 | 抽之前 | 抽之后 |
|---|---|---|
| `implementation_statements` | 137 | **116**（−21） |
| `cross_deployment_unit_statements` | 103 | **85**（−18） |
| `cross_deployment_unit_edges` | 28 | 28（未变，没有边归零） |
| `api_statements` | 38 | 38（未变，证明 DTO/VO 确实没动） |

逐边变化（事前预测 22→5，实测一致）：

| 边 | 前 | 后 | 跨部署单元 |
|---|---|---|---|
| `integration → publishing` | 22 | **5** | 是 |
| `agent → publishing` | 10 | 9 | 是 |
| `automation → publishing` | 4 | 2 | 否（同属内容域） |
| `blogagent → publishing` | 6 | 5 | 否 |

剩下那 5 条是 `BlogService` / `PublicationService` 两个 `application` 层类型的引用 ——
它们是**实现**，不是契约，本来就该留到 Wave 2 按 §75 决定改成什么调用方式。

### 3bis.5 ⚠️ 记录而不解决：`BlogPublisher` 是一个跨进程会失效的 SPI

`PublicationService`（内容域）构造器注入 `List<BlogPublisher>`，按 `platform()` 收成 Map。
单体里 Spring 能把两个域的 bean 一起注入；**拆成两个进程之后这不可能成立**。

两条出路，都不改变接口形状，所以现在抽出来是安全的：

1. 发布适配器跟着端口走，并入内容域（即 CSDN / 掘金适配器归属改为内容自动化）；
2. 发布改成对外的 HTTP / 事件调用（integration 变成被调用方）。

**必须在 Wave 2 之前决定**，否则拆到一半会发现 `integration` 的实现注不进内容域。
另注意 §76 已经有一个同类未决问题（`/api/agent/mcp-servers` 的归属），两者应当一起裁。

---

## 3ter. 部署单元耦合图（Wave 2 的入手顺序由这张图决定）

§2.2 的进度表按"域 → 域"计数，但 Wave 2 拆的是**部署单元**，不是域。
把同一条基线按 `scan.DEPLOYMENT_UNIT` 聚合一次，得到真正要解的那张图：

| # | 方向 | 实现语句数 |
|---|---|---|
| 1 | `dinqor-agent` → `dinqor-content-automation` | 19 |
| 2 | `dinqor-agent` → `dinqor-core-host` | 14 |
| 3 | `dinqor-content-automation` → `dinqor-core-host` | 12 |
| 4 | `dinqor-content-automation` → `dinqor-platform-integration` | 6 |
| 5 | `dinqor-core-host` → `dinqor-content-automation` | 7 |
| 6 | `dinqor-platform-integration` → `dinqor-content-automation` | 6 |
| 7 | `dinqor-platform-integration` → `dinqor-agent` | 5 |
| 8 | `dinqor-platform-integration` → `dinqor-core-host` | 5 |
| 9 | `dinqor-core-host` → `dinqor-platform-integration` | 1 |
| 10 | `dinqor-agent` → `dinqor-platform-integration` | 2 |
| | **合计** | **77**（10 条有向边） |

⚠️ 本表在 §3quater 抽出 OSS 契约后**重新测量**过：`content-automation → platform-integration`
8 → 6、`core-host → platform-integration` 5 → 1、`agent → platform-integration` 4 → 2，
总数 85 → 77。**形状没有变** —— 仍是 10 条有向边、仍是同一个强连通分量，
所以 `deployment-unit-coupling` 门禁不受影响。这正是 §16.2 那句话的实证：
**契约线在减语句，不改形状**；要改形状得等某条边整体归零（三条域间边归零了，
但对应的单元间边还有别的域在撑着）。

### 3ter.1 图的两个性质

**性质一：四个单元构成一个强连通分量。** 互相依赖的对有 4 组
（`agent ↔ platform-integration`、`content-automation ↔ core-host`、
`content-automation ↔ platform-integration`、`core-host ↔ platform-integration`）。
另有两条**单向**边：`agent → content-automation`（19）与 `agent → core-host`（14），
没有反向边；但通过 `agent → content-automation → core-host → platform-integration → agent`
这条弦回路，四个单元仍然互相可达。

**这不是缺陷。** 拆分之后这些就是普通的服务间调用，"A 调 B 而 B 也调 A"很正常。
它的真实含义是：**Wave 2 没有"先摘叶子"的顺序可用**。每个单元都得同时处理
出边和入边，而契约模块正好能转换其中一条（让调用方依赖端口而不依赖实现）——
所以**能推动这张图的是契约线，不是 lib 线**。

**性质二：`bloghtml` 完全孤立。** 它在实现层面**一条边都没有**（入、出皆无）。
§3 把它排在 Wave 1 单独先搬，前提就是这个 —— 这是**前提，不是巧合**。
只要出现一条指向它或来自它的边，Wave 1 的单独抽取就要依赖一次没人排期的转换。

### 3ter.2 门禁 `deployment-unit-coupling`

上面两个性质用三条判据冻住（比 `cross-domain-import-baseline` 更关注图的**形状**，
后者只管**规模**）：

1. `bloghtml` 保持孤立；
2. 没有新单元加入这个强连通分量（分量用传递闭包算，≤10 个单元不值得引入图库）；
3. 没有新的互相依赖单元对（判据 2 更弱、更可操作：一组互相依赖的对是两条具体的语句，
   分量只是个摘要）。

反向也报：不再互相依赖的对会提示从 `MUTUAL_UNIT_PAIRS` 基线里删掉两个方向，
免得已经解开的纠缠继续藏在基线里。

三条判据都用**注入法**验证过（改代码 → 观察 FAIL → 恢复 → 观察 PASS）：

| 判据 | 注入方式 | 观察到的失败信息 |
|---|---|---|
| 1 | 给 `BlogHtmlPreviewService` 加一条指向 `agent` 的 import | `dinqor-bloghtml-preview has cross-unit implementation dependency/ies ['dinqor-bloghtml-preview -> dinqor-agent']` |
| 2 | 把 `recitation` 拆成独立单元 `dinqor-recitation` | `['dinqor-recitation'] joined the Wave 2 strongly-connected component` |
| 3 | 给 `EmailReminderService` 加一条指向 `agent` 的 import | `new mutually-dependent unit pair(s) [('dinqor-agent', 'dinqor-content-automation')]` |

⚠️ 判据 2 需要**第六个单元**才会触发（今天只有 5 个，`bloghtml` 孤立，
所以 `component - WAVE2_UNITS` 恒为空）。它是一条**前瞻护栏**：
当 `payment-api` / `knowledge-api` 这类契约模块将来对应出新的部署单元时，
这个门禁会立刻发现它落进了这团纠缠里。验证时用 `recitation` 临时造了第六个单元。

⚠️ **判据 3 的第一版是错的**：我最初用推导式生成四个单元的全部 12 个有序对当基线，
门禁立刻报 `4 direction(s) cleared` —— 那是**我的基线错了，不是代码错了**
（`agent → content-automation` 和 `agent → core-host` 本来就是单向的）。
已改成硬编码真实的 8 个有序对。

---

## 3quater. 第二个业务契约：`contracts/integration-api`（OSS 端口）

### 3quater.1 与 `content-api` 的根本差别：这次不是"搬"，是"抽端口"

`content-api` 是**搬家**：`BlogPublisher` 本来就是一个接口，连着它的 DTO 一起换个包就行。
`integration-api` 不是 —— `integration` 域被跨域引用的 7 个类型**没有一个是接口**：

| 类型 | 被谁引用 | 形态 | 能直接搬吗 |
|---|---|---|---|
| `oss.OssService` | agent / knowledgebase / publishing / recitation | 具体类（阿里云 SDK、`@Value`、懒建客户端） | ✗ 要抽端口 |
| `oss.UploadResponse` | 同上 4 个 | 纯 DTO（lombok） | ✓（去掉 lombok 后） |
| `mcp.csdn.application.CsdnCredentialService` | automation | 具体类（MyBatis mapper + javax.crypto） | ✗ |
| `mcp.juejin.application.JuejinCredentialService` | automation | 具体类（同上 + Jackson） | ✗ |
| `mcp.juejin.application.JuejinBlogSyncService` | agent / automation | 具体类，且 import 了 `publishing.application.*` | ✗ |
| `mcp.csdn.application.CsdnBlogSyncService` | agent | 具体类，同上 | ✗ |
| `mail.MailService` | automation / identity | 具体类，且 import 了 **`automation.domain.EmailReminderMailTemplates`** | ✗ **被业务域依赖挡住** |

所以本轮只做了能做的两个（合计 −8 条），并把"为什么剩下 5 个不能直接搬"写清楚 ——
这比硬搬 5 个具体类要好：把 `CsdnCredentialService` 搬进契约会连 MyBatis-Plus
和 javax.crypto 一起拖进去，契约当场变成 §19 禁止的超级 jar。

### 3quater.2 端口签名：`byte[]` 而不是 `MultipartFile`

`OssService` 原本有 3 个公开方法：

```java
UploadResponse upload(MultipartFile file, String pathPrefix);                          // 流式
UploadResponse upload(byte[] bytes, String originalName, String contentType, String prefix);
boolean        deleteByUrlQuietly(String url);
```

`MultipartFile` 是 **spring-web 类型**，放进契约会让 spring-web 出现在每个依赖契约的服务
（含 §19 那个 WebFlux Gateway）的 classpath 上，而且跨进程时**没有 MultipartFile 可传**。
所以端口只收 `byte[]`；`upload(MultipartFile, String)` 作为**本域内的便捷重载**留在
`OssService` 上（只有本域的 `FileController` 用它）。

⚠️ **这是一次真实的行为改动，必须留痕**：三个跨域调用方从"把 `MultipartFile` 的输入流
直接交给 SDK"变成"先 `getBytes()` 读进内存"。三点理由说明可接受：

1. 三个调用方都在上传前卡了大小上限（agent 20MB、publishing 10MB、recitation 有校验），
   所以读进内存是**有界**的；
2. 同域内已经有一处这么做了 —— `KnowledgeFileService` 一直在用 `byte[]` 重载；
3. 跨进程之后本来也只能传字节。

⚠️ **顺带修掉一条误导性日志**：`byte[]` 重载的失败信息原本写死
`"智能体图片上传至云存储失败"`，但这条方法现在服务 4 个域（智能体附件、知识库文件、
博客媒体、背诵音频）。已改成中立的 `"文件上传至云存储失败"`，并保留 cause。
没有测试断言过这条文案，改它是安全的。

### 3quater.3 `UploadResponse`：手写而不是 lombok

契约 POM 只能有 test-scope 依赖，源码只能 import JDK（门禁 `contract-dependency-boundary`），
所以 `@Data @Builder @NoArgsConstructor @AllArgsConstructor` 全部去掉，改成
**显式 final 类 + 显式 getter + 规范构造器**。

- 保留 `getUrl()` 这套前缀是刻意的：22 处调用点（4 个域）因此**一行都不用改**，
  JSON 字段名也完全不变。改成 record 会让 22 处访问器全变（`url()`），diff 更大且没有收益。
- 去掉 `builder()` 只影响 4 处构造点（`OssService`×2、`FileService`、1 个测试）。
- 新增 `UploadResponseTest`：构造器的参数顺序就是契约的字段顺序，而
  `url` / `originalName` / `storedName` **三个相邻的 String** 重排不会有任何编译错误，
  所以用互不相同的字面量逐个断言钉住它（§11：字段改动属于契约改动）。

### 3quater.4 收益（重新生成基线后实测）

| 边 | 实现语句 | 跨部署单元 |
|---|---|---|
| `knowledgebase → integration` | 2 → **0** | 边归零 |
| `publishing → integration` | 2 → **0** | 边归零 |
| `recitation → integration` | 2 → **0** | 边归零 |
| `agent → integration` | 4 → 2 | 仍跨单元（剩下的是 CSDN / 掘金 同步服务） |
| **合计** | **116 → 108** | **85 → 77**（−8） |

    implementation_statements          116 → 108  (-8)
    cross_deployment_unit_statements    85 →  77  (-8)
    cross_deployment_unit_edges         28 →  25  (3 条边归零)
    api_statements                      38 →  38  (未变)

三条边直接归零 —— 这是契约抽取第一次让**边数**下降（`content-api` 那次 28 → 28 没变）。

### 3quater.5 ⚠️ 剩下的 5 个类型：为什么不能直接搬

**CSDN / 掘金 的凭证与同步服务（4 个）**：都是具体类，内部依赖 MyBatis-Plus mapper、
`javax.crypto`、Jackson、以及各自的 `infrastructure` 包。搬它们必须先抽端口
（`CredentialPort` / `BlogSyncPort`），也就是本轮对 OSS 做的同一件事。而
`JuejinBlogSyncService` / `CsdnBlogSyncService` 还 import 了
`publishing.application.PublicationService` 与 `BlogService` —— 那是**反向**的跨单元依赖，
属于另一条边（`integration → publishing`，已在 `content-api` 那次降到 5 条）。

> ⚠️ **草稿订正（事后补）**：这里写的 `CredentialPort` / `BlogSyncPort` 都是**单数**，
> 实际两样都落成了**两个端口**（每个平台一个）：
> `CsdnBlogSyncPort` / `JuejinBlogSyncPort`（第十六刀）与
> `CsdnCredentialPort` / `JuejinCredentialPort`（第十九刀）。
> 判据是**参数表**：只有当合并后的签名对**每一个**实现都是它真正需要的形状时才合并
> （§3duodevicies.2 与 ADR 待办 38）。
> ⇒ **侦察阶段的端口草稿只写能力名，不要写死个数** —— 个数由调用点的参数表决定，
> 而那要等数清跨域表面之后才知道。

**`MailService`：被一处业务域依赖挡住，搬不动。** 它 import 了
`com.xander.lab.automation.domain.EmailReminderMailTemplates` —— 一个**业务域的类型**。
契约不能 import 业务域（门禁判据一），所以要么先把模板挪到别处、要么让调用方把
渲染好的内容传进来。**在解开这处依赖之前，`MailService` 无论如何都进不了契约。**

### 3quater.6 ⚠️ 记录而不解决：端口解决了"依赖方向"，没解决"跨进程"

`OssPort` 让 4 个域不再依赖 `integration` 的**实现类**，这是实打实的进步：
拆分之后它们仍然编译得过，只需要换一个 `OssPort` 的实现（HTTP 客户端桩）。
对比 `content-api` 里那个 `BlogPublisher`（§3bis.5）—— 那个连端口形状都没定，
Spring 的 `List<BlogPublisher>` 注入在跨进程时直接失效。**端口先立起来，替换就成了装配问题。**

但要说清楚：**本轮没有让任何东西变成真正的服务间调用**。4 个域仍在同一个进程里，
拿到的是同一个 `OssService` bean。真正的跨进程调用是 Wave 2 的事。

---

## 3quinquies. 第三个业务契约：`contracts/core-api`（模型路由 SPI）

### 3quinquies.1 又一个"搬"，而且是本轮最干净的一次

`ModelGateway` 被跨域引用的 2 个类型**本来就是接口与 record**，
不像 `integration` 那样需要先造端口：

| 类型 | 被谁引用 | 形态 | 处理 |
|---|---|---|---|
| `admin.application.ModelGateway` | agent / blogagent / recitation / studio | **接口** | 搬 |
| `admin.application.ModelGateway.ModelEndpoint` | 同上 4 个 | **record**（嵌套） | 跟着搬 |
| `admin.application.FeatureModelNotConfiguredException` | blogagent（catch）/ `GlobalExceptionHandler` | `RuntimeException` 子类 | 跟着搬 |

判据仍是 §3quater.1 那条：**实现跨部署单元**。实现（`ModelGatewayService`，
读 `model_provider` / `model_feature_config` 两张表）留在 `dinqor-core-host` 的 admin 域，
调用方却横跨三个部署单元 —— agent（`dinqor-agent`）、blogagent
（`dinqor-content-automation`）、recitation + studio（`dinqor-core-host`）。

### 3quinquies.2 异常跟着接口走，不跟着实现走

`FeatureModelNotConfiguredException` 不是"实现的意外"，而是**接口的约定行为**：
网关开着、某功能没有可用端点时就抛它，调用方**必须向上传播而不回退到环境变量**。
blogagent 的图片客户端 `catch` 它，`GlobalExceptionHandler` 按类型映射成 HTTP 400。
留在 admin 域只会白白多留一条跨单元 import，所以三个类型一起走。

### 3quinquies.3 收益（重新生成基线后实测）

```
agent      -> admin   2 -> 0     （边归零，跨部署单元）
blogagent  -> admin   4 -> 0     （边归零，跨部署单元）
recitation -> admin   2 -> 0     （边归零，同单元）
studio     -> admin   2 -> 0     （边归零，同单元）

implementation_statements          108 -> 98  (-10)
cross_deployment_unit_statements    77 -> 71  (-6)
cross_deployment_unit_edges         25 -> 23  （2 条跨单元边归零）
api_statements                      38 -> 38  （未变）
```

⚠️ **`admin` 作为被依赖目标的实现依赖现在归零**：四条入边**整条消失**，
而不只是数字变小。剩下唯一一条 `virtualpay → admin` 是 `api_statements`
（`admin.api` 层的 import），那是**正在建的契约面**，不是待还的债。

⚠️ 与 §3ter 一致：耦合图 71 条但**形状未变**（仍 10 条有向边、同一强连通分量），
所以 `deployment-unit-coupling` 门禁不受影响。

### 3quinquies.4 ⚠️ 带点条目的盲区：这一次**只有门禁**能发现问题

`integration.api` 那次（§6.1）已经知道：登记裸域名会让契约"隐形"。
这次做了对照实验，把结论说得更准 —— **`core.api` 从 `SHARED_KERNEL` 里删掉之后，
所有指标一个都不动**：

```
登记 core.api：impl 98 / cross 71 / api 38   unclassified_sources = [DinQorAIApplication.java]
删掉 core.api：impl 98 / cross 71 / api 38   unclassified_sources = [DinQorAIApplication.java, core]
```

也就是说，**契约包的包名前缀不是业务域名时（`core` 不在 `BUSINESS_DOMAINS` 里），
扫描器既不会把它当业务域算进边，也不会把它当共享内核排除 —— 那些 import 只是
被静默丢掉，而数字与"正确"状态完全一致。** 唯一的信号是
`unclassified_sources` 多出一个 `core`，以及 `shared-kernel-classification` 门禁的 FAIL：

```
FAIL  shared-kernel-classification  module(s) ship package(s) core.api (contracts/core-api)
      that SHARED_KERNEL does not cover -- imports of them would be dropped from the
      cross-domain baseline without any coupling being removed.
```

**结论：对带点条目，门禁是唯一的保护，指标完全靠不住。**
不要因为"数字好看"就跳过门禁。`core.api` 已登记（`scan.py` 的 `SHARED_KERNEL`）。

### 3quinquies.5 与 `agent-api` 的关系：那个契约还卡在 Jackson 上

本轮**没有**顺手动 `agent` 域那 4 个被 `integration` 引用的类型
（`AgentTool` / `AgentToolRegistry` / `AgentDynamicToolSource` / `AgentPromptSection`），
原因是它们**不都是纯 JDK**：

- `AgentToolRegistry` 是 `@Component` **具体实现**（Spring + Jackson），根本不能进契约；
- `AgentTool` 虽然是接口，但 `parametersSchema()` 返回 **`com.fasterxml.jackson.databind.JsonNode`**；
- 只有 `AgentDynamicToolSource`（接口）与 `AgentPromptSection`（record）是纯 JDK。

`payment-api` 也卡在同一处：`BillingService.chargeTokens(...)` 收
`com.xander.lab.common.ModelUsage`，而它住在 `libs/xander-model`、带 Jackson 依赖
（`ModelUsage.fromJson(JsonNode)`）。

所以这是一个**必须一起回答的问题**，两个契约都等它：

> 契约的依赖边界到底是"只许 JDK"，还是"JDK + 无框架的共享内核库（`xander-model`）"？

三个可选方向，**本轮未定**：

1. **维持 JDK-only**，契约自带等价 DTO（`TokenUsage(inputTokens, cachedTokens, outputTokens)`），
   调用方在边界转换。代价：`chargeTokens` 的 8 个调用点各加一次转换；
   `AgentTool` 的 `JsonNode` 无处可去，只能把 `parametersSchema()` 改成 `String`（调用点更多）。
2. **放宽到"JDK + `xander-model`"**，把 `CONTRACT_ALLOWED_IMPORT_PREFIXES` 从
   `('java.',)` 改成允许 `com.xander.lab.common.`。代价：契约开始传递 Jackson
   （WebFlux Gateway 本来就有 Jackson，实际无害），但"契约零依赖"这条不变量没了。
3. **按类型分开**：数据形状纯 JDK 的走方向 1，签名里真的有 `JsonNode` 的走方向 2。
   代价：判据变成"看情况"，而"看情况"正是 §13 要避免的。

**建议方向 1**，因为它保住了"契约可被任何服务依赖"这条硬不变量，而代价是机械的
（转换点可数、可用测试钉住）。但这是设计决策，不应当在实现里顺手做掉。

> **✅ 已拍板（2026-09-17，见 `adr-contract-boundaries.md` §1 ADR-1）：方向 1。**
> 契约维持 JDK-only，自带等价 DTO。`payment-api` 那一半已按方向 1 落地，
> 实测记录在本文 **§3octies**：8 个调用点里只有 4 个真的需要换算，
> 而且实现侧退化成一次类型替换。`agent-api` 那一半**仍未做**。
>
> 被明确否决的是方向 2 —— `CONTRACT_ALLOWED_IMPORT_PREFIXES` **不得**为了过门禁而放宽；
> 方向 3 被否决的理由不变（"看情况"就是 §13 要避免的）。

---

## 3septies. 扩展 `contracts/core-api`：`UserLookupPort`（用户展示名端口）

### 3septies.1 第一次"扩展现有契约"，不是新建模块

前四刀都是新建 `contracts/<name>/`，每次要改四处（根 POM `<modules>`、应用 POM 依赖、
`Dockerfile` 的 `COPY`、`scan.py` 的 `SHARED_KERNEL`）。这次只是往已有的 `core-api`
里加一个类型，**四处都不用动** —— 模块已存在、已接线、包已登记。

**记下来**：后续几刀如果落在已建的 4 个契约里（`content-api` 承接 `publishing`、
`core-api` 承接 `identity`、`payment-api` 承接 `billing` 的端口），构建接线是零成本。
真正要新建的只有 `agent-api` / `automation-api` / `knowledge-api` / `studio-api`。

### 3septies.2 判据：两个调用方在做**同一件事**

抽端口的信号不是"这个类是具体实现"（那只是必要条件），而是
**两个不同域各自写了一遍同样的代码**：

```java
// publishing.application.BlogService#resolveAuthor
User user = userMapper.selectById(userId);
if (user == null) return "User " + userId;
if (user.getNickname() != null && !user.getNickname().isBlank()) return user.getNickname();
if (user.getUsername() != null && !user.getUsername().isBlank()) return user.getUsername();
return "User " + userId;

// components.application.ComponentService#shareComponent
User user = userMapper.selectById(userId);
if (user != null) authorName = user.getNickname() != null ? user.getNickname() : user.getUsername();
```

两处都注入 `identity.infrastructure.UserMapper`（MyBatis mapper）和
`identity.domain.User`（`sys_user` 实体），都只为了取一个展示名。
这直接违反 identity 自己的 `package-info`（"feature modules ... must not parse tokens
or query the user table directly"），而且 `publishing` 在 dinqor-content-automation、
`identity` 在 dinqor-core-host —— **跨了部署单元**，拆分后就是"一个服务直连另一个服务的库表"。

**判据补充（已进 skill）**：看见两个域各写一遍同样的查询/映射逻辑，
那基本就是一个端口的位置；单看"它是不是具体实现"会漏掉这种。

### 3septies.3 端口只回答**问题**，不替调用方决定**显示策略**

两个调用方的兜底**不一样**：博客用 `"User " + userId`，组件库用 `"匿名用户" + userId`。
所以端口不返回兜底文案，只返回"展示名或 `null`"：

```java
public interface UserLookupPort {
    String displayName(Long userId);
}
```

把兜底塞进端口会逼两个域接受同一种文案 —— 那是**显示策略**，不是 identity 的数据。
**规矩**：端口回答数据问题；格式、兜底、占位符留在调用方。

### 3septies.4 ⚠️ 抽取时发现一处行为差异，而且**没有任何测试覆盖**

两个实现的判据其实不一样：

| | 旧 `BlogService` | 旧 `ComponentService` |
|---|---|---|
| 判据 | `nickname != null && !nickname.isBlank()` | `nickname != null` |
| `nickname = ""` 时 | 落到 `username` | **返回空串** |

也就是说，昵称为空串时组件库会把**空作者名**显示出来 —— 一个真实缺陷。
端口统一用"非空白"判据，所以这是**行为改动**（按 skill 规矩必须在提交信息里写明）。

⚠️ 关键点：`ComponentService` **没有任何测试**，作者名也**没有任何断言**
（已 grep 确认）。所以这个差异如果不去读代码，谁都不会发现；
换判据也不会有任何测试变红。**这也是为什么要为端口本身补测试** ——
新判据只此一份，必须被钉住，否则下一次有人"顺手简化"成 `!= null`，缺陷就悄悄回来了。

### 3septies.5 ⚠️ 端口宽度是**没有任何东西在守**的不变量

端口和普通接口的区别：普通接口改签名会编译报错，端口**变宽**不会。
给 `UserLookupPort` 加一个 `findById` / `getEmail` / `listUsers`：

- 编译**通过**（实现类不用改，加 `default` 就行；不加也可以只是实现类要补）；
- 指标**不动**（`api_statements` 数的是调用点的 import，不是接口方法数）；
- 门禁**不报**（`contract-dependency-boundary` 只看 import 和 POM 依赖）。

于是"用户查询端口"会慢慢长成所有人绕过 identity 边界的新入口，全程无人报警。
所以用反射把**方法个数**钉住：

```java
assertEquals(1, UserLookupPort.class.getDeclaredMethods().length,
        "端口变宽了。它存在的意义是收窄 identity 的暴露面……");
```

**注入验证**：给端口加一个 `default String email(Long)` → 编译仍然通过、
其余 9 条 `ModelGatewayTest` 全绿，**只有这一条红**。这证明它守的是别人守不住的东西。
（已进 skill。）

### 3septies.6 收益（重新生成基线后实测）

```
publishing -> identity   2 -> 0   （跨部署单元，边消失）
components -> identity   2 -> 0   （同单元，边消失）

implementation_statements          90 -> 86  (-4)
cross_deployment_unit_statements   64 -> 62  (-2)
implementation_edges               35 -> 33  （2 条边消失）
cross_deployment_unit_edges        23 -> 22
api_statements                     38 -> 38
```

⚠️ 4 条移除里只有 2 条是跨单元的（`components` 与 identity 同属 dinqor-core-host）。
⚠️ **耦合图形状未变**：仍 10 条有向单元边、同一个 SCC，
`content-automation -> core-host` 从 6 降到 4 但没有归零（`identity` 的入边还有
`virtualpay` / `admin` / `agent` / `integration` / `profile` 撑着）。

### 3septies.7 下一目标：`BlogMediaService` + `BlogMediaAsset`（需要决定）

`publishing` 是剩余最大目标（24 条 / 17 跨单元），其中 **`agent -> publishing` 9 条是
全仓最大的剩余跨单元边**。分类过一遍后确认：**`publishing` 的 4 个跨域类型没有一个是
"能直接搬"的**（全是具体 `@Service` 或 MyBatis 实体），所以必须抽端口。

最值得做的是 `BlogMediaService` + `BlogMediaAsset`（合计约 11 条）：

- `BlogMediaAsset` 是 **MyBatis 实体**（`@TableName("blog_media_asset")`），却被
  agent / blogagent / studio 三个域 import，主要用于接住 `BlogMediaService` 的返回值；
- `BlogMediaService` 的 6 个跨域方法里，`uploadImage` 收 `MultipartFile`（spring-web）、
  `saveAgentImage` 收 `BlogAgentImageClient.GeneratedImage`（**blogagent 的类型**）——
  后者意味着 publishing 反过来 import blogagent，**这是一个环**：
  `publishing.application.BlogMediaService` → `blogagent.infrastructure.BlogAgentImageClient`。

**但它不能顺手做，因为有一处 §11 决定要做**：`BlogMediaAsset` 是**前端可见**的 ——
`BlogMediaController` 的 `GET /images` 与 `POST /images` 直接返回
`Result<BlogMediaAsset>`，`BlogAgentTaskVO.illustrations` 也是 `List<BlogMediaAsset>`
（由 `BlogAgentService:396` 填充）。换成 DTO 会改 HTTP 响应形状，
属于契约变更，要么把 DTO 的字段名与实体**逐一镜像**并用测试钉住序列化结果，
要么走 §11 流程。**这是一个决定，不是重构的顺带产物。**

### 3septies.8 这一刀已经做完（2026-09-17）

决策落定为"**镜像**"：`MediaAssetRef` 13 个字段与实体逐一同名同类型，
所以序列化出的 JSON 与改造前完全一致 —— 这次提交可以被证明是**纯重构**，
`docs/contract-changes.yaml` **故意没有**加条目（判据见
[`adr-contract-boundaries.md`](adr-contract-boundaries.md) §2.5 / §2.6）。

**没有新建 `publishing-api`**：`contracts/content-api` 本来就是发布契约
（已装 `BlogPublisher` / `PublishCommand` / `PublishResult` / `PublishingLimits`），
按 §91「契约按能力命名」扩展它即可 —— 因此**不用改构建接线**。

新增三个契约类型：`MediaAssetRef`（引用）、`StoredImage`（见下）、
`MediaAssetPort`（6 方法 + `MAX_IMAGE_BYTES` 常量）。

环的拆法：端口按内容域**真正用到**的字段收参，只取
`generated.bytes()` 与 `generated.contentType()`，于是
`GeneratedImage` 的 import 消失。**它不能搬进契约**，因为它带
`com.xander.lab.common.ModelUsage`（有 `fromJson(JsonNode)`，Jackson 泄漏）。

`StoredImage` 存在的原因是**契约不能依赖契约**：最省事的写法是复用
`integration.api.UploadResponse`，但 `contract-dependency-boundary` 只允许
JDK + 本包，且拒绝任何非 test 作用域的 POM 依赖。

实测收益：

```
agent      -> publishing    9 -> 3     （跨部署单元，仍是全仓最大剩余跨单元边）
blogagent  -> publishing    5 -> 2     （同单元）
studio     -> publishing    2 -> 0     （跨部署单元，边消失）
publishing -> blogagent     1 -> 0     （同单元，边消失 —— 环断了）

implementation_statements          86 -> 74  (-12)
implementation_edges               33 -> 31
cross_deployment_unit_statements   62 -> 54  (-8)
cross_deployment_unit_edges        22 -> 21
```

⚠️ 12 条里只有 8 条跨单元。**耦合图形状未变**（`deployment-unit-coupling`
仍报 4 个单元同一个 SCC）—— `studio -> publishing` 归零**没有**让 studio 变叶子。


## 3sexies. 第四个业务契约：`contracts/payment-api`（计费词表）

### 3sexies.1 为什么是 `payment-api` 而不是 `billing-api`

§18 给的 8 个模块名是固定的，里面没有 `billing-api`。被搬的类型住在 `billing` 域，
但契约名是 `payment`，于是包名是 `com.xander.lab.payment.api`。

这不是"换个名字"的小事：`payment` 既不是业务域（域叫 `billing` / `virtualpay`），
也不在 `SHARED_KERNEL` 里 —— 它**必须是带点条目** `payment.api`，理由与 §6.1 相同。
顺带说明 §18 的命名是按**能力**（payment）而不是按**现有模块**（billing）来的，
这正是 §91 想要的效果：契约名不跟着当前代码结构走。

### 3sexies.2 又一次"搬"，而且这次**验证方式**也值得记下来

`BillingBiz` 是 final 常量类、私有构造器、**零 import**，所以搬运不碰任何行为。
但"不碰行为"这件事本轮不是靠眼睛确认的，而是靠一次**字面量整体比对**：

```bash
diff <(git show HEAD:<旧路径> | grep -oE '"[a-z0-9-]+"' | sort) \
     <(grep -oE '"[a-z0-9-]+"' <新路径> | sort)
# -> 无输出，LITERALS IDENTICAL
```

**规矩**：搬常量类 / 枚举类时，"看起来一样"不算验证。用 `git show HEAD:<path>`
取出原文，抽出字面量排序后 diff。这样"漏抄一个常量"和"抄错一个字母"都会当场暴露，
而这两种错误都**不会**被编译器发现（少一个常量：只要没人用就编译得过；抄错字母：字符串）。

### 3sexies.3 契约测试用**整体比对**而不是逐条断言

`BillingBizTest` 用反射取出**全部** `public static final String` 字段，与预期 `Map`
整体 `assertEquals`：

```java
for (Field f : BillingBiz.class.getDeclaredFields()) {
    if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) continue;
    if (f.getType() != String.class) continue;
    found.put(f.getName(), (String) f.get(null));
}
assertEquals(EXPECTED, actualConstants());
```

**为什么不用逐条 `assertEquals`**：逐条断言**漏掉"新增了一个常量但没人回来补断言"**
这种情况，而那恰恰是最可能发生的 —— 新增计费项时，人会记得改常量、不会记得改测试。
整体比对让新增 / 改名 / 删除三种改动**全部**失败，不需要任何人记得补一行。

⚠️ 这也是为什么这条测试不是"常量等于自己"的空测试：这些字符串是
`points_ledger.biz_type` 的列值，改名**不会编译报错**，只会让库里已有的流水行静默失配
（统计按旧值查不到、新扣费照常写入）。属 §2.3"静默降低既有能力"。

⚠️ 另外钉住了一处**跨模块耦合**：`BillingService.isCreditBiz()` 按
`GRANT_REGISTER` / `MERGE_CREDIT` 判断一笔流水是收入还是消耗（决定余额方向）。
新增一个"收入类"业务项而忘了同步那处判断，余额方向会反 —— 运行期才暴露。
测试里把"哪两个是收入类"显式钉住，改动者至少会在测试名里看到这件事。

### 3sexies.4 收益（重新生成基线后实测）

```
agent      -> billing   6 -> 3   （跨部署单元）
integration-> billing   4 -> 2   （跨部署单元）
automation -> billing   2 -> 1   （跨部署单元）
blogagent  -> billing   2 -> 1   （跨部署单元）
studio     -> billing   2 -> 1   （同单元，dinqor-core-host 内部）

implementation_statements          98 -> 90  (-8)
cross_deployment_unit_statements   71 -> 64  (-7)
cross_deployment_unit_edges        23 -> 23  （无变化）
api_statements                     38 -> 38
```

⚠️ **8 条语句移除、但只有 7 条是跨单元的**：`studio → billing` 的 1 条是同单元边。
别把"移除 8 条"直接写成"跨单元 −8"。

⚠️ **没有一条边归零**，因为 `billing` 的入边同时被 `BillingService` 撑着 ——
7 个域仍各自 import 那个具体 `@Service`。所以本轮**不需要**从基线里删边，
`at_zero` 为空。这与 `core-api` 那次（四条边全归零、`admin` 入边清空）形成对照：
**词表搬走了，行为没有搬走。** 契约能搬走的只有数据形状，剩下的必须靠端口。

### 3sexies.5 ⚠️ 带点条目的盲区：第三次确认，而且这次最强

把 `payment.api` 从 `SHARED_KERNEL` 删掉做对照实验：

```
登记 payment.api：impl 90 / cross 64 / api 38   unclassified_sources=[DinQorAIApplication.java]
删掉 payment.api：impl 90 / cross 64 / api 38   unclassified_sources=[DinQorAIApplication.java, payment]
```

**三个指标逐字节相同**，而耦合一点没变 —— 那些 import 被**静默丢掉**了。
唯一信号是 `unclassified_sources` 多出 `payment`，以及
`shared-kernel-classification` FAIL：

> module(s) ship package(s) payment.api (contracts/payment-api) that SHARED_KERNEL does not
> cover -- imports of them would be dropped from the cross-domain baseline without any
> coupling being removed.

这是三次实验里最干净的一次，因为 `core` 那次至少还多一个 `unclassified_sources` 条目能看出端倪，
而这里的"错状态"与"对状态"在**全部三个头条数字上完全一致**。
**结论重申（§6.1 / §3quinquies.4）：对带点条目，门禁是唯一的保护，指标完全靠不住。**

### 3sexies.6 ⚠️ 顺手修掉一个门禁自身的缺陷：pass 消息混了基线与实时

`cross-domain-import-baseline` 的通过消息原来是：

```python
detail = (f"{recomputed} implementation statements across {len(base_edges)} edges "
          f"({live['counts']['cross_deployment_unit_statements']} cross a deployment unit)")
```

`recomputed` 来自**基线文件**，`cross_deployment_unit_statements` 来自**实时扫描**。
两个数描述的是**两个不同时刻**，拼成一句话就**不对应任何一个真实状态** ——
本轮它打印的是 "98 implementation statements ... (64 cross a deployment unit)"，
而当时真实状态是 90/64。更糟的是：**它永远显示不出"缩减"**，
而缩减正是这个门禁的 docstring 说自己要追踪的东西
（"the Wave 0B progress number stops meaning anything"）。

已改为两个数都取实时值，并在与基线不同时附一句 `; baseline was N`：

```
PASS  cross-domain-import-baseline   90 implementation statements across 35 edges
                                     (64 cross a deployment unit)
```

**规矩（已进 backend-norms skill）**：门禁的 pass 消息里**所有数字必须取自同一时刻**。
想要"对比"就显式写成两个字段（`X; baseline was Y`），不要在一个句子里混。
做注入验证时也要**专门测 pass 路径**：只测 FAIL 路径的话，一个永远显示错数字的
pass 消息会一直藏在"全绿"里 —— 它不影响门禁的判定，只影响人读到的东西，
而人读到的东西决定了下一步做什么。

---

## 3octies. 第五刀：`BillingService` → `payment.api.BillingPort`（ADR-1 首次真实应用）

### 3octies.1 这一刀回答的是"卡住两个契约"的那个问题

`§3quinquies.5` 列出的三选一（JDK-only / JDK+xander-model / 看情况）由 **ADR-1** 拍板：
**JDK-only，契约自带等价 DTO**。`payment-api` 是第一个真实吃到这个代价的地方，
所以这里的实测数字同时也是 ADR-1 成本的第一个样本。

### 3octies.2 卡点只有一处，而它在端口形状里可以绕开

7 个方法里只有 `chargeTokens` 卡住，因为它收 `ModelUsage`。而 `ModelUsage` 本身
**完全干净**（`record (long, long, long)`，零注解）—— 它只是**住错了模块**
（`libs/xander-model` 为 `fromJson(JsonNode)` 声明了 `jackson-databind`）。

于是这一刀的关键不是"怎么绕开 Jackson"，而是**选一个与现有实现同形的端口形状**：

```java
public record TokenUsage(long inputTokens, long cachedTokens, long outputTokens) { ... }
```

访问器与 `ModelUsage` 同名（`inputTokens()` / `cachedTokens()` / `outputTokens()` /
`isEmpty()`），所以 `BillingService` 的方法体**一行没动**，只换了参数类型：

```java
public class BillingService implements BillingPort {
    @Override @Transactional
    public void chargeTokens(..., TokenUsage usage, String model, String remark) { /* 原样 */ }
    private long tokenToPoints(TokenUsage usage, String model) { /* 原样 */ }
}
```

**记下来**：抽端口时"端口形状"是设计自由度。形状选得与实现同形，实现侧就退化成类型替换；
选得不一样，实现侧就要写适配层 —— 而适配层是新的出错面。

### 3octies.3 换算只落在 4 个类里，不是 8 个

| 调用方 | 是否需要换算 |
|---|---|
| `AgentConversationService` | 是 —— 内部 `UsageAccumulator.sum()` 直接返回 `TokenUsage`（全类唯一一处） |
| `BlogAgentService` | 是 —— 私有静态 `billable(ModelUsage)`，覆盖 4 个调用点 |
| `ImageGenerationTool` | 是 —— 调用点内联 |
| `Img2ThreeService` | 是 —— 调用点内联 |
| `PublishPostTool` | 否（`chargeFlat` + `flatPoints`） |
| `EmailReminderScheduler` | 否（同上） |
| `CsdnMcpApplicationService` | 否（同上） |
| `JuejinMcpApplicationService` | 否（同上） |

**§3quinquies.5 里估的"8 个调用点各加一次转换"偏保守**：一口价计费那条路径
（4 个调用方、共 5 个调用点）完全不碰 `usage`。真实成本是 3 个类 + 1 处内部汇总。

### 3octies.4 ⚠️ 一处真实换位：`TokenUsage` 顺序 ≠ `recordAndDeduct` 顺序

`recordAndDeduct` 收 `(..., Long input, Long output, Long cached, ...)`，
`TokenUsage` 的分量是 `(input, cached, output)` —— **后两个正好相反**。
`chargeTokens` 里那句 `recordAndDeduct(..., usage.inputTokens(), usage.outputTokens(),
usage.cachedTokens(), ...)` 就是这处对调。

写错的表现很阴：**扣费金额照旧**（金额走 `tokenToPoints`，只读前两个分量），
只有流水的 `output_tokens` / `cached_tokens` 两列互换 —— 对账时金额对得上、明细对不上。
新增 `BillingServiceTest.tokenUsagePositionsLandInTheRightLedgerColumns`，
用三个互不相同的值钉住三列。

### 3octies.5 端口测试这次钉**完整签名**，不只是方法名

`MediaAssetPortTest` 只钉方法名（参数全是单一含义的，`byte[]` / `String` 不构成相邻混淆）。
`BillingPortTest` 必须多钉一层：`chargeTokens` 头四个参数是
`(Long, String, String, String)` —— **三个相邻的 `String`**（业务项 / 幂等键 / 业务引用）。
换位不报错，表现是"幂等键写到 `biz_type` 列上"，于是同一笔业务重复扣费、或对账查不到。

手法：用 `getSimpleName()` 拼出 `"void(Long,String,String,String,TokenUsage,String,String)"`
这样的字符串做整体比对 —— 既能区分装箱/原始类型（`chargeFlat` 的 `points` 是原始 `long`），
又不至于长到读不动。

**注入验证（2026-09-17 实测）**：加一个 `default String debugBilling()` 后，
反应堆编译通过、`BillingBizTest` 6/6 与 `TokenUsageTest` 9/9 全绿，
**只有 `BillingPortTest` 红 3 条**（方法数 7→8、签名表、`default` 检查）。

### 3octies.6 收益（重新生成基线后实测）

```text
agent       -> billing      3 -> 0   (跨单元)
integration -> billing      2 -> 0   (跨单元)
automation  -> billing      1 -> 0   (跨单元)
blogagent   -> billing      1 -> 0   (跨单元)
identity    -> billing      1 -> 0   (同单元)
profile     -> billing      1 -> 0   (同单元)
studio      -> billing      1 -> 0   (同单元)

implementation_statements          74 -> 64   (-10)
implementation_edges               31 -> 24   (-7)
cross_deployment_unit_statements   54 -> 47   (-7)
cross_deployment_unit_edges        21 -> 17   (-4)
api_statements / api_edges         38 / 11 (不变)
```

**7 条边全部归零**（含 3 条同单元的）。原因：`payment.api` 已在 `SHARED_KERNEL` 里，
"业务域 → 共享内核"的 import 不计入跨域边 —— 与 §3septies 的 `UserLookupPort`、
§3septies.8 的 `MediaAssetPort` 同一机制。`billing` 的入边从 **10 条语句降到 0**。

⚠️ **耦合图形状没变**：`deployment-unit-coupling` 仍报 4 个单元在一个 SCC 里、
4 对互相依赖。这一刀减的是**存量**，不是形状 —— 与 §3septies.8 的提醒一致。

### 3octies.7 另一半 `agent-api`：**已做**，见 §3decies

当时记的是"`AgentTool.parametersSchema()` 返回 `JsonNode`，**50 个实现、41 个覆写**，
按 ADR-1 只能改成 `String` 或 `Object`，改动面比 billing 大一个量级"。

**结论是那个前提本身要改**：不该去改 `AgentTool`，也不该搬它。`integration -> agent` 的
5 条实现语句用"加一个窄端口"消掉了，50 个实现一个没动。完整论证见 §3decies 与
`adr-contract-boundaries.md` §6。

---

## 3nonies. 第六刀：`BlogService` → `content.api.ContentPostPort / PublicationPort`

完整论证在 `docs/migration/adr-contract-boundaries.md` §5，这里只记与"抽库"直接相关的部分。

### 3nonies.1 又是一次"抽端口"，而且这次的**判据最清楚**

`BlogService` 是 `@Service` + `@RequiredArgsConstructor`，注入四个 MyBatis mapper
（`BlogPostMapper` / `BlogCategoryMapper` / `BlogTagMapper` / `BlogPostViewMapper`）、
`UserLookupPort` 与 `StringRedisTemplate`。搬进契约 = 把 MyBatis 与 Redis 拖进每个消费者，
V9 §19 第一个就否。所以只在契约里描述接口，实现留在内容域。

### 3nonies.2 ⚠️ 难点不在端口，在"同域调用点比跨域多得多"

这是与前三刀**方向相反**的一次：

| | 同域调用点 | 跨部署单元语句 |
|---|---|---|
| `BlogService` | `BlogController` **17 个** | **9 条 / 3 条边** |

前几刀都是"跨域调用点少，把域方法留在原地"。这次如果按老办法"改签名 + 改所有调用点"，
就会为了 9 条语句去动 17 个同域调用点 —— 而**同域调用点根本不算跨域边界**。
所以做法反过来：

- `BlogService implements ContentPostPort`；
- 旧公开方法**签名全部不变**，退化成"读 `UserContext` → 转调私有重载"的薄壳；
- 端口方法显式收 `userId`。

结果：`BlogController` 17 个调用点、`LocalBlogPublisher`、`BlogPlanRunService`、
`BlogAgentService` **一行未改**。

### 3nonies.3 镜像而不是收窄 —— 而且这次**能证明**

`ContentPost`（17 个分量）镜像 `BlogPostVO` 的可序列化表面；`ContentPostPage`（6 个分量）
镜像 `PageData`。理由与 §3bis.2 相同：换类型后线上 JSON 逐字节不变 ⇒ 可证明是纯重构
⇒ 不进 `docs/contract-changes.yaml`。

`PageData` 不能直接用还有第二个原因：它是**前端**分页返回体（存在理由就是"简化 `IPage`，
只留前端要的字段"），与服务间分页是两个受众 —— V9 §19 的教训就是别让一个类型服务两个受众。
另外它住在 `libs/xander-mybatis-support`，那个模块为它声明了 MyBatis-Plus，契约依赖不到。

**这次镜像关系是可验证的**：`ContentPostShapeTest`（住在 `apps/core-host`，契约侧看不见
`BlogPostVO`）用三条独立断言钉住 —— 属性名集合相同、同值序列化成同一棵 JSON 树、
以及**生产代码里那条转换**（`BlogService.toContract`，反射调用）的输出与源对象同树。
第三条是前几刀没有的：它证明的不只是"两个类型长得一样"，而是"实现真的按镜像搬了"。

⚠️ 写这个测试时踩到了 `McpJsonMapperFactory` 那个缺陷的**同一个根因**：裸 `new ObjectMapper()`
序列化 `LocalDate` 直接抛 `InvalidDefinitionException`。测试里显式注册 `JavaTimeModule` 才通过。

### 3nonies.4 这一刀真正消灭的是"相邻同类型位置参数"

| 旧签名 | 危险 | 新类型 |
|---|---|---|
| `updateBlog(Long, String, String, String, String, String, List)` | **五个相邻 `String`**：`summary`/`content` 写反 → 摘要列拿到整篇正文 | `ContentPostInput` |
| `listMyPosts(Integer, String, Boolean, Boolean, Integer)` | **两个相邻 `Boolean`**：`juejin`/`csdn` 写反 → 两条 SQL 都返回非空结果，静默错结果 | `ContentPostQuery` |
| `getMyBlogs(Integer, String, int, int)` | 两个相邻 `int`（page/size） | **保留位置参数**（分页是通用形状，且只有同域在用） |

`ContentPostStatus` 进契约是另一件事：`QueryPostsTool` 与 `QueryUnsyncedPostsTool` 各自把
`"draft" ⇄ 0` 写了一遍。⚠️ 它**不能改成枚举**（`blog_post.status` 是 `int` 列）。
`BlogService.STATUS_*` 保留为**别名**，仍是常量表达式，`switch` 的 `case` 标签照旧可用。

### 3nonies.5 `PublicationPort`：零改动的端口形状是**设计出来的**

`PublicationService` 的两个方法签名已经只含 `content-api` 类型，端口照抄它，
实现侧只加 `implements` + `@Override`，**类体一行未改**。

判据：**优先选与现有实现同形的端口**（访问器同名 ⇒ 实现侧退化成类型替换）。
端口形状是设计自由度，不要为了"看起来更像端口"去重构签名。

### 3nonies.6 收益（重新生成基线后实测）

```text
agent       -> publishing   3 -> 0   (跨单元，impl)
integration -> publishing   5 -> 0   (跨单元，impl；api 11 -> 0)
profile     -> publishing   1 -> 0   (跨单元，impl)

implementation_statements          64 -> 55   (-9)
implementation_edges               24 -> 21   (-3)
cross_deployment_unit_statements   47 -> 38   (-9)
cross_deployment_unit_edges        17 -> 14   (-3)
api_statements                     38 -> 25   (-13)
api_edges                          11 -> 10   (-1)
```

**`publishing` 的跨单元入边归零**（13/9 → 4/0）：剩下 4 条全是同单元的
（`automation` 2 条、`blogagent` 2 条），它们拆完还在 `dinqor-content-automation` 一个进程里。
这与 §3octies.6 的 `billing` 10/7 → 0/0 是同一类结果。

⚠️ **耦合图形状依然没变**：`deployment-unit-coupling` 仍报 4 个单元在一个 SCC 里、4 对互相依赖。

### 3nonies.7 ⚠️ 残留：1 条 api statement（`agent -> publishing` 这条边还在）

`PublishPostTool` 仍 `import com.xander.lab.publishing.api.BlogPostVO`，因为
`BlogAgentService.publish` / `findPublishedPost` 返回它。这条边现在是
`api_statements: 1, implementation_statements: 0` —— 按本仓库自己的判据
（`cross-domain-imports.yaml` 的 `note`：*Only implementation_statements counts*）**已不算存量**。

不顺手做掉的理由：要消掉它得改 `BlogAgentService` 的公开返回类型 **和**
`BlogAgentController` 的返回类型，那是 **blogagent ↔ publishing** 这条边界的事；
而且 `publishing.api` 整个遗留 api 包（`BlogPostDTO` / `BlogPostVO`）会被后续某一刀整体处理。

**⚠️ 一个容易误读的指标细节**：`counts.cross_deployment_unit_edges` 数的是
**至少有一条 implementation statement** 的跨单元边。所以 `edges` 列表里现在有 **15** 条跨单元边，
而计数是 **14**。数边数时不要只看列表长度。

### 3nonies.8 下一刀

按剩余跨单元实现语句排序（当前基线）：

| 目标域 | 总数 / 跨单元 | 说明 |
|---|---|---|
| `automation` | 10 / 10 | 最大的跨单元目标，入边来自 `agent` 6 / `knowledgebase` 2 / `admin` 1 / `integration` 1 |
| `integration` | 9 / 9 | `automation -> integration` 6 条是最大的一条边 |
| `recitation` | 6 / 6 | `agent -> recitation` 4 + `automation -> recitation` 2 |
| `agent` | **0 / 0** | **已归零**（§3decies）。原先是 `integration -> agent` 5 条 |
| `blogagent` | 9 / 5 | `agent -> blogagent` 4 + `studio -> blogagent` 1 |
| `identity` | 11 / 3 | 11 条里 8 条同单元 |
| `publishing` | 4 / **0** | **已归零**（本刀） |
| `virtualpay` | 1 / 0 | 同单元 |

当前最大的跨单元边是 `agent -> automation`（6）与 `automation -> integration`（6）。
`agent` 已在 §3decies 归零，剩余最大的跨单元目标是 `automation`（10）与 `integration`（9）。

---

## 3decies. 第七刀：`agent-api` —— 不搬 `AgentTool`，只加一个窄端口

完整论证在 `docs/migration/adr-contract-boundaries.md` §6 / §2.7，这里只记与"抽库"直接相关的部分。

### 3decies.1 目标边：一条**方向错误**的边

`integration.mcp.client.application` 里三个文件 import 了 agent 的实现类，5 条实现语句，
全部跨部署单元（`dinqor-platform-integration` → `dinqor-agent`）：

| 文件 | import | 用途 |
|---|---|---|
| `AgentMcpServerService` | `AgentToolRegistry` | 配置变更后反向调 `invalidate()` |
| `McpToolSource` | `AgentDynamicToolSource`、`AgentTool` | 实现消费方的动态来源接口 |
| `RemoteMcpTool` | `AgentPromptSection`、`AgentTool` | 实现消费方的工具接口 |

形态上像 SPI，所以看起来理所当然。问题是 `AgentTool` / `AgentDynamicToolSource` 是 agent 的
**进程内抽象**：提供方实现它们，等于把自己的编译单元绑在消费方的实现类上，而且**是提供方在调
消费方的实现**，控制流反了。

### 3decies.2 ⚠️ 包名：`com.xander.lab.agent.api` 已经被这个域自己占了

`contracts/<x>-api` 的默认包名是 `com.xander.lab.<x>.api`，已建的四份都是这么落的。
**但这条对 `agent` / `automation` / `studio` 不成立**——这三个域已经把
`com.xander.lab.<域>.api` 用作自己的**前端 HTTP 层**：

```text
com/xander/lab/
├── agent/       api  application  domain  infrastructure   ← api 已被占用
├── automation/  api  application  domain  infrastructure   ← api 已被占用
├── studio/      api  application  domain  infrastructure   ← api 已被占用
├── integration/ file  mail  mcp  oss                       ← 没有 api，所以 integration-api 能落在 .api
└── publishing/  api  application  domain  infrastructure   ← 但 content-api 与它不同名
```

契约再用这个包名会有两个后果，第二个是致命的：

1. **同一个包出现在两个 jar 里**（`apps/core-host` 与 `contracts/agent-api`）；
2. **该域自己的 Controller 会被算成共享内核。** `SHARED_KERNEL` 是前缀匹配，登记 `agent.api`
   就同时覆盖了那 12 个 Controller / VO / 请求体；它们的 `domain_of` 变成 `agent.api`（不是业务域
   `agent`），于是每个 Controller 引用自己的 application 层都会产生一条 `agent.api -> agent`
   的**新边**——把 `implementation_statements` 推高几十条，并同时触发
   `shared-kernel-reverse-dependency` 违规。

所以这三个契约取**下一个空着的段**：`spi`，`SHARED_KERNEL` 登记 `agent.spi` 这类带点条目
（最长匹配，不会连带覆盖 `agent.api`）。

> **加契约模块前的第一步**：`ls apps/core-host/src/main/java/com/xander/lab/<域>/`，
> 看 `api` 是否已被占用。占用了就换段，**别改扫描器**。

名字带 `spi` 也不只是躲碰撞：本端口确实是**宿主定义、插件实现**的经典 SPI 形态，与
`BlogPublisher` 同类——agent 是宿主，integration 是插件。

### 3decies.3 判据的第三种情况：**接口也不能搬**

前六刀攒下的判据是两条：

| 情况 | 动作 |
|---|---|
| 类型本身是**接口**或**纯 DTO** | **搬**进契约 |
| 类型是**具体实现**（`@Service` / 带 mapper / 带框架注解） | **抽端口**，实现留在原地 |

这一刀是第三种，而且是前两条都没覆盖的：

| 情况 | 动作 |
|---|---|
| 类型是接口，但**签名里带框架类型**（`JsonNode`），且有几十个实现 | **不动它**，另立一个**窄端口**，把框架类型挡在端口外 |

`AgentTool` 三条独立的否决理由：

1. `parametersSchema()` 返回 `JsonNode` —— ADR-1 不许，而放宽 `CONTRACT_ALLOWED_IMPORT_PREFIXES`
   是明令禁止的动作；
2. 6 个 `default` 成员 + 一个 5 方法的嵌套 `ProgressListener` —— 搬过去等于把 agent 的整套工具
   词汇表塞进每个依赖方；
3. **50 个实现、41 个覆写 `parametersSchema()`** —— 搬接口就要改这 50 个文件。

所以这一刀**不动** `AgentTool`，在 `contracts/agent-api` 里另立
`RemoteToolCatalog` / `RemoteTool` / `RemoteToolCall`，只回答消费方真正要问的问题。
**收益有一半来自"没做什么"**：50 个实现一个都没动。

### 3decies.4 端口形状：三处 JSON 全部走 `String`

schema、参数、返回值都是 JSON，契约里全部以**文本**出入：

```java
String parametersSchemaJson();
String approvalReason(String argsJson);
String invoke(Long userId, String argsJson, RemoteToolCall call);
```

代价是调用侧多一次解析（适配器里缓存）、提供方多一次 `toString`；对照的是一次真实网络往返。
收益是契约保持零依赖，`contract-dependency-boundary` 不用动。

`RemoteTool` 的 7 个方法**正好**是消费方适配器要实现的 `AgentTool` 成员里除去 `reconcile`
的那 7 个。`reconcile` 没有对应方法：远端工具查不了——副作用在第三方那里，本平台既没有记录
也没有能力去问。

**准则不带 `order`。** 契约只给正文（`promptText()`），顺序由渲染方定（统一挂
`AgentPromptSection.ORDER_SIDE_EFFECT`）。把 `int order` 放进契约，agent 的常量表
（50 / 100 / 200 / 300）就得跨模块再抄一份，两份值可以**无声漂移**，漂移的后果是提示词里准则
排序变了而没有任何测试会红。

### 3decies.5 反向依赖：版本号取代 `invalidate()` 回调

三条 import 里最麻烦的是 `AgentMcpServerService` 那条。改法是**把"我变了"的通知换成
"你问我变没变"的轮询**：

- `AgentDynamicToolSource` 加 `default long version()`（默认 `0` = 永不变）；
- `AgentToolRegistry` 每次读取先读各来源版本，与缓存比对，不一致才重建；
- `McpToolSource.invalidate()` 顺手 `incrementAndGet()` 自己的版本号；
- `AgentMcpServerService.changed()` 只剩 `toolSource.invalidate()`。

控制流于是只剩一个方向：**注册表问，来源答，来源永远不需要知道注册表是谁。**

> `version()` 之所以可以是 `default`：`AgentDynamicToolSource` 是**函数式接口**，测试里大量用
> lambda 实现它；加抽象方法会让那些 lambda 全编译不过，而它们表达的正是"版本恒为 0 的静态来源"
> 这个合法语义。默认值 `0` 恰好就是那个语义——这不是"静默变宽"，是"默认值正确"。

**⚠️ 一个没有别的症状的顺序坑**：注册表必须**先读版本、后取工具**。反过来会留下一个
**永久性**的陈旧缓存——期间发生的变更让"新版本 + 旧工具"一起被存下来，版本再也不会变，
那份旧工具就再也刷新不掉。只能用**调用顺序断言**钉住
（`AgentToolRegistryTest#theVersionIsReadBeforeTheToolsSoAConcurrentChangeCannotBeCachedAsFresh`）。

> 写这条测试时踩了一次：第一版断言"缓存命中时两个调用都不该发生"，实测是
> `[version, tools, version]`——**版本是每次都要读的**，不读就发现不了变更，而发现变更正是这个
> 机制存在的全部理由。缓存命中的只是"取工具"那一步。

### 3decies.6 实测收益

```text
implementation_statements        55 → 50      (-5)
implementation_edges             21 → 20
cross_deployment_unit_statements 38 → 33      (-5)
cross_deployment_unit_edges      14 → 13
api_statements                   25 → 25      (未动)
```

`integration → agent` 这条边**整条消失**。更重要的是图形状变了：`deployment-unit-coupling`
从"4 个单元 4 对互依"变成"**4 个单元 3 对互依**"，并明确报出
`1 pair(s) no longer mutual ... [('dinqor-agent', 'dinqor-platform-integration')]`
——这是这条门禁第一次报出"互依对减少"。`MUTUAL_UNIT_PAIRS` 里那两个方向已按提示删掉。

### 3decies.7 下一刀

按剩余跨单元实现语句排序（这一刀之后的基线 33 条 / 13 条边）：

| 目标域 | 总数 / 跨单元 | 说明 |
|---|---|---|
| `automation` | 10 / 10 | 最大的跨单元目标：`agent -> automation` 6、`knowledgebase` 2、`admin` 1、`integration` 1 |
| `integration` | 9 / 9 | `automation -> integration` 6 是最大的一条边，但卡在 `MailService` 上（§3quinquies） |
| `recitation` | 6 / 6 | `agent -> recitation` 4 + `automation -> recitation` 2 |
| `blogagent` | 5 / 5 | `agent -> blogagent` 4 + `studio -> blogagent` 1 |
| `identity` | 3 / 3 | `agent` 2 + `integration` 1 |
| `publishing` | 2 / **0** | **跨单元已归零** |
| `agent` | **0 / 0** | **已归零**（本刀） |

`automation-api` 是下一个最大目标，10 条全跨单元 —— 见 §3undecies。

---

## 3undecies. 第八刀：`contracts/automation-api` —— 最大的目标域，收窄而不是镜像

完整论证在 `docs/migration/adr-contract-boundaries.md` §7（含 §7.10 那处门禁空转的
实测证据）。这里只留实施与数字。

### 3undecies.1 范围：只做 `agent → automation` 这一条边

10 条语句指向 `automation`，来自 4 个单元。这一刀**只做 6 条**（`agent`），另外三条
各自有明确理由被推迟（`admin` 那条需要 SSE 广播端口，形状不同；`integration` 那条
构成真环，要改 `MailService` 的 API）。**一次一刀、每刀可证明是纯重构**，比一次吞掉
10 条然后说不清哪条引入了行为变化要好。

### 3undecies.2 五个新类型，全部是收窄而非镜像

| 契约类型 | 分量数 | 域 VO | 域 VO 字段数 |
|---|---|---|---|
| `EmailReminderView` | 11 | `EmailReminderTaskVO` | 20 |
| `EmailReminderDraft` | 12 | `EmailReminderCreateRequest` | 12 |
| `ContentPlanView` | 11 | `BlogContentPlanVO` | 24 |
| `ContentPlanDraft` | 10 | `BlogContentPlanCreateRequest` | 10 |
| `ContentPlanEdit` | 8 | `BlogContentPlanUpdateRequest` | 15 |

**没有一个替换前端可见类型**，所以 §2.5 的镜像规则不适用：域 VO 全部原地不动，
继续由各自的控制器发给前端。

### 3undecies.3 ⚠️ 字段集要从消费方读取点反推 —— 这一刀真的漏了一个字段

`EmailReminderView` 的 Javadoc 把 `sendTime` 写成 `QueryEmailRemindersTool` 要读的字段，
但 record 的**分量列表里没有它**。漏掉的症状是工具输出静默少一个键，不是编译错误。

抓法：对 6 个工具跑
`grep -oE "\.(get|is)[A-Z][A-Za-z0-9]*\(" | sort -u` 取并集，再与 record 逐分量对账。
`ContentPlanView` 的 11 个分量完全吻合，`EmailReminderView` 少 1 个，已补。

> Javadoc 里列了消费方字段清单 **不等于** record 里有那些分量 —— 两者分开手写就会分开漂移。

### 3undecies.4 ⚠️ "没传"与"传了空"：record 化最容易丢的语义

`UpdatePlanTool` 的 EDIT 是"只改提交了的字段"（没传 → 保持原值）。
换成位置构造的 record 后，`syncCsdn` / `syncJuejin` / `topic` 一旦被兜底成
`false` / 空串，**每次编辑都会静默关掉同步或清空主题**，而用户没说过这话。

所以 `ContentPlanEdit` 的两个布尔分量是装箱 `Boolean`，并且加了**两条互补**断言：
`editLeavesUnspecifiedFieldsNull`（缺省必须 `null`）与
`editCarriesExplicitFalseBooleans`（显式 `false` 必须落成 `false`）。

### 3undecies.5 换算落点必须由应用模块测（契约侧看不见域类型）

契约模块的 `RecordShapeTest` 只能证明"契约自己没变"。真正会静默错的是**换算那一行** ——
`EmailReminderView` 有六个相邻 `String` 加两个 `Instant`，把收件人填成主题不会有编译错误。
所以新增 `EmailReminderPortMappingTest`(2) / `ContentPlanPortMappingTest`(3)，
用**互不相同的字面量**逐字段钉住 `toView`。

⚠️ 三个相邻的 `Boolean` 只有两个取值，**一组字面量钉不满**：
`(true, false, true)` 时把第一个和第三个对调测试仍然全绿。所以用**两组互补**字面量。

### 3undecies.6 实测收益

```text
implementation_statements         50 → 44      (-6)
implementation_edges              20 → 19      (-1)
cross_deployment_unit_statements  33 → 27      (-6)
cross_deployment_unit_edges       13 → 12      (-1)
api_statements                    25 → 16      (-9)
```

`api_statements` 掉 9 是这一刀最值得记的数字：6 个工具原本 import 的 9 个
`automation.api.*` 类型全部换成 `automation.spi.*`，而**契约包在 `SHARED_KERNEL` 里，
不计入任何指标**。这就是"用契约换掉实现依赖"在数字上的样子。

图形状：`dinqor-agent` **脱离 Wave 2 强连通分量**（只剩出边：→content-automation 4、
→core-host 6、→platform-integration 2），成为和 `bloghtml` 一样"可以单独抽取"的单元。

### 3undecies.7 ⚠️ 顺带发现一处门禁空转（已修）

`deployment-unit-coupling` 的判据 2 原本取 `component_of(WAVE2_UNITS[0])`，
而 `WAVE2_UNITS[0]` 正是 `dinqor-agent`。agent 脱离分量后，这条判据的**根变成了单元素**，
`joined` 恒为空、**再也不会红**。实测：构造新单元加入真实分量后，
旧判据 `joined = []` → PASS（空转），新判据 `joined = ['dinqor-newbie']` → FAIL。

修法：改成对**每个** Wave 2 单元取分量的并集；detail 行改报**实测**分量大小
（原来打印 `len(WAVE2_UNITS)` 这个常量，会把"agent 已脱离"这个进度盖掉），
并新增"哪些单元已可单独抽取"的进度提示。详见 ADR §7.10。

### 3undecies.8 下一刀

按剩余跨单元实现语句排序（这一刀之后的基线 27 条 / 12 条边）：

| 目标域 | 总数 / 跨单元 | 说明 |
|---|---|---|
| `automation` | 4 / 4 | `agent → automation` 6 条已归零（本刀）；剩 `knowledgebase` 2、`admin` 1、`integration` 1 |
| `integration` | 9 / 9 | `automation → integration` 6 是最大的一条边，但卡在 `MailService` 上（§3quinquies） |
| `recitation` | 6 / 6 | `agent → recitation` 4 + `automation → recitation` 2 |
| `blogagent` | 5 / 5 | `agent → blogagent` 4 + `studio → blogagent` 1 |
| `identity` | 3 / 3 | `agent` 2 + `integration` 1 |
| `publishing` | 2 / **0** | **跨单元已归零** |
| `agent` | **0 / 0** | **已归零**（§3decies） |

两个候选：①`automation-api` 的**零接线扩展**（`knowledgebase → automation` 2 条，
只需往已有模块加端口）成本最低；②把 `agent → recitation`(4)
与 `agent → blogagent`(4) 合成一刀，清掉 `dinqor-agent` 的**出边** ——
那时 agent 进出都归零，完全孤立，是最干净的一个抽取单元。

**候选 ① 已做 → §3duodecies**（第九刀，端口名是 `NotificationPort`，不是当时预写的
`BlogNotificationService` 端口 —— 理由见 §3duodecies.2）。
下一个仍是候选 ②。

---

## 3duodecies. 第九刀：`automation-api` 的零接线扩展 —— `NotificationPort`

**零构建接线的一刀。** 不新增模块 ⇒ 根 POM 的 `<modules>`、应用 POM 的依赖、
`scan.py` 的 `SHARED_KERNEL`、**Dockerfile** 四处一处都没动。
于是这次改动里没有"构建接线"可以出错，全部风险收敛到语义上 —— 而语义有反射测试守着。

### 3duodecies.1 目标边：`knowledgebase → automation`（2 条，1 个文件）

`KnowledgeIngestionService` 在大部头文档不自动生成结构图时，要发一条"阅读某一章时再生成"的通知。
通知表 `blog_notification` 属 `automation`，所以它 import 了
`automation.application.BlogNotificationService` 与 `automation.domain.BlogNotification`。

这刀是 §3undecies.12 与 `contracts/automation-api` 的 `package-info` 早就写下的待办，
原文是"零接线的扩展，只是不属于这一刀"。

### 3duodecies.2 端口名是 `NotificationPort`，不是预写的 `BlogNotificationService` 端口

`automation` 那一族类型**全都带 `Blog` 前缀**（`BlogNotification` 实体 / `BlogNotificationService` /
`BlogNotificationMapper` / `BlogNotificationVO` / `BlogNotificationController`）。
但契约里的先例是**丢掉这个遗留前缀**：`ContentPlanPort` 对应的是 `BlogContentPlanService`，
不是 `BlogContentPlanPort`。本刀沿用先例 ⇒ `NotificationPort`。

### 3duodecies.3 两个类型都不能搬，理由还是两条不同的

- `BlogNotification` 带 `@TableName("blog_notification")` ⇒ **域实体**，ADR-2 §2.2 不许跨边界。
- `BlogNotificationService` 是 `@Service` ⇒ **具体实现**，抽取判据第二种：抽端口。

### 3duodecies.4 ⚠️ 常量必须跟着端口搬（第二次踩，这次是设计出来的）

`TYPE_KNOWLEDGE_STRUCTURE_PENDING` 与 `RESOURCE_KNOWLEDGE_FILE` 原本声明在**实体上**。
不搬走的话，消费方为了拿这两个常量仍然要 import 实体，实现语句**一条都减不掉**。
判据：`grep -rn "import com.xander.lab.automation" <knowledgebase 包>` 结果只剩 `.spi`。

搬得干净的前提是它们在 `automation` 内部**无人使用**（实测：只有 knowledgebase 的服务与测试引用）。
另外四个 `TYPE_*` 由 `BlogPlanRunService` 使用，**刻意留在实体上** —— 端口只带消费方真正用到的。
实体的类注释写明了这条分界，防止下次有人加回去。

### 3duodecies.5 ⚠️ 落库的字面量要单独钉：这里的常量值**是数据**

两个字符串写进 `blog_notification.type` / `.resource_type`，前端按它们分流。
所以契约测试钉**字面量本身**，消费方测试钉"传下去的就是这个常量"。
**只有消费方测试是不闭合的** —— 生产代码与测试用同一个常量，常量值错了两边一起错。

### 3duodecies.6 端口形状选"与实现同形"，返回 `Long` 而非 `void`

唯一消费方忽略返回值，按"端口只收调用方真正用到的"应该收窄成 `void`。
**没收窄**，因为 Java 不允许同名同参只差返回值 ⇒ 收窄会让实现侧**必须改名**，
那是与这条缝无关的改动。同形 ⇒ 提供方只是"加 `implements` + 加 `@Override`"，可证明行为不变。
这是与 §3nonies.5（`PublicationPort` 零改动）同一条思路的第二次应用，
也是与 §3undecies 里"同名不同签名 + `ForView` 后缀"**相反**的选择 —— 那里是同名不可避免。

### 3duodecies.7 实测收益（重新生成基线后）

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 44 | **42** |
| `implementation_edges` | 19 | **18** |
| `cross_deployment_unit_statements` | 27 | **25** |
| `cross_deployment_unit_edges` | 12 | **11** |
| `api_statements` | 16 | **16**（不变） |

**`api_statements` 这次一条没掉**，与 §3undecies 掉 9 相反：消费方 import 的是
`automation.application` / `automation.domain`（都是实现语句），从没碰过 `automation.api`。

测试：`automation-api` 22 → **31**，`core-host` 1151 不变，全反应堆 1301 → **1310**（0 fail）。

### 3duodecies.8 图形状：`knowledgebase` 变成**纯汇点**

改完之后 `knowledgebase` 在 `docs/cross-domain-imports.yaml` 里**整域消失** —— 它没有出边了。
**纯汇点**意味着它不欠任何域的实现依赖，抽取时不需要为它准备契约，只要它依赖的契约就位。
（`bloghtml` / `dinqor-agent` 是"孤立"，与"纯汇点"的区别是纯汇点仍会被别人依赖。）

`automation` 的扇入也削到只剩两条，且**两条都卡在别的决定上**：`admin` 要 `SseEmitter`
（框架类型，形状不同）、`integration` 是邮件模板真环（要改 `MailService` 的 API）。

### 3duodecies.9 ⚠️ 注入验证逮到三个测试缺陷 + 一处门禁空转（没修）

四次注入，前三次如期变红。**第四次（加一个无参方法）逮到的不是产品缺陷，是测试缺陷**：
断言无条件读 `getParameterTypes()[0]`，无参方法时抛的是下标越界，报的信息指向数组而不是问题。
同款写法在 `EmailReminderPortTest` / `ContentPlanPortTest` 都有，一并加了守卫。

顺带发现 `getDeclaredMethods()[0]` 这种**按下标反射**的写法有两处（本刀新增的一处 +
`contracts/core-api/UserLookupPortTest` 里既有的一处）。**JVM 不保证声明顺序**，
加一个方法就会静默换检查对象。两处都改成按名字取。

**第五次注入没红，而且这是对的**：把常量放回实体、让 `knowledgebase` 重新 import 它 ⇒
扫描器如实报 42 → 43 条、边 18 → 19，而 `cross-domain-import-baseline` 仍报 **PASS**。
原因是该门禁把 `scan.py` 的产物当作自己的基线，在"先 scan 后 gates"的既定流程下基线恒等于实时值。
这是**既有缺陷**（修法要动 CI 设计，见 ADR §10 待办第 16 项），本刀只改了门禁 docstring 里
那句失实的保证。

> **教训**：一个判据的"基线"必须来自判据之外。拿被测方自己的产物当基线，等于让它自己给自己打分
> —— 与"门禁不复用生成方的哈希函数"是同一条忌讳。与 §3undecies.7 的判据空转、
> `util` 死条目三者同源：**判据只朝一个方向看，另一个方向没人看。**

### 3duodecies.10 为什么没有加 `contract-changes.yaml` 条目

同 §3bis / §3nonies / §3undecies：没有改任何前端可见类型。
`automation.api.BlogNotificationVO` / `BlogNotificationListResult` 一行未动，路由与响应形状未变。
两个常量是**搬家**（同字面量、同名字，只是从实体挪到端口），唯一的可观测差异是 Java 全限定名，
而它不出进程边界。

### 3duodecies.11 下一刀

仍是 §3undecies.8 的候选 ②：把 `agent → recitation`(4) 与 `agent → blogagent`(4) 合成一刀，
清掉 `dinqor-agent` 的**出边** —— 那时 agent 进出都归零、完全孤立。
（`agent` 还剩 `identity` 2 与 `integration` 2，所以"出边归零"要连这两条一起，
共 12 条；得再分两刀。）

> 实际执行时**只做了这一半**，而且理由变了 —— 见 §3terdecies：`recitation` 有**两个**
> 跨域消费方（`agent` + `automation`），而 `automation → recitation` 是 content-automation
> 指向 core-host 的**最后一条**实现边。做 recitation 的边际收益因此远大于"清 agent 出边"。

---

## 3terdecies. 第十刀：`contracts/recitation-api`（材料能力）—— 逮到一处自调用失事务

### 3terdecies.1 目标边：`recitation` 是第一个有**两个**跨域消费方的目标域

前九刀的目标域都只有一个方向的扇入。侦察时发现 `recitation` 有两条：

| from → to | impl | api | 文件 |
|---|---|---|---|
| `agent → recitation` | 4 | 4 | `CreateKnowledgeTool` `GradeAnswerTool` `QueryKnowledgeTool` `QuizKnowledgeTool` |
| `automation → recitation` | 2 | 0 | `BlogContentPlanService` `BlogPlanRunService` |

两条都是 `RecitationService`，都调 `getMaterial` / `searchMaterials` / `countMaterials` /
`createMaterial`。**消费方并集就是端口的宽度** —— 这正是 §2.5 那条判据的用法。

⚠️ 但 `automation → recitation` 只有 2 条却是**关键的一条**：它是 content-automation
指向 core-host 的**最后一条**实现边。做 recitation 的边际收益因此远大于"清 agent 出边"
（后者要 12 条才归零）。**按边数排序选刀会选错。**

### 3terdecies.2 这一刀只做材料能力，测验能力另开一刀

四个工具里两个只用材料（`CreateKnowledgeTool` / `QueryKnowledgeTool`），
另两个只用测验（`QuizKnowledgeTool` / `GradeAnswerTool`）。后者要碰
`KnowledgeQuizService` 的四个嵌套 record，其中 `KnowledgeQuizAttemptView.items`
的类型是 **`Object`** —— 一个**没有形状**的字段。

一个没有形状的字段没法搬进契约（搬过去等于把"随便什么"写进契约），
也没法收窄（消费方读的就是里面任意结构）。**这需要一个形状决定，不是一个搬家决定。**
所以这一刀只做材料，测验单独一刀 —— 与 §3quinquies 里"邮件模板真环要改 `MailService` 的 API"
同一种处理：**卡在一个决定上，就不在这一刀里顺手做。**

### 3terdecies.3 包名：`recitation.api` 已被前端 HTTP 层占用 → 走 `recitation.spi`

`apps/core-host/src/main/java/com/xander/lab/recitation/api/` 下有 6 个文件
（`RecitationController` `CreateRecitationMaterialRequest` `RecitationMaterialView` 等）。
硬塞契约进这个包会（a）一个包进两个 jar，（b）把 controller 判成共享内核 ⇒
几十条假 `recitation.api -> recitation` 边 + 每条一个 `shared-kernel-reverse-dependency` 违规。

这是**第四次**撞上同一个坑（前三次：`agent.spi` §3decies.2、`automation.spi` §3undecies.2、
`studio.spi` 待做）。**新增契约前必做**：

```bash
ls apps/core-host/src/main/java/com/xander/lab/<domain>/
```

### 3terdecies.4 筛选条件收窄：`MaterialQuery`（5 分量）→ `MaterialFilter`（4 分量）

域侧 `RecitationService.MaterialQuery` 是 `(search, masteryLevel, dueOnly, limit, archiveFilter)`。
跨域消费方**全部只用 4 参数构造器** —— 它们**从不写 `ArchiveFilter`**：

```bash
grep -rn "MaterialQuery(" apps/core-host/src/main/java --include=*.java | grep -v "/recitation/"
```

⇒ `archiveFilter` 是**提供方的策略**（"归档的要不要算"），不是消费方的数据。
契约侧只收前 4 个，`toQuery()` 换算时补 `ArchiveFilter.ACTIVE`（域侧的默认）。

⚠️ **这不是"少一个字段"，是"少一个维度"。** 换算层必须显式写出补什么值，
不能靠域侧的默认参数 —— 否则以后域侧改默认，契约侧的行为会静默跟着变。

`MaterialFilter.all()` 是静态工厂（`(null, null, false, 100)`），
让 `QueryKnowledgeTool` 的 `MASTERED_FILTER` / `DUE_FILTER` / `all()` 三处常量构造
有地方落脚。**端口上的常量要跟着端口搬（§3duodecies.4），但静态工厂不用搬 —— 它没有实现依赖。**

### 3terdecies.5 收窄而不是镜像：`MaterialView` 14 → 9

域侧 `recitation.api.RecitationMaterialView` 有 14 个分量。消费方读取点并集是 9 个：

| 消费方 | 读的字段 |
|---|---|
| `CreateKnowledgeTool` | `id` `title` `knowledgeType` `testMode` |
| `QueryKnowledgeTool` | 上述 + `masteryScore` `masteryLevel` `reviewCount` `nextReviewAt` `content` |
| `BlogContentPlanService` / `BlogPlanRunService` | `id` `title` `content`（当归属检查 + 正文） |

**不带的 5 个**：`normalizedContentLength` `lastReviewAt` `archivedAt` `createdAt` `updatedAt`。
前两个是**前端展示用**的派生量（`normalizedContent` 的码点数），后三个是审计列。

⚠️ **`normalizedContentLength` 尤其不能带**：它要求契约侧能算码点数，也就是要求契约知道
`normalizedContent` 的存在 —— 而 `normalizedContent` 本身**不该跨边界**（它是域侧的规范化中间产物）。

### 3terdecies.6 `MaterialDraft`：去掉 `jakarta.validation` 注解的那一份

域侧 `CreateRecitationMaterialRequest` 带 `@NotBlank` / `@Size` 等注解 ⇒
**按 ADR-1 不能进契约**（契约不许有框架注解，`jakarta.validation` 也是框架）。
`MaterialDraft(title, content, knowledgeType, testMode)` 是它的无注解等价物，
4 个分量逐字段同名同序。

`toRequest(MaterialDraft)` 是**纯换算、不兜底**：
`testMode` 缺省必须落成 `null`（域侧用 `null` 表示"用默认的 AI_QA"），
**不能**在换算层写 `draft.testMode() == null ? "AI_QA" : draft.testMode()`
—— 那就是 §3undecies.4 那条"没传 ≠ 传了空"的重犯。这条由注入 C 专门守。

### 3terdecies.7 `getMaterial` 同名同参 → 富方法改名 `getMaterialForView`

端口要 `MaterialView getMaterial(Long, Long)`，域侧富方法返回
`recitation.api.RecitationMaterialView`。**Java 不允许同名同参只差返回值** ⇒
沿用 §3undecies.5 的先例（`updateStatus` / `updateStatusForView`），
富方法加 `ForView` 后缀，端口拿规范名。

调用点只有一处（`RecitationController` 一行），blast radius 极小 ——
**改名成本要实测，不能凭"这个名字很常用"的印象判断**：

```bash
grep -rn "\.getMaterial(" apps/core-host/src/main/java --include=*.java
```

### 3terdecies.8 ⚠️ 这一刀逮到一处**既有缺陷**：端口方法自调用失事务

写端口方法时对照上一刀的写法，发现 §3undecies 的两个端口实现方法**都少了 `@Transactional`**：

```java
@Override
public EmailReminderView create(Long userId, EmailReminderDraft draft) {
    return toView(create(userId, toRequest(draft)));   // 自调用！
}
```

`create(Long, EmailReminderRequest)` 上**有** `@Transactional`，但
**Spring 的 `@Transactional` 走代理，自调用绕过代理** ⇒ 走端口路径时事务**不存在**。

为什么是**实质缺陷**而不是洁癖：`EmailReminderService#create` 第一行是
`reminderMapper.lockUserForReminderCreation(userId)`，实现是
`SELECT ... FOR UPDATE` —— **它的全部作用就是串行化并发创建**。
没有事务 ⇒ 锁在语句结束就释放 ⇒ 幂等检查与插入之间**有窗口** ⇒
两个并发请求可以各插一条。

`BlogContentPlanService` 的 `create` / `update` / `updateStatus` 三处同病，
`lockUserForPlanCreation` 同理。**共 5 个方法。**

修法：端口方法上加与富方法**一致**的 `@Transactional`（含 `readOnly` / `REQUIRES_NEW` 等属性）。
⚠️ 不能只写 `@Transactional` —— 富方法上写的是
`@Transactional(propagation = Propagation.REQUIRES_NEW)` 的，端口方法也必须一样。

### 3terdecies.9 `PortDelegationTransactionTest`：把这条不变量交给机器守

**这条规则没有门禁守**（`scan.py` 只看 import 边，看不见注解）。所以写了
`apps/core-host/src/test/java/com/xander/lab/migration/PortDelegationTransactionTest.java`：

1. **完整性**：扫描 `com.xander.lab` 下所有实现 `*Port` / `*Gateway` 的具体类，
   与测试里手工维护的清单**双向比对**。这条**立刻就还了本钱** ——
   我加 `RecitationService` 时忘了把它列进清单，全量跑时正是这条断言变红。
2. **委托必须带注解**：对每个端口方法，若它的方法体**只**由"转调同类的富方法 + 换算"构成，
   则它必须有 `@Transactional`（且属性与富方法一致）。
3. **反向 sanity**：确认扫描真的扫到了类（否则"0 违规"可能是因为什么都没扫到）。

**判据写成"方法体是否只有一次同类自调用"** —— 而不是"方法名是否出现在某张表里"。
后者会随重构漂移，前者是结构性事实。

⚠️ 这条测试的**价值在注入验证里被证实**：去掉 `@Transactional` 后
**只有它变红**，完整性与反向 sanity 两条保持绿 —— 说明三条断言互不干扰。

### 3terdecies.10 一条不变量的**搬家**：工具侧写不出来的断言，挪到换算层

`QueryKnowledgeToolTest` 原来有一条断言：搜索 SQL 必须含 `archived_at IS NULL`。
但**契约侧的 `MaterialFilter` 没有归档维度**（§3terdecies.4）⇒ 工具侧**再也写不出**这条断言。

处理：把它**搬**到提供方（`RecitationServiceTest`），并改成从端口路径进
（`service.searchMaterials(userId, new MaterialFilter(...))`），断言 `capturedSql()`
含 `archived_at IS NULL` 与 `next_review_at <= ?`。

⚠️ **搬家的风险是"两边都没了"**：删掉工具侧那条时，新家还没写 ⇒ 不变量在两次编辑之间**无人看守**。
所以顺序是**先写新家、再删旧家**，然后用注入 B 证明新家真的会红。

> **教训**：端口收窄会**删掉消费方的表达力**。收窄时必须问"这条断言以后谁来写"。
> 答案要么是"提供方的测试"（搬家），要么是"契约侧的换算测试"（换形状），
> **不能是"没人写"**。

### 3terdecies.11 端口"变宽"依然没人守（第七次确认）

给 `RecitationMaterialPort` 加一个 `default` 方法 ⇒ 编译通过、26 条门禁**全 PASS**、
五个指标**一个不动**，**只有反射测试红**（3 条）。这是第七次同样的结论。

### 3terdecies.12 实测收益（重新生成基线后）

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 42 | **38** |
| `implementation_edges` | 18 | **17** |
| `cross_deployment_unit_statements` | 25 | **21** |
| `cross_deployment_unit_edges` | 11 | **10** |
| `api_statements` | 16 | **13** |

`api_statements` 掉 3 的原因是三个 `recitation.api.*` import 换成了 `recitation.spi.*`，
而**契约包在 `SHARED_KERNEL` 里不计任何指标**。§3undecies 掉 9、§3duodecies 掉 0、
本刀掉 3 —— **看收益必须两列都看。**

### 3terdecies.13 图形状：`content-automation ↔ core-host` 不再是互依对

`automation → recitation` 消失后，content-automation 到 core-host 的实现依赖**归零**
（它仍被 core-host 反向依赖两次：`studio → blogagent` 与 `admin → automation`）。

`MUTUAL_UNIT_PAIRS` 里那对**必须删掉**，否则门禁报
"1 pair(s) no longer mutual, delete both directions"。
**这是 `deployment-unit-coupling` 双向判据的价值** —— 过期条目会让图看起来比实际更乱。
（对比 §3undecies.7 那处**单**向判据空转：同一个门禁里，双向的那条救了它。）

Wave 2 强连通分量仍是 {content-automation, core-host, platform-integration}，
但环里**已经不需要 content-automation → core-host 这条腿**。

### 3terdecies.14 为什么没有加 `contract-changes.yaml` 条目

同 §3bis / §3nonies / §3undecies / §3duodecies：`contract-changes.yaml` 记的是
**前端可见**的契约变更。`MaterialView` / `MaterialDraft` / `MaterialFilter` 都是新类型、
前端永远看不到；`recitation.api.RecitationMaterialView` 与 `RecitationController`
的响应形状一个字没变（`getMaterial` → `getMaterialForView` 是同域改名，路由/形状/错误码均未变）。

### 3terdecies.15 注入验证：五次注入，每次变红的都正好是预期的那些

| # | 注入 | 期望 | 实测 |
|---|---|---|---|
| A | 端口加 `default` 方法 | 3 条 | **3 条**（`RecordShapeTest` 保持绿） |
| B | `toQuery` 补 `ArchiveFilter.ALL` | 3 条 | **3 条** |
| C | `toRequest` 给 `testMode` 兜底 | 1 条 | **1 条** |
| D | `toNarrowView` 对调 `title`/`content` | 1 条 | **1 条** |
| E | 去掉 `EmailReminderService#updateStatus` 的 `@Transactional` | 1 条 | **1 条** |

注入 A 顺带确认 §3duodecies.9 修的测试缺陷有效：无参方法让"首参是调用方 id"的断言
**给出的是有意义的失败**（"端口方法必须显式收方 id"），不是 `ArrayIndexOutOfBounds`。

### 3terdecies.16 下一刀

`agent → recitation` 还剩 2 条（测验能力，卡在 `items` 是 `Object` 的形状决定上）。
真正该做的是**测验能力那一刀**，因为它同时清掉 `agent` 的 2 条**出边**与
`QuizKnowledgeTool` / `GradeAnswerTool` 两个工具的全部 `recitation.api.*` import。

⚠️ 做之前必须先定 `items` 的形状 —— 这是一个**设计决定**，不是搬家决定。
候选：①把它收窄成一个新的契约 record（要反推所有读点）；②让契约侧的
`AttemptView` 干脆不带 `items`，让消费方自己按需查。

**已做，见 §3quaterdecies。** 两个候选都**没被采用** —— 正确答案是第三种：
契约带**文本**（`itemsJson`），解析上移到工具。理由见 §3quaterdecies.3。

---

## 3quaterdecies. 第十一刀：`recitation-api` 的测验能力 —— 一个无形状字段，与两份投影的一致性

### 3quaterdecies.1 目标边

| from → to | impl | api | 文件 |
|---|---|---|---|
| `agent → recitation` | 2 | 1 | `QuizKnowledgeTool`、`GradeAnswerTool` |

这是 §3terdecies.16 写下的待办。做完之后 `agent → recitation` **整条边消失**，
`recitation` 成为一个**零跨域消费方**的域（材料与测验两条能力都在契约上，
没有任何域 import `recitation.*` 的实现包）。

### 3quaterdecies.2 这一刀与上一刀的分工：`openQuiz` / `recordQuiz` 的调用方只有那两个工具

§3terdecies.7 遇到过"端口方法要拿规范名，但域侧富方法同名同参只差返回值"，
于是富方法加了 `ForView` 后缀（`getMaterialForView` / `updateStatusForView`）。
这一刀**没有这个问题**：

```java
@Override
@Transactional
public QuizSessionView openQuiz(Long userId, Long materialId, Long conversationId) { ... }

@Override
public QuizOutcomeView recordQuiz(Long userId, Long quizSessionId, Long conversationId,
                                  String verdict, List<GradedItemDraft> rawItems) { ... }
```

按 §3nonies / §7.5 的判据，**调用方只有 agent 的工具（加服务自己的测试）⇒ 直接换返回类型**，
不保留富方法。判据的输入要实测：

```bash
grep -rn "\.openQuiz(\|\.recordQuiz(\|\.listQuizzes(" apps/core-host/src --include=*.java \
  | grep -v "KnowledgeQuizService.java"
```

结果里只有那两个工具与 `RecitationControllerTest`。`listQuizzes` 只被
`RecitationController` 用 ⇒ **不上端口**（前端 HTTP 契约，受众不同，§2.7 那条线）。

> **判据是"这个方法还有没有别的调用方"，不是"它看起来像不像一个富方法"。**

### 3quaterdecies.3 ⚠️ 无形状字段：`items` 是 `Object`，三种做法只有第三种对

域侧 `KnowledgeQuizAttemptView` 有一个分量：

```java
/** 逐题证据，反序列化自 items_json 列。 */
private final Object items;
```

它的运行时类型是"从 `items_json` 那一列反序列化出来的任意 JSON" ——
**那一列里可能有旧版本写下的内容**。三种做法：

| 做法 | 结果 |
|---|---|
| 把 `Object` 搬进契约 | 等于把"随便什么"写进契约。契约要能回答"这个分量是什么形状"，`Object` 回答不了 |
| 收窄成一个新的契约 record（`QuizItemView`） | 契约会开始**假装知道**历史行里存的是什么形状。旧行可能没有 `comment`，也可能多一个字段 —— 契约一旦钉了形状，读取端就必须做兼容，而它**没有做兼容的地方** |
| **契约带文本（`itemsJson`），解析留给消费方** | ✅ 契约只承诺"这是一串 JSON 文本"，不承诺内部形状 |

`QuizAttemptView.itemsJson` 因此是 `String`，解析上移到工具里
（`QuizKnowledgeTool.parseItems`，空/非法/`null` 一律退化成空数组 ——
单条历史坏掉不该把整场测验带下水）。

> **这一次 ADR-1 不是代价，而是答案。** 契约不带 JSON 库 ⇒ 契约**没法**解析 ⇒
> 只能带文本；而"契约没法解析"正是 ADR-1 想要的。
> 前三刀 ADR-1 都表现为"要绕一下"（§3octies / §3decies / §3undecies），
> 这一刀它**直接给出了正确答案**：一个"什么都不知道"的契约，
> 恰好是唯一不会对历史行撒谎的契约。

### 3quaterdecies.4 两个域内 record 被**删掉**，不是搬走

`KnowledgeQuizService.QuizSession` / `QuizOutcome` / `GradedItem` 是服务里的嵌套 record，
**同域生产消费方为零**（只有那两个工具 + 服务自己的测试）。所以它们不是"搬进契约"，
而是**被契约类型取代后删除**：

| 原类型 | 取代者 | 分量变化 |
|---|---|---|
| `KnowledgeQuizService.QuizSession` | `QuizSessionView` | 15 → 15（全带） |
| `KnowledgeQuizService.QuizOutcome` | `QuizOutcomeView` | 11 → 10（去掉 `lastReviewAt`） |
| `KnowledgeQuizService.GradedItem` | `GradedItemDraft` | 4 → 4 |
| `KnowledgeQuizAttemptView`（前端可见） | `QuizAttemptView`（新增，**收窄**） | 8 → 6 |

> **"删掉它不会有别的症状"的约束必须有专门测试**（推演法：假设删掉，看还有谁会红）。
> 这里的验证是：删掉后全反应堆 1362 个测试绿，且 `RecitationControllerTest` **一行没改**
> —— 说明它们真的只有那两个工具在依赖。
> 注意 `KnowledgeQuizAttemptView` **不是被取代**，它是**新增的第二个投影**（见 .6）。

`QuizOutcomeView` 去掉 `lastReviewAt` 是**收窄**（按 §2.5 的分支判断）：
`GradeAnswerTool` 只读 `masteryLevel` / `previousMasteryLevel` / `reviewCount` / `nextReviewAt`。
`QuizSessionView` 则是**全带** —— 工具 15 个分量全读。

### 3quaterdecies.5 两个常量跟着端口搬（第二次）

`MAX_ITEMS = 20` 与 `MAX_VERDICT_CHARS = 1000` 从 `KnowledgeQuizService` 搬到
`RecitationQuizPort`。理由与 §3duodecies.4 完全相同：`GradeAnswerTool` 要把它们写进
工具的 JSON Schema（"最多 N 题"），只要还写着 `KnowledgeQuizService.MAX_ITEMS`，
工具就仍然 import 提供方的实现类，实现语句一条都没少 —— 端口白抽了。

**搬完的判据**（比看指标快）：

```bash
grep -rn "import com\.xander\.lab\.recitation\." apps/core-host/src/main/java/com/xander/lab/agent/
# 结果里只剩 recitation.spi.*
```

### 3quaterdecies.6 ⚠️ 一份实体、两份投影：新增一条"它们必须一致"的测试

`KnowledgeQuizAttempt` 这一张表现在被投影成两个类型：

| 投影 | 类型 | 受众 | `items` 形态 |
|---|---|---|---|
| 前端 | `recitation.api.KnowledgeQuizAttemptView` | `RecitationController` | **已解析**（`Object`） |
| 契约 | `recitation.spi.QuizAttemptView` | `agent` 的两个工具 | **文本**（`String`） |

两个投影各写一份最危险的地方是**它们会悄悄漂移**，而两边各自的测试都不会红：
前端的测试只断言解析后的结构，契约的测试只断言文本，**谁都不看"这两个投影在共享字段上是否一致"**。

`RecitationQuizPortMappingTest.theContractProjectionAndTheFrontendProjectionAgreeOnTheFieldsTheyShare`
把这条补上：同一条历史行，两个投影的 `questionCount` / `correctCount` / `score` / `verdict` /
`createdAt` 必须相等。

⚠️ **注入 D 证明它有独立价值**：把 `toAttemptView` 的 `questionCount` 与 `correctCount` 对调时，
它和逐字段落点测试**同时**变红，而工具侧与前端侧的测试全绿。
没有它，一旦哪天有人把逐字段落点测试当成"太琐碎"删掉，两份投影就会静默漂移。

> **凡"一份实体投影成两份类型"的地方都要配一条同款断言。**
> `ContentPost` / `MediaAssetRef` 收窄时（§4 待办）会再次遇到。

### 3quaterdecies.7 端口形状：2 个方法、2 个常量、没有 `default`

```java
int MAX_ITEMS = 20;
int MAX_VERDICT_CHARS = 1000;

QuizSessionView openQuiz(Long userId, Long materialId, Long conversationId);
QuizOutcomeView recordQuiz(Long userId, Long quizSessionId, Long conversationId,
                           String verdict, List<GradedItemDraft> items);
```

域侧 `KnowledgeQuizService` 有 7 个公开方法，端口只收 2 个。**没收进来的**：
`openQuiz(Long, Long)` 两参重载（**没有任何生产调用方**）、
`listQuizzes(Long, Long)`（只有控制器在用）、以及几个私有 helper。

⚠️ `openQuiz` 的两参重载这次**保留在服务上**（不像 §3terdecies.7 那样改名）：
它不与端口方法冲突，只是端口不收它 —— 两参重载继续服务控制器路径。
**"同名不同签名"只在真的冲突时才需要处理**，不要为了对称去改没冲突的东西。

### 3quaterdecies.8 端口"变宽"依然没人守（第八次确认）

同 §3decies.7 / §3undecies.8 / §3duodecies.6 / §3terdecies.11：
加一个 `default` 方法编译通过、指标不动、26 条门禁全 PASS，**只有反射测试会红**。
本轮注入 A 第八次确认（3 条红，`RecordShapeTest` 保持绿）。
**端口宽度仍然只有测试在守，没有门禁。**

### 3quaterdecies.9 实测收益

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 38 | **36** |
| `implementation_edges` | 17 | **16** |
| `cross_deployment_unit_statements` | 21 | **19** |
| `cross_deployment_unit_edges` | 10 | **9** |
| `api_statements` | 13 | **12** |

**`agent → recitation` 整条边从清单里消失。**
`api` 与 `impl` 的降幅这次是 **1 比 2**（`GradeAnswerTool` 原本只 import 了实现类，
没有 `recitation.api.*` import）—— 又一次印证**看收益必须两列都看，比例没有规律**：

| 刀 | impl 降 | api 降 |
|---|---|---|
| §3undecies（第八刀） | 6 | **9** |
| §3duodecies（第九刀） | 2 | **0** |
| §3terdecies（第十刀） | 4 | **3** |
| **本刀（第十一刀）** | **2** | **1** |

### 3quaterdecies.10 图形状：`recitation` 成为零跨域消费方的域

`agent → recitation` 消失后，`recitation` 与 `agent` 之间的**双向**依赖都不存在了。
`agent` 的剩余出边四条**全部跨部署单元**：

| 边 | impl | api | 单元 |
|---|---|---|---|
| `agent → blogagent` | 4 | 2 | `dinqor-agent` → `dinqor-content-automation` |
| `agent → identity` | 2 | 0 | → `dinqor-core-host` |
| `agent → integration` | 2 | 0 | → `dinqor-platform-integration` |
| `agent → publishing` | 0 | 1 | → `dinqor-content-automation` |

Wave 2 强连通分量仍是 {content-automation, core-host, platform-integration}；
`dinqor-agent` 与 `dinqor-bloghtml-preview` 仍是孤立单元（可单独抽取）。

### 3quaterdecies.11 为什么没有加 `contract-changes.yaml` 条目

同 §3bis / §3nonies / §3undecies / §3duodecies / §3terdecies：
**这一刀没有改变任何前端可见的形状**。

- `RecitationController` 的四个端点路由、响应形状、错误码一行未变。
- 前端可见的 `KnowledgeQuizAttemptView` **原样保留**（控制器继续用它），
  契约侧的 `QuizAttemptView` 是**新增的另一个类型**，不是它的替代品。
- `openQuiz` 的返回类型变了，但 `openQuiz` **不是 HTTP 端点** —— 控制器走自己那条路径。

所以这是一次可证明的纯重构。

### 3quaterdecies.12 注入验证：五次注入，每次变红的都正好是预期的那些

| # | 注入 | 期望 | 实测 |
|---|---|---|---|
| A | 端口加 `default` 方法 | 3 条 | **3 条**（`RecordShapeTest` 与材料端口测试保持绿） |
| B | `MAX_ITEMS` 20 → 10 | 1 条 | **1 条** |
| C | `openQuiz` 里 `title` 与 `knowledgeType` 对调 | 1 条 | **1 条**（工具测试与服务测试保持绿） |
| D | `toAttemptView` 里 `questionCount` 与 `correctCount` 对调 | 2 条 | **2 条** |
| E | 守卫清单里删掉 `KnowledgeQuizService` | 1 条 | **1 条**，消息点名缺的是哪个类 |

注入 A 里的"参数个数守卫"给出的是**有意义的失败**
（"injectedWidening 没有参数：端口方法必须显式收调用方 id"），
不是 `ArrayIndexOutOfBounds` —— 这是 §3duodecies.9 修掉的那处测试缺陷
在**连续两刀里的第二次生效**。

### 3quaterdecies.13 测试

`recitation-api` 16 → **35**（`RecitationQuizPortTest` 10 + `RecordShapeTest` 15 是扩展后的）；
`core-host` 1161 → **1168**（`RecitationQuizPortMappingTest` 4 是新类，
两个工具测试各 +1/+2）；全反应堆 1336 → **1362**（0 fail / 0 err）。
26 门禁 → 24 pass / 0 fail / 2 skip；`contract-dependency-boundary` 报 **7 个模块 / 49 个源文件，JDK-only**。

### 3quaterdecies.14 下一刀

`agent → blogagent`（4 impl / 2 api，三个工具：`BlogGenerationTool` / `ImageGenerationTool` /
`PublishPostTool`）。它是 agent 最大的出边，而且 `PublishPostTool` **一个文件同时贡献两条边**
（`agent → blogagent` 与 `agent → integration` 那 2 条）⇒ 做它可能顺带清掉第三条边。

⚠️ 与这一刀不同的是：`blogagent` 的目标类型可能**不是** `Object` 那样的无形状字段，
但也可能撞上 `agent-api` 那种"要抽的端口属于 agent 自己的工具体系"的反向问题 ——
**先侦察，再定形状**。

**结果（§3quinquiesdecies）**：形状与 `agent-api` 那条不同 —— 目标类型**两个都不能搬**
（一个 25 字段带 `@TableName`、一个 17 字段的 `api` VO），但**也没有无形状字段**；
这一刀真正的新东西是**参数顺序**。上面那句"可能顺带清掉第三条边"**猜错了一半**：
清掉的不是猜的 `agent → integration`（那 2 条还在 `PublishPostTool` 里，见 §3quinquiesdecies.9），
而是**没猜到的** `agent → publishing`（0 impl / 1 api）—— 因为 `PublishPostTool` 恰好是
`BlogPostVO` 的**唯一跨域读者**。
> 教训：**"一个文件贡献多条边"的预测，不要猜是哪一条。** 先 `grep` 出那个文件到底 import 了谁，
> 再判断哪条边会被动到 —— 否则会像这次一样，把"收益存在"和"收益是哪一条"混为一谈。

## 3quinquiesdecies. 第十二刀：`contracts/blogagent-api` —— 参数顺序归一，与"守卫只朝一个方向看"的第三次实例

### 3quinquiesdecies.1 目标边

| from → to | impl | api | 文件 |
|---|---|---|---|
| `agent → blogagent` | 4 | 2 | `BlogGenerationTool`、`PublishPostTool`（`ImageGenerationTool` 见 §3quinquiesdecies.9） |
| `agent → publishing` | 0 | 1 | `PublishPostTool`（`BlogPostVO` 的**唯一**跨域读者） |

这是 §3quaterdecies.14 写下的待办，也是本文的**第二个"建新模块"的刀**（上一个是 §3terdecies）。
做完之后 `agent → blogagent` 从 4/2 降到 **2/0**（只剩 `ImageGenerationTool`），
而**没在待办里写出来的** `agent → publishing` **整条边消失**。

### 3quinquiesdecies.2 侦察：一个目标域、三条出边，只有两条值得这一刀做

`blogagent` 是 `agent` 最大的出边目标，而且它有**三个**跨域消费方：

| 消费方 | 原来依赖 | 性质 |
|---|---|---|
| `BlogGenerationTool` | `blogagent.application.BlogAgentService`、`blogagent.api.BlogAgentTaskCreateRequest`、`blogagent.api.BlogAgentTaskVO`、`blogagent.domain.BlogAgentTask` | 工具类，走服务 |
| `PublishPostTool` | `blogagent.application.BlogAgentService`、`publishing.api.BlogPostVO` | 工具类，走服务 |
| `ImageGenerationTool` | `blogagent.infrastructure.BlogAgentImageClient`、`BlogAgentProperties` | **形状不同**：一个具体客户端 + 一个 `@ConfigurationProperties` |

前两个是"薄壳 + 换算"的熟悉形状，第三个不是。`ImageGenerationTool` 被**刻意留下**
（§3quinquiesdecies.9），所以这一刀只做前两个。

> 判据仍然是"这个消费方要的**端口形状**和我已经会做的那个是不是同一个"，
> **不是**"它是不是 import 了目标域"。同一个域的三个消费方可以形状完全不同。

### 3quinquiesdecies.3 ⚠️ 参数顺序归一：端口统一 `(userId, taskId)`

提供方这四个方法的声明顺序是**反的**：

```java
public BlogPostVO publish(Long taskId, Long userId)
public BlogPostVO findPublishedPost(Long taskId, Long userId)
public BlogAgentTaskVO runStream(Long taskId, Long userId, ...)
public BlogAgentTask findReadyTaskByClientRequestId(Long userId, String clientRequestId)  // 这个是对的
```

端口统一成 `(userId, ...)`，理由三条：

1. 前四个契约（`EmailReminderPort`、`ContentPlanPort`、`RecitationMaterialPort`、
   `RecitationQuizPort`）全都是 userId 在首位 —— 归一才有一致的消费方心智。
2. 消费方的心智模型是"我（userId）对某个资源（taskId）做事"。
3. 第三条最要命：**既有的"首参必须是调用方 id"守卫只断言"第一个参数的类型是 `Long`"，
   而 `publish(Long taskId, Long userId)` 同样满足它。**

### 3quinquiesdecies.4 ⚠️ "守卫只朝一个方向看"的第三次实例

这一刀之前的每一份端口测试里都有一条守卫，名字叫
`everyMethodTakesTheCallerIdExplicitlyAsItsFirstParameter`，
而它的**实现**只断言 `getParameters()[0].getType() == Long.class`。

**名字声称的是强性质（"是调用方 id"），判据断言的是弱化后的性质（"是 Long"）。**
所以如果这里照抄提供方的顺序，守卫会**绿着**放过一个它自己名字所禁止的形状。

> 这是"判据只朝一个方向看"的**第三次**实例。前两次：
> ①`SHARED_KERNEL` 里的 `util` 是死条目（门禁只查"每个包都有条目"，不查"每个条目都有包"）；
> ②`deployment-unit-coupling` 判据 2 的根取了 `WAVE2_UNITS[0]`，而它恰好是已脱离分量的
> `dinqor-agent` ⇒ `joined` 恒为空 ⇒ 判据再也不会红（§3undecies.7）。
>
> 三次的共同点：**判据断言的是一个弱化后的性质（"是 Long" / "有包" / "有成员"），
> 而名字声称的是强性质（"是调用方 id" / "有反向条目" / "会变红"）。**

⚠️ 这一条的**发现方式**值得记：它不是注入验证发现的，而是**写端口签名时顺手想到**的
（"如果我照抄提供方顺序，守卫会怎样？"）。注入验证只能证明"已写的判据会红"，
证明不了"判据的强度够不够" —— 后者要靠**反着用一次**：把判据拿去断言一个它本该拒绝的形状。

### 3quinquiesdecies.5 于是守卫改钉**参数名**

`javac` 在本项目开了 `-parameters`，参数名会进 class 文件。已实测：

```bash
javap -v -p contracts/recitation-api/target/classes/com/xander/lab/recitation/spi/RecitationMaterialPort.class \
  | grep MethodParameters
```

⇒ `MethodParameters` 属性在。所以新契约的测试不再只断言类型，而是断言**参数名**：

```java
assertThat(method.getParameters()[0].getName()).isEqualTo("userId");
assertThat(method.getParameters()[1].getName()).isEqualTo("taskId");
```

这条判据是**强**的：`publish(Long taskId, Long userId)` 会让它红，而类型断言不会。
失败信息本身就说明了问题：

```text
BlogAgentPort.publish 的第一个参数叫 ownerId，不叫 userId。只断言"类型是 Long"是不够的：
publish(Long taskId, Long userId) 同样满足那个断言。
```

> ⚠️ 代价：这条守卫依赖 `-parameters`。如果哪天有人把它从编译配置里删掉，
> `MethodParameters` 消失，`getParameters()[0].getName()` 退化成 `arg0` ⇒ 守卫**变红**
> （而不是静默放过）。这是一个**会自己喊出来的**依赖，可以接受。

### 3quinquiesdecies.6 收窄：4 个分量与 5 个分量

| 契约类型 | 域侧来源 | 分量 | 说明 |
|---|---|---|---|
| `BlogAgentTaskDraft` | `blogagent.api.BlogAgentTaskCreateRequest` | 2 | 去掉 `@NotBlank` / `@Size`（`jakarta.validation` 也是框架，ADR-1） |
| `BlogAgentTaskView` | `blogagent.domain.BlogAgentTask` | **4** | 域实体 25 个字段且带 `@TableName` ⇒ 按 ADR-2 不许跨边界 |
| `BlogAgentPostView` | `publishing.api.BlogPostVO` | **5** | 17 个字段；两个消费方读的就是这 5 个 |

`BlogAgentTaskView` 只带 `id` / `title` / `summary` / `publishedPostId`：`BlogGenerationTool`
的 `outcome()` 读的正是这四个。⚠️ `publishedPostId` 是**装箱的 `Long`**，因为工具用
`== null ? -1 : value` 区分"没发布过"与"发布了"（"没传 ≠ 传了空"，§3duodecies.7）。

`BlogAgentPostView` 的 `status` 是**装箱的 `Integer`**：域侧 `BlogPostVO.status` 可以为 `null`，
而调用方的判据是 `status == null || status != 1`。换成 `int` 会把"没传"变成"传了 0"。

⚠️ `BlogAgentPostView` 与 `content.api.ContentPost` **形状相同但不能合并**：契约不许依赖契约
（只允许 JDK + 本包，§2.7 / §4.6），而且一个是 blogagent 的能力面、一个是内容域的文章模型，
让其中一个替另一个发言就是把两个域的词汇表绑在一起。归一放在 `PublishPostTool` 内部
（一个 5 行的私有 `LocalPost` record）。

### 3quinquiesdecies.7 顺手收益：`agent → publishing` 整条边消失

`PublishPostTool` 是 `publishing.api.BlogPostVO` 的**唯一跨域读者**。它改走 `BlogAgentPostView`
之后，`agent → publishing`（0 impl / 1 api）整条边从清单里消失。

> 这是"**端口只收调用方真正用到的字段**"的直接后果：
> 不为了"统一返回类型"把整个 VO 搬上端口，那条边就不会存在。
> 反过来说，**端口收宽一点点的代价可能是一整条边**。

⚠️ 与 §3quaterdecies.14 的预测对照：那一节猜"`PublishPostTool` 一个文件同时贡献
`agent → blogagent` 与 `agent → integration` ⇒ 做它可能顺带清掉第三条边"。
**猜错了。** `agent → integration` 那 2 条还在（`PublishPostTool` 仍 import 集成域），
真正消失的是**没被猜到的** `agent → publishing`。

> 教训：**"一个文件贡献多条边"的预测，不要猜是哪一条。**
> 先 `grep` 出那个文件到底 import 了谁，再判断哪条边会被动到 ——
> 否则会把"收益存在"和"收益是哪一条"混为一谈。

### 3quinquiesdecies.8 实测收益（重新生成基线后）

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 36 | **34** |
| `implementation_edges` | 16 | **16**（不变） |
| `cross_deployment_unit_statements` | 19 | **17** |
| `cross_deployment_unit_edges` | 9 | **9**（不变） |
| `api_statements` | 12 | **9** |
| `api_edges` | 8 | **6** |

`agent → blogagent` **4 impl / 2 api → 2 impl / 0 api**；`agent → publishing`（0 / 1）**整条边消失**。

⚠️ **两条边数指标一个都没动。** 原因不同：
`agent → blogagent` 因为 `ImageGenerationTool` 还在，边还在；
`agent → publishing` 消失了，但它是 **api-only 边**（0 impl），不进 impl 边计数。

> **"边数不变"不等于"什么都没发生"** —— 这与 §3quaterdecies 那次
> "api 与 impl 降幅 1 比 2"是同一类现象：**四个指标要一起看，任何一个单独看都会误判。**
> 一个只看 `implementation_edges` 的人会认为这一刀白干了。

`api_statements` 12 → 9（−3）：三个工具原本 import 的 3 个 `blogagent.api.*` 类型
（`BlogAgentTaskCreateRequest` / `BlogAgentTaskVO`）与 `publishing.api.BlogPostVO` 全换成
`blogagent.spi.*`，而**契约包在 `SHARED_KERNEL` 里不计任何指标**（§6）。

### 3quinquiesdecies.9 刻意没做的事

- **`ImageGenerationTool`（图片生成）**。它走 `blogagent.infrastructure`，形状与前面所有端口都不同：
  是一个具体客户端 + 一个 `@ConfigurationProperties`，而且它自带两个决定：生成的图片以
  `byte[]` 过界；用量要以三 long 的记录过界，而 `ModelUsage` 住在 `libs/xander-model`（带 Jackson）
  ⇒ 按 ADR-1 契约不能依赖它，需要契约自带等价 DTO（同 `payment.api.TokenUsage` 的先例）。
  **两个决定都不是搬家决定**，所以单独一刀。
- **`generateTopicSeries`**：唯一调用方是 `automation.BlogContentPlanService`，与 blogagent
  **同属 `dinqor-content-automation`** ⇒ 改它不动任何跨单元指标。
- **`listSessions` / `reviseStream` / `reviewCompliance`**：调用方都是 `BlogAgentController`
  （同域前端 HTTP 层，§2.7 那条线）。
- **`BlogAgentService` 的 14 个构造依赖**没有动。端口实现是"薄壳 + 换算"，服务内部结构不变。

### 3quinquiesdecies.10 ⚠️ `runStream` 的静默换位：三参重载必须点名 `ForView`

`runStream` 现在有三个重载：

```java
runStream(Long taskId, Long userId, Consumer<String> onDelta)                        // 前端 SSE 用
runStream(Long taskId, Long userId, BiConsumer<String, String> onEvent)              // 同域
runStream(Long userId, Long taskId, BiConsumer<String, String> onEvent, BooleanSupplier)  // 端口
```

第三个与第二个**参数表逐字相同**（`Long, Long, BiConsumer, BooleanSupplier`），只是含义与返回值
不同 —— 所以两者不能同名，端口拿规范名，同域那个加 `ForView` 后缀（§3duodecies 的同名规则）。

**危险在于三参重载的转发**：它原来写的是 `runStream(taskId, userId, onEvent, () -> false)`。
如果保留这行，它会**静默解析到端口方法**（四参、同样的类型），两个 `Long` 直接换位 ——
而编译器不会说一个字。所以它必须显式写成 `runStreamForView(taskId, userId, ...)`，
并且这一点由 `BlogAgentPortMappingTest.theThreeArgumentOverloadForwardsToTheForViewMethodNotThePortMethod`
钉住（用 `verify(service, never()).runStream(any(), any(), any(), any())` 反向断言）。

> 一般化的教训：**当"归一参数顺序"产生了一个与旧方法参数表逐字相同的新重载时，
> 所有原来转发到旧方法的调用点都变成了静默换位点。**
> 数量不多（这里 1 处），但每一处都要显式点名新名字，且每一处都要有测试。
> 这与 §4.6 的 `TokenUsage` 换位、§5.6 的"相邻同类型参数"是同一个家族：
> **两个同类型参数挨在一起时，编译器永远帮不上忙。**

### 3quinquiesdecies.11 两个富方法改名 + 一个"直接就是实现"

| 方法 | 处理 | 同域调用方 |
|---|---|---|
| `runStream(taskId, userId, BiConsumer, BooleanSupplier)` | 改名 `runStreamForView` | 三参重载 + `BlogAgentController` 的 SSE |
| `publish(taskId, userId)` | 改名 `publishForView` | `BlogAgentController#publish`（响应体就是 `BlogPostVO`） |
| `findReadyTaskByClientRequestId(userId, clientRequestId)` | 改名 `findReadyTaskByClientRequestIdForView` | `BlogAgentServiceIdempotencyTest` |
| `findPublishedPost(taskId, userId)` | **直接就是端口实现**，换签名换返回类型 | **无**（唯一调用方是跨域的 `PublishPostTool`） |

⚠️ 最后一行按 ADR §7.5 判据：**同域生产消费方为零 ⇒ 不保留富方法**，直接改。
给一个没有调用方的富方法留后缀是死代码。"删掉它不会有别的症状"由全量测试守着
（`BlogAgentController` 从不调用它）。

⚠️ `create` 那一侧**不用改名**：端口方法收 `BlogAgentTaskDraft`，与域侧
`BlogAgentTaskCreateRequest` **参数类型不同**，不构成"同名同参"，两个重载可以共存。

> **"冲突"不等于"同名"。** 只有"同名**且**同参数表"才冲突。
> 不要为了对称去改那些本来就不冲突的方法。

### 3quinquiesdecies.12 为什么没有加 `contract-changes.yaml` 条目

三个契约类型都是**新缝**（不是替换某个前端可见类型），而且都**收窄**了。
按 §2.5 的分支判断：镜像才可能"序列化逐字节不变 ⇒ 可证纯重构 ⇒ 不进登记表"，
收窄是**新形状**，本应登记 —— 但它们**不是前端可见类型**：`BlogAgentTaskView` 与
`BlogAgentPostView` 只出现在跨进程端口上，前端看不到；`BlogAgentTaskDraft` 同理。
`contract-changes.yaml` 登记的是"前端可见契约的变化"，这三个不在其内。

⚠️ 反过来说：`BlogAgentTaskView` / `BlogAgentPostView` **在 Wave 2 之后会变成线上格式**
（跨进程调用）。届时它们的字段顺序就是线上格式，任何重排都要走 §11。
这一条已记入 ADR §13 待办第 26 项。

### 3quinquiesdecies.13 注入验证：五次注入，每次变红的都正好是预期的那些

| # | 注入 | 期望 | 实测 |
|---|---|---|---|
| A | 端口加一个 `default` 方法 | 2 | **2**（`declaresNoDefaultMethods` + `hasExactlyTheseFiveMethods`） |
| B | 端口方法首参改名（`userId` → `ownerId`） | 2 | **2**（两条参数名守卫） |
| C | `publish` 里两个 `Long` 对调 | 1 | **1** |
| D | `toTaskView` 里 `title` 与 `summary` 对调 | 3 | **3**（三个测试共用那个换算） |
| E | `PortDelegationTransactionTest` 的守卫清单里删掉 `BlogAgentService` | 1 | **1**，消息点名缺哪个类 |

⚠️ **注入 A / B / C / D 的期望值第一版写的是 3 / 1 / 2 / 1，全部与实测不符**，已按实测改。
写文档时**不要沿用上一刀的数目** —— 每次的测试分布都不同（A 之所以是 2 而不是 3，
是因为"没有 `default`"这条被两个测试分别断言；D 之所以是 3，是因为那个换算被三个测试共用）。

**注入 B 是这一刀新建立的守卫，它的失败信息本身就是 §3quinquiesdecies.4 的证据**（见上）。
**注入 C 逮到一处测试缺陷（已修）**：第一版把换位写成"用具体字面量打桩，写反了自然匹配不上"，
结果症状是 `null` 一路传到断言里变成 **NPE** —— 报的信息指向"某处为 null"而不是"两个 Long 换位了"。
改成 `thenAnswer` 里断言实收的两个值之后，失败信息是 `expected: 9L but was: 42L`。

> **守卫的失败信息必须指向真正的原因。** 这条在本项目已踩过一次
> （`getDeclaredMethods()[0]` 越界，见 ADR §8.11）。**第二次踩同一个坑，说明它值得进规范。**

⚠️ 顺带确认一个**分工**：`everyPostComponentComesFromItsOwnSourceField` 用的是
`any(), any()`，所以它**抓不到换位**；换位只由那条专门的测试抓。两个测试各有职责，缺一不可 ——
**"有一个测试覆盖了这个方法"不等于"这个方法的所有性质都被覆盖了"。**

### 3quinquiesdecies.14 测试

`blogagent-api` **+15**（新模块）：`BlogAgentPortTest`(9) + `RecordShapeTest`(6)。
`apps/core-host` 1168 → **1177**（+9）：`BlogAgentPortMappingTest`(7) 是新类 ——
钉三个换算的逐字段落点、两处换位、以及"三参重载必须点名 `ForView`"；
`BlogGenerationToolTest` 与 `PublishPostToolTest` 改 mock 端口后各净增（其中一个新增用例是
`reconcileOnATaskIdReadsThePublishedPostWithUserIdFirst`，把 `findPublishedPost` 的换位
与 `publish` 的换位分开覆盖）；`PortDelegationTransactionTest` 清单加一个类（数目不变）。
全反应堆 1362 → **1386**（0 fail / 0 err）。
26 门禁 → 24 pass / 0 fail / 2 skip；`contract-dependency-boundary` 报
**8 个模块 / 54 个源文件，JDK-only**；`dockerfile-module-coverage` 报 12 个模块。

### 3quinquiesdecies.15 下一刀

剩余跨单元实现边（按语句数）：`automation → integration` 6、`agent → blogagent` 2、
`agent → identity` 2、`agent → integration` 2、`admin → automation` 1、
`identity → integration` 1、`integration → automation` 1、`integration → identity` 1、
`studio → blogagent` 1。

按"能不能一刀干净收掉"排序，候选：

1. **`agent → blogagent` 剩下的 2 条（`ImageGenerationTool`）** —— 同一目标域的第三刀，
   形状是新的（具体客户端 + `@ConfigurationProperties`），两个决定（`byte[]` 过界、
   契约自带用量 DTO）。做完 `agent → blogagent` 归零。
2. **`agent → identity`（2）** —— 在 `agent/api/` 下，是 WebSocket 握手鉴权，
   形状与工具类**完全不同**，要单独判断（可能落进 `xander-auth-core` 而不是契约）。
3. **`agent → integration`（2）** —— 与 `PublishPostTool` 同一文件，可与第 1 项合刀。
4. **`automation → integration`（6）** —— 目前最大的跨单元边，但卡在 `MailService` 的
   API 变更上（模板环，§3quater.5），不是纯粹的"抽端口"。

**结果：第 1 项已做完（§3sexdecies）。** `agent → blogagent` **整条边消失**，
`agent` 对 `blogagent` 的依赖归零。剩 `identity` 2 与 `integration` 2 —— **两条都跨部署单元**。

---

## 3sexdecies. 第十三刀：`blogagent-api` 的图片能力 —— 一个不收 `userId` 的端口

### 3sexdecies.1 目标边

`agent → blogagent` 剩 **2 impl / 0 api**，两条语句都在 `ImageGenerationTool` 里，是两个 import：

```java
import com.xander.lab.blogagent.infrastructure.BlogAgentImageClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
```

⚠️ **包名是 `infrastructure`**，不是上一刀消费的 `application`。
这一刀的消费方**绕过了 `BlogAgentService`**，直接拿基础设施层的两个类：

| 类型 | 形状 | 判据 |
|---|---|---|
| `BlogAgentImageClient` | `@Component` 具体类（RestClient + HttpClient + 端点回退 + 下载 URL 校验 + 10MB 上限） | 具体实现 ⇒ **抽端口**（ADR-2 判据②） |
| `BlogAgentProperties` | `@Data @Component @ConfigurationProperties(prefix = "blog.agent")` | 配置类 ⇒ **本来就不该过界**（见 §3sexdecies.6） |

### 3sexdecies.2 侦察：`GeneratedImage` 只有两处消费方，一处跨域一处同域

- **跨域**：`agent.ImageGenerationTool`（这一刀要动的）；
- **同域**：`blogagent.BlogAgentService.generateIllustrations`（不动）。

⇒ 同域那条路径继续直接调 `imageClient` ⇒ **域内 record `GeneratedImage` 一个字都不用改**。
这是"一半的价值来自没碰的东西"的又一例：改完 `agent → blogagent` 归零，
而 blogagent 域内部一行没动。

### 3sexdecies.3 ⚠️ 形状决定一：这是**第二个端口**，不是往 `BlogAgentPort` 加两个方法

最省事的做法是给 `BlogAgentPort` 加 `isImageEnabled()` / `generateImage(...)`。**没这么做。**

判据是端口设计的老规则：**端口宽度 = 调用方适配器被迫实现的成员集。**
`BlogAgentPort` 现有的 5 个方法形状是"用户拥有的资源上的域方法"（都收 `userId`，
都落到 `BlogAgentTaskMapper` / `blogService`）。而图片能力的形状是"一个具体客户端 + 一份配置"，
既不落库也不认人。混进一个接口，`BlogAgentService` 就得同时扮演两种角色，
而**下一位读者会以为这 7 个方法是同一种东西**。

```java
public interface BlogAgentImagePort {
    boolean isEnabled();
    BlogAgentImage generate(String prompt);
}
```

⚠️ 收益：**扩契约模块加第二个端口，构建接线是零**（不新增模块 ⇒ 根 POM、应用 POM、
`scan.py` 的 `SHARED_KERNEL`、Dockerfile 四处一个都不用改）。同
`core-api` + `UserLookupPort`、`automation-api` + `NotificationPort`、
`recitation-api` + `RecitationQuizPort` 的先例。

### 3sexdecies.4 ⚠️ 形状决定二：图片以 `byte[]` 过界

契约不能带 Jackson（ADR-1）⇒ 也就不能带 `InputStream` / `Resource` / 任何序列化容器。
先例已有：`MediaAssetPort.recordGenerated(userId, ..., byte[] bytes, ...)`。

⚠️ 代价必须写下来：10MB 上限、下载 URL 校验、内容类型嗅探**全部留在提供方**
（`BlogAgentImageClient`）。契约只承诺"给你一份字节和它的元信息"。

### 3sexdecies.5 ⚠️ 形状决定三：`ModelUsage` 带 Jackson ⇒ 契约自带三 long 的等价 DTO

`GeneratedImage` 的第 4 个分量是 `com.xander.lab.common.ModelUsage` ——
它**本身是干净的**（纯 record、无注解），但住在 `libs/xander-model`，
而那个模块声明了 `jackson-databind`（同一模块里还有 `ModelUsage.fromJson(JsonNode)`
和 `ModelCallResult.payload`）。

⚠️ **契约不能 import 一个带框架的共享库**，哪怕要用的那个类型自己是干净的。
这是 ADR-1 从"不能 import Jackson"往前推一步的直接结果，也是 §4 踩过一次的坑
（`payment.api.TokenUsage` 就是这么来的）。于是**又写了一个三 long 的 record**：

```java
public record BlogAgentImageUsage(long inputTokens, long cachedTokens, long outputTokens) {
    public static final BlogAgentImageUsage NONE = new BlogAgentImageUsage(0, 0, 0);
    public boolean isEmpty() { return inputTokens <= 0 && cachedTokens <= 0 && outputTokens <= 0; }
}
```

它与 `payment.api.TokenUsage` **逐字段同形**。**刻意的重复，不是疏漏** ——
契约不能依赖契约（§2.3），"同一形状写两遍"是这条规则的标准代价。

⚠️ **三个相邻的 `long` 是这一刀最危险的一处**：换序能编译、能过大部分测试，
只会在生产上静默错账。由 `imageUsageHasThreeAdjacentLongs` 钉住（分量名 + 顺序 + 类型），
注入 C 实测 3 处红。

⚠️ 还有一条容易写反的语义：`isEmpty()` 是 **"每一个分量都 ≤ 0"**，不是"有任意一个是 0"。
一次只命中缓存的正常调用（`outputTokens == 0`）若被判成"没有用量"，那次调用就**静默不扣费**。
`imageUsageIsEmptyOnlyWhenEveryComponentIsNonPositive` 用三组互补字面量把两个方向都钉住。

### 3sexdecies.6 ⚠️ 形状决定四（这一刀真正的设计点）：消费方不读提供方的配置

如果端口只回一份 `byte[]`，`ImageGenerationTool` 就得自己知道 `model` 和 `size`
才能写进素材的 `meta`；它唯一的来源是 `BlogAgentProperties`，也就是 `blog.agent` 前缀的配置。

⚠️ **一旦消费方去读提供方的 `@ConfigurationProperties`，提供方的"配置"就变成了跨域契约。**
配置可以改名、可以给默认值、可以整段不存在（网关路径下 `blog.agent` 就不配），契约不行。
而且这会让 `blog.agent.*` 的任何一个键变成**事实上的公共 API**。

所以由**提供方的适配器**把"这次生成实际用了什么"填进结果：

```java
return new BlogAgentImage(generated.bytes(), generated.contentType(), generated.extension(),
        usage == null ? BlogAgentImageUsage.NONE
                : new BlogAgentImageUsage(usage.inputTokens(), usage.cachedTokens(), usage.outputTokens()),
        generated.model(), properties.getImageSize());
```

⚠️ 两个字段来源**不一样**，而且是刻意的：`model` 取 `generated.model()`（**实际端点**报的）；
`size` 取 `properties.getImageSize()`（客户端**不报**实际像素，只能给请求值）。

⚠️ 注意 `usage == null` 必须落成 `NONE`，**不能过界成 `null`** ——
契约承诺非空，消费方因此读三个分量时**没有兜底**。否则症状是：图片已生成已入库，
然后在扣费那一步 NPE。

### 3sexdecies.7 ⚠️ 顺手修掉一个静默缺陷：`writeMeta` 记的是首选模型，不是实际端点

**这一刀在改动过程中发现的既有缺陷**，不是这一刀引入的。

`ImageGenerationTool.writeMeta` 原来写 `properties.getImageModel()` —— 配置里的**首选**模型。
而 `BlogAgentService.generateIllustrations`（同域那条）一直写 `generated.model()`。

⚠️ 为什么出错：`BlogAgentImageClient` 会在**多个候选端点间回退重试**。
首选端点失败、兜底端点成功时，产物是兜底端点生成的，`meta.model` 却记成首选模型的名字；
网关路径下 `getImageModel()` 还可能是**空白**。

⚠️ 而它是**静默**的：图片已经生成、已经入库，只有 `meta` 里那个字段是错的，
没有任何断言、没有任何日志会响。

修法：`writeMeta(String title, String prompt, String model, String size)` 改成收实际值。
由 `imageModelComesFromTheGeneratedResultNotFromTheConfiguration` 守 ——
把 `properties.getImageModel()` 打桩成 `"配置里的首选模型"`、`GeneratedImage.model()`
打桩成 `"实际兜底端点模型"`，断言素材 `meta` 里是后者。注入 F 实测 2 处红。

⚠️ **通用经验：提供方报的值与消费方配置的值同名时，必须明确谁是真相。**
这里真相是提供方报的值 —— 只有它知道这次实际发生了什么。

### 3sexdecies.8 ⚠️ 分量顺序：前五个与 `GeneratedImage` 逐位对齐，`size` 只能追加在末尾

```java
// GeneratedImage:   bytes, contentType, extension, usage, model
// BlogAgentImage:   bytes, contentType, extension, usage, model, size
public record BlogAgentImage(byte[] bytes, String contentType, String extension,
                             BlogAgentImageUsage usage, String model, String size) { }
```

⚠️ 唯一的新字段 `size` **必须追加在末尾**。插在 `model` 旁边（"它俩都是元信息"）
会让换算从"逐位直接映射"变成"错位映射"，**而编译器不会响** ——
两个位置都是 `String`，换过去照样编译，只是把尺寸写成模型名。

由 `imageKeepsSizeLastSoTheFirstFiveStayPositionallyAligned` 钉住；注入 D 实测 **6** 处红。
⚠️ 2/3 位（`contentType` / `extension`）与 5/6 位（`model` / `size`）是**两对相邻 `String`**，
注入 B 证明 2/3 位也有守卫。

### 3sexdecies.9 ⚠️ 唯一不收 `userId` 的端口，以及成对的反向守卫

`BlogAgentImagePort` 是这一刀之后**唯一一个不收 `userId` 的端口**。前面几个端口
（`UserLookupPort`、`NotificationPort`、`RecitationQuizPort`、`BlogAgentPort` …）
第一个参数都是 `userId`，而 §3quinquiesdecies.5 刚把守卫从"断言类型"改成"断言参数名"。

于是出现真实风险：**下一位读者看到"别的端口都收 `userId`"，就给这个也加上。**
而那个参数**没有任何一步会去读它** —— 提供方是一次无状态的模型调用，不落库、不看
`UserContext`、不分片。真正需要身份的两步在**别的端口**上，且都显式收了：

```java
mediaAssetPort.recordGenerated(userId, ...);   // 存素材
billingPort.chargeTokens(userId, ...);         // 扣积分
```

⚠️ **加一个没人读的参数不是"一致性"，而是把"这里需要身份"这个错误信号写进契约。**

所以守卫这次**反着写** —— 新增 `ContractShape.assertNoParameterIsNamed(type, "userId")`：

```java
static void assertNoParameterIsNamed(Class<?> type, String name) {
    for (Method method : type.getDeclaredMethods()) {
        if (method.isSynthetic()) continue;
        for (Parameter parameter : method.getParameters()) {
            assertFalse(name.equals(parameter.getName()), ...);
        }
    }
}
```

由 `BlogAgentImagePortTest.takesNoCallerIdAndThatIsDeliberate` 调用。
**注入 E 是这一刀最有价值的一次验证**：往端口加一个 `default void track(Long userId)`，
期望"`default` 守卫红"，实测**三处红**，其中一处正是这条反向守卫 —— 证明它真的接着线。

⚠️ **结论提升为规则：端口收不收 `userId` 必须是一对守卫，正面一条、反面一条。**
只有正面那条时，"所有端口都收"是一个**没人反对就会漂移**的一致性论点；
配上反面那条，每个端口"要不要身份"才是一个**被断言过的决定**。

### 3sexdecies.10 实测收益

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 34 | **32** |
| `implementation_edges` | 16 | **15** |
| `cross_deployment_unit_statements` | 17 | **15** |
| `cross_deployment_unit_edges` | 9 | **8** |
| `api_statements` | 9 | **9**（不变） |
| `api_edges` | 6 | **6**（不变） |

`agent → blogagent` 从 **2 impl / 0 api** 到**整条边消失**。`agent` 对 `blogagent` 归零；
`blogagent` 只剩 `automation`（同单元）与 `studio`（跨单元 1 条）两个消费方。

⚠️ **与 §3quinquiesdecies.8 对照着看**：上一刀两条边数指标**一个都没动**，这一刀两条都动了。
两次代码改动量相近（各 2 条语句），但一次"边还在"、一次"边没了"。
**六个指标要一起看，任何一个单独看都会误判** —— 上一刀若只看 `implementation_edges`，
会得出"什么都没发生"的错误结论。

### 3sexdecies.11 为什么没有加 `contract-changes.yaml` 条目

三个契约类型都是**新缝**（不是替换某个前端可见类型），而且都**收窄**了。
按 §2.5 的分支判断：镜像才可能"序列化逐字节不变 ⇒ 可证纯重构 ⇒ 不进登记表"，
收窄是**新形状**。但它们**不是前端可见类型** —— `BlogAgentImage` 只出现在跨进程端口上。

⚠️ 与 §3quinquiesdecies.12 同款提醒：Wave 2 之后 `BlogAgentImage` 会变成**跨进程线上格式**，
届时它的 6 个分量顺序就是线上格式，任何重排都要走 **V9 §11（契约冻结）**。

### 3sexdecies.12 注入验证：六次注入

| # | 注入 | 实测 |
|---|---|---|
| A | 端口加一个 `default boolean isDisabled()` | **2**（`declaresNoDefaultMethods` + `hasExactlyTheseTwoMethods`） |
| B | `BlogAgentImage` 的 `contentType` 与 `extension` 对调 | **2** |
| C | `BlogAgentImageUsage` 三个 `long` 换序 | **3** |
| D | `BlogAgentImage` 的 `model` 与 `size` 对调 | **6** |
| E | 端口加一个 `default void track(Long userId)` | **3**（含反向守卫） |
| F | `generate` 改回记 `properties.getImageModel()` | **2**（含 §3sexdecies.7 那条回归守卫） |

⚠️ **实测值与"想当然的期望值"不一致，这是第二次**（第一次是 §3quaterdecies 的注入 D）。
按直觉，"`model` / `size` 对调"应只红 1 处（那条换算测试），实测红了 **6** 处 ——
换算测试、两条专门测试、以及三条工具测试都读这两个字段。
**期望值必须实测，不能从上一刀继承。**

⚠️ **注入脚本第一次跑没有回滚**（脚本自己的 bash bug：revert 段引用了未定义变量，
`set -u` 直接退出），注入 A 的 `default boolean isDisabled()` 留在了工作区。
发现方式：`git status --short` + 读文件末尾。修好后重跑，脚本自检打印
**"OK: 5 个文件全部逐字节还原（blob sha 相同）"**。
⚠️ **注入脚本失败后必须检查工作区**，否则会把注入当代码提交。

⚠️ `mvn` 控制台在 UTF-8 管道里是 GBK 乱码，但**测试名与 ASCII 断言值可读**
（`expected: <size> but was: <model>`），足够给每次注入归因。

### 3sexdecies.13 测试

`blogagent-api` 15 → **26**（+11）：`BlogAgentImagePortTest`(7) 是新类；
`RecordShapeTest` 6 → 10（+4，含 `ALL_RECORDS` 改成 5 个记录的循环）。
`apps/core-host` 1177 → **1187**（+10）：`ImageGenerationToolTest` 2 → 6（+4，改 mock 端口）、
`BlogAgentPortMappingTest` 7 → 13（+6，新增 4 参 `newService` 重载暴露 `imageClient` / `properties`）。
全反应堆 1386 → **1407**（0 fail / 0 err）。
26 门禁 → 24 pass / 0 fail / 2 skip；`contract-dependency-boundary` 报
**8 个模块 / 57 个源文件，JDK-only**（上一刀 54）；`dockerfile-module-coverage` 仍报 12 个模块。

### 3sexdecies.14 刻意没做的事

- **`BlogAgentService.generateIllustrations`（同域那条）**：继续直接调 `imageClient`。
  同域调用没有边界要守，绕一层端口只是多一层间接。这也是 `GeneratedImage` 不用改的原因。
- **10MB 上限、下载 URL 校验、端点回退、`usage` 解析**：全部留在 `BlogAgentImageClient`。
- **图片风格的措辞**：`TOOL_IMAGE_STYLE` 留在工具里，博客配图的措辞留在 `BlogAgentService` 里。
  **两个措辞刻意不同**，端口只收组装好的 `prompt` —— 端口不带渲染方的策略常量。
- **`BlogAgentProperties` 的任何其他字段**（超时、`maxIllustrations`…）：不上端口。
  端口只暴露"这次生成实际用了什么"，不暴露"提供方怎么配置的"。
- **`BlogAgentImageClient` 本身**：一个字没改。注入 F 之所以能改回去，是因为换算发生在服务里 ——
  这也说明**这一刀没有动客户端的契约**。

### 3sexdecies.15 下一刀

剩余跨单元实现边（按语句数）：`automation → integration` 6、`agent → identity` 2、
`agent → integration` 2、`admin → automation` 1、`identity → integration` 1、
`integration → automation` 1、`integration → identity` 1、`studio → blogagent` 1。

候选（同 §3quinquiesdecies.15，第 1 项已划掉）：

1. **`agent → identity`（2）** —— 在 `agent/api/` 下，是 WebSocket 握手鉴权，
   形状与工具类**完全不同**，要单独判断（可能落进 `xander-auth-core` 而不是契约）。
2. **`agent → integration`（2）** —— 与 `PublishPostTool` 同一文件，可单独一刀。
3. **`automation → integration`（6）** —— 目前最大的跨单元边，但卡在 `MailService` 的
   API 变更上（模板环，§3quater.5），不是纯粹的"抽端口"。

**结果：第 1 项已做完（§3septdecies）。** `agent → identity` **整条边消失**，
`agent` 只剩一条出边（`integration` 2）。

---

## 3septdecies. 第十四刀：`AccessTokenLookupPort` —— 一个**产出**身份的端口

### 3septdecies.1 目标边：两条 import 都在 `agent/api/` 下

`agent → identity` 的 2 条 impl 语句分在两个文件里，但指向同一个类
`identity.application.AuthService`：`AgentStreamAuthHandshakeInterceptor`（握手鉴权用它）
与 `AgentWebSocketConfig`（只是把它转手传进去）。

⚠️ **这是第一个消费方在 `api/` 层的刀** —— 前十三刀的消费方都是工具类或应用服务。
拦截器不在 `@Controller` 链上，走不到 `AuthInterceptor`，所以必须自己完成鉴权。

### 3septdecies.2 侦察：`AuthService` 有三个跨域消费方，用的是**两种能力**

| 消费方 | 用到的 `AuthService` 方法 | 这其实是哪种能力 |
|---|---|---|
| `agent`（WS 握手） | `getUserIdFromAccessToken(String) -> Long` | **令牌 → 身份** |
| `integration`（McpOAuth） | `validateAccessToken(String) -> boolean` | **令牌 → 有效性** |
| `profile`（概览） | `getUserInfo(Long) -> TokenResponse.UserInfo` | 用户资料读取（**另一种能力**） |

⚠️ 前两个是**同一个能力**的两种投影：`getUserIdFromAccessToken` 的第一句就是
`if (!validateAccessToken(token)) return null;`。这个观察决定了形状 ——
**端口按能力定义，不按消费方定义**。若为 `agent` 建一个只写 `getUserIdFromAccessToken` 的契约，
`integration` 之后就得再建一个只写 `validateAccessToken` 的契约，
**同一个能力被两份契约各描述一遍**（签名 / 过期 / 非 refresh / 会话登记表仍在），迟早漂移。

### 3septdecies.3 ⚠️ 为什么落 `core-api` 而不是 `agent-api`

`core-api` 的 `package-info` 已把判据写死：**"一个类型属于这里，判据不是'看起来像契约'，
而是它的实现跨部署单元"**；而且 **§18 的 8 个契约名里没有 identity**，
`core-api` 是 dinqor-core-host 的契约，identity 正好住在那里。

三条理由，按分量排序：

1. **判据一致** —— 实现（`AuthService`）在 dinqor-core-host，与 `UserLookupPort` 同一个模块。
2. **`identity` 不需要新增任何模块依赖** —— 它已经 `implements UserLookupPort`，
   `identity → core-api` 这条编译依赖**本来就存在**。若建在 `agent-api`，
   identity 就要为同一个能力去依赖 `agent-api`；等 `integration` 也来用，再依赖 `integration-api`
   ⇒ **提供方被迫依赖每一个消费方的契约**。
3. **未来宿主能直接用** —— V11 §52 判定"凡承载 SSE/WS 的部署单元都必须维护 Security Local Cache"，
   而 `core-api` 是这些单元都该依赖的模块。

⚠️ **ADR-2 的"消费方拥有"不等于"契约要放在消费方那个域的模块里"** ——
它说的是形状按消费方的真实需要裁剪、契约定义在 `contracts/` 这个跨域位置上。
`UserLookupPort` 就是同一个先例（消费方是 `publishing`/`components`，它住在 `core-api`）。

### 3septdecies.4 ⚠️ 为什么只有这一个方法：两个判断**可证等价**

`integration` 用的是 `validateAccessToken`，看起来需要一个 `boolean` 方法。**没有为它加第二个**：

| 令牌类型 | 会通过 `validateAccessToken` 吗 | 依据 |
|---|---|---|
| access token | 会，且 subject 一定可解析 | `generateTokenResponse` 是**唯一**生产调用方，传 `String.valueOf(user.getId())` |
| refresh token | **不会** | `isRefreshToken` 为真时直接 false |
| bind token（微信绑定） | **不会** | `type` 是 `bind`，但它**不写进** `login:token:` 前缀，`hasKey` 为假 |

⇒ `validateAccessToken(t)` ⟺ `resolveUserId(t) != null`。所以 `integration` 那一刀
可以直接复用本端口。**这一刀没顺手改它**（另一个消费方、另一条边，`McpOAuthService` 有自己的测试）。

⚠️ 记一个**将来的分歧点**：若出现"subject 非数字、却仍登记在 `login:token:` 下"的令牌类型，
两个判断会分叉（`resolveUserId` 返回 `null`、`validateAccessToken` 返回 `true`）。
分叉方向是**更严**，不是安全问题；但那时必须重新决定，不能再靠这条等价性。

### 3septdecies.5 ⚠️ 第一个**产出**身份的端口 ⇒ 守卫是反的

前几个端口的第一个参数都是调用方身份，守卫是正面的（断言参数名是 `userId`）。
本端口**反过来**：它的作用就是从令牌里**得出**身份。方法里出现 `userId` 参数，
说明有人在让它做"校验这个令牌属不属于这个人" —— 那是另一个能力，
且会诱使实现者把归属校验和令牌校验写成一步。

```java
// AccessTokenLookupPortTest
@Test void takesNoUserIdBecauseItProducesOne() { /* 遍历所有方法的所有参数，断言没有叫 userId 的 */ }
```

**注入 B（往签名里加一个 `Long userId` 参数）实测 2 处红，其中一处正是它。**

⚠️ 与 §3sexdecies.9 合起来才完整：**"端口收不收 `userId`"必须是一对守卫**，且现在有三种情况 ——
其他端口"该收、断言它收了"；`BlogAgentImagePort`"不该收、断言它没收"；
本端口"不该收、**而且是因为它要产出**"。**没有第四种。**

### 3septdecies.6 ⚠️ 端口实现此前**一个测试都没有**

`getUserIdFromAccessToken` 被抽成端口之前，**没有任何测试覆盖** ——
`AuthServiceTest` 只覆盖 `validateAccessToken` 两条。它当时只是 identity 内部的便捷方法。

⚠️ 抽成端口后它变成 **agent 的 WebSocket 握手唯一的身份来源**：返回 `null` 就把所有连接挡在 401，
返回错的值就把别人的会话开给这个人。**"这是契约的实现"意味着它的行为必须被钉住**，
所以补了 4 条：活令牌返回 subject / 已登出返回 `null` 且**短路不解析** /
refresh 令牌返回 `null` 且**不查 Redis** / subject 非数字返回 `null` 而不是抛异常。

⚠️ 与 §3terdecies 的"端口方法自调用失事务"同族：**抽端口会让原本不重要的东西变重要，
而"没人写那条断言"不会有任何东西报错。判据：把方法提升成契约实现时，先查它有没有测试。**

### 3septdecies.7 ⚠️ `PortDelegationTransactionTest` 的清单必须同步

`AuthService` 现在实现了 `*Port` ⇒ 类路径扫描会找到它 ⇒ `PORT_IMPLEMENTATIONS` 必须加它。
那个测试的 `theAuditedClassListIsComplete` 是**双向**判据，漏加**直接变红** ——
这次是判据自己把问题喊出来的。

⚠️ 顺带记一条**没被守住**的：检测器只在**同一个类**里找 `@Transactional` 的同名方法。
若端口实现是一个**独立的小 `@Service`**、转调另一个类的富方法，检测器**看不到**。
这次选"让 `AuthService` 自己实现"而不是新加适配器类，部分原因就是这个 —— 留在同类里守卫才覆盖得到。

### 3septdecies.8 实测收益

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 32 | **30** |
| `implementation_edges` | 15 | **14** |
| `cross_deployment_unit_statements` | 15 | **13** |
| `cross_deployment_unit_edges` | 8 | **7** |
| `api_statements` | 9 | **9**（不变） |
| `api_edges` | 6 | **6**（不变） |

`agent → identity` **整条边消失**；`agent` 只剩一条出边 `agent → integration`（2 impl）。
⚠️ 最近三刀的边数表现各不相同（第十二刀边数**不动**，第十三、十四刀各 −1），
**六个指标要一起看**。

### 3septdecies.9 为什么没有加 `contract-changes.yaml` 条目

新端口是**新缝**，只有一个方法；既没改路径、没改响应形状、也没改错误码 ——
它把一条已有的域内调用换成端口调用，行为逐字不变（同一个实现方法、同样的判据、同样的 `null` 语义）。

### 3septdecies.10 注入验证：六次，其中一次**第一遍是无效的**

| # | 注入 | 实测 |
|---|---|---|
| A | 端口加一个 `default boolean isDisabled()` | **2** |
| B | 端口方法多收一个 `Long userId` 参数 | **2**（含反向守卫） |
| C | 端口参数改名 `accessToken` → `token` | **1** |
| D | 实现永远返回 `null` | **2** |
| E | 实现无视令牌、固定返回 `1L` | **2** |
| F | 消费方无视端口结果、固定用 `42L` | **1** |

⚠️ **E 的第一遍结果是假的。** 脚本把 D 的还原锚点写成 `            return null;`，
而 `AuthService` 里有**三处**这一行 ⇒ 还原断言失败 ⇒ **D 没被还原**；
接着 E 的正向锚点已被 D 改掉 ⇒ **E 根本没生效**，它报的"2 处红"是 D 的残留。
发现方式是脚本收尾 blob 比对打印 `FAIL: 还原不完整` + `git status` 里 `AuthService.java` 仍是脏的。
用"整个方法体块"当锚点重跑后，D 与 E 各自生效、各自还原成功。

⚠️ 这是上一刀那条教训的**第二次实例、成因不同**：上一刀是引用未定义变量（`set -u` 中途退出），
这一刀是**还原锚点不唯一**。**共同点：脚本"跑完了"不等于"注入生效了、也还原了"。**
判据升级：**两条锚点动手前都数一遍出现次数（必须都是 1）；每条注入跑完立刻做一次 blob 级校验** ——
否则一条还原失败会**污染后面所有注入的结果**，而表格看上去仍然"每行都有数字"。

### 3septdecies.11 刻意没做的事

- **V11 的 `ws-ticket` 机制**（`POST /api/v2/agents/ws-ticket` → 30–60s 一次性票据 → 握手用
  `wss://.../ws?ticket=`）。这是方案里 WS 的**目标**，属 V11 附录 E 的"SSE / WS Token"
  工作流（0/5，卡在 `apps/gateway`）。本刀只做**行为逐字不变**的抽端口，不顺手改鉴权协议。
- **`integration` 改用本端口**（§3septdecies.4 已证明等价）—— 另一条边，单独一刀。
- **去掉那次 Redis `hasKey`** —— V11 §28.1 的意图是"发送边界 O(1) 本地 cache 校验"，
  而 `SecurityStateCache` 已经在 `AgentWebSocketConfig` 里了。但去掉它**会改变行为**
  （登出后不再阻止新连接），那是安全决策不是重构。
- **`profile → identity` 的 `getUserInfo`** —— 用户资料读取，另一种能力、另一种形状。

### 3septdecies.12 下一刀

`agent` 只剩 `agent → integration`（2）。剩余跨单元边按语句数：
`automation → integration` 6、`agent → integration` 2、`admin → automation` 1、
`identity → integration` 1、`integration → automation` 1、`integration → identity` 1、
`studio → blogagent` 1。

1. **`integration → identity`（1）** —— **本刀的直接后续**：同一个端口、同一套判据，
   §3septdecies.4 的等价性证明已经写好，改一行即可。做完**跨单元的 identity 边全部归零**。
2. **`agent → integration`（2）** —— 与 `PublishPostTool` 同一文件。
3. **`automation → integration`（6）** —— 目前最大，卡在 `MailService` 的 API 变更上（模板环）。

---

## 3octodecies. 第十五刀：`McpOAuthService` 接入 `AccessTokenLookupPort` —— 第二个消费方，以及一条 1 语句的边锁住了整个 `dinqor-core-host`

> 契约刀次 = 第十五刀（ADR §15）。Wave 0B 总刀次 = **第二十二刀**（见 `v9-wave0a.md` §33）。

### 3octodecies.1 目标边：import / 字段 / 调用三处

`integration -> identity` 是 **1 条实现语句**，全部落在
`integration.mcp.security.application.McpOAuthService`：import（第 7 行）、
字段（第 63 行）、调用（第 312 行）。本刀是 §3septdecies 的**直接后续** ——
第十四刀把端口建在 `core-api` 时就写明了"这个能力不止一个消费方"，
本刀去兑现它。

⚠️ `profile -> identity`（1 impl，`ProfileOverviewService.getUserInfo`）**不在本刀范围内**：
那是**另一种能力**（拿用户资料，不是验令牌），且同单元。

### 3octodecies.2 ⚠️ 这一刀不是"把布尔换成 null 判断"：消费方复制了一份身份解析

`requireWebUser` 原文是两步：`authService.validateAccessToken(token)` 判有效性，
再 `Long.parseLong(jwtUtil.getSubject(token))` **自己又解析一遍主体**。
这两步**合起来就是** `resolveUserId`。也就是说 `integration` 不只问了一个布尔，
它**重新实现了一遍身份解析**，还必须知道「用户 id 是 JWT 的数字形式主体」这条实现细节。

⇒ 本刀的实际形状是**删掉一份复制品**。

### 3octodecies.3 ⭐ 顺带的收获：`JwtUtil` 这个协作者从消费方整个消失

`jwtUtil` 在 `McpOAuthService` 里**只有那一行**在用。字段 / import / 构造参数
（`@RequiredArgsConstructor`，它是最后一个字段 ⇒ 只少一个尾参）/ 测试里的 `@Mock` 一起去掉。

⚠️ **抽端口的收益不总是"少一条 import"**：这一刀里消费方少了一个协作者、
少了一份解析逻辑、少了一条隐含假设 —— 而这些**指标看不出来**（只降 1）。

### 3octodecies.4 ⚠️ 消掉一条潜在故障路径：非数字主体从 500 变成 401

旧写法 `validateAccessToken` 通过后直接 `Long.parseLong(...)`，主体非数字 ⇒
`NumberFormatException` 冒到顶层 ⇒ **500**。新写法端口内部 `try/catch` 后返回 `null`
⇒ 消费方抛 `McpOAuthException(401)`。按 §3septdecies.4 的等价性证明这条路径**今天不可达**，
但端口让它在结构上不可能发生 —— 消费方再也拿不到"半验证通过"的中间状态。

### 3octodecies.5 ⭐ 端口设计被验证：**零构建接线**

`integration` 是第二个消费方。接入它只需要改消费方自己：
`contracts/core-api` **一行没动**（无新方法 / 无新类型 / 无新模块），
`pom.xml` **一行没动**（同属 `apps/core-host`，`core-api` 早就是依赖）。
这正是"端口建在 `core-api` 而不是 `agent-api`"的回报 ——
若建在 `agent-api`，今天 `integration` 要么依赖 `agent-api`（为一个与 agent 无关的能力），
要么再造一个同能力的新契约（两份契约描述同一能力，Javadoc 迟早漂移）。
**判据：一个能力一个端口；第二个消费方接入时的成本，是对这条判据最诚实的检验。**

### 3octodecies.6 测试

`apps/core-host` 1191 → **1194**（`McpOAuthServiceTest` 20 → **23**，+3）：

1. `revokeClientGrantRejectsWhenThePortSaysTheTokenIsNotAnIdentity` —— 端口答不上来时
   必须 `login_required` / **401**，且**不许碰 mapper**；
2. `revokeClientGrantRequiresABearerPrefixedHeader` —— 非 `Bearer ` 前缀在**调端口之前**就拒；
3. `listClientGrantsScopesTheQueryToTheUserIdThePortReturned` —— **端口返回的 777
   必须出现在查询条件里**（钉住"用的是端口返回值，不是常量"）。

⚠️ 第 3 条一开始写成 `verify(grantMapper).selectList(argThat(w -> w.getParamNameValuePairs()
.containsValue(777L)))`，**红了**。原因是 MyBatis-Plus 的 `getParamNameValuePairs()`
**惰性填充**：参数只在 SQL 片段被渲染时才进 map，而 mapper 是 mock，永远不会渲染。
仓里既有 8 处同类断言都是**先显式调一次 `getSqlSegment()`** 再读 map。
（已写进 `backend-norms`。）

### 3octodecies.7 实测收益

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 30 | **29** |
| `implementation_edges` | 14 | **13** |
| `cross_deployment_unit_statements` | 13 | **12** |
| `cross_deployment_unit_edges` | 7 | **6** |
| `api_statements` | 9 | 9（不动） |
| `api_edges` | 6 | 6（不动） |

`integration -> identity` **整条边消失** ⇒ **`identity` 作为目标域的跨单元边全部归零**
（剩 `virtualpay` 5 / `admin` 2 / `profile` 1 全是同单元边）。

### 3octodecies.8 ⭐⭐ 图形状突变：一条 1 语句的边锁住了整个 `dinqor-core-host`

改完后 `deployment-unit-coupling` 门禁报 `dinqor-core-host` **也脱离了** Wave 2 强连通分量，
并要求从 `MUTUAL_UNIT_PAIRS` 里删掉 `('dinqor-core-host', 'dinqor-platform-integration')`
（**两个方向都删**）。原因只有一条：单元级边 `platform-integration -> core-host`
的**全部内容就是这一条 1 语句的边**。

⚠️ 此前一直把图形状读成"core-host、content-automation、platform-integration 是一团"；
实际成环的是 `content-automation -> platform-integration`（6）与
`platform-integration -> content-automation`（1）这一对。
**`core-host` 的成员资格完全挂在一条大小为 1 的边上。**

⇒ **教训**：**强连通分量的成员资格可以挂在一条大小为 1 的边上**；
每次动跨单元边之后要**重新推导**图形状，不能沿用上一刀的形状结论。
剩余分量 = `{content-automation, platform-integration}`；
**`dinqor-agent` 与 `dinqor-core-host` 现在都可以被单独抽取**。

### 3octodecies.9 为什么没有加 `contract-changes.yaml` 条目

契约模块**一个字都没改**（没有新方法、新字段、新分量）。本刀只是让一个消费方
从"依赖实现类"改成"依赖已有契约" —— 契约的**公共面**没动，所以没有契约变更要登记。

### 3octodecies.10 注入验证：四次

| # | 注入 | 实测红 |
|---|---|---|
| A | 消费方不再调用端口，`userId` 写死 `123L` | **2** |
| B | 消费方丢掉 `null` 守卫（忘了端口可以答不上来） | **1** |
| C | 消费方把整个 `Authorization` 头传给端口（忘了剥 `Bearer `） | **2** |
| D | 消费方把 `401` 改成 `403` | **1** |

⚠️ **两次实测与直觉不符**（本仓第三次 / 第四次）：
- **A 红两处**：写死 `123L` 之后 `pendingAuthorization` 那条用例**恰好**也是 `123L`
  （`mockWebLogin` 用的就是 `123L`）⇒ 它绿着过；红的是
  `listClientGrantsScopesTheQueryToTheUserIdThePortReturned` 与
  `revokeClientGrantRejectsWhenThePortSaysTheTokenIsNotAnIdentity`。
- **C 红两处**（直觉是 1）：⚠️⚠️ 这条照出了一条更重要的规则 —— 失败信息是
  `Expected McpOAuthException to be thrown, but nothing was thrown`，只能由
  **`userId` 非 null** 解释；而测试里没有任何 stub 匹配 `resolveUserId("Bearer web-token")`。
  查 Mockito 5.7.0 源码（`org.mockito.internal.util.Primitives`）：
  `PRIMITIVE_OR_WRAPPER_DEFAULT_VALUES.put(Long.class, 0L)` ⇒
  **未 stub 的 `Long` 返回值是 `0L`，不是 `null`**。
  而本端口用 `null` 表示"这个令牌不构成有效身份" ⇒ **未 stub 的 mock 会伪装成
  "答上来了、用户 id 是 0"**，`if (userId == null)` 守卫**一次都不会被触发，而测试照常绿**。
  ⇒ **判据：凡"用 `null` 表达失败"的契约方法，测试里必须显式 stub 成 `null`**，
  绝不能让未 stub 的默认值替它回答（`Long`/`Integer` → `0`，`Boolean` → `false`，
  `String`/对象 → `null`）。已写进 `backend-norms`。

⚠️ **D 是有意加的一条守卫强度探针**：`401` 不是"随便一个错误码"，它是 MCP 客户端协议的
一部分（`login_required` + 401）。只断言 `assertThrows(McpOAuthException.class)` 抓不到它，
必须有 `assertEquals(401, error.status())`。

⚠️ 本刀注入脚本改用 **Python**（`D:/blog/.workbuddy-ai/tmp/inject15.py`）：bash 版踩过两次
（引用未定义变量、还原锚点不唯一），Python 版可以把"两条锚点动手前数一遍出现次数"
与"每条注入后立刻比对 blob"写成**前置断言 + 后置断言**，失败即 `return`，不会带着残留继续。
⚠️ 但脚本第一版在 B 上**主动中止**：B 是"删除式注入"（把丢守卫建模成删掉一行），
它的还原锚点必然是正向锚点的子串 ⇒ 前置断言拒绝执行。已按"给每条注入声明还原锚点的
期望出现次数、再用**施加后**的计数消歧"修正（已写进 `backend-norms`）。

### 3octodecies.11 刻意没做的事

- `profile -> identity`（1 impl）：另一种能力，且同单元。
- `agent -> integration`（2 impl）：`PublishPostTool`，另一条边、另一个消费方。
- `automation -> integration`（6 impl）：卡在 `MailService` 的 API 变更上，不是纯抽端口。
- `McpOAuthService` 里 `jwtUtil` 的其它潜在用法：确认过**没有**。

### 3octodecies.12 下一刀

剩余跨单元实现边（**6 条**）：`automation → integration` 6、`agent → integration` 2、
`admin → automation` 1、`identity → integration` 1、`integration → automation` 1、
`studio → blogagent` 1。

1. **`agent → integration`（2）** —— `PublishPostTool`；做完 **`agent` 的出边全部归零**。
2. **`studio → blogagent`（1）** —— 先侦察，可能很便宜。
3. **`automation → integration`（6）** —— 最大，卡在 `MailService` 的 API 变更上。
4. **`admin → automation`（1）** —— 需要 SSE 广播端口。

---

## 3novemdecies. 第十六刀：站外同步抽成 `CsdnBlogSyncPort` / `JuejinBlogSyncPort` —— `agent` 的出边全部归零，`dinqor-agent` 完全离开跨域边表

> 契约刀次 = 第十六刀（ADR §16）。Wave 0B 总刀次 = **第二十三刀**（见 `v9-wave0a.md` §34）。

### 3novemdecies.1 目标边：两条 import 都在**同一个文件**里

`agent -> integration` 是 **2 条实现语句**，两条都在
`agent.application.PublishPostTool`（第 13、14 行）：它直接 import 了
`integration.mcp.csdn.application.CsdnBlogSyncService` 与
`integration.mcp.juejin.application.JuejinBlogSyncService` —— 两个具体 `@Service` 类。

⚠️ **本刀是 §3octodecies 的直接后续**：上一刀的"下一刀"第一条就写着
"做完 **`agent` 的出边全部归零**"。`agent` 此前只剩这一条边，所以这一刀
**把 `dinqor-agent` 从跨域边表里彻底划掉**。

### 3novemdecies.2 ⚠️ 形状决定一：这是**两个**端口，不是一个带 `platform` 参数的端口

第一直觉是一个端口加一个 `platform` 字符串参数。**否掉了**，三条理由：

1. **参数表不同，合并就要么造假要么变宽。** 掘金要求自己的分类与标签 id
   （`juejinCategoryId` / `juejinTagIds`），CSDN 没有这两个概念。合并成一个端口，
   CSDN 那条路就必须收下两个它永远不用的参数（**端口变宽**），或者端口只带交集、
   把掘金指定分类/标签的能力砍掉（**能力静默降级**，§2.3 禁止）。
2. **域自己就是这么分的。** `integration` 域为两个平台各有一套
   `*CredentialService` / `*BlogSyncService` / `*BlogSyncController` /
   `*McpApplicationService`，两套凭证存储、两套限流。端口跟着域的真实分解走。
3. **零适配器。** 每个端口的方法表与它对应实现类的公开方法**逐字相同**，
   实现类加一句 `implements` 就够了 —— 不需要新增一个按平台字符串分派的中间类，
   也就没有"分派写错只在运行时才炸"的新缝。

**判据：合并只有当"合并后的签名对每一个实现都是它真正需要的形状"时才正确。**
⚠️ 这偏离了 §3quater.5 草拟的单数名 `BlogSyncPort`（`CredentialPort` 同样是草稿单数、
同样是两套）—— 草稿是在"还没看参数表"的时候写的。

### 3novemdecies.3 ⚠️ 形状决定二：端口留住了跨域消费方"现在一律传 `null`"的那两个参数

`JuejinBlogSyncPort.sync` 的 `juejinCategoryId` / `juejinTagIds`，在**两个跨域消费方**
那里**今天全是 `null`**（`PublishPostTool` 与 `BlogPlanRunService` 各一处，都是 `(null, null)`）。

按"没人用就砍掉"的直觉，这两个参数该删。**没删**，理由：

- **同域的前端 HTTP 层会传真实值。** 跨进程之后，端口是那条路径**唯一能穿过**的形状 ——
  砍掉参数等于把 HTTP 路径的能力一起砍掉。
- ⇒ **判据：一个参数该不该留在端口上，看"它有没有具名消费方"，不看"它现在有没有跨域消费方"。**
  砍掉一个"今天没人传"的参数，就是让能力在拆服务时**静默消失**（§2.3）。

### 3novemdecies.4 ⚠️ 形状决定三：契约不许依赖契约 ⇒ `BlogSyncResult` 是 `PublishResult` 的等价 DTO

端口返回的平台结果，实现类手里的是 `content-api` 的 `PublishResult`
（`platform` / `externalId` / `url`）。**契约不许依赖契约**（ADR-2）⇒
`integration-api` 自带一个 `BlogSyncResult`，**分量名与顺序逐字相同**。

这是**第二次**遇到同一个形状（第一次是 `ModelUsage` → `TokenUsage`，
第三次是 `ModelUsage` → `BlogAgentImageUsage`）。

- **换算落在一处**：实现类里的私有 `toContract`，三个分量**逐个具名**写出
  （都是 `String`，按位置写会编译通过、换位只在运行时才炸）。
- ⚠️ **两份投影必须一起改**，而且"两个投影在共享字段上相等"的断言
  **只能写在实现类的测试里** —— 只有实现类同时看得见两个类型。

### 3novemdecies.5 ⚠️ 代价：实现类必须改返回类型，于是同域 **5 个**消费方跟着改

Java 不许"同名同参、只差返回值"的重载 ⇒ `sync` / `findSucceeded` 只能返回契约的
`BlogSyncResult`，实现类的**公开方法签名**跟着变，于是同域消费方也要跟着换类型：

| 消费方 | 改动 |
|---|---|
| `CsdnBlogSyncController` | `Result<PublishResult>` → `Result<BlogSyncResult>` |
| `JuejinBlogSyncController` | 同上 |
| `CsdnMcpApplicationService` | `syncOwnedPost` 的返回类型 |
| `JuejinMcpApplicationService` | `publishArticle` / `syncOwnedPost` |
| `DualPublishMcpApplicationService` | 局部变量类型 |

⚠️ **`grep` 的目标要选"那个类型"而不是"那个类"。** 第一遍按服务类名找只得到 4 个消费方；
编译前按 `PublishResult` 再 `grep` 一次，才多出 `DualPublishMcpApplicationService`
（它调 `CsdnMcpApplicationService.syncOwnedPost`，自己不是 SyncService 的消费方）。

### 3novemdecies.6 ⭐ 实测收益：`dinqor-agent` 完全离开跨域边表

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 29 | **26** |
| `implementation_edges` | 13 | **12** |
| `cross_deployment_unit_statements` | 12 | **9** |
| `cross_deployment_unit_edges` | 6 | **5** |
| `api_statements` | 9 | 9（不动） |
| `api_edges` | 6 | 6（不动） |

`agent -> integration` **整条边消失**，`automation -> integration` 6 → 5。

⭐⭐ **`dinqor-agent` 现在在实现层**出边 0、入边 0**，在 api 层同样一条边都没有** ——
它不再出现在边表里。⇒ **`dinqor-agent` 的抽取不再需要任何契约工作**，
`deployment-unit-coupling` 的分量只剩 `{content-automation, core-host, platform-integration}`。

### 3novemdecies.7 测试

两个 SyncService 改造前**没有自己的测试**（只被 `PublishPostToolTest` 间接覆盖）。
它们现在是契约端口的实现，所以补了两个测试类：

| 测试类 | 用例 | 钉住什么 |
|---|---|---|
| `CsdnBlogSyncServiceTest` | 7 | 两份投影的换算；幂等键的**负号**；取 `categoryName` 而不是 `category`；软删与不存在同路径拒绝；`postId == null` 短路 |
| `JuejinBlogSyncServiceTest` | 10 | 同上 + 标签回落（`null` 与**空列表**都回落）与分类直通 |

契约侧三个测试类（`BlogSyncResultTest` 4 / `CsdnBlogSyncPortTest` 6 / `JuejinBlogSyncPortTest` 7）
与一个共用的 `ContractShape`（与 `blogagent-api` 那个同名类是同一条判据的第二次落地 ——
两个契约模块在 test scope 上互不可见，只能各带一份）。

| 计数 | 前 | 后 |
|---|---|---|
| `contracts/integration-api` | 3 | **20** |
| `apps/core-host` | 1194 | **1212** |
| 全反应堆 | 1420 | **1455**（0 fail） |
| 26 门禁 | 24 pass / 0 fail / 2 skip | 24 pass / 0 fail / 2 skip |
| `contract-dependency-boundary` | 8 模块 / 58 源文件 | 8 模块 / **61** 源文件 |

⚠️ **`PortDelegationTransactionTest.theAuditedClassListIsComplete` 先红了。**
加完 `implements` 还没跑注入，它就报"端口实现类的清单与实际不符" ——
类路径扫描发现了两个没被审到的实现。**这是那条双向完整性判据第二次当场付费**
（第一次是逮到漏写 `RecitationService`）。已把两个类加进 `PORT_IMPLEMENTATIONS`，
并同步类注释里"全仓 N 个端口实现"的计数（15 → 17）。

### 3novemdecies.8 注入验证：九次，其中三次**第一遍量不到 core-host**

| # | 注入 | 契约模块红 | `core-host` 红 | 合计 |
|---|---|---|---|---|
| A | 消费方不再传 `(null, null)`，把文章分类塞进掘金分类参数 | 0 | 2 | 2 |
| B | CSDN 幂等键丢掉负号（`-postId` → `postId`，与本站发布撞键） | 0 | 2 | 2 |
| C | CSDN 取 `category` 而不是 `categoryName`（相邻的两个 `String`） | 0 | 2 | 2 |
| D | 两份投影的换算把 `platform` 与 `url` 对调（三个 `String`） | 0 | 4 | 4 |
| E | CSDN `findSucceeded` 丢掉 `postId == null` 短路（下一句要算 `-postId`，NPE） | 0 | 2 | 2 |
| F | 掘金不再用调用方给的 tag id，一律回落到文章自己的标签 | 0 | 2 | 2 |
| G | 端口加一个 `default` 方法（不通知任何实现就扩大契约面） | **6** | 0 | 6 |
| H | 端口首参名字改成 `postId`（类型仍是 `Long`，只断言类型会绿着过） | **2** | 0 | 2 |
| I | `BlogSyncResult` 把 `url` 与 `externalId` 两个分量对调 | **4** | **7** | 11 |

**⚠️ G / H / I 的 `core-host` 列是第一遍量不到的，原因不是测试弱，是量法：**

- `contracts/integration-api` 在反应堆里排在 `apps/core-host` **前面**。契约模块一红，
  `core-host` 的测试**一个都没跑**。
- 加 `-fae` **不够**：`-fae` 只续跑"**不依赖**失败模块"的模块，而 `core-host`
  恰恰依赖 `integration-api`，照样被跳过。第一遍（无 `-fae`）G/H/I 报 3 / 1 / 2，
  第二遍（`-fae`）报 6 / 2 / 4 —— **都只是契约模块那一半**。
- **修法**：把 `-Dtest` 收窄到 `core-host` 自己的四个测试类，让契约模块
  **一个测试都匹配不到**（因此永远不会红）。补测结果：**G = 0、H = 0、I = 7**。
  ⚠️ 这时契约模块连 `Tests run` 行都不打印 ⇒ **"没有汇总行"必须判成"0 个测试"，
  不是"编译失败"**（第一版脚本混了这两件事，报了个假的 `-1`）。

这条量法本身值得记，因为它给的是**假的低分**：只由契约模块反射测试守着的注入
（G / H）看起来"只影响 6 个 / 2 个测试"，真正的影响面是**所有实现方与所有消费方**。

**⭐ 顺带确认**：G / H 在 `core-host` 侧**真的是 0** —— 端口加 `default` 方法、
端口首参改名，消费方的 Mockito 桩**一个都不动**（Mockito 按位置匹配，实现类自己声明参数名，
都不看接口的参数名）。⇒ 这两个形状**只有契约模块的反射测试在守**，
`assertNoDefaultMethods` / `assertFirstParameterIsTheCallerId` 不是锦上添花，是**唯一**的守卫。

⚠️ **两个把这次测量卡住的工具坑**（已写进 `notes/repo-workflow.md`）：
①**`sha1sum` ≠ `git hash-object`**（前者对原始字节、后者对 `blob <len>\0` + 内容且做 CRLF→LF 归一，
同一文件两个值 `60a4fe6b…` / `5c7408d4…`）⇒ 还原判据要写清用哪个算法、全程只用同一个；
②**注入脚本不要自己起 `bash`**（沙箱里 `subprocess("bash", ...)` 解析成 WSL 的 `bash.exe`，
被程序黑名单拦掉、脚本静默退出），改成脚本只改文件、`mvn` 由外层 shell 跑。

### 3novemdecies.9 为什么没有加 `contract-changes.yaml` 条目

与 §3septdecies.9 / §3octodecies.9 同一条理由：**线上形状没变**。
两个 HTTP 控制器的响应类型从 `Result<PublishResult>` 变成 `Result<BlogSyncResult>`，
但两份 record 的分量名与顺序逐字相同（`platform` / `externalId` / `url`），
Jackson 按分量名序列化 ⇒ **JSON 逐字节不变**。

⇒ **判据是"线上 JSON 变了没有"，不是"Java 类型名变了没有"。**
`contract-changes-completeness` 仍报 14 条，`api-migration-key-coverage` 仍报
213 条覆盖全部路由 —— 两条都 PASS。

### 3novemdecies.10 刻意没做的事

- **`automation → integration` 的剩下 5 条没碰。** 卡在 `CsdnCredentialService` /
  `JuejinCredentialService` / `MailService` 上，与本刀不是同一种形状
  （凭证服务的端口形状还没设计；`MailService` 卡在
  `automation.domain.EmailReminderMailTemplates` 这个**反向依赖**上，要改 API 而不是加端口）。
- **两个 SyncService 的 `toContract` 私有助手没有合并成共用的工具类。**
  两个类分属两个包，共用要么新开一个公共包、要么放宽可见性。三行 × 2 的重复比一个新缝便宜。
- **没有顺手收窄 `PublishResult`。** 它是 `content-api` 的既有契约，本刀只是多了一个读者。

### 3novemdecies.11 下一刀

剩余跨单元实现边（**5 条**）：`automation → integration` 5、`admin → automation` 1、
`identity → integration` 1、`integration → automation` 1、`studio → blogagent` 1。

1. **`studio → blogagent`（1）** —— 先侦察，可能很便宜。
2. **`automation → integration`（5）** —— 目前最大；2 条是凭证服务（要新设计端口形状），
   1 条是 `MailService`（卡在邮件模板的反向依赖上，要改 API 而不是加端口）。
3. **`admin → automation`（1）** —— 需要 SSE 广播端口。
4. **`identity → integration`（1）** —— 与 `integration → automation` 构成**真环**，要一起看。

⚠️ **`dinqor-agent` 已经可以从边表里划掉**（§3novemdecies.6）—— 它的抽取不再需要任何契约工作。

---

## 3vicies. 第十七刀：`studio → blogagent` 的凭证回落从**代码**搬到**配置** —— 第一次不靠 Java 改动收掉一条边

> 契约刀次 = 第十七刀（ADR §17）。Wave 0B 总刀次 = **第二十四刀**（见 `v9-wave0a.md` §35）。

### 3vicies.1 目标边：不是"调能力"，是**借别人的凭证**

`studio → blogagent` 是 **1 条实现语句**，落在
`studio.infrastructure.Img2ThreeModelClient`
（`dinqor-core-host → dinqor-content-automation`）：它 import 了 blogagent 的
`infrastructure.BlogAgentProperties`，用来给 img2three 的模型凭证兜底 ——
**消费方读提供方的 `@ConfigurationProperties`**，正是 ADR 待办 30 禁止的形状。

```java
private final Img2ThreeProperties properties;          // studio 自己，prefix = img2three
private final BlogAgentProperties blogAgentProperties; // blogagent 的，prefix = blog.agent

private String resolveApiKey()  { return hasText(properties.getApiKey())  ? properties.getApiKey()  : blogAgentProperties.getApiKey(); }
private String resolveModel()   { return hasText(properties.getModel())   ? properties.getModel()   : blogAgentProperties.getModel(); }
private String resolveBaseUrl() { return hasText(properties.getBaseUrl()) ? properties.getBaseUrl() : blogAgentProperties.getBaseUrl(); }
```

⇒ 语义是「img2three 有自己的模型端点配置，**没配就借用 blogagent 的**」。

### 3vicies.2 ⚠️ 侦察结论：三个"抽端口"候选都被否掉

| 做法 | 为什么不行 |
|---|---|
| 在 `blogagent-api` 加端口把解析后的端点暴露出去 | 正是待办 30 禁止的：**提供方的配置变成跨域契约**（端口返回的就是"我怎么配置的"） |
| 在 `core-api` 加"默认模型端点"端口 | 看着像能力归位，但 studio 不是在调 blogagent 的能力 —— 它在**做自己的模型调用**，只是借上游凭证。为"借凭证"立契约，等于把"两个域共用一套上游凭证"合法化，而 `config-ownership.yaml` 已把它们记成两家各自的 `service_private` / `secret` |
| 让 `Img2ThreeProperties` 自己配全、删掉回落 | ⚠️ **行为变更**：只配了 `BLOG_AGENT_*` 的部署会直接报"尚未配置模型服务"。`application-local.yml` 恰好就是这种部署 |

⇒ 真正的形状是：**回落属于"配置层"，不属于"代码层"。** 它是"同一个值在两个服务里都要有"，
不是"一个服务向另一个服务要东西"。拆服务后前者由运维 / Nacos 承担，后者要跨进程调用 ——
而**为了读一个凭证去跨进程调用**显然不是目标架构想要的。

### 3vicies.3 ⭐ 形状决定：占位符写**属性名**，不是环境变量名

```yaml
img2three:
  agent:
    base-url: ${IMG2THREE_AGENT_BASE_URL:${blog.agent.base-url:}}
    api-key: ${IMG2THREE_AGENT_API_KEY:${blog.agent.api-key:}}
    model: ${IMG2THREE_AGENT_MODEL:${blog.agent.model:}}
```

⚠️ 这里有一个**很容易写错、写错了不会报错**的选择：写 `${blog.agent.api-key:}`（属性名）
还是 `${BLOG_AGENT_API_KEY:}`（环境变量名）？

- `application-local.yml` 是**直接把值写在 `blog.agent.api-key` 上**的（不是设环境变量）；
- 写环境变量名 ⇒ 本地那份配置**回落不到** ⇒ 行为变了，而且没有任何东西会报错。

⇒ **必须是属性名。** 注入 B 证明这条有守卫接着。

⚠️ 一个正确的巧合：本仓 `agent.model.*`（`application.yml` 第 252–255 行）**早就**在用同样的写法
复用 `BLOG_AGENT_*`（那边写的是环境变量名），而 `agent` 与 `blogagent` **也不在同一个部署单元**。
⇒ "在 YAML 层复用另一个域的配置"在本仓是**既有做法**，只是它**不在扫描器的视野里**。见 §3vicies.10。

### 3vicies.4 ⚠️ 这一刀不动任何 Java 契约

`img2three` 与 `blogagent` 之间**没有能力调用**，只有"同一个值"：
无新模块、无新契约类型、无新端口、`pom.xml` 一行没改、`contracts/` 一个字没改。
Java 侧只做减法：删一个 import、删一个字段、删三个私有方法，`endpoints()` 改成直接取 `properties`。

### 3vicies.5 ⚠️ 代价：单元测试再也构造不出那条路径 ⇒ 必须用**真实交付的** YAML 守住

把回落从代码搬到配置，代价是**可测性**：直接 `new Img2ThreeProperties()` 不经过任何 YAML，
于是"回落真的存在"这件事在单元测试里**无法表达**。

⇒ 新增 `Img2ThreeCredentialFallbackTest`：把**真实交付的** `application.yml` 用
`YamlPropertySourceLoader` 加载进来，用 `PropertySourcesPropertyResolver` 解析那三个键，
再叠一个模拟的 `application-local.yml` / 环境变量源。三条用例：

1. 只配了 `BLOG_AGENT_*` ⇒ 三个键整体回落到它；
2. 同时配了 `IMG2THREE_AGENT_*` ⇒ img2three 自己的赢；
3. `blog.agent.*` 写成**属性**（`application-local.yml` 的真实形态）⇒ 也能回落。

**这是"搬家"之后唯一能守住它的东西** —— 与 §3quaterdecies 那条"一份实体两份投影必须配跨投影断言"
同源：**东西搬走了，守卫也要跟着搬，否则搬完就没人看了。**

### 3vicies.6 顺手补掉一条**零覆盖**的路径

`endpoints()` 的"网关关闭"分支此前**一条测试都没有**：两个既有用例的网关桩都返回非空端点，
方法在第二行就早退了。补了两条（`Img2ThreeModelClientTest` 2 → **4**）：

| 用例 | 钉住 |
|---|---|
| `buildsTheEndpointFromImg2ThreeOwnSettingsWhenTheGatewayIsOff` | 网关关闭时 baseUrl / apiKey / model **三个都取对**（起真 HTTP server 看请求落点与请求体） |
| `reportsNotConfiguredWhenNeitherImg2ThreeNorTheBlogAgentFallbackIsSet` | 两端都空时抛 `IllegalStateException`，且文案同时点名 `IMG2THREE_AGENT_API_KEY` 与 `BLOG_AGENT_*` |

⚠️ 与 ADR 待办 32 同族：**改一段代码时顺手数一下它有几条测试** —— 这里答案是 **0**，
而它是运维唯一会看到的提示。

### 3vicies.7 实测收益

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 26 | **25** |
| `implementation_edges` | 12 | **11** |
| `cross_deployment_unit_statements` | 9 | **8** |
| `cross_deployment_unit_edges` | 5 | **4** |
| `api_statements` | 9 | 9（不动） |
| `api_edges` | 6 | 6（不动） |

`studio → blogagent` **整条边消失** ⇒ ⭐ **`studio` 作为来源域在实现层与 api 层都一条出边都没有了**
（它此前的另一条 `studio → admin` 已被 `core-api` 的 `ModelGateway` 收掉）。

### 3vicies.8 注入验证：六次（三条在 YAML，三条在 Java）

| # | 注入 | 落在 | 实测红 |
|---|---|---|---|
| A | 回落整条丢掉（只认 `IMG2THREE_AGENT_API_KEY`） | `application.yml` | **2** |
| B | 回落写成**环境变量名** `${BLOG_AGENT_API_KEY:}` | `application.yml` | **1** |
| C | 回落反过来**盖过** img2three 自己的值 | `application.yml` | **1** |
| D | 网关关闭时 baseUrl 写死成常量 | `Img2ThreeModelClient` | **1** |
| E | `apiKey` / `model` 两个相邻 `String` 换位 | `Img2ThreeModelClient` | **1** |
| F | 只把 `base-url` 这一行的回落丢掉 | `application.yml` | **1** |

六条全部生效，两个目标文件跑完后**逐字节还原**（`git hash-object` 与基线相同：
`application.yml` = `2e452954…`、`Img2ThreeModelClient.java` = `6b74c9a5…`）。

⚠️ **B 是本刀最有价值的一条**：它把"占位符写属性名还是环境变量名"这个**写错了不会报错**的选择
变成一条会红的断言。没有 `alsoFallsBackToABlogAgentPropertyWrittenInAProfileFile` 的话，
B 会**绿着过**，而本地部署的 img2three 会静默失去凭证。

⚠️ **D 的失败信息没有指向真正的原因**（"守卫的失败信息"第三次实例，见 ADR 待办 45）：
它把 baseUrl 写死成 `https://api.openai.com/v1` 之后，用例不是断言失败，而是**真去请求公网**、
24 秒后以网络错误结束（`Errors: 1` 而非 `Failures: 1`）。红是红了，但原因看起来像"网络问题"。

⚠️ **两个工具坑（已写进 `notes/repo-workflow.md`）：**

1. **⚠️ 注入锚点必须对"行尾已归一"的文本匹配。** 本仓工作区 CRLF / LF **混杂**
   （实测 `application.yml` 是 **LF**、`Img2ThreeModelClient.java` 是 **CRLF**），
   而锚点以 `\n` 结尾 ⇒ 在 CRLF 文件里 `…));\r\n` 不含子串 `…));\n`，锚点**静默 0 命中**
   （第一遍 D 就是这样中止的）。修法：读时把 `\r\n` 归一成 `\n` 再匹配，写回时按记录的原行尾还原。
   ⚠️ 顺带一条读数坑：**Git Bash 里 `grep -c $'\r'` 判 CRLF 不可信**（对 LF 文件也报满行数），
   要用 Python 数 `b"\r\n"` 的字节数。
2. **⚠️ 注入脚本不要在前台跑。** 六条约 4.5 分钟，前台会在默认超时处被 SIGTERM，
   **把目标文件留在注入态**（实测残留了 E）。必须后台跑；**任何中断之后第一件事是比 blob**。
   这是"脚本跑完了 ≠ 注入生效了、也还原了"的第四次变体 —— 这次是"脚本被杀了"。

### 3vicies.9 为什么没有加 `contract-changes.yaml` 条目

契约模块一个字没改，路由与响应形状都没动 ⇒ 没有契约变更要登记。

### 3vicies.10 ⚠️ 刻意没做的事 / 残留

- **⚠️ 没有解决"谁拥有 img2three 的凭证"这个配置归属问题。** `config-ownership.yaml` 把
  `blog.agent.api-key` 记成 `owner: content-automation` + `secret` + `NACOS: DINQOR-CONTENT-AUTOMATION`，
  把 `img2three.agent.api-key` 记成 `owner: studio` + `secret` + `NACOS: DINQOR-CORE`。
  改造后 img2three 的默认值仍指向 content-automation 的环境变量 —— 这是**运维约定**而不是代码依赖，
  但清单里没写、也没有门禁守。**本刀刻意没替这个决定做主**（删回落是行为变更）。
- **⚠️ 扫描器看不见配置层的跨域引用。** 本刀把一条 import 换成一行 YAML 占位符，指标如实下降，
  但"studio 的配置默认值指向 content-automation 的环境变量"这件事在
  `cross-domain-imports.yaml` 里**一个字都看不到**。与 `SHARED_KERNEL` 漏登记那个盲区同类：
  **指标只能看见它被设计来数的那种耦合。**
- **没有动 `agent.model.*` 那条同样形状的 YAML 回落**（它没有 Java 边，不在本刀范围）。
- **没有删 `Img2ThreeProperties` 里那三个 Java 默认值 `""`** —— 它们现在是"没配"的表示，
  `hasText` 判断仍然需要。

### 3vicies.11 下一刀

剩余跨单元实现边（**4 条**）：`automation → integration` 5、`admin → automation` 1、
`identity → integration` 1、`integration → automation` 1。

⚠️ **`integration ↔ automation` 是唯一一对互相依赖的单元**（5 条出 + 1 条回）。本刀之后它成了
最大的一块，**要一起看** —— 只收一边会把环变成"单向但仍在跨单元"：

1. **`admin → automation`（1）** —— `AdminNotificationController`，需要 SSE 广播端口。
   ⚠️ 契约不许带框架类型 ⇒ 端口**不能**直接返回 `SseEmitter`，形状要先设计。
2. **`integration → automation`（1）** —— `MailService` import
   `automation.domain.EmailReminderMailTemplates`，是**域实体跨边界**（ADR-2 直接禁止的形状），
   要改 API 而不是加端口。
3. **`identity → integration`（1）** —— `AuthService`。

---

## 3unvicies. 第十八刀：`MailPort` —— 一个**枢纽**上的三条边，跨单元实现图变成**无环链**

### 3unvicies.1 目标边：三条边都经过同一个类

`integration.mail.MailService` 是本刀之前唯一一个**同时参与三条跨单元边**的类型：

| 边 | 语句 | 落点 |
|---|---|---|
| `automation → integration` | 1 | `EmailReminderScheduler` 调 `sendReminderMail(...)` |
| `identity → integration` | 1 | `AuthService` 调 `sendVerificationCode(...)` |
| `integration → automation` | 1 | **反向**：`MailService` import `automation.domain.EmailReminderMailTemplates` |

⭐ 第三条与第一条闭成一个**真环**，也是当时跨单元实现图里**最后**一个环。
ADR §7.12 早就写下"要断环必须改 `MailService` 的 API（让调用方把渲染好的正文传进来），
不是加个端口就行"；`contracts/automation-api/pom.xml` 与它的 `package-info` 里也各留了一段
同样的说明。**本刀就是那一刀**，两处说明已改成"已做"。

### 3unvicies.2 ⚠️ 侦察结论：真环的成因不是"互相调用"，是"提供方替消费方渲染"

```java
sendReminderMail(String to, String subject, String userMessage,
                 Instant scheduledAt, String contentType, String templateId)
```

六个参数里只有 `to` / `subject` 是**投递**需要的；后四个**只服务于渲染**
（归一化内容类型、按显示时区格式化时间、挑品牌化 HTML 版式、生成纯文本兜底）。
而渲染要用的两样东西**都不是 integration 的**：

- `automation.domain.EmailReminderMailTemplates` —— 另一个域的**业务类型**（ADR-2 直接禁止）；
- `email-reminder.display-zone` —— `docs/config-ownership.yaml` 里 **owner 记在
  `content-automation` 名下**的键。⭐ **台账是对的，代码是错的。**

⇒ 这不是"两个服务互相调用"（拆完后 A 调 B、B 调 A 是正常的），而是
**提供方在替消费方做消费方的业务**。判据：

> **渲染**（"这封提醒长什么样"）是 automation 的业务概念；
> **投递**（"把它送出去"）才是 integration 的能力。

⚠️ 这条判据与本仓既有布局吻合：`integration/mail/` 里**本来就**住着
`LoginVerificationMailTemplates` —— "邮件模板住在邮件包里"是本仓既有做法。
真正的错位只有一处：**提醒**的模板住在 `automation`，而它的渲染却在邮件包里。

### 3unvicies.3 ⭐ 形状决定：端口只带"发什么"，不带"怎么渲染"

```java
void sendSimpleMail(String to, String subject, String content);
void sendVerificationCode(String email, String code);
void sendHtmlMail(String to, String subject, String plainText, String html);
```

端口上**没有 `Instant`、没有 `contentType`、没有 `templateId`、没有 `displayZone`**。
一旦它们回来，提供方就要重新 import 业务域的模板类、重新读别人家的配置键 ——
反向边与"读别人的配置"会**一起**复活。

⚠️ **没有把 `sendSimpleMail` 与 `sendHtmlMail` 合并成一个带 `html` 可选参数的方法**：
载体不同（`SimpleMailMessage` vs MIME multipart），而且 `html == null` 时"退回纯文本"
这种隐式回退会让**"渲染失败"看起来像"这封邮件本来就没有正文"**。

三个方法各有**具名消费方**：`sendSimpleMail` → `EmailReminderScheduler` 的 PLAIN 分支
（以及同单元的 `RedisHealthMonitor`）；`sendVerificationCode` → `identity.AuthService`；
`sendHtmlMail` → `EmailReminderScheduler` 的 HTML 分支。
⚠️ `sendVerificationCode` 看似"就是 `sendHtmlMail` 的一个特例"，但它的正文**在提供方内部生成**，
所以它是"发一封特定业务的邮件"而不是"投递"。

### 3unvicies.4 ⚠️ 刻意不收 `userId` —— 由**反向**守卫钉住

`sendVerificationCode` 发生在**登录之前**，那时还没有身份；另外两个是**传输**，
归属判定在 `automation` 自己的库里、调本端口**之前**就做完了。
⚠️ 但"别的端口都收 `userId`"这条**一致性**会诱使后来者顺手补上，补完之后
编译过、指标不动、**26 条门禁全绿**、消费方的 Mockito 桩一个都不用改。
⇒ `MailPortTest.takesNoCallerIdAndThatIsDeliberate`（`assertNoParameterIsNamed`）钉住。
这是"端口收不收身份要成对守卫"（§3septdecies 定下）的**第三个**实例。

### 3unvicies.5 ⚠️ 代价：改 API 而不是加端口 ⇒ 消费方跟着改

| 改动 | 文件 |
|---|---|
| `MailService` 收窄成只投递 + `implements MailPort` | `integration/mail/MailService.java` |
| 渲染搬回本域（纯函数、无字段、无 Spring） | `automation/domain/EmailReminderMailRenderer.java`（**新增**） |
| 调度器依赖端口、自己渲染 | `automation/infrastructure/EmailReminderScheduler.java` |
| `AuthService` 依赖端口 | `identity/application/AuthService.java` |
| 端口实现清单 +1 | `migration/PortDelegationTransactionTest.java` |

⚠️ **两处行为细节被逐字保留**：①`ZoneId.of(displayZone)` 原本就在 HTML 分支**里面**，
所以 `display-zone` 配错时**纯文本提醒不受影响** ⇒ 渲染器收 `String displayZoneId`
而不是 `ZoneId`（收 `ZoneId` 会把解析提到调用点，纯文本提醒跟着一起炸）；
②选了 `"none"`（不要版式）时**纯文本兜底里不带时间与时区**。
分别由 `invalidDisplayZoneOnlyBreaksTheHtmlPath` 与
`noneTemplateKeepsTimeOutOfThePlainTextFallback` 钉住 —— 后者是搬家时最容易被"顺手统一"掉的一处。

⚠️ 邮件失败文案变了（`"定时提醒邮件发送失败"` → `"邮件发送失败，请稍后重试"`），
但**落库的错误文本没变**：`EmailReminderScheduler.rootMessage(e)` 取的是**根因**消息
（SMTP 异常本身），不是包装层。这正是那条 `rootMessage` 循环存在的理由。

### 3unvicies.6 顺手补掉**两条零覆盖**

| 此前 | 为什么没人发现 |
|---|---|
| `MailService` **没有测试类** | 它没实现任何契约 ⇒ 方法体只在三个消费方的测试里以 **mock** 出现，**真实实现一次都没被跑过** |
| `sendReminderMail` 的渲染逻辑**零断言** | 只被调度器间接走到，而调度器的测试把邮件服务整个 mock 掉了 |

⇒ 新增 `MailServiceTest`（4 条）与 `EmailReminderMailRendererTest`（6 条）。
⚠️ 与"把域内方法提升成契约实现时先查它有没有测试"同源：
**抽端口会让原本不重要的东西变重要**，而"没人写那条断言"不会有任何东西报错。

⭐ **`MailServiceTest` 第一版就红了一条，而且红得有价值**：`sendVerificationCode` 的
HTML 正文里，验证码是**逐位放进不同的 `<span>`** 的（`buildDigitCells`），
所以 HTML 原文里**根本不含**连续子串 `246810`。第一版对两份正文都断言
`contains("246810")` ⇒ 红。判据改成"纯文本按**连续子串**判、HTML 按**剥掉标签与空白后的文本**判"，
并把"HTML 是逐位渲染的"这个既有形状一起钉住。

### 3unvicies.7 实测收益：跨单元实现图变成**无环链**

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 25 | **22** |
| `implementation_edges` | 11 | **9** |
| `cross_deployment_unit_statements` | 8 | **5** |
| `cross_deployment_unit_edges` | 4 | **2** |
| `api_statements` / `api_edges` | 9 / 6 | 9 / 6（**不动**） |

三条边**全部消失**（`identity → integration`、`integration → automation` 归零，
`automation → integration` 5 → 4），剩下两条构成一条**链**：

```text
dinqor-core-host ──1──▶ dinqor-content-automation ──4──▶ dinqor-platform-integration
```

⇒ **每一个 Wave 2 单元都是单元素分量，跨单元实现图无环。**
`MUTUAL_UNIT_PAIRS` **清空**。环的作用是**否认抽取顺序**；链给了一个。

### 3unvicies.8 注入验证：八次

`-Dtest` 收窄到"依赖方自己的测试类"（待办 41 的规矩）。

| # | 注入 | 落在 | 实测红 |
|---|---|---|---|
| A | `MailPort` 上加一个带 `templateId` 的 **`default`** 重载 | `MailPort` | **4** |
| B | `MailPort` + `MailService` 把 `sendHtmlMail` 的 `plainText` / `html` 换位 | 契约 + 实现 | **1** |
| C | `MailService` 去掉 `implements MailPort`（连带删三个 `@Override`） | `MailService` | **2** |
| D | 渲染器删掉 PLAIN 早退分支 | `EmailReminderMailRenderer` | **5** |
| E | 渲染器把 `wrapped` 恒置 `true` | `EmailReminderMailRenderer` | **1** |
| F | 渲染器把 `ZoneId.of` 提到 PLAIN 分支之前 | `EmailReminderMailRenderer` | **1** |
| G | 调度器把 html / plain 两个投递分支互换 | `EmailReminderScheduler` | **3** |
| J | `MailService` 重新 import automation 的域类型（不使用） | `MailService` | **门禁 2 条** |

⭐ **A 最有价值**：它把"端口变宽"做成一次**编译通过、指标不动、26 门禁全绿、
消费方 mock 一个都不用改**的改动 —— 只有反射测试能守。

⭐ **B 证明反射断言有独立价值**：换位后行为测试 `MailServiceTest` **4/4 全绿**
（它断言"两份正文都在"，与顺序无关），只有按**参数名**钉的 `MailPortTest` 红了。
⚠️ 第一遍 `-Dtest=MailPortTest,MailServiceTest` 时 `MailServiceTest` **根本没跑**
（`integration-api` 一红，依赖它的 `core-host` 整块跳过）⇒ "它绿着"是**没跑**不是**通过**。
第二遍单独跑才拿到 4/4。**跨模块注入时，"某个测试没红"必须先确认它跑了**（待办 41 第二次）。

⭐⭐ **J 把两条门禁分开了**：

| 门禁 | 注入后直接跑 | 注入后**先 `scan.py` 再**跑 |
|---|---|---|
| `cross-domain-import-baseline` | **FAIL** | **PASS**（23/10/6/3） |
| `deployment-unit-coupling` | **FAIL** | **FAIL** |

⇒ 前者的红只是"改了代码没重跑 `scan.py`"（它的基线是 `scan.py` 的产物）；
后者的基线是代码里的常量 `MUTUAL_UNIT_PAIRS` ⇒ **清空后的基线确实是最严的形式**。

八条注入全部逐字节还原。⚠️ 两条工具坑：①**注入脚本的前置断言要按形状分开写**
（`fwd` 与 `mut` 互为子串时对称判据会在某一个方向恒假 —— 本刀 J 的**还原**被自己的断言卡住，
`MailService` 留在注入态；第十七刀踩的是另一半边）；②**注入要红在判据上，不能红在编译上**
（C 第一版只删 `implements` ⇒ 三个 `@Override` 编译失败，测的是 javac 不是判据）。

### 3unvicies.9 为什么没有加 `contract-changes.yaml` 条目

同 §3bis / §3nonies / … / §3vicies：`contract-changes.yaml` 登记的是**前端可见契约的变化**。
`MailPort` 是**服务间**契约，路由 / 响应形状 / 错误码 / 前端可见类型**一个都没动**；
`MailService` 的收窄也只影响 Java 调用方。`contract-changes-completeness` 仍报 14 条。

⚠️ 与前面几刀**不同的一点**：本刀动了 `integration → automation` 这条**反向边**，
按 V9 §2.3 的元规则值得问一句"这算不算能力变化"。结论是**不算**：
`MailService` 的**能力**（能发邮件）没变，变的是**它的参数表**，而它没有前端可见入口。
真正被削掉的是一处**架构违规**。

### 3unvicies.10 ⚠️ 刻意没做的事 / 残留

- **`RedisHealthMonitor` 的共享内核残留已清掉**：它已移到
  `operations.redishealth`，只依赖 `RedisHealthProbe` 与 `integration-api` 的 `MailPort`；
  具体 `MailService` 仍留在 integration。这样 common 不再反向依赖业务 implementation，
  `shared-kernel-reverse-dependency` 的 Wave 0B 基线已归零。
- **`email-reminder.display-zone` 的归属没动**：改造后读它的人（`EmailReminderScheduler`）
  与 owner（`content-automation`）终于是同一个，但 `config-ownership.yaml` 里那条记录
  **一个字都没改**（本来就记对了）。⚠️ 与 §3vicies.10 的 `img2three` 那条不同：
  那里是"默认值指向别人的环境变量"，这里**没有这种引用**。
- **`MailPort` 上没有 `default` 方法**，也没有任何"以后可能会用"的方法 ——
  端口宽度由 `MailPortTest` 用反射钉住，注入 A 证明它接着线。

### 3unvicies.11 下一刀

剩余跨单元实现边 **2 条 / 5 语句**：

1. **`automation → integration`（4）** —— **全部**是凭证服务：
   `BlogContentPlanService` / `BlogPlanRunService` 各 import
   `CsdnCredentialService` / `JuejinCredentialService`。形状与 `OssPort` 同族，
   且有 §3sexdecies.2 的先例（一个平台一个端口）⇒ 很可能是两套 `CredentialPort`。
   ⚠️ 本文 §3quater.5 的草稿写的是单数 `CredentialPort`，取这一刀时先复核。
2. **`admin → automation`（1）** —— `AdminNotificationController`，需要 SSE 广播端口。
   ⚠️ 契约不许带框架类型 ⇒ 端口**不能**直接返回 `SseEmitter`。这是 `automation` 的
   **最后一条入边**，收掉它 `automation` 就成了**零跨域消费方**的域。

⚠️ 两条都收掉之后**跨单元实现边归零**，`cross-domain-imports.yaml` 里只剩同单元的边；
`deployment-unit-coupling` 的三条判据将全部只剩"不许变坏"的含义。

---

## 3duodevicies. 第十九刀：`CsdnCredentialPort` / `JuejinCredentialPort` —— 跨单元实现边只剩一条

### 3duodevicies.1 目标边，以及"先数跨域表面"这一步

`automation → integration`，4 条实现语句，落在 `BlogContentPlanService` 与
`BlogPlanRunService` 上，每个文件各 import 两个凭证服务。

⚠️ 侦察第一件事**不是想端口形状，是数清跨域表面**。两个实现类各有 **6 个公开方法**，
而跨域位置只用到**一个** `isAuthorized(Long)`：

| 方法 | 跨域调用点 |
|---|---|
| `isAuthorized(Long)` | `toVO`（填前端派生字段）、`publishCsdn` / `publishJuejin`（发布前置条件） |
| `isExpired` / `markExpired` / `requireCookie` / `requireCredential` / `save` / `delete` | **全在 `integration` 域内** |

⭐ **端口宽度 = 调用方适配器被迫实现的成员集。** 这一刀只搬 6 个里的 1 个；
另外 5 个端出去，消费方的适配器就被迫实现 4 个它从不调用的成员。
**一半的价值来自没搬的东西。**

### 3duodevicies.2 为什么是两个端口：与第十六刀同源，但**代价相反**

三条理由与 §3vicies 那一刀（站外同步）同源：**域自己就是这么分的**（两套
`*PlatformCredential`、两个 mapper、两个独立的加密密钥）、**零适配器**、
**合并要造一个域里没有的词汇**。

⚠️ 但第十六刀有一条**附带收益**在这里**消失了**，而且是反过来的：

| | `*BlogSyncPort`（第十六刀） | `*CredentialPort`（本刀） |
|---|---|---|
| 方法名 | 相同（`sync` / `findSucceeded`） | 相同（`isAuthorized`） |
| **参数表** | **不同** —— 掘金多收分类与标签 id | **逐字相同** |
| "接收者写反"的后果 | **编译失败** | **编译通过** |

⇒ 第十六刀写下"两个端口的方法表与各自实现逐字相同 ⇒ 实现类只加一句 `implements`"，
那句话在**参数表不同**的前提下顺带挡住了一类错误。本刀把这个前提拿掉了。

### 3duodevicies.3 ⚠️ 新缝：**同形不同源** ⇒ 静默换位点的第三类

| # | 形状 | 例子 |
|---|---|---|
| 1 | **同名同参的重载** —— 新方法的参数表与旧方法逐字相同时，转发点显式点名旧名字，编译器没有意见 | §3quater / 待办 20 |
| 2 | **端口归一参数顺序** —— 提供方 `(taskId, userId)` 与调用方 `(userId, taskId)` 归一后两个 `Long` 换位 | ADR 待办 20 |
| 3 | **同形不同源** —— 两个**不同提供方**的对象，方法名与签名逐字相同，**接收者写反**编译得过 | **本刀** |

```java
if (!csdnCredentialPort.isAuthorized(plan.getUserId()))   // ← 写成 juejinCredentialPort
```

编译通过、耦合指标一动不动、26 条门禁全绿。症状是"CSDN 发布前检查了掘金的授权"，
而两边都返回 `boolean`，类型层面没有任何信号。

### 3duodevicies.4 ⭐ "某个地方有测试挡着" ≠ "这个错误形状被守住了"

**这是本刀最值得记的一条。** 同一个"接收者写反"，实测（§3duodevicies.7 注入 C / D）：

| 消费方 | 写反的位置 | 实测红 | 其中**既有**用例 |
|---|---|---|---|
| `BlogPlanRunService` | `publishCsdn` 的接收者 | **3** | **2 条** |
| `BlogContentPlanService` | `toVO` 里 `csdnAuthorized` 的来源 | **1** | **0 条** |

`BlogPlanRunService` 那两条既有用例是**碰巧**挡住的：它们给 CSDN 桩 `true`、
而掘金桩没打桩（Mockito 默认 `false`），正好把两者分开，于是写反之后 CSDN 那条路
拿不到授权、`verify(publicationService).publish(…, "csdn")` 不满足 ⇒ 红。
而 `BlogContentPlanService` 的既有用例**根本没有读那两个派生字段的断言** —— 那里是真的盲区。

⇒ **"某个地方有测试挡着"不能推广成"这个错误形状被守住了"。**
同一形状、同一刀、两个消费方，一个红 3 条、一个只红新加的 1 条。

判据必须是**不对称桩**（一个平台授权、另一个不授权），两个方向各一条。

⚠️ **顺带澄清一条此前写得太宽的话。** 前面几刀反复写过"这类缺陷注入验证发现不了"——
**过宽了**。正确的区分是：

| | 注入能不能发现 | 例子 |
|---|---|---|
| **位置**问题 —— 某个具体地方没人守 | **能**（注入落在那里就红） | 注入 D：`toVO` 的派生字段没人断言 |
| **判据**问题 —— 判据的**形状**不够强 | **不能**（永远看不见），只能**反着用一次** | 只断言参数**类型**不断言**参数名**（注入 E） |

⇒ 一条判据写完，要问两个问题：**①它守的位置有人管吗？②它的形状够强吗？**
①靠注入，②靠"拿判据去断言它本该拒绝的形状"。

⚠️ **同一个坑在第十六刀的两个同步端口上早就存在**：`BlogPlanRunService` 只注入了
`JuejinBlogSyncPort`，下一个同时接入两个同步端口的调用方就会踩到。本刀没去补，
登记为 ADR 待办 54。

### 3duodevicies.5 落地：零换算、零新类型

两个端口各一个方法，方法表与各自实现类那个公开方法**逐字相同**：

```java
public interface CsdnCredentialPort   { boolean isAuthorized(Long userId); }
public interface JuejinCredentialPort { boolean isAuthorized(Long userId); }
```

实现类只加一句 `implements` 与一个 `@Override`，消费方只换注入类型 ——
**没有新 DTO、没有新记录、没有换算、没有中间适配器**。这是本刀与前面十五刀最不一样的地方：
`OssPort` 有 `byte[] ↔ MultipartFile` 的换算，`MailPort` 把渲染整个搬走，
这一刀**只换了一个类型名**。⚠️ 但"没有换算"不等于"没有新缝"（§3duodevicies.3）。

⚠️ 两个端口的**"可用"口径不同**（CSDN 额外要求用户名非空，因为它要生成回链；
掘金只要求 cookie 非空）—— 这个差异**属于实现，不属于契约**：
跨域位置没有任何调用方读得出它。写在端口注释里，不写进签名。

### 3duodevicies.6 ⭐ 实测收益：跨单元实现边只剩 `admin → automation`

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 22 | **18** |
| `implementation_edges` | 9 | **8** |
| `cross_deployment_unit_statements` | 5 | **1** |
| `cross_deployment_unit_edges` | 2 | **1** |
| `api_statements` / `api_edges` | 9 / 6 | 9 / 6（不动） |

`automation → integration` **整条边消失**。`automation` 作为来源域只剩两条**同单元**出边
（`→ blogagent` 4/2、`→ publishing` 2/0）。

⚠️ 剩余跨单元实现边**只剩一条** `admin → automation`（1）⇒ **跨单元实现图现在是一条链**
`core-host ──1──▶ content-automation`，且 `MUTUAL_UNIT_PAIRS` 已清空（第十八刀）。

### 3duodevicies.7 注入验证：七次

落点分两类，**必须分开跑**（待办 41）：契约模块排在反应堆前面，它一红就让依赖它的
`core-host` 整块跳过。契约侧（A / B / E / F）用 `-Dtest=CsdnCredentialPortTest`，
消费方侧（C / D）用各自消费方的测试类。

| # | 注入 | 落在 | 实测红 |
|---|---|---|---|
| A | 端口加一个 `default` 方法 | `CsdnCredentialPort` | **2** |
| B | 返回类型 `boolean` → `Boolean` | 端口 + 实现 | **2** |
| C | ⭐ `publishCsdn` 的接收者换成掘金端口 | `BlogPlanRunService` | **3**（含 2 条既有） |
| D | ⭐ `toVO` 里 `csdnAuthorized` 改从掘金端口取值 | `BlogContentPlanService` | **1**（只有新加的） |
| E | 首参改名 `userId` → `credentialOwnerId` | `CsdnCredentialPort` | **1** |
| F | 端口 `extends` 掘金孪生接口 | `CsdnCredentialPort` | **2** |
| G | 消费方退回具体 `@Service` | `BlogPlanRunService` | **指标 18/8/1/1 → 20/9/3/2** |

⭐ **A**：给端口加一个带实现体的 `default` 方法 ⇒ 编译过、指标一动不动、26 门禁全绿、
消费方一个 mock 都不用改，只有反射测试红。**这条缝每加一次端口就开一次。**

⭐ **C / D 的对照**见 §3duodevicies.4 —— 本刀最有价值的一条。

⭐ **E**：改名之后**没有任何其它断言变红**，证明"断言参数名"那条守卫真的接着线
（只断言类型 `Long` 会绿着过）。

**G** 证明**指标确实是这一刀搬动的**；同时它是"判据的基线必须来自判据之外"的
**第九次**实测 —— 注入后按既定流程（先 scan 再 gates）跑，
`cross-domain-import-baseline` 报 **PASS**（`20 statements across 9 edges`），
存量涨了门禁照样绿。

⚠️ 两条工具坑：①G 第一版把两个 import 写成相邻一块，但它们中间夹着
`JuejinBlogSyncPort` ⇒ **锚点 0 命中**（"锚点唯一"要连"它是不是真的相邻"一起查）；
②G 会把 `scan.py` 的产物改成注入态的数字 ⇒ **收尾必须无条件重跑一次 scan**
（产物既是基线，就绝不能带着注入态落盘）。

⚠️ 七个目标文件全部逐字节还原（`git hash-object` 与基线逐个相同）。

### 3duodevicies.8 为什么没有加 `contract-changes.yaml` 条目

同 §3vicies.9 与 §3unvicies.9：没有改任何**线上形状**。两个端口是新类型，
不是对既有 HTTP / SSE / WebSocket 契约的改动；消费方注入的类型换了，
但同进程内仍是同一个 bean，返回值类型一分没变 ⇒ 可证纯重构。

### 3duodevicies.9 ⚠️ 刻意没做的事 / 残留

**`BlogContentPlanService` 的"已知残留"没修。** `ContentPlanPort` 的读方法都经过 `toVO`，
而 `toVO` 要调两个 `isAuthorized` 去填前端要的派生字段；随后 `toView` 把它们**收窄掉**
（`ContentPlanView` 里没有这两个字段）。⇒ 一个智能体并不读的派生字段，
能让一次状态变更失败，而两次凭证查询的结果被**丢掉**。

⚠️ 修了会同时**改变行为**（端口路径不再可能被凭证查询的失败带崩），
而本次抽取要能**被证明**是纯重构。⇒ 刻意不顺手做，登记为 ADR 待办 53。
**这一刀让这处残留更具体了（现在它经过端口），但没有让它变好。**

> ⚠️ **2026-09-20 订正（ADR §24）：待办 53 已做。**
> 判据是"凭证在**这条路径里的语义角色**"，结论是 **fail-soft**（失败降级为 `false` + 一条
> 带固定标记的 WARN）。两处与本段原文不符，都要改口：
> 1. **口径是宽的** —— 修在 `toVO` 上，端口路径与前端路径一起受益；**不是**"让端口路径绕开
>    `toVO`"那个窄口径（理由与回退动作见 ADR §24.6）。
> 2. **后果比"能让一次状态变更失败"重一档** —— `create` / `update` / `updateStatus` 都是
>    `@Transactional`，凭证查询抛出的异常会逃出事务方法 ⇒ 一次**已经写成功**的变更被**回滚**。
> 另：实测那两个派生字段在**本仓与前端仓都零消费方**，而 `list` / `listAll` 是逐行调 `toVO`
> ⇒ 每个计划行 2 次查询（**2N 次**）。删字段会改线上 JSON，登记为 ADR 待办 61。

### 3duodevicies.10 下一刀

1. **`admin → automation`（1）** —— 唯一剩下的跨单元实现边。`AdminNotificationController`
   需要 SSE 广播能力，⚠️ 契约不许带框架类型 ⇒ 端口**不能**返回 `SseEmitter`，
   形状要先设计。
2. **补 `*BlogSyncPort` 的"同形不同源"守卫**（§3duodevicies.4 末）。

⚠️ 两条都收掉之后**跨单元实现边归零** —— 那时要重新想 `MUTUAL_UNIT_PAIRS` /
`WAVE2_UNITS` 的关系。

## 3trevicies. 第二十刀：`NotificationBroadcastPort` —— 跨单元实现边**归零**，以及一条被错记成"卡在框架类型上"的边

### 3trevicies.1 目标边：不是"调能力"，也不是"借凭证"，是**把一条广播转发出去**

`AdminNotificationController` 注入 `BlogPlanSseService`（`automation.infrastructure`），
只为了调它的 `broadcast` 发一条诊断消息。这是 §3duodevicies.10 留下的**最后一条**跨单元实现边
（`dinqor-core-host ──1──▶ dinqor-content-automation`）。

⚠️ 上一刀把它记成"**卡在框架类型上**"：`subscribe` 返回 `SseEmitter`、`push` 收
`planId` / `runId`，于是"契约不许带框架类型"这条铁律像是把这条路封死了。
**这个结论是错的，而且错在一个很基本的地方。**

### 3trevicies.2 ⭐ 侦察结论：**整个类碰不得 ≠ 其中一条方法碰不得**

先把跨域**表面**数一遍（§3duodevicies.1 立下的规矩）：消费方一共只用了
`BlogPlanSseService` 的**一个**方法，签名是

```java
public int broadcast(String type, String title, String message, Long planId, Long runId)
```

—— 三个 `String`、两个 `Long`、返回 `int`。**没有任何框架类型。**

⇒ "这个类碰不得"是真的（`subscribe` / `push` / 连接注册表 / 封禁校验全都不能上端口），
但"它里面**每一条**方法都碰不得"是假的。
**窄端口的判据从来不是"这个类长什么样"，而是"这条边上流过了什么"。**

⚠️ 错记的代价：`package-info.java` 与 `NotificationPort` 的注释里都写着
"SSE 广播端口…要的是 `SseEmitter`…单独一刀" ⇒ **一条本来当刀就能收掉的边，
被记成了"形状要先设计"**，于是它在边表上多躺了一刀。两处注释本刀都已订正。

### 3trevicies.3 ⭐ 形状决定一：端口只收**三个**参数，而不是实现的五个

`planId` / `runId` 是 **blog-plan 域的概念**，而唯一调用方（admin 的诊断端点）
**永远传 `null`**。

- 收下它们 ⇒ 契约上出现两个"要求每个调用方每次都撒谎"的参数。
- 不收 ⇒ 转发给 `push` 的两个值由"调用方传 `null`"变成"实现里写死 `null`"。

⇒ **收窄可证行为保持**：`push` 本来就把 `null` 序列化成空串，出站载荷**逐字节不变**。

⚠️ 这**偏离了** §3duodecies.6 立下的先例"端口形状选**与实现同形**"。理由是把两种
"多出来的东西"分开：

| 多出来的东西 | 调用方要做什么 | 该不该上端口 |
|---|---|---|
| 多余的**返回值**（§3duodecies 的 `Long`） | 忽略它 | 可以留（同形 ⇒ 实现类只加 `implements`） |
| 多余的**参数**（本刀） | 每次**伪造一个值** | **不该留** |

⇒ **"同形"是好性质，不是目标。** 目标是端口宽度 = 调用方真正需要的形状；
当"同形"要求调用方**撒谎**时，同形让位。

### 3trevicies.4 ⚠️ 形状决定二：这是**第三个** `automation` 端口，不是往 `NotificationPort` 加方法

`NotificationPort` 是 `knowledgebase → automation` 的**落库通知**（2 方法 + 2 常量）。
本刀这条边是 **SSE 实时广播** —— 不同的消费方、不同的能力、不同的形状
（一个写库、一个推连接）。⇒ 新端口 `NotificationBroadcastPort`（`automation.spi`，同包）。
判据同 §3sexdecies.3：**两种形状不混。**

### 3trevicies.5 ⚠️ 形状决定三：返回 `int`（**连接**数），而这个语义要有人守

实现返回的是**当前登记在册的连接数**（一个用户开两个标签页算 2）。
这个语义**此前只写在 `AdminNotificationController` 的注释里**，没有任何断言。

⇒ 端口保留 `int`，并补一条断言钉住（注入 G：`recipients += connections.size()`
改成 `+= 1`，只有它红）。⚠️ 与 §3decies 同族：**"没人写那条断言"不会有任何东西报错。**

### 3trevicies.6 端口"变宽"依然没人守（**第九次确认**）

给端口加一个带实现体的 `default` 方法：**编译过、指标一动不动、26 门禁全 PASS、
消费方一个 mock 都不用改**，只有反射测试红（注入 A，**4** 条）。

本刀顺手把新测试的 `methodNamed` 从"取第一个同名方法"改成"断言**恰好一个**" ——
改之前只有签名清单会红，改之后**两个**用例一起红（ADR 待办 57）。

### 3trevicies.7 ⭐ 实测收益：**跨单元实现边归零**

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 18 | **17** |
| `implementation_edges` | 8 | **7** |
| `cross_deployment_unit_statements` | 1 | **0** |
| `cross_deployment_unit_edges` | 1 | **0** |
| `api_statements` / `api_edges` | 9 / 6 | 9 / 6（不动） |

`admin` 作为来源域在跨单元层面**归零**（它仍有两组**同单元**出边：
`admin → identity` 2、`admin → virtualpay` 1）；`automation` 作为目标域也**归零**。
⚠️ 剩下的 7 条实现边**全部同单元**。

### 3trevicies.8 ⚠️⭐ 归零之后，"= 0"这条不变式**没有任何门禁守着**（新缝）

**本刀最值得记的一条。** 归零之前图里至少还有一条边；归零之后，
**"跨单元实现边 = 0"成了这一轮重构的头条成果，而它是无人看守的**：

| 门禁 | 新增一条**单向**跨单元实现边（A → B，无回边）时会怎样 |
|---|---|
| `deployment-unit-coupling` 判据 1 | 通过 —— 它只管 `bloghtml` 有没有边 |
| `deployment-unit-coupling` 判据 2 | 通过 —— 单向边**不会让任何单元进入强连通分量** |
| `deployment-unit-coupling` 判据 3 | 通过 —— 没有产生新的互依对 |
| `cross-domain-import-baseline` | 通过 —— 它的基线是 `scan.py` 的**产物**（待办 16） |

⇒ **两条门禁都不会红。** 而这不是本刀造成的缝：`deployment-unit-coupling` 从写下来那天
起就只判**形状**（"不许出现新的环 / 新的互依对"），判**大小**从来是
`cross-domain-import-baseline` 的活 —— 而那条的基线取自被测物本身（待办 16，**已问过九次**）。

⚠️ 归零之前这条缝**被掩盖着**：那时任何人读 `cross-domain-imports.yaml` 都能看到非零值。
归零之后，**"0"本身成了要守的东西**，而它现在只活在文档里。

修法（**未做**，ADR 待办 56）：在 `ci_gates.py` 写常量
`EXPECTED_CROSS_UNIT_IMPLEMENTATION_EDGES = 0`，与 `MUTUAL_UNIT_PAIRS` 同一个模式
（基线来自**判据之外**）。⚠️ 这个常量在归零**之前**写不出来（数字一直在动，写死就得每刀改一次），
**归零之后它才成为一个稳定常量** —— 所以这个修法是被本刀**解锁**的，而本刀**刻意没有顺手做**：
它是一次 CI 设计变更，与待办 16 同族，应该由人拍板。

### 3trevicies.9 门禁改动：让通过消息报**实测**边数

`deployment-unit-coupling` 的通过消息此前是"…the cross-unit implementation graph is
acyclic…"。⚠️ 图形状变了之后这句话开始**掩盖结果**：它对"空图"和"有几条单向边"
读起来**一模一样**，而这两件事的迁移含义完全不同（前者是完工，后者还有活）。
而判据本身在两种形状下都成立，所以**永远不会有人因为"它红了"去修它**。

⇒ 补上实测值：`…acyclic (0 cross-unit edge(s) / 0 statement(s) left)…`。
这是 §3unvicies 那条"分量大小为 1 时另起一句"的**同族第二次**：失真的都是**通过消息**，
不是判定。⇒ **任何通过消息里的形容词（"acyclic" / "isolated" / "singleton"），
都要问一句"它对另一种形状还成立吗？两种形状读起来一样吗？"**

### 3trevicies.10 注入验证：七次

落点分三类，**契约侧与消费方侧必须分开跑**（待办 41）：契约侧（A / B / C / F）用
`-Dtest=NotificationBroadcastPortTest`（`core-host` 匹配不到测试、不会被跳过）；
消费方侧（E / G）用 `-Dtest=BlogPlanSseServiceTest`；**D 的判据是指标，不跑 maven**。

| # | 注入 | 落在 | 实测红 |
|---|---|---|---|
| A | 端口加一个 `default` 重载 | `NotificationBroadcastPort` | **4** |
| B | 返回值 `int` → `Integer` | 端口 + 实现 | **2** |
| C | ⭐ 把 `planId` / `runId` 放回端口 | 端口 + 实现 + 消费方（三处同改） | **2** |
| D | 消费方退回具体 `@Service` | `AdminNotificationController` | **指标 18/8/1/1 → 18/8/1/1 的形状回来** |
| E | ⭐ 扇出里写死的两个 `null` → `0L` | `BlogPlanSseService` | **1** |
| F | 端口上多一个常量 | `NotificationBroadcastPort` | **1** |
| G | `recipients += connections.size()` → `+= 1` | `BlogPlanSseService` | **1** |

⭐ **C**：要**三个文件一起改**才能编译过 —— 恰好说明"**镜像**只需改端口就能编译，
而它仍然被守卫拦住"：守卫判的是**端口自己的形状**，不是"改起来费不费劲"。

⭐ **E**：这是"收窄可证行为保持"的**唯一一条**断言。把写死的 `null` 换成 `0L`，
出站载荷就从空串变成 `"0"`，而**除它以外没有任何东西会红**。

**D** 证明**指标确实是这一刀搬动的**：`impl / edges / cross_stmt / cross_edges`
= `18 / 8 / 1 / 1`，`admin -> automation impl = 1`。

⚠️ 七个目标文件全部逐字节还原（`git hash-object` 与基线逐个相同）。

### 3trevicies.11 ⚠️ 一条差点被误判成"还原失败"的读数：同一次验证里混用了**两种哈希算法**

E / G 的 `restore` 行打印 `1ae8d38b…`，而驱动脚本与收尾 `git status` 打印 `8df1c761…`
—— 同一个文件、同一个时刻、**两个哈希**，看起来像还原没到位。

实测（文件 12695 字节、267 个 CRLF、0 个裸 LF；`git cat-file -t 1ae8d38b` ⇒
`Not a valid object name`）：

| 算法 | 值 |
|---|---|
| 原始字节（注入脚本的 `blob()`，带 `blob <len>\0` 头） | `1ae8d38b…` |
| `sha1sum`（原始字节，**不带**头） | `87578e00…` |
| `git hash-object`（**先做 CRLF→LF 归一**；本仓 `core.autocrlf=true`） | `8df1c761…` |

⇒ `1ae8d38b` **就是**还原后基线的原始字节哈希。**没有残留。**
这是 §3novemdecies.8 那条（`sha1sum` ≠ `git hash-object`，ADR 待办 42）的
**第三种形态**，而且比前两种更难发现：前两种是"两个**外部工具**给出不同值"，
这次是**同一个脚本的两条代码路径**各自打印一个，还**挨着打印**在同一个输出里。

⇒ 已把注入脚本的 `blob()` 改成先归一 CRLF 再哈希，与 `git hash-object` **逐字等价**；
复跑 E（`apply` → `0ed8dd20…`、`restore` → `8df1c761…`），两侧一致；七条注入再全量
`apply` + `restore` 一遍，逐条 ok、收尾三文件哈希不变、`git status` 仍 12 项。

⚠️ 判据层面的教训：**"脚本打印了一个陌生的哈希"与"文件被改坏了"是两件事；
分辨它们的是 `git diff`（差异是否逐行都是本次意图），不是哈希比对。**
（登记为 ADR 待办 58。）

### 3trevicies.12 为什么没有加 `contract-changes.yaml` 条目

同 §3duodevicies.8：新端口是新类型；消费方注入的类型换了，但同进程内仍是同一个 bean，
出站载荷（`push` 的两个 `null` → 空串）**逐字节不变**，返回值语义一分没变 ⇒ 可证纯重构。

### 3trevicies.13 ⚠️ 刻意没做的事 / 残留

1. **"把 0 变成有人守的不变式"没做**（§3trevicies.8 / ADR 待办 56）——
   本刀解锁的、最该做的一件，但它是 CI 设计变更，不该在一次抽取里悄悄落地。
2. **`*BlogSyncPort` 的"同形不同源"守卫没补**（§3duodevicies.4 末 / ADR 待办 54）。
3. **`BlogContentPlanService` 的 `toVO` 残留没修**（ADR 待办 53）——
   它**会改变行为**，所以不是重构，要单独决定。
4. **`NotificationPortTest` / `ContentPlanPortTest` / `EmailReminderPortTest` 三处
   `methodNamed` 仍是旧写法**（ADR 待办 57）。

> ⚠️ **2026-09-20 订正：上面四条现在全部做完了。**
> ①待办 56 → ADR §21（**不是抽取刀**）；②待办 54 → ADR §22（⚠️ 那一刀的**前提是错的**，
> `PublishPostTool` 早就同时持有两个同步端口）；③待办 53 → ADR §24（**会改行为**，口径比
> 原文宽）；④待办 57 → ADR §23（⚠️ **实测是七处不是三处**，而且清单里还多写了一个）。
> ⚠️ 四条里有三条的"清单/前提"是**推**出来的而不是**数**出来的 —— 两次都在动手时被实测推翻
> （§22.1 / §23.2）。**凡文档里写"某处/几处还有 X"，动手前先 `grep` 数一遍。**

### 3trevicies.14 下一刀

**没有"下一刀抽取"了** —— 跨单元实现边已经归零。剩下的是三类**不是抽取**的工作：
①把"0"变成有人守的不变式（最该做的一件）；②补同形守卫；③决定 `toVO` 残留。

⚠️ 还有一件**更大**的事：`cross-domain-imports.yaml` 里剩下的 7 条边**全部同单元**。
`deployment-unit-coupling` 三条判据此后只剩"不许变坏"的含义 ——
那时要重新想 `MUTUAL_UNIT_PAIRS` / `WAVE2_UNITS` 的关系（§3unvicies.7 已把互依对清空）。

## 4. 必须留痕的判断

### 4.1 `PageData` 放 `xander-mybatis-support`，不放 `xander-model`

**§19 与 §20 在这里互相矛盾。** §19 把 `PageData` 列进"纯共享"的 5 个类之一；
§20 把它归给 `xander-model`，同时又要求 `xander-model` "无 MyBatis"。

两者不可能同时成立：`PageData` 的全部内容就是对
`com.baomidou.mybatisplus.core.metadata.IPage` 的适配器
（构造器 `PageData(IPage<T>)` 与 `PageData.of(IPage<T>)`）。

**裁决：让"无 MyBatis"这条赢。** `PageData` 进 `xander-mybatis-support`，跟着它真正依赖的技术走。

### 4.2 §20 提到的"公共 TypeHandler"在本仓库不存在

`grep -rn "TypeHandler"` 扫全仓返回**零**结果。所以这一项**没有东西可搬**，
不是"搬了但没记录"。将来若新增，归属 `xander-mybatis-support`。

### 4.3 `HealthController` / `RedisHealthProbe` 不进任何 lib

§19 明确："运维型 Redis 健康检查不进通用 libs"。这两个类**不能**为了"让 common 清空"
而塞进 `xander-model` 或 `xander-mybatis-support` —— 那正是 §19 要消灭的超级 jar 的做法。
它们属于 application/operations，Wave 1 跟随宿主。告警调度器已归入
`operations.redishealth`，并通过 `integration-api` 的 `MailPort` 消费邮件能力。

### 4.4 包名要跟着归属走：`identity.security` → `auth.core`

前两个库（`xander-model`、`xander-mybatis-support`）迁移时**保留了原包名**
（`com.xander.lab.common` / `com.xander.lab.config`），为的是不把 800+ 文件的 diff 里
混进 import 变更、让 rename 检测还能看出"到底改了什么"。

**`xander-auth-core` 反过来必须改包名**，因为原来的包名是错的：
`com.xander.lab.identity.security` 里的 `identity` 是**业务域**，而 `UserContext` 被
16 个域引用。把它原样搬进 `libs/` 会同时造成两个后果：

1. **架构上是假的**：一个共享库的类挂在业务域的包下，等 `identity` 真的变成独立服务时，
   这个包名就在撒谎。
2. **机器计数上是隐形的**：跨域扫描器**按包名**判定归属（`domain_of` 取 FQCN 第 4 段）。
   保留 `identity` 的话，那 35 条 import 仍然会被算成"跨域 implementation import"，
   于是这次抽取的价值在清单上完全看不到 —— 而清单是这个项目唯一的进度依据。

所以规则是：**包名真实就保留，包名指向业务域就必须改。**
`common` / `config` 是通用名，保留；`identity.security` 是业务域名，改成 `auth.core`
（与 artifact 名 `xander-auth-core` 对齐）。

改动量：48 个文件的 import + 4 个文件的 `package` 声明。全部是字节级替换，无逻辑改动。

### 4.5 ⚠️ `UserContext` 的 `ThreadLocal` 不是 Reactive 中立的

`UserContext` 零框架依赖，所以从**依赖**角度它是 Servlet/Reactive 中立的，符合 §20。
但它是用 `ThreadLocal` 实现的，而 `ThreadLocal` 只在"一线程一请求"的 Servlet 栈上成立 ——
WebFlux 下请求会在线程间迁移，本类会静默读到 `null` 或**别的请求的身份**。

§20 把 "trusted identity model" 明确列为本库允许的内容，而这就是现存的 identity model，
所以本轮按 §20 抽出来，**并把这个约束写在类 javadoc、package-info 和库 POM 的 description 里**，
而不是靠"把它留在业务域包下"来回避。Wave 1 的 Gateway 是 WebFlux，必须用 Reactor context
传递身份，不能用本类。

---

## 5. 构建层面的两条硬约束

这两条都是踩过才发现的，**不是风格偏好**。

### 5.1 带 `<modules>` 的 POM 必须是 `packaging=pom`

第一次尝试让根 POM 同时当聚合器和应用模块（`<modules>` + 默认 `packaging=jar`），
Maven 直接拒绝：

```text
'packaging' with value 'jar' is invalid. Aggregator projects require 'pom' as packaging.
```

所以根 POM 变成 `xander-lab-parent`（`packaging=pom`），应用搬到
`apps/core-host/` 并有自己的 POM。**`src/` 搬到 `apps/core-host/src/` 的唯一原因就是这条。**

### 5.2 `spring-boot-maven-plugin` 只能声明在应用模块

声明在父 POM 里，`repackage` goal 会被所有子模块继承；lib 没有 main class，
构建报 `Unable to find main class`。所以它**只**出现在 `apps/core-host/pom.xml`。

⚠️ `apps/core-host` 的 `artifactId` **保持 `xander-lab-backend`**：
`deploy.sh` 的 `APP_NAME` 和 docker-compose 的服务名都依赖它。改模块目录名 ≠ 改 artifactId。

---

## 6. 工具链联动（搬目录时必须同步改的地方）

搬类会让"按路径找文件"的工具静默失效 —— 失效方式是**计数变小但不报错**，
所以这几处必须一起改：

| 位置 | 改动 |
|---|---|
| `scripts/migration/scan.py` | `APP_ROOT` / `LIB_ROOT` / **`CONTRACTS_ROOT`** / `java_roots()` / `java_files()` / **`shared_kernel_packages()`**（`libs/*` 与 `contracts/*` 都扫）；所有 `JAVA_ROOT.rglob("*.java")` 换成 `java_files()`；**新库或新契约的包名要加进 `SHARED_KERNEL`** |
| `scripts/migration/ci_gates.py` | `gate_legacy_redis_reference_check` 的白名单路径 + **"白名单里的文件必须存在"判据**；`shared-kernel-classification` 改用 `shared_kernel_packages()` |
| `scripts/migration/audit_legacy_sql.py` | `DB_DIR = scan.DB_DIR`，不再硬编码 `src/main/resources/db` |
| `Dockerfile` | **每个模块两处**：先 COPY 它的 `pom.xml`（否则 `dependency:go-offline` 报 `Child module ... does not exist`），再 COPY 它的源码目录。由门禁 `dockerfile-module-coverage` 检查 |
| `.gitignore` / `.dockerignore` | `application-local.yml` 的路径。**漏改会让本地密钥变成可提交文件，并被 `COPY apps ./apps` 打进镜像** |
| `AGENTS.md` / `backend-norms` skill | 测试路径与物理布局说明 |

⚠️ **`java_roots()` 必须同时扫 `apps/core-host/src/main/java`、`libs/*/src/main/java`
和 `contracts/*/src/main/java`。**
只扫应用根的话，搬出去的类会从所有清单里消失，而清单的计数只是变小、不会报错。

⚠️ **`SHARED_KERNEL` 漏加新模块的包名，后果是"数字变好、耦合没变"**：扫描器按包名判定归属，
包名不在 `SHARED_KERNEL` 也不在业务域时，指向它的 import 会被整条丢掉，跨域基线于是**下降**。
这正是 §13 要防的静默漏计，所以 `shared_kernel_packages()` 从目录树推导、
`shared-kernel-classification` 门禁比对两者 —— 漏加就 FAIL，而不是悄悄少算。

⚠️ **契约模块的包名尤其要登记。** 契约的存在意义就是"成为唯一合法的跨服务依赖"，
如果 `content` 不在 `SHARED_KERNEL` 里，把 4 个类型搬进契约之后跨域基线**一条都不会少**，
这次抽取在唯一的进度指标上完全隐形。这是本轮实测过的：`content` 登记后
`shared_kernel_sources` 才从 `[auth, common, config, filter]` 变成含 `content`。

### 6.1 ⚠️ 契约落在业务域命名空间里时，`SHARED_KERNEL` 必须写**带点**的条目

§18 列的 8 个契约里，**有 4 个的名字同时是业务域**：
`agent-api` / `automation-api` / `integration-api` / `studio-api`。
它们的包落在该域的命名空间里，于是撞上扫描器原本的一条硬限制：

**`SHARED_KERNEL` 原本只按"第一段"匹配**（`domain_of()` 取 `parts[3]`），
所以 `com.xander.lab.integration.api` 无法被单独表达 —— 只能登记 `integration`，
而那会把整个域一起划成共享内核。

已改成：**条目是包前缀、允许带点、按最长匹配**（`shared_kernel_owner()`），
`shared_kernel_packages()` 也从"第一段"改成回报**实际声明的包**（`content.api` 而不是 `content`）。

| 包 | 最长匹配 | 结果 |
|---|---|---|
| `com.xander.lab.integration.api.UploadResponse` | `integration.api` | 共享内核 → 不计入跨域基线 |
| `com.xander.lab.integration.oss.OssService` | （无） | 业务域 `integration` → 计入 |
| `com.xander.lab.agent.spi.RemoteTool` | `agent.spi` | 共享内核 → 不计入跨域基线 |
| `com.xander.lab.agent.api.AgentConversationController` | （无） | 业务域 `agent` → 计入 |

⚠️ **登记裸的 `integration` 不会"把域误划成共享内核"——真正的后果是契约隐形。**
实测（把 `integration` 加进 `SHARED_KERNEL` 后重跑）：跨域基线**仍是 116 / 85，一条都没少**，
因为扫描器里 `BUSINESS_DOMAINS` 优先，`domain_of` 返回的 `integration` 仍被当作业务域。
真正的两个后果是：

1. **契约隐形**：`domain_of("…integration.api.X")` 返回 `integration`（业务域），
   于是搬进契约的类型**照样被算作域间边**，这次抽取在进度指标上不动一分 —— 正是本门禁存在的理由；
2. **假报警**：`integration → <各域>` 的每条 import 都会变成
   `shared-kernel-reverse-dependency` 违规（实测多出 5 条）。

所以 `shared-kernel-classification` 新增判据二「**窄性**」：
**任何条目都不得等于业务域名、也不得是业务域名的前缀**。裸 `integration` 直接 FAIL。
判据一「覆盖」同时改成按声明包深度比对，报错信息现在给出 `content.api` 这种精确包名。

⚠️ **但"契约落在 `<域>.api`"这条只对 `integration` 成立，对另外三个是错的。**
`agent` / `automation` / `studio` 已经把 `com.xander.lab.<域>.api` 用作自己的**前端 HTTP 层**
（Controller / VO / 请求体），契约再用这个包名会同时造成"一个包两个 jar"和
"该域的 Controller 被算成共享内核、凭空多出几十条 `<域>.api -> <域>` 边并触发反向依赖违规"。
这三个契约因此取下一个空段 `spi`（`agent.spi` / `automation.spi` / `studio.spi`）。
`integration` 没事是因为它按接入类型分包（`file` / `mail` / `mcp` / `oss`），压根没有 `integration/api`。
详见 §3decies.2 与 `adr-contract-boundaries.md` §2.7。

⚠️ 这次改动的安全验证方式是**基线必须逐字节不变**：改完扫描器、**不搬任何类**，
重新生成 `docs/cross-domain-imports.yaml` 与 `docs/migration-scan-report.md`，
`git diff -- docs/` **必须为空**。实测为空（116 / 85 / 28 / 38 全部不变），
说明最长匹配对改动前就存在的每个包返回的都是原值。

---

## 7. 与 §80 的关系

§80 禁止 `classpath*:/mapper/*.xml` 全局吞入。**今天它是对的**：全仓只有一个应用模块，
classpath 上没有别人的 mapper 可吞。§80 的禁令写在"拆分后"的语境里。

本轮**刻意不改这一行配置**，因为改完无法验证 —— 五个 `@SpringBootTest` 都是切片上下文
（显式 `classes = {...}`），没有任何测试会启动 MyBatis-Plus 并解析 `mapper-locations`。

Wave 0B 的处置是**用门禁冻结本地 fallback**：`mybatis-scan-scope` 门禁有三条判据，
见 §8。Wave 1 把 MyBatis 配置搬进 Nacos Service Private DataId 时一并收窄。

Wave 1 现已接通：`infrastructure/nacos/dinqor-core.yaml` 使用
`classpath:/mapper/*.xml`，只加载 core-host 自己的 mapper。默认 `application.yml` 的
`classpath*:` 仅作为不启用 Nacos 的本地 fallback；`nacos-config-profile` 门禁会同时守住
DataId 导入和生产路径收窄。

同时删掉了 `type-aliases-package` 里指向 **不存在包** 的 `com.xander.lab.entity`
（旧式分层遗留的空目录）。剩下三个是真实域包，但唯一一份 mapper XML
（`mapper/BlogPostMapper.xml`）通篇用全限定类名，所以这个属性目前整体未被使用 ——
这是**发现**，本轮不据此删属性。

---

## 8. 怎么验证

```bash
# 全量测试：应用模块 + 各 lib / 契约模块的合计
mvn -o test

# 全部门禁（含 mybatis-scan-scope / xml-authoring-safety / shared-kernel-classification /
# contract-dependency-boundary / dockerfile-module-coverage）
python scripts/migration/ci_gates.py
```

⚠️ **`mvn -o test` 的 "Results: Tests run: N" 是逐模块打印的。**
类搬进 lib 之后，总数会拆成 `dinqorai-backend` + 各 lib + 各契约模块多处，
只看最后那个 `Results:` 会以为"测试变少了"。
**要核对的是各模块之和。**最新一轮（§3octodecies 之后，2026-09-18）：

| 模块 | Tests run |
|---|---|
| `xander-auth-core` | 4 |
| `contracts/content-api` | 67 |
| `contracts/integration-api` | 3 |
| `contracts/core-api` | 18 |
| `contracts/payment-api` | 21 |
| `contracts/agent-api` | 21 |
| `contracts/automation-api` | 31 |
| `contracts/recitation-api` | 35 |
| `contracts/blogagent-api` | 26 |
| `apps/core-host` | 1194 |
| **合计** | **1420**（0 failures / 0 errors） |

第十五刀的增量（1417 → 1420）：

* `apps/core-host` 1191 → **1194**（+3）：`McpOAuthServiceTest` 20 → **23** ——
  端口答不上来时必须 401 且不碰 mapper、非 `Bearer ` 前缀在调端口之前就拒、
  **端口返回的 userId 必须出现在查询条件里**（§3octodecies.6）。
  契约模块与其它模块**一行没改**（零构建接线）。

第十四刀的增量（1407 → 1417）：

* `contracts/core-api` 12 → **18**（+6）：`AccessTokenLookupPortTest`(6) 是新类 ——
  方法个数、没有 `default`、签名（按名字取方法）、包与接口、
  **反向守卫**（断言没有参数叫 `userId`）、签名里没有框架类型。
* `apps/core-host` 1187 → **1191**（+4）：`AuthServiceTest` 29 → **33** ——
  §3septdecies.6 补的四条端口实现测试（活令牌 / 已登出 / refresh / subject 非数字）。
  `AgentStreamAuthHandshakeInterceptorTest` 与 `PortDelegationTransactionTest`
  只换 mock 类型与清单，**数目不变**。

第十三刀的增量（1386 → 1407）：

* `contracts/blogagent-api` 15 → **26**（+11）：`BlogAgentImagePortTest`(7) 是新类 ——
  含那条**反向**守卫（断言端口里没有叫 `userId` 的参数）；
  `RecordShapeTest` 6 → **10**（+4，两个新 record 各一条形状断言 + 一条 `size` 必须在末尾 +
  一条"空用量只在三个分量全非正时成立"）。
* `apps/core-host` 1177 → **1187**（+10）：`ImageGenerationToolTest` 2 → **6**（+4：
  未配置时不调提供方、meta 记的是提供方给的模型与尺寸、空用量照样记账、风格前缀由工具拼）；
  `BlogAgentPortMappingTest` 7 → **13**（+6：逐分量落点、`model` 来自生成结果而不是配置、
  `size` 来自配置、`null` 用量退化成 `NONE`、prompt 原样透传、`isEnabled` 直接转发）。

第十二刀的增量（1362 → 1386）：

* `contracts/blogagent-api` **+15**（新模块）：`BlogAgentPortTest`(9) + `RecordShapeTest`(6)。
  其中 `BlogAgentPortTest` 里有**两条**断言参数名的守卫（§3quinquiesdecies.5）。
* `apps/core-host` 1168 → **1177**（+9）：`BlogAgentPortMappingTest`(7) 是新类 ——
  钉三个换算的逐字段落点、**两处换位**（`publish` 与 `findPublishedPost` 各一条）、
  以及"三参重载必须点名 `ForView`"；`BlogGenerationToolTest` 与 `PublishPostToolTest`
  改 mock 端口后各净增；`PortDelegationTransactionTest` 清单加一个类（数目不变）。

第十一刀的增量（1336 → 1362）：

* `contracts/recitation-api` 16 → **35**（+19）：`RecitationQuizPortTest`(10) 是新类；
  `RecordShapeTest` 6 → **15**（+9）—— 四个新 record 各一条形状断言 + 一条"没有框架注解"。
* `apps/core-host` 1161 → **1168**（+7）：`RecitationQuizPortMappingTest`(4) 是新类 ——
  钉三个换算的逐字段落点，外加**一条"两份投影在共享字段上相等"**（§3quaterdecies.6）；
  `QuizKnowledgeToolTest` 6 → 8（+2，两个 `itemsJson` 退化用例）；
  `GradeAnswerToolTest` 7 → 8（+1，常量与 schema 不许漂移）；
  `KnowledgeQuizServiceTest` 15 → 15（改名，数目不变）；`PortDelegationTransactionTest`
  清单加一个类（数目不变）。

前两轮的增量（1310 → 1336）：

* `contracts/recitation-api` **+16**（新模块）：`RecitationMaterialPortTest`(10) +
  `RecordShapeTest`(6)。
* `contracts/automation-api` 22 → **31**（+9）：§3duodecies 的 `NotificationPortTest`。
* `apps/core-host` 1151 → **1161**（+10）：`RecitationMaterialPortMappingTest`(4) ——
  钉 `toNarrowView` 的 9 个分量与两个纯换算；`PortDelegationTransactionTest`(3) ——
  §3terdecies.9 的委托事务守卫；`RecitationServiceTest` 的归档不变量从工具侧**搬来**(1)；
  `QueryKnowledgeToolTest` / `CreateKnowledgeToolTest` 改 mock 端口后净增 2。

⚠️ `target/surefire-reports/` **不会被自动清理**，里面会留下已经搬走的测试类的报告。
用脚本按目录累加 `Tests run` 会把这部分算进去（曾出现
`apps/core-host` 累加得 1059、实际只跑 1055 的情况，差的 4 正是已迁出的
`UserContextTest` / `JwtUtilTest`）。**以控制台输出为准，不要以报告目录为准。**

⚠️ **另一个坑：`-Dtest=X` 会让整个反应堆失败。** 没有匹配测试的模块会报
`No tests matching pattern ... were executed!` 并把 BUILD 打成 FAILURE。
必须同时加 `-Dsurefire.failIfNoSpecifiedTests=false`。
另外 `-pl <module>` 需要配 `-am`，否则兄弟模块的 SNAPSHOT jar 不在本地仓库里，
报 `Could not find artifact com.xander:xander-model:jar:0.0.1-SNAPSHOT`。

⚠️ **注入验证要单独跑被测模块。** 反应堆在第一个失败模块处**直接中断**，
后面的模块根本不跑。所以在反应堆里跑注入验证，只能证明"没有别的模块先炸"，
不能证明"只有那个测试类红" —— 得用 `-pl contracts/content-api test` 单独跑一次。

⚠️ **`-fae` 救不了"依赖方"**（§3novemdecies.8 实测）。`mvn -fae test` 只续跑
"**不依赖**失败模块"的模块；`apps/core-host` 依赖 `contracts/integration-api`，
所以契约模块一红，`core-host` 照样被整块跳过 —— 契约侧注入**永远量不到 core-host**，
记下的红数是**下界**。修法是把 `-Dtest` 收窄到**依赖方自己的测试类**，
让契约模块**一个测试都匹配不到**（因此永远不会红）。⚠️ 此时契约模块连 `Tests run`
行都不打印 ⇒ **"没有汇总行"必须判成"0 个测试"，不是"编译失败"**。
⚠️ 另：**`sha1sum` ≠ `git hash-object`**（前者对原始字节、后者对 `blob <len>\0` + 内容
且先做 CRLF→LF 归一），还原判据必须写清用哪一个、全程只用同一个。

⚠️ **注入锚点必须对"行尾已归一"的文本匹配**（§3vicies.8 实测）。本仓工作区 CRLF / LF
**混杂**（同一次里 `application.yml` 是 LF、`Img2ThreeModelClient.java` 是 CRLF），
而锚点是以 `\n` 结尾写的 ⇒ 在 CRLF 文件里 `…));\r\n` 不含子串 `…));\n`，
锚点**静默 0 命中**。修法：读时把 `\r\n` 归一成 `\n` 再匹配，写回时按记录的原行尾还原。
⚠️ 顺带一条读数坑：**Git Bash 里 `grep -c $'\r'` 判 CRLF 不可信**（对 LF 文件也报满行数），
要用 Python 数 `b"\r\n"` 的字节数。

⚠️ **注入脚本不要在前台跑**（§3vicies.8）。六条约 4.5 分钟，前台会在默认超时处被 SIGTERM，
**把目标文件留在注入态**（实测残留了一条）。必须后台跑；**任何中断之后第一件事是比 blob**。
这是"脚本跑完了 ≠ 注入生效了、也还原了"的第四次变体 —— 这次是"脚本被杀了"。

`shared-kernel-classification`：`libs/` 与 `contracts/` 实际提供的每个
`com.xander.lab.<pkg>` 包都必须在 `SHARED_KERNEL` 里。见 §6 的说明与注入验证。

`contract-dependency-boundary` 的两条判据：契约源码只 import JDK 与自己的包；
契约 POM 除 test scope 外无依赖。见 §3bis.3。

`dockerfile-module-coverage`：根 POM 的每个 `<module>` 都必须在 Dockerfile 里
既 COPY 了 `pom.xml`、又 COPY 了源码目录。见 §6 表格。

`deployment-unit-coupling` 的三条判据：`bloghtml` 保持孤立；没有新单元加入 Wave 2
的强连通分量；没有新的互相依赖单元对。这是唯一一条看**图形状**而不是**规模**的门禁，
详见 §3ter。三条判据都用注入法验证过（判据 2 需要临时造一个新单元才能触发）。

⚠️ **判据 2 曾经空转过（2026-09-17 修）**：它原本取
`component_of(WAVE2_UNITS[0])`，而 `WAVE2_UNITS[0]` 正是 `dinqor-agent`。
`automation-api` 那一刀把最后一条指向 agent 的实现边消掉之后，agent 只剩出边、
脱离分量，这条判据的**根就变成了单元素集合** —— 而"没人指向它的单元"的分量永远
只含自己，`joined` 恒为空，**判据再也不会红**。

> 教训：**判据的根不能是一个会变的量。** 写"取第一个"的时候隐含假设了
> "第一个一定在分量里"，而这个假设没有被任何东西守住。
> 与 `shared-kernel-classification` 缺反向判据（`util` 是死条目）同源。

修法：对**每个** Wave 2 单元取分量的并集；detail 行改报**实测**分量大小
（原来打印的是 `len(WAVE2_UNITS)` 这个常量，会把"agent 已脱离"这个进度盖掉），
并新增"哪些单元已可单独抽取"的进度提示。实测证据见 ADR §7.10 与 §3undecies.7。

`xml-authoring-safety` 的两条判据：注释里不得出现 `--`；文件必须能被 XML 解析器解析。
第二条是本轮新增的 —— 写 `contracts/content-api/pom.xml` 的 `<description>` 时写了
`List<BlogPublisher>`，裸 `<` 让 Maven 报
`TEXT must be immediately followed by END_TAG and not START_TAG`，和 `--` 一样是
**反应堆在编译任何类之前整体失败**。判据一抓不到它，所以补了判据二。

`mybatis-scan-scope` 的三条判据：

1. mapper XML 必须落在 `apps/<module>/src/main/resources/mapper/`。
   落在 `libs/` 里 = 全局吞入的"文件版"：共享 jar 在**每个**服务的 classpath 上，
   等于每个服务都加载了别的域的 SQL。
2. 每份 mapper XML 必须能被本模块自己的 `mapper-locations` 匹配到。
   匹配不到 = 死文件，运行时报 "Invalid bound statement"，看起来像代码 bug 而不是配置 bug。
3. `classpath*:/mapper/` 不得扩散到新的配置文件（`KNOWN_MYBATIS_GLOBAL_SCAN` 白名单）。

三条判据都做过注入验证（造出违规 → 门禁 FAIL → 逐字节还原 → PASS）。

---

## 9. 已知缺口

| 缺口 | 影响 | 状态 |
|---|---|---|
| `xander-observability-core` 的 exporter / 指标实现未开始 | 基础层已提供安全的 traceId/requestId 值与传播名称；Servlet Filter、Gateway Reactor Context、Actuator/Prometheus 已接入，具体 exporter 仍按 Wave 1/2 观测方案推进 | Wave 1/2 |
| `*-api` 契约缺失 | **2026-09-20 订正：8 个契约模块均已建立并纳入 Maven**（`agent-api`、`automation-api`、`blogagent-api`、`content-api`、`core-api`、`integration-api`、`payment-api`、`recitation-api`）。当前跨部署单元实现依赖 `0` 条、边 `0` 条；剩余实现依赖全部为同单元边。原行中的历史数字仅保留在后续过程记录中，不再作为当前缺口统计 | ✅ 已处理 |
| §21 的 ArchUnit 规则未落地 | 目前是 Python 门禁（`ci_gates.py`）承担同等职责。ArchUnit 需要模块真实存在才有落脚点，且现有规则已能拦住回退 | 观察中 |
| `BlogPublisher` 跨进程会失效 | 见 §3bis.5，Wave 2 前必须决定适配器归属 | **需要决策** |
| 空目录已清理但无法提交 | 六个旧式分层空目录（`controller` `dto` `entity` `mapper` `service` `util`）**从未被 Git 跟踪** —— Git 不跟踪空目录。清理只影响本地工作区，仓库里本来就没有它们 | 已处理 |

### 9.1 `xander-observability-core`：先落框架无关基础层

§20 要求 Gateway 能依赖这项能力，因此本轮没有把 Servlet Filter 搬进共享库，而是先落地
纯 JDK 的 `CorrelationContext` 与 `CorrelationHeaders`：

- 入站 header 缺失或包含换行、控制字符、超长内容时生成新的 UUID，避免日志和响应头注入；
- `X-Trace-Id` / `X-Request-Id` 与 `traceId` / `requestId` 的 MDC 名称统一；
- `apps/core-host` 的 `RequestLoggingFilter` 负责 Servlet/MDC 生命周期，库本身不依赖 Spring、
  Servlet、WebFlux、Redis 或 exporter。

Micrometer、Actuator 和 WebFlux Reactor Context 适配已经留在各宿主模块；库本身仍不引入框架耦合，
具体 exporter 与业务指标按 Wave 1/2 的观测方案继续推进。
