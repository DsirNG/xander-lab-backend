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
| `core-api` | 未开始 | — | 按目标域聚合，`core-host` 相关边 |
| `agent-api` | 未开始 | — | — |
| `automation-api` | 未开始 | — | — |
| `integration-api` | 未开始 | — | 最大的剩余目标域（7 个域指向它，共 17 条） |
| `knowledge-api` | 未开始 | — | — |
| `studio-api` | 未开始 | — | — |
| `payment-api` | 未开始 | — | — |

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
| 4 | `dinqor-content-automation` → `dinqor-platform-integration` | 8 |
| 5 | `dinqor-core-host` → `dinqor-content-automation` | 7 |
| 6 | `dinqor-platform-integration` → `dinqor-content-automation` | 6 |
| 7 | `dinqor-platform-integration` → `dinqor-agent` | 5 |
| 8 | `dinqor-platform-integration` → `dinqor-core-host` | 5 |
| 9 | `dinqor-core-host` → `dinqor-platform-integration` | 5 |
| 10 | `dinqor-agent` → `dinqor-platform-integration` | 4 |
| | **合计** | **85**（10 条有向边） |

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
| 7 个 `*-api` 契约未建 | 剩余跨部署单元实现依赖 **85** 条（原 103）。`integration-api` 是下一个最大目标域（7 个域指向它，17 条） | Wave 0B 剩余 |
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
