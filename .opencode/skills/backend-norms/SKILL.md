---
name: backend-norms
description: DinQorAI Java 后端（xander-lab-backend）编码规范。Use when writing or editing Java code, SQL migrations, Spring Boot configs, or backend tests in this project. Covers module boundaries, Result/HTTP contract, SQL comments, transactions, idempotency, credentials, tests and verification commands.
---

# Java 后端规范（xander-lab-backend）

## 模块边界（最重要，违反会成环）

业务模块按能力组织：`api` / `application` / `domain` / `infrastructure` /（可选）`security`。

- 禁止往全局 `controller` / `service` / `entity` / `mapper` 包塞业务类；已迁移模块：`identity`、`agent`、`blogagent`、`publishing`、`automation`、`integration`、`studio`、`components`、`bloghtml`、`billing`、`admin`。
- 跨模块只能调**应用接口或文档化共享契约**，禁止依赖他人 mapper。
- 环形依赖是硬伤：新增 `A→B` 前先查是否已有 `B→A`（例如 admin→identity 已存在，identity 就不能依赖 admin 的表/mapper，只能由 admin 调用 identity 的应用服务）。
- 每个业务模块根目录要有 `package-info.java` 说明归属与允许依赖；架构性变更必须同步更新 `docs/ARCHITECTURE.md`。
- 每个公开业务类/接口要有一句职责式 JavaDoc；并发控制、token 处理、幂等、重试、兼容性处理前要加注释说明"为什么"。

**物理布局（V9 §18 起）**：应用源码在 `apps/core-host/src/`，抽出的共享库在
`libs/<name>/src/`，跨服务的契约模块在 `contracts/<name>/src/`，
根 `pom.xml` 是聚合器 + 父 POM（`packaging=pom`，`artifactId=xander-lab-parent`）。
`spring-boot-maven-plugin` 只声明在 `apps/core-host/pom.xml`，**不要**挪到父 POM
（子模块会继承 repackage 目标，而它需要 main class，库模块会直接构建失败）。

**新增一个模块要改四处，缺一处就是"本地好的、只有产物坏"**：
根 POM 的 `<modules>`、应用 POM 的依赖、`scan.py` 的 `SHARED_KERNEL`（包名）、
**`Dockerfile` 两行**（先 `COPY <module>/pom.xml` 供 `dependency:go-offline` 用，
再 `COPY <顶层目录> ./<顶层目录>` 供构建用 —— 漏了第一行报
`Child module ... does not exist`）。门禁 `dockerfile-module-coverage` 会拦住 Dockerfile 那两行。

跨这些根的路径引用要一起更新：`scripts/migration/` 下的门禁允许清单、`docs/*.yaml` 里
curated 的 `file:`/`source:`/`scan_ref`（`scan_ref` 由扫描器自动刷新，其余要手改）。
`scripts/migration/scan.py` 用 `java_roots()` 同时扫应用根、所有 `libs/*/src/main/java`
与所有 `contracts/*/src/main/java`，
**不要**把它写死回单根 —— 否则一旦有类被移进 libs 或 contracts，它会从所有机器清单里静默消失
（包括遗留 Redis 键清单，那正是"常量被搬走就没人管了"的成因）。

⚠️ **把类移进 libs 时，新库的包名必须加进 `scan.py` 的 `SHARED_KERNEL`。**
扫描器按包名判定归属：包名不在 `SHARED_KERNEL` 也不是业务域时，指向它的 import 会被整条
丢掉，跨域基线于是**下降** —— 数字变好而耦合没变，是最坏的一种"通过"。
门禁 `shared-kernel-classification` 会比对 `libs/` 实际提供的包与 `SHARED_KERNEL`，漏加即 FAIL。

⚠️ **包名要跟着归属走**：`common` / `config` 这类通用名可以跟着类一起搬（保 rename 检测），
但**业务域名不能**。`UserContext` 从 `identity.security` 搬到 `xander-auth-core` 时改成了
`auth.core` —— 共享库的类挂在业务域包下，一是架构上撒谎，二是扫描器仍按业务域算跨域依赖，
抽取的价值在清单上完全看不到。

⚠️ **库自己拥有自己的测试**：类搬进 libs，它的测试也跟着进 `libs/<name>/src/test/java`，
并在 lib 的 POM 里加 test-scope 依赖。测试留在应用模块等于应用拥有它不再拥有的类。
核对测试总数时要**各模块相加** —— `mvn -o test` 的 `Results:` 是逐模块打印的，
只看最后一个会误判成"测试变少了"。也别用 `target/surefire-reports/` 累加：它不清理，
会留下已迁出测试类的旧报告。

## 契约模块（`contracts/<domain>-api`）

**什么时候把类型放进 `contracts/`**：它跨**部署单元**。判断方法不是"看起来像契约"，而是
"它的实现方和调用方拆分后会在两个进程里"。先例：`BlogPublisher` 由内容域
（`dinqor-content-automation`）定义并消费，实现却在 integration 域
（`dinqor-platform-integration`）—— 留在 `publishing.domain` 就会变成"integration 在实现
另一个服务的接口"，依赖方向反了。

**硬约束（门禁 `contract-dependency-boundary` 检查）**：

- **零依赖**。契约源码只允许 import JDK 与自己的包；POM 里除 test scope 外不得有依赖。
  理由：契约要被**每个**服务依赖，包括 WebFlux Gateway，而 §19 说它一旦被拖进
  `spring-boot-starter-web` 就启动失败。长胖的契约就是超级 common.jar 换了个名字。
- **不放实现**：没有 `@Service`、没有 Repository、没有 mapper。
- **改字段 = 契约变更**：字段增删改影响两个服务的序列化，走 §11 的 Contract Freeze 流程。

**包名要登记进 `SHARED_KERNEL`**（同 libs，见上）。契约尤其要注意：契约的存在意义就是
"成为唯一合法的跨服务依赖"，包名不登记的话，把类型搬进契约之后跨域基线**一条都不会少**，
这次抽取在唯一的进度指标上完全隐形。

**不要顺手把 api 层的 DTO/VO 一起搬。** 它们在 `<domain>.api` 下，扫描器**本来就**算作
契约面（`api_statements`）而不是待还的债，搬它们不改变任何被度量的数字，只会把 diff 撑大；
而且它们是**前端**的 HTTP 契约，与服务间契约受众不同。`publishing.api.BlogPostVO` 还同时是
`BlogPostMapper` 的投影目标（`@JsonIgnore tagsRaw` 承接 `GROUP_CONCAT`），搬它之前必须先把
上线形状与持久化投影分开。

**搬 vs 抽端口：先看那个类型是不是接口。** 这是两种不同的活，别混：

- 类型**本来就是接口**（或纯 DTO）→ 搬家，连同它的 DTO 一起换包。先例 `BlogPublisher`。
- 类型是**具体实现**（`@Service`、拖 SDK / mapper / `@Value`）→ 不能搬，只能**抽端口**：
  在契约里新建一个接口，让原实现 `implements` 它，调用方改依赖接口。
  先例 `OssPort` / `OssService`。

抽端口时的两条硬规矩：

1. **端口签名不得含框架类型。** 典型的坑是 Spring 的 `MultipartFile` —— 它是 spring-web 类型，
   放进契约会让 spring-web 出现在每个依赖契约的服务（含 WebFlux Gateway）的 classpath 上，
   而且**跨进程时根本没有 MultipartFile 可传**。改成 `byte[]` + 元信息，调用方在调用点转换。
   原方法可作为**本域内的便捷重载**留在实现类上（先例：`OssService.upload(MultipartFile, String)`
   留给同域的 `FileController`）。
2. **换签名会带来真实行为改动，必须留痕。** 从"把 `MultipartFile` 的输入流交给 SDK"改成
   "先 `getBytes()` 读进内存"是**流式 → 全量**的变化。可接受的前提是调用方上传前已经卡了大小上限；
   写进提交信息与 `docs/migration/lib-extraction.md`，别当成纯重构。

**`SHARED_KERNEL` 里契约的包名可能要带点。** §18 的 8 个契约里有 4 个
（`agent-api` / `automation-api` / `integration-api` / `studio-api`）的名字同时是业务域，
包只能落在 `com.xander.lab.<域>.api`。条目按**最长前缀**匹配，所以登记 `integration.api`
（**不要**登记裸 `integration`：那会让契约隐形，因为业务域在扫描器里优先）。

**搬类型进契约时，调用方要一起改。** `OssService` 那次一共动了 4 个域 + 4 个测试文件。
改完必须 `grep -rn "<旧包名>" --include=*.java apps/ contracts/` 复核，
**不要信 Edit/替换脚本的成功返回** —— 本轮就因为漏改一个赋值左侧（`ossService = mock(...)`
只改了右边）而编译失败一次。

**⚠️ 从包里搬走类型时，`import` 要按"**每一个**被搬的类型"补，不能只按你搜的那个名字补。**
被搬的类型原来在 `admin.application`，同包引用**没有 import 语句**。搬走之后，
`ModelGatewayService` 里裸写的 `FeatureModelNotConfiguredException` 就找不到了
（编译报 `找不到符号: 类 FeatureModelNotConfiguredException`），
而按 `\bModelGateway\b` 搜索的脚本**一条都命中不了它** —— 因为那个名字里没有 "ModelGateway"。
规矩：搬完先把**每个**被搬类型的简单名各搜一遍（`grep -rn "\b<SimpleName>\b"`），
再逐个补 import；或者干脆先编译一次，让 javac 把所有缺符号的位置一次列出来。
另外，只出现在 Javadoc `{@code}` 或字符串里的名字**不需要** import，
自动补 import 的脚本会给这类文件塞进一个**未使用**的 import（本轮 `GlobalExceptionHandler`
和 `FeatureModelConfig` 各中一次），补完要回头检查。

**⚠️ 契约包名前缀不是业务域名时（`core.api`），指标完全靠不住，只有门禁能发现问题。**
`integration.api` 那次已经知道登记裸域名会让契约隐形；`core.api` 这次做了对照实验，
结论更硬：**把 `core.api` 从 `SHARED_KERNEL` 里删掉，`implementation_statements` /
`cross_deployment_unit_statements` / `api_statements` 一个都不动**
（98 / 71 / 38 完全一致）。因为 `core` 既不在 `BUSINESS_DOMAINS` 里，也不在
`SHARED_KERNEL` 里，那些 import 只是被**静默丢掉**，而数字与"正确"状态一模一样。
唯一信号是 `unclassified_sources` 多出一个 `core`，以及
`shared-kernel-classification` 门禁 FAIL。**别因为数字好看就跳过门禁。**

**⚠️ 契约的依赖边界（JDK-only）遇到 Jackson 时不要顺手放宽。** `agent-api` 与
`payment-api` 都卡在同一处：`AgentTool.parametersSchema()` 返回 `JsonNode`、
`BillingService.chargeTokens(...)` 收 `ModelUsage`（住在 `libs/xander-model`，
带 Jackson）。放宽 `CONTRACT_ALLOWED_IMPORT_PREFIXES` 是**设计决策**，
不是重构的顺带产物 —— 见 `docs/migration/lib-extraction.md` §3quinquies.5，
先把问题记下来问清楚，别在实现里悄悄改掉这条不变量。

## API 与异常契约

- 所有错误响应：`{ "code", "message", "data" }`；code 必须与真实 HTTP 语义一致（400/401/403/404/5xx 不被 HTTP 200 包装）。
- 登录缺失/过期/无效：真实 HTTP 401 + `{ "code": 401, "message": "未登录或登录已过期", "data": null }`。
- 服务层抛 `ResponseStatusException`（明确 4xx）或 `IllegalArgumentException`；控制器不写业务逻辑；`GlobalExceptionHandler` 统一转换。
- 禁止用 `catch (Exception)` 返回空对象/通用成功；有意的降级要记录日志 + 归一化原因。
- 定时/后台长任务返回任务/运行 ID，不把远端完成前的时间挂在请求上。
- 新增私有接口：必须在 `AuthInterceptor` 补齐鉴权并同步测试。

## SQL 与迁移

- 每张表、每一列都要有**中文 `COMMENT`**，解释枚举/外键/单位/归属/生命周期，不重复字段名。
- 新建表：写 baseline `db/*_schema.sql`（`CREATE TABLE IF NOT EXISTS`）；
- 存量表任何改动：`db/*_migration.sql`（线上需手工执行），并保持 baseline 同步；
- 禁止用建表语句悄悄更新存量表、禁止启动时代码自动改表。
- 归属过滤、任务扫描、幂等键、外键、稳定分页都要加索引。
- UTC 存调度时刻，展示边界才转用户时区。

## 事务 / 并发 / 幂等 / 调度

- 事务声明在应用服务用例边界；纯 DB 读用 `readOnly = true`。
- AI/CSDN/OSS/SMTP/MCP 等网络调用必须在事务之外；先落意图、再调远端、结果单独事务持久化。
- 可重试变更要有幂等键（DB 唯一约束或原子 Redis）；状态机用条件更新（含期望前态），0 行更新 = 竞争或已完成。
- 状态/阶段定义成常量或枚举并文档化转移，禁止散落字符串字面量。
- 调度器：轮询原子认领、有界批量、多实例不重复；单条失败不中断整批。

## 凭据与安全

- 平台凭据按用户×平台存储；AES-GCM 静态加密，密钥只走环境变量（先例：`MODEL_PROVIDER_ENCRYPTION_KEY`、`WECHAT_CREDENTIAL_ENCRYPTION_KEY`）。
- 实体只存密文；任何视图/接口不回传密钥明文，只回 `configured` 布尔标记。
- 绝不记录密码、验证码、JWT、cookie、API key、鉴权头、第三方请求头；日志用结构化参数化（不用 `System.out`/`printStackTrace`）。
- 登录、注册、验证码、发布、昂贵 AI 生成等必须限流。

## 配置

- 新配置组用类型化 `@ConfigurationProperties` 并校验；带凭据/调度器/外部副作用的功能默认关闭直到配置与迁移就绪。
- 生产值只能来自环境变量或本地配置（`application-local.yml` 自动加载、不得提交）。
- 只在 JDK/现有栈解决不了时加依赖。
- **无法验证的配置改动不要做**。改配置前先确认有没有测试真的会加载它 —— 例如
  `mybatis-plus.mapper-locations` 在本仓库**没有任何测试会解析**（五个 `@SpringBootTest`
  都是切片上下文，显式 `classes = {...}` 并 `@MockBean` 掉 mapper），所以改它属于
  「改完只会被生产环境发现」。这类情况一律**用门禁把现状钉住**，等有验证手段的那一波再改
  （参考 `ci_gates.py` 里 `KNOWN_MYBATIS_GLOBAL_SCAN` / `KNOWN_SHARED_KERNEL_VIOLATIONS`
  的「只禁新增、修好即删条目」手法）。
- MyBatis 配置（V9 §80）：mapper XML 只能放在 `apps/<module>/src/main/resources/mapper/`，
  **不要**放进 `libs/` —— 共享 jar 在每个服务的 classpath 上，等于每个服务都加载了别的域的
  SQL。新增 mapper XML 后必须确认它被本模块自己的 `mapper-locations` 匹配到，
  否则是死文件，运行时报 `Invalid bound statement`，看起来像代码 bug 而不是配置 bug。

## POM / XML

- **XML 注释里不能出现 `--`**。Maven 会在编译任何类之前整体失败：
  `Non-parseable POM pom.xml: in comment after two dashes (--) next character must be > not ...`。
  写注释时把破折号对改成逗号/冒号/「到」。
- **POM 的 `<description>` / `<name>` 是文本节点，裸 `<` 和裸 `&` 同样让文件不可解析**：
  写 `List<BlogPublisher>` 会报 `TEXT must be immediately followed by END_TAG and not START_TAG`。
  要写就用 `&lt;`，或直接改成"a plain List"这种不含尖括号的说法。
  两条都由门禁 `xml-authoring-safety` 拦住（判据一查注释里的 `--` 并给出精确位置，
  判据二对每个 `*.xml` 跑一次 XML 解析兜底）。
- 带 `<modules>` 的 POM **必须** `packaging=pom`（`Aggregator projects require 'pom'`），
  所以根 POM 不能兼作应用模块。
- `spring-boot-maven-plugin` **只能**声明在应用模块（`apps/core-host/pom.xml`）。
  放父 POM 会让库模块继承 `repackage`，库没有 main class，报 `Unable to find main class`。
- 库与应用共用的依赖版本走父 POM 的 `<properties>`（例如 `${mybatis.plus.version}`），
  **不要**在子 POM 内联写死：同一依赖两个版本会在 classpath 上出现两份类，报错现场离原因很远。

## 测试

- 模块在 `apps/core-host/src/test/java` 有镜像包；应用服务/安全规则/调度器/适配器必须有行为测试。
- 控制器测试覆盖校验、鉴权、归属、真实 HTTP 状态。
- 单元测试不依赖网络/生产凭据/真邮箱/真发布账号；确定性、相互独立、清理 ThreadLocal/静态状态。
- 修 bug 必须带回归测试；禁止为过构建删弱测试。

## 验证门禁（改动后必须执行并贴结果，不要直接宣布完成）

```bash
# 窄测试（相关模块）
mvn -q "-Dtest=写入你的测试类" test
# 全量
mvn -q test
# 至少确认编译
mvn -q -DskipTests compile
```

⚠️ **在 Git Bash 里 `mvn` 直接跑不起来**（MSYS 路径没被转成 Windows 路径，
报 `ClassNotFoundException: plexus.classworlds.launcher.Launcher`）。用完整 java 命令行：

```bash
"C:/jdk-17.0.2/bin/java.exe" \
  -classpath "D:/soft/apache-maven-3.9.6/boot/plexus-classworlds-2.7.0.jar" \
  "-Dclassworlds.conf=D:/soft/apache-maven-3.9.6/bin/m2.conf" \
  "-Dmaven.home=D:/soft/apache-maven-3.9.6" \
  "-Dmaven.multiModuleProjectDirectory=D:/blog/xander-lab-backend" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o test
```

迁移相关的机器门禁（清单一致性 / 依赖方向 / 配置归属等）另有一套，改动后也要跑：

```bash
python scripts/migration/ci_gates.py
```

**改了任何 POM 或新增模块时，先跑门禁再跑 Maven。** `xml-authoring-safety` 与
`dockerfile-module-coverage` 覆盖的失败都是"反应堆在编译任何类之前整体失败"，
跑一次门禁几秒钟就能拿到精确到行号的报错，而 `mvn` 要先走完一轮启动。

### 门禁本身也要验证：注入法

新增或修改一条门禁，**必须**证明它真的会拦。方法是注入：
造出违规 → 跑门禁观察 FAIL（且失败信息指向你造的那处）→ 还原 → 观察 PASS。
只写不验的门禁和没有门禁一样 —— 它可能恒真，也可能判据写反了还一路 PASS。
若某条判据在**当前代码状态下无法触发**（例如"不得有新单元加入"需要第六个单元才成立），
就临时造出那个前提（把某个域临时指到新单元），验完再还原 —— 不要因为"现在触发不了"
就跳过验证，也不要删掉这条前瞻护栏。

⚠️ 还原后**不要**用"原始字节 sha256 相等"证明还原成功：本仓库 `core.autocrlf=true`，
`git checkout -- <file>` 会把 LF 重写成 CRLF，字节数会变（实测 `scan.py` 差 1347 字节
= 正好 1347 行，看起来像丢了内容，其实 `git diff` 是空的）。正确判据是 **blob 级相等**：

```bash
P=path/to/File.java
[ "$(git hash-object "$P")" = "$(git rev-parse HEAD:$P)" ] && echo RESTORED
```

### ⚠️ 不要用 `git rm` 删文件（会连带删掉整个目录树）

2026-09-16 实测：`git rm -q apps/core-host/src/main/java/com/xander/lab/integration/oss/UploadResponse.java`
（**单个文件**）之后，`apps/core-host/src/main/java/com/xander/lab/integration/` 下
**84 个文件全部从工作区消失**，而 `git status` 只报 ` D`。
文件都在 HEAD 里，`git reset -q HEAD -- <dir> && git checkout HEAD -- <dir>` 可完整恢复。

**规矩：删被跟踪的文件用普通删除，让 `git add -u` 去记录。**

```bash
python -c "import pathlib; pathlib.Path('path/to/File.java').unlink()"
# 然后 git add -u，git status 会显示 ' D'，和 git rm 的效果一样，但不会连带删目录
```

**任何批量删除/移动之后，立刻数一遍目录里的文件数**，别只看 `git status`：

```bash
find <dir> -name "*.java" | wc -l     # 和删除前对比
```

本环境对"删目录"这类操作明显不可靠（另有嵌套 ref 静默丢失、见 `notes/repo-workflow.md`），
所以**不要**用会自己决定删目录边界的工具。

本地环境：JDK 17 = `C:\jdk-17.0.2`，Redis localhost:6379，MySQL 101.33.246.103:3306（xander_lab）。提交标题用中文并以「【dxd】」为前缀，正文含 `desc:`；只 stage 任务相关文件，不提交凭据与本地配置。