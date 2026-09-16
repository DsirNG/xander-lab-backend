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

Wave 0B 已抽出 **3 个 lib**（`xander-model`、`xander-mybatis-support`、`xander-auth-core`）
与 **1 个契约模块**（`contracts/content-api`），`common` 从 12 类降到 6 类，
另有 2 个类从**业务域包** `identity.security` 迁出、4 个类从 `publishing.domain` 迁出。
剩下 3 个 lib 与 7 个契约模块未开始。

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
| `xander-webmvc-support` | 未开始 | 目标 `GlobalExceptionHandler` `ResultStatusResponseBodyAdvice` MVC 认证适配器 `WebMvcConfigurer` |
| `xander-observability-core` | 未开始 | 目标 TraceId/requestId + MDC 约定 + 指标命名约定（不含具体 exporter） |
| `xander-test-support` | 未开始 | — |

### 2.2 业务契约（§18 `contracts/`，§91）

| 契约模块 | 状态 | 内容 | 跨部署单元实现依赖的收益 |
|---|---|---|---|
| `content-api` | **已完成** | `BlogPublisher`（发布 SPI）`PublishCommand` `PublishResult` `PublishingLimits` | `integration → publishing` 22 → 5；总计 −18 条跨单元 import |
| `integration-api` | **已完成** | `OssPort`（对象存储端口）`UploadResponse` | `knowledgebase/publishing/recitation → integration` 各 2 → **0（边归零）**；`agent → integration` 4 → 2；总计 **−8** |
| `core-api` | **已完成** | `ModelGateway`（运行时模型路由 SPI）`ModelGateway.ModelEndpoint` `FeatureModelNotConfiguredException`；**`UserLookupPort`（用户展示名端口）** | `agent/blogagent/recitation/studio → admin` 四条边**全部归零**（10 → 0）；`publishing/components → identity` **两条边归零**（4 → 0）；跨部署单元累计 **−8**；总计 **−14** |
| `agent-api` | 未开始 | — | — |
| `automation-api` | 未开始 | — | — |
| `knowledge-api` | 未开始 | — | — |
| `studio-api` | 未开始 | — | — |
| `payment-api` | **已完成**（第一步／共两步） | `BillingBiz`（`points_ledger.biz_type` 词表） | `agent 6→3`、`integration 4→2`、`automation 2→1`、`blogagent 2→1`、`studio 2→1`；总计 **−8**（跨单元 −7） |

`integration` 域**不是一次性搬完的**：它被 7 个域引用的 7 个类型里，只有 2 个是"能直接搬"的。
剩下 5 个（CSDN / 掘金 的凭证与同步服务、`MailService`）都是**具体实现而非接口**，
搬它们要先做端口抽取；其中 `MailService` 还 import 了
`automation.domain.EmailReminderMailTemplates`（一个业务域类型），
在解开那处依赖之前**根本搬不动**。详见 §3quater.5。

⚠️ **契约模块不是"共享库"**，但它的包名必须登记在 `SHARED_KERNEL` 里，
理由见 §6 与 §10.3：否则把类型搬进契约之后，跨域基线**一点都不会动**，
这次抽取在唯一的进度指标上完全隐形。

`common` / `config` / `filter` 三个包**当前剩余 11 个类**，全部还在应用模块里：

```text
common/HealthController.java              ← 运维端点，见 §4
common/RedisHealthMonitor.java            ← 运维型 Redis，见 §4
common/RedisHealthProbe.java              ← 运维型 Redis，见 §4
common/security/ConnectionRegistry.java   ← 连接注册表（§49）
common/security/SecurityPlaneConfiguration.java
common/security/SecurityStateCache.java
config/GlobalExceptionHandler.java        ← 目标是 xander-webmvc-support
config/ResultStatusResponseBodyAdvice.java← 目标是 xander-webmvc-support
config/ShedLockConfiguration.java
config/WebConfig.java                     ← 目标是 xander-webmvc-support
filter/RequestLoggingFilter.java          ← 目标是 xander-observability-core
```

---

## 3. 三个已抽出的 lib

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

### 4.3 `HealthController` / `RedisHealthMonitor` / `RedisHealthProbe` 不进任何 lib

§19 明确："运维型 Redis 健康检查不进通用 libs"。这三个类**不能**为了"让 common 清空"
而塞进 `xander-model` 或 `xander-mybatis-support` —— 那正是 §19 要消灭的超级 jar 的做法。
它们属于 application/operations，Wave 1 跟随宿主。

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
它们的包只能落在 `com.xander.lab.<域>.api`，于是撞上扫描器原本的一条硬限制：

**`SHARED_KERNEL` 原本只按"第一段"匹配**（`domain_of()` 取 `parts[3]`），
所以 `com.xander.lab.integration.api` 无法被单独表达 —— 只能登记 `integration`，
而那会把整个域一起划成共享内核。

已改成：**条目是包前缀、允许带点、按最长匹配**（`shared_kernel_owner()`），
`shared_kernel_packages()` 也从"第一段"改成回报**实际声明的包**（`content.api` 而不是 `content`）。

| 包 | 最长匹配 | 结果 |
|---|---|---|
| `com.xander.lab.integration.api.UploadResponse` | `integration.api` | 共享内核 → 不计入跨域基线 |
| `com.xander.lab.integration.oss.OssService` | （无） | 业务域 `integration` → 计入 |

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

Wave 0B 的处置是**用门禁冻结现状**：`mybatis-scan-scope` 门禁有三条判据，
见 §8。Wave 1 把 MyBatis 配置搬进 Nacos Service Private DataId 时一并收窄。

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
**要核对的是各模块之和**（本轮 `apps/core-host` 1055 + `xander-auth-core` 4 +
`content-api` 4 = 1063；其中 content-api 的 4 条是**本轮新增**的 `PublishCommandTest`，
不是搬过来的，所以总数比上一轮多 4 是预期的）。

⚠️ `target/surefire-reports/` **不会被自动清理**，里面会留下已经搬走的测试类的报告。
用脚本按目录累加 `Tests run` 会把这部分算进去（本轮就出现了
`apps/core-host` 累加得 1059、实际只跑 1055 的情况，差的 4 正是已迁出的
`UserContextTest` / `JwtUtilTest`）。**以控制台输出为准，不要以报告目录为准。**

`shared-kernel-classification`：`libs/` 与 `contracts/` 实际提供的每个
`com.xander.lab.<pkg>` 包都必须在 `SHARED_KERNEL` 里。见 §6 的说明与注入验证。

`contract-dependency-boundary` 的两条判据：契约源码只 import JDK 与自己的包；
契约 POM 除 test scope 外无依赖。见 §3bis.3。

`dockerfile-module-coverage`：根 POM 的每个 `<module>` 都必须在 Dockerfile 里
既 COPY 了 `pom.xml`、又 COPY 了源码目录。见 §6 表格。

`deployment-unit-coupling` 的三条判据：`bloghtml` 保持孤立；没有新单元加入 Wave 2
的强连通分量；没有新的互相依赖单元对。这是唯一一条看**图形状**而不是**规模**的门禁，
详见 §3ter。三条判据都用注入法验证过（判据 2 需要临时造第六个单元才能触发）。

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
| 3 个 lib 未抽 | §91 的 Wave 0B 只完成 3/6（webmvc-support / observability-core / test-support） | Wave 0B 剩余 |
| ⚠️ `xander-observability-core` **在本仓库没有内容可抽** | 见 §9.1 —— §20 描述的 TraceId/MDC/指标约定整体不存在，而 Gateway 的依赖清单里有它 | **需要决策** |
| 4 个 `*-api` 契约未建 | 剩余跨部署单元实现依赖 **62** 条（原 103 → 85 → 77 → 71 → 64 → 62）。按被依赖目标排序（总数 / 跨单元）：`publishing` 24/17、`identity` 11/3、`automation` 10/10、`blogagent` 10/5、`billing` 10/7（**卡在 Jackson 决策上**，见 §3quinquies.5）、`integration` 9/9、`recitation` 6/6、`agent` 5/5。**最大剩余目标是 `publishing`**，其中 `agent → publishing` 9 条是全仓最大的剩余跨单元边（见 §3septies.7） | Wave 0B 剩余 |
| §21 的 ArchUnit 规则未落地 | 目前是 Python 门禁（`ci_gates.py`）承担同等职责。ArchUnit 需要模块真实存在才有落脚点，且现有规则已能拦住回退 | 观察中 |
| `BlogPublisher` 跨进程会失效 | 见 §3bis.5，Wave 2 前必须决定适配器归属 | **需要决策** |
| 空目录已清理但无法提交 | 六个旧式分层空目录（`controller` `dto` `entity` `mapper` `service` `util`）**从未被 Git 跟踪** —— Git 不跟踪空目录。清理只影响本地工作区，仓库里本来就没有它们 | 已处理 |

### 9.1 ⚠️ `xander-observability-core`：§20 描述的东西在这份代码里不存在

按 §20 去找它的落点，结果是：

- `grep -rn "MDC\.\|TraceId\|traceId"` 全仓**零命中**（唯一叫 `requestId` 的是 MCP OAuth
  的授权请求 id，与链路追踪无关）；
- 无 micrometer、无 actuator、无 `logback-spring.xml`，`application.yml` 里没有任何
  `logging.pattern` / `%X{`；
- `filter/` 包里只有 1 个类 `RequestLoggingFilter`，而它
  **`extends OncePerRequestFilter` + `import jakarta.servlet.*`** —— 是 Servlet 组件。

也就是说 §20 说的"TraceId/requestId 生成与传播 + MDC 约定 + 指标命名约定"**整体不存在**；
唯一看起来相关的文件还**不能**放进这个库 —— §20 要求 Gateway 可依赖它，而 Gateway 是
WebFlux，把 Servlet 组件塞进去与"WebFlux 应用依赖 spring-boot-starter-web 启动失败"
是同一类事故。

这与 §4.2（"公共 TypeHandler 不存在"）同类，但更严重：**§20 的 Gateway 依赖清单里，
三个库有一个目前是空的。** 处置方向（本轮未定）：要么在 Wave 1 引入 Actuator/Micrometer 时
**新建**这个库，要么承认它是 §20 的一处过度设计并修订 §20。
