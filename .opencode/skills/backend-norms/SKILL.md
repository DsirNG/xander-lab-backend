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

### ⚠️ 注释只写"功能 / 业务"，不写"重构过程"

**规范**（2026-09-20 用户明确要求）：注释与 JavaDoc 只回答两件事 ——
**这段代码做什么**、**为什么必须这样做**（业务规则、并发、幂等、安全、兼容性、非直觉的约束）。
一句话职责式 JavaDoc + 必要处的"为什么"内联注释。就这些。

**禁止写进代码注释的**（这些属于 `docs/migration/` 的文档，不属于代码）：

| 不许出现 | 例子 |
|---|---|
| 重构刀次 / 阶段 | `第十四刀`、`Wave 0B`、`本刀` |
| ADR / 设计文档编号 | `ADR-2`、`ADR §26`、`V9 §20`（POM 里指"内容出处"可以，正文里讲道理不行） |
| 待办编号 | `待办 59`、`见待办 63` |
| 注入验证 / 测试过程 | `注入 A5a 实测`、`修复前报 PASS` |
| 变更史叙述 | `这个类原来注入 …`、`本条目以前写着 …`、`已订正` |
| 迁移指令 | `不要在别处复述这条理由`、`接 Micrometer 那天要…` |
| 逃生口讨论 | `另一条路是拆成两个 advice，那会…` |

**判据**：如果这段注释在"这个文件从第一天就是这么写的"世界里会显得莫名其妙，
那它就不是注释，是变更日志。**变更日志进 `docs/migration/`，代码里只留结论。**

⚠️ 迁移指令（"以后要怎样"）**尤其危险**：它写在注释里没人会去执行，但读起来像已经决定了。
这类内容一律进 ADR 待办表。

⚠️ 反面样本：`AuthInterceptor` / `xander-webmvc-support` 的 JavaDoc 第一版写了 40 行
"为什么它依赖端口而不是 AuthService、这是第几刀、行为怎么变的" —— 已压缩成 6 行。
`package-info.java` 尤其容易被写成设计文档。它可以有**两节**：**成员清单**（这个包放什么）
+ **硬约束**（改这个包的人必须遵守什么，例如"只许 JDK""不许 `default` 方法""分量顺序是位置契约"）。
**不许有**的：`原来 import` 表格、刀次记录、已断环的追认、"有意不在这次改动里的"清单、文档路径。

#### 批量清洗注释时的工具坑（2026-09-20 清 85 个文件时踩到）

**① 扫描器必须按块注释状态跟踪。** 只判"行首是不是 `*` / `//` / `<!--`"会漏掉
**多行 XML 注释的续行**和**块注释里不以 `*` 开头的行**。实测 `libs/` + `contracts/`：
行首法 **130 处 / 69 文件**，状态跟踪法 **169 处 / 79 文件** —— **少算 30%**。
量任何"注释债"之前先确认扫描器跟状态。

**② `Path.read_text()` 默认做换行符归一化。** 不带 `newline=""` 时 `\r\n` 会变成 `\n`，
再 `write_text(..., newline="")` 写回，**整个文件从 CRLF 变 LF**。
本次 85 个文件全被转换，之所以没在 diff 里露馅只是因为 `core.autocrlf=true`
（含空白 772 行 vs 忽略空白 764 行）。⇒ 批量改文件前先 `git config core.autocrlf`；
要逐字节保真就用 `read_bytes()` / `write_bytes()`。

**③ 替换脚本必须报告「零命中规则」。** 规则集里更宽的那条会先吃掉目标文本、留下残句
（本次出现 2 处：`* 详见 </p>`、`* 这份权衡记在 </p>`）。**零命中清单是发现它们的主要手段** ——
只报"改了多少行"看不见残句。多行块替换要按"最长匹配优先"排规则顺序。

**④ 改注释也要跑完整验证。** 注释里有 `{@code}` / `<!-- -->` / javadoc 结构，写坏照样炸构建。
本次 85 个文件（含 1 个 `pom.xml`）重跑了 compile + 全量 test + 26 门禁 + 扫描器指标。

**⑤ 清洗范围要留白。** 本次只清 `libs/` + `contracts/`，`apps/` 单独一刀 ——
85 个文件的注释重写混进功能提交里，diff 就没法读了。

**⑥ ⭐ 注释债有两个面：注释 + 字符串字面量。第二把扫描器是必须的**（2026-09-20 清 `apps/` 时发现）。
日志文案、`@DisplayName`、JUnit `.as(...)` 描述里藏着**和注释一模一样的过程叙述**
（`"安全平面 Bootstrap 完成（V11 §27.1）："`），而**注释扫描器结构上就看不见它们**。
`apps/` 实测：注释 373 处，字符串另加 **30 处 / 14 文件** —— 前三刀全漏。
⇒ 扫描器成对使用：块注释状态扫描 + 字符串扫描
（`re.compile(r'"((?:[^"\\]|\\.)*)"')`，**跳过注释行**，否则会把注释里的引号当字面量）。

⚠️ **测试断言里的文档编号比注释里的更该改。** `SecurityStateBootstrapTest` 原本写
`.hasMessageContaining("§27.1")` —— 把**文档编号本身**钉进了测试，编号一改测试就失去意义。
改成 `.hasMessageContaining("不得进入负载均衡")`，钉**行为主张**。这是**加强**，不是清理。

**⑦ ⭐ 纯注释改动也会打红门禁。** 删掉一行 javadoc 就可能让 `@Scheduled` 行号移位、
使 `docs/scheduled-inventory.yaml` 过期（详见下面 `@Scheduled` 那节）。
⇒ **"只改了注释"不是"不用跑门禁"的理由**；顺序永远是
「改源码 → `python scripts/migration/scan.py` → `python scripts/ci_gates.py`」。

**⑧ 逐行改写脚本的形状。** `rule(path, ln, old, new)`（`new=None` = 整行删），
**片段必须在该行恰好命中一次**，0 次或 >1 次都记错误并跳过 —— 这一条在落盘前抓到了
2 条转录错的规则。匹配要**引号无关**（源码用弯引号 `“ ”`，规则里敲的是 ASCII `"`）：
把 `["\u201c\u201d]` 归成一类，且替换时**保留实际匹配到的那个引号字符**，
否则会把闭引号改写成开引号。
⚠️ **机械遍会连"理由"一起吃掉**（本次约 8 行，如 ADR-1 的正文
「契约必须与序列化框架无关」被连引用一起删掉），所以必须留一遍**逐行改写**把理由搬回正文。
`apps/` 的 `§` 引用多数**不是出处标签而是规则本身**（实测 `§` 238 处），
**不能照搬 `libs/`+`contracts/` 的规则表**。

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

⚠️ **四处里只有 Dockerfile 那一处有门禁；根 POM 那一处靠 maven 自己喊。** 实测（Wave 0B
第二十六刀，`xander-webmvc-support`）：把 `<module>` 注释掉 ⇒ `mvn -pl apps/core-host -am compile`
报 `Could not find artifact com.xander:xander-webmvc-support`（**响的**，不是静默的）；
把 Dockerfile 那行 `COPY` 注释掉 ⇒ **门禁修好之前报 PASS**（见下面"全文子串匹配"那节）。
⇒ **四处都要逐处注入一次**，不能假设"有门禁"就等于"门禁看得见这一处"。

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

**搬 vs 抽端口：先看那个类型是不是接口。** 三种情况，别混：

| 那个类型长什么样 | 动作 | 先例 |
|---|---|---|
| **本来就是接口**（或纯 DTO） | **搬家**，连同它的 DTO 一起换包 | `BlogPublisher` |
| **具体实现**（`@Service`、拖 SDK / mapper / `@Value`） | **抽端口**：契约里新建接口，原实现 `implements` 它，调用方改依赖接口 | `OssPort` / `OssService` |
| **是接口，但签名里带框架类型，且有几十个实现** | **不动它**，另立一个**窄端口**把框架类型挡在外面 | `RemoteToolCatalog` / `AgentTool` |

第三种是 2026-09-17 新增的一行，也是最容易做错的一行 —— 前两种的直觉都会让你去动那个类型，
而正确答案是**连碰都不要碰它**。实测 `AgentTool`：签名返回 Jackson 的 `JsonNode`（ADR-1 直接
违规）、带 6 个 `default` 成员 + 一个 5 方法的嵌套监听器、**50 个实现 / 41 个覆写
`parametersSchema()`**。搬它 = 让 Jackson 进契约；改它的签名 = 动 50 个文件。

正确做法是在契约里建一个**窄**端口，只收调用方**真正用到**的成员，且 JSON 一律走 `String`：

```java
public interface RemoteTool {                 // 7 个方法，全是调用方实现适配器时必需的那 7 个
    String name();  String description();  String parametersSchemaJson();
    String promptText();  String approvalReason(String argsJson);
    boolean readOnly();
    String invoke(Long userId, String argsJson, RemoteToolCall call);
}
```

然后在**两边各写一个适配器**：实现侧 `implements RemoteTool`（一个类），调用方侧
`RemoteToolAdapter implements AgentTool`（一个类）。收益是 **50 个实现一行没动** ——
这次抽取一半的价值来自"没碰的东西"。判断"端口该多宽"的方法：**列出调用方的适配器
被迫要实现的成员，那就是端口的方法集**（`reconcile` 调用方不需要，所以端口里没有）。

**抽端口的真正信号：两个域各自写了一遍同样的代码。** 只看"它是不是具体实现"会漏掉关键判断 ——
`UserLookupPort` 那次，`BlogService.resolveAuthor` 与 `ComponentService.shareComponent`
都在注入 `identity` 的 **MyBatis mapper** 去 `selectById` 然后拼作者名。
**重复的查询 / 映射逻辑基本就是一个端口的位置**；而"另一个域直接用了你的 mapper"
本身就是硬违规（identity 的 `package-info` 明文禁止），跨部署单元时更是拆分后
"一个服务直连另一个服务的库表"。看到 mapper / entity 被别的域 import，先想端口。

**端口只回答"问题"，不替调用方决定"显示策略"。** `UserLookupPort` 返回
展示名或 `null`，**不返回兜底文案** —— 因为两个调用方的兜底不一样
（`"User " + id` 与 `"匿名用户" + id`）。把兜底塞进端口会逼两个域接受同一种文案。
格式、兜底、占位符留在调用方。

抽端口时的三条硬规矩：

1. **端口签名不得含框架类型。** 典型的坑是 Spring 的 `MultipartFile` —— 它是 spring-web 类型，
   放进契约会让 spring-web 出现在每个依赖契约的服务（含 WebFlux Gateway）的 classpath 上，
   而且**跨进程时根本没有 MultipartFile 可传**。改成 `byte[]` + 元信息，调用方在调用点转换。
   原方法可作为**本域内的便捷重载**留在实现类上（先例：`OssService.upload(MultipartFile, String)`
   留给同域的 `FileController`）。
2. **换签名会带来真实行为改动，必须留痕。** 从"把 `MultipartFile` 的输入流交给 SDK"改成
   "先 `getBytes()` 读进内存"是**流式 → 全量**的变化。可接受的前提是调用方上传前已经卡了大小上限；
   写进提交信息与 `docs/migration/lib-extraction.md`，别当成纯重构。
3. **⚠️ 端口宽度是没有任何东西在守的不变量，必须用测试钉住。** 普通接口改签名会编译报错，
   端口**变宽**不会：加一个 `default` 方法 → 编译通过、指标不动
   （`api_statements` 数的是调用点的 import，不是方法数）、门禁不报
   （`contract-dependency-boundary` 只看 import 与 POM 依赖）。
   于是"用户查询端口"会慢慢长成所有人绕过边界的新入口。用反射钉住方法个数：

   ```java
   assertEquals(1, UserLookupPort.class.getDeclaredMethods().length,
           "端口变宽了。它存在的意义是收窄暴露面……");
   ```

   **2026-09-17 实测的强度**：给 `RemoteTool` 加一个 `default String unusedCapability()`
   之后，**26 条门禁全 PASS**、编译通过、所有指标不动 —— 只有反射测试红（2 个）。
   所以这些反射测试**就是**这条不变量的唯一防线，不是"补充"。

   注入验证方法：加一个 `default` 方法（这样实现类不用改，只有宽度测试会红）。

   **⚠️ 钉"完整签名"，不要只钉方法名。** 三个相邻的 `String` 换位不会报错，
   只会静默错账（实测：`chargeTokens` 头四个参数 `(Long, String, String, String)`
   换位会把幂等键写到 `biz_type` 列上）。用 `getSimpleName()` 列表断言**类型 + 顺序 + 方法名**：

   ```java
   assertEquals(List.of("name():String", "invoke(Long,String,RemoteToolCall):String", ...),
                declaredSignatures(RemoteTool.class));
   ```

   配套：断言**没有任何 `default` 方法**（`getDeclaredMethods()` 里 `isDefault()` 全为假）、
   断言**没有继承别的接口**、断言**签名里没有框架类型**（见下）。

   **⚠️ 反射检查参数类型时，数组要先 `getComponentType()`。**
   `byte[].class.getName()` 返回 **`"[B"`**，不是 `"java.…"`。所以
   `name.startsWith("java.")` 这种写法会把端口**自己选的** `byte[]` 签名判成"泄漏了框架类型"，
   测试一跑就红，而红的原因看起来像"代码错了"：

   ```java
   Class<?> type = parameter.isArray() ? parameter.getComponentType() : parameter;
   assertTrue(type.isPrimitive() || name.startsWith("java.")
           || name.startsWith("com.xander.lab.content.api."));
   ```

4. **同一个方法名、两个重载、返回类型不同 —— 这是保留同域实体访问的正规手法。**
   Java 不能只靠返回类型重载，所以参数表必须不同：

   ```java
   @Override public MediaAssetRef  uploadImage(Long, byte[], String, String)  // 端口，跨域
            public BlogMediaAsset uploadImage(Long, MultipartFile)            // 同域（BlogMediaController）
   ```

   两者都调同一个私有实现体，**不复制校验逻辑**；`requireUser` 放在两个入口而不是实现体里，
   是为了让"登录先于参数"这个检查顺序在入口上看得见。
   **每个入口的检查顺序要与改造前逐条一致**（登录 → 类型 → 大小），否则
   `IllegalStateException` 与 `IllegalArgumentException` 会互换，HTTP 状态码跟着变 ——
   而编译器不会告诉你。

**⚠️ 端口方法必须显式收调用方 id —— `ThreadLocal` 跨不了进程。**
`BlogService` 原本有 7 个方法内部读 `auth.core.UserContext`，身份靠调用方 `setUserId` 传。
同进程里这能工作，但**跨进程根本没有 ThreadLocal 可传** —— 不收 `userId` 的端口只是在描述
一个无法跨进程的调用，等于没抽。附带收益：`getPublishedPostId(requestId)` 一次请求里读了
**3 次** `UserContext`，收 `userId` 之后自然收敛成 1 次。

**⚠️ 用"版本号轮询"替换反向回调，把控制流变成单向的。** 抽端口时最容易漏掉的一类依赖
是**反向的**：实现方在状态变化时回调调用方去清缓存。实测
`AgentMcpServerService.changed()` 原来直接调 `AgentToolRegistry.invalidate()` —— 于是
`integration` 域 import 了 `agent` 域，方向反了，正是要消灭的那条边。

拆法不是"把 `invalidate` 也放进端口"（那只是把反向依赖搬个地方），而是：

```java
// 端口侧：一个廉价、单调不减的版本号
long catalogVersion();

// 调用方侧：读到版本变了才重建，而不是等人来通知
private Map<String, AgentTool> allTools() {
    List<Long> versions = currentVersions();       // ⚠️ 先读版本
    Snapshot current = allToolsSnapshot;
    if (current != null && current.versions().equals(versions)) return current.tools();
    Map<String, AgentTool> built = merge(tools, null);
    allToolsSnapshot = new Snapshot(versions, built);
    return built;
}
```

四条要点：

1. **⚠️ 读顺序是"先版本、后工具"，反过来会留下一个永久性的陈旧缓存。**
   先取工具再取版本，并发变更时会存下"新版本 + 旧工具"，而版本此后再也不变，
   缓存**永远**不会重建。这个错误**没有任何状态可见** —— 不抛异常、指标不动、门禁不报，
   只能靠测试记录调用顺序来钉：
   ```java
   assertThat(calls).containsExactly("version", "tools", "version");
   ```
   （注意缓存命中时序列是 `[version, tools, version]` —— **版本每次都要读**，那正是机制本身；
   被跳过的只有工具抓取。我第一次把断言写成 `[version, tools]`，红了才发现自己理解错了。）
2. **版本号必须是 `long` 且单调不减**，`0` 的语义是"永不变化"。给 `default long version()`
   在这里是**正确**的，不是"静默变宽"：接口是函数式接口、测试里用 lambda 实现，
   而 `0` 就是一个语义合法的默认值。判据是"默认值是不是一个合法的真实语义"。
3. **多源时别折成一个 `long`，除非你能证明不撞。** 本仓库两处形状不同且都有理由：
   `AgentToolRegistry` 用 `List<Long>` 逐元素比较（源列表长度在装配期会变，折叠会撞）；
   `RemoteToolDynamicSource` 用 `version * 31L + catalog.catalogVersion()` 折叠
   （目录列表长度构造后固定 + 每个版本单调 ⇒ 可证单射）。**注入验证**：造一个 9×9 的
   版本对穷举，断言没有两对折到同一个值。
4. **一个 provider 抛异常不能把别的 provider 的工具一起弄没。** 注册表是**按源**catch 的，
   所以一个聚合源里必须**按 provider** 再 catch 一层 —— 否则一个远端 MCP server 抖动
   会让所有 provider 的工具集体消失。`RemoteToolDynamicSource` 就为此多了一层 try/catch。

**⚠️ 端口不该携带渲染方的策略常量。** `RemoteTool.promptText()` 只给文本，**不给 `order`**；
`order` 是 agent 域渲染提示词时的排序口径，由调用方适配器统一映射
（`AgentPromptSection.sideEffect(text)`）。把 `order` 放进端口 = 让 agent 的常量表在
契约里复制一份，两边可以静默漂移。

**⚠️ 端口抽走之后，端口用到的常量也必须一起搬走 —— 否则"断了实现依赖"是假的。**
`UpdateEmailReminderTool` 要判断 `PAUSE` 该写哪个状态，原来写
`EmailReminderService.STATUS_PAUSED`。**只要还写着那个名字，消费方就仍然要 import
提供方的实现类**，实现语句一条都没少 —— 端口白抽了。正解：把常量声明在端口上，
实现类通过 `implements` 继承拿到它们（不再重复声明）。

> **判据（比看指标快得多）**：抽完端口后，
> `grep -rn "import <提供方域>" <消费方包>` 的结果应该**只剩契约包**。
> `automation-api` 那一刀之后，`com/xander/lab/agent/` 下 15 条 `automation.*` 导入
> 全部是 `automation.spi.*` —— 一条 `automation.application` / `automation.api` 都没有。

**⚠️ "没传"与"传了空"不是一回事 —— 这是 record 化最容易丢的语义。**
域侧的 `@Data` 请求体用 `null` 表达"这个字段用户没说，保持原值"，
工具侧靠 `if (args.path("x").isBoolean())` 实现。换成位置构造的 record 之后，
这个条件极易被写成 `args.path("x").asBoolean()` —— 缺省时得到 `false`，
于是**每次编辑都静默关掉同步开关**，而用户根本没说过这话。

三条硬性做法：
1. 可选布尔分量声明成**装箱 `Boolean`**，不是 `boolean`（`boolean` 无法表达"没说"）。
2. 换算层**不要**用 `defaultText` / `Boolean.FALSE` 兜底这些字段 ——
   `topic` 兜成空串 = "改成空"，布尔兜成 `false` = "明确说不要"。
3. 加**两条互补**断言：`editLeavesUnspecifiedFieldsNull`（缺省必须 `null`）与
   `editCarriesExplicitFalseBooleans`（显式 `false` 必须落成 `false`）。
   只加一条，另一个方向就会静默错。

**⚠️ 同域调用点比跨域多得多时，把旧公开方法留成"薄壳"，不要改它们的签名。**
判断依据：**同域调用点不算跨域边界**。实测 `BlogService`：`BlogController` 有 17 个调用点
（全部同域），跨部署单元只有 9 条语句 / 3 条边。按"改签名 + 改所有调用点"去做，
等于为了 9 条语句去动 17 个不该动的调用点。正确做法：

```java
public BlogPostVO createBlog(BlogPostDTO dto, boolean publish, String requestId) {  // 签名不变
    return createBlog(UserContext.getUserId(), toInput(dto), publish, requestId);   // 薄壳
}
private BlogPostVO createBlog(Long userId, ContentPostInput input, ...) { ... }     // 真实现
@Override public ContentPost create(Long userId, ContentPostInput input, boolean publish) {
    return createBlog(userId, input, publish, null);                                 // 端口转调
}
```

薄壳里**只做取 id 与类型转换**，一行业务逻辑都不要动；顺手把 `switch case` 里的裸数字
（`publish ? 1 : 0`）换成常量，其余照抄。这样同域调用点、`@Transactional` 边界、
异常类型与 HTTP 状态码全部不变。**注意别把异常类型"顺手统一"** ——
`requireUser` 抛的是 `IllegalArgumentException`（不是 `IllegalStateException`），
换类型会改 HTTP 状态码。

**⚠️ 镜像关系可以"机器证明"，而且应该证明。** 换类型（`BlogPostVO` → `ContentPost`）
之所以能宣称是纯重构，判据是"序列化 JSON 逐字节不变"。三条独立断言，
**必须住在应用模块**（契约侧看不见 `BlogPostVO` / `PageData`）：

1. 两侧的可序列化属性名集合相同 —— 用 Jackson 的
   `mapper.getSerializationConfig().introspect(javaType).findProperties()`，
   不需要造实例，直接看类型；
2. 同一组值在两侧 `valueToTree(...)` 得到**同一棵树** —— 能抓到"同名但序列化行为不同"
   （比如某一侧加了 `@JsonFormat`、或把 `List` 换成了别的集合类型）；
3. **生产代码里那条转换**（`BlogService.toContract`，反射调用私有静态方法）的输出与源对象同树
   —— 前两条只证明"两个类型长得一样"，这一条才证明"实现真的按镜像搬了"。

⚠️ 写这个测试会踩到 `McpJsonMapperFactory` 那个缺陷的**同一个根因**：裸 `new ObjectMapper()`
序列化 `LocalDate` 直接抛 `InvalidDefinitionException`。测试里的 mapper 要
`registerModule(new JavaTimeModule()).disable(WRITE_DATES_AS_TIMESTAMPS)`。

**⚠️ 消灭"相邻同类型位置参数"要抽具名 DTO，并且测试要断言"字面量两两不同"。**
三个真实风险（旧签名全是位置参数）：

| 旧签名 | 危险 | 新类型 |
|---|---|---|
| `updateBlog(Long, String×5, List)` | `summary`/`content` 写反 → 摘要列拿到整篇正文 | `ContentPostInput` |
| `listMyPosts(Integer, String, Boolean, Boolean, Integer)` | `juejin`/`csdn` 写反 → 两条 SQL 都返回非空结果，静默错 | `ContentPostQuery` |
| `getBlogs(Integer, String, int, int)` | page/size 两个相邻 `int` | 保留位置参数（通用形状 + 只同域在用） |

**测试里那组相邻同类型分量必须给互不相同的字面量** —— 都给同一个值的话，
把它们对调测试仍然全绿，顺序就白钉了。这条比"分量名列表"更关键。

**⚠️ 但 `Boolean` 分量只有两个取值，一组字面量钉不满 —— 要两组互补的。**
`ContentPlanView` 有三个相邻 `Boolean`（`syncCsdn` / `syncJuejin` / `runOnce`）。
取 `(true, false, true)` 时，把**第一个和第三个对调**测试仍然全绿。
所以 `ContentPlanPortMappingTest` 用两组互补组合（`(true,false,true)` 与
`(false,true,false)`）：两组都过，任意两个分量对调必有一条红。
**规律：n 个相邻同类型分量至少需要 ⌈log₂(n+1)⌉ 组互补字面量，
而 `Boolean` 只能给 2 个取值，所以 3 个以上就必须靠组合。**
（`payment-api` 那次 `TokenUsage` 三个相邻 `long` 可以用全不相同的值，所以一组就够 ——
`Boolean` 是特例，别照抄"一组就够"的结论。）

**⚠️ 当契约 record 与它镜像的域内 record 并存时，新分量只能追加在末尾。**
`BlogAgentImage`(6) 镜像 `GeneratedImage`(5)，前五个**逐位对齐**，唯一的新字段 `size`
追加在最后：

```java
// GeneratedImage:   bytes, contentType, extension, usage, model
// BlogAgentImage:   bytes, contentType, extension, usage, model, size
```

把 `size` 插在 `model` 旁边（"它俩都是元信息，放一起"）会让换算从"逐位直接映射"
变成"错位映射"，**而编译器不会响** —— 两个位置都是 `String`，换过去照样编译，
只是把尺寸写成模型名。**判据**：一条专门断言"前 n 个分量逐位对齐、新分量在末尾"的测试
（`imageKeepsSizeLastSoTheFirstFiveStayPositionallyAligned`）。

**⚠️ 词表类进契约，但不要改成枚举。** `ContentPostStatus`（`DRAFT=0` / `PUBLISHED=1` /
`TRASHED=-1`）进契约是因为两个 agent 工具各自把 `"draft" ⇄ 0` 写了一遍。
但 `blog_post.status` 是 **`int` 列**，MyBatis 投影与线上 JSON 都按数字走，换枚举会同时改
数据库列类型与前端契约。保留 `BlogService.STATUS_*` 作为**别名**
（前端 HTTP 契约与同域服务都在用，删了会把一次契约抽取变成一次前端可见的改名）；
别名仍是常量表达式，`switch` 的 `case` 标签照旧可用 —— 用
`case ContentPostStatus.DRAFT -> ...` 把"能当 case 标签用"变成一条可执行断言。

**⚠️ 扩展现有契约不需要构建接线。** 新建 `contracts/<name>/` 要改四处（见下），
但往**已存在**的契约里加类型是零成本：模块已接线、包已登记。
`core-api` 承接 `identity` 的类型、`content-api` 承接 `publishing` 的类型，都是这一类。
**先确认"已有契约里是不是已经有同一能力的类型"** —— 2026-09-17 那次，决策说的是
"建 `publishing-api`"，落地时发现 `content-api` 本来就是发布契约（已装
`BlogPublisher` / `PublishCommand` / `PublishResult`），扩展它就行，省掉四处接线。

**⚠️ 契约不能依赖契约。** `contract-dependency-boundary` 只允许 JDK + 本包
（`CONTRACT_ALLOWED_IMPORT_PREFIXES`），并**拒绝任何非 test 作用域的 POM 依赖** ——
Maven 编排不了 test-only 的跨契约依赖，所以门禁干脆全禁。后果是字段相同的类型要各写一份：
`content-api` 不能复用 `integration.api.UploadResponse`，只能自己定义 `StoredImage`。
**遇到这种情况改设计，不要放宽门禁** —— 放宽的那一刻 ADR-1 就只剩文档里一句话了。
（"契约能不能依赖契约"本身仍是未拍板的问题，见
`docs/migration/adr-contract-boundaries.md` §6。）

**⚠️ 契约也不能依赖"带框架的共享内核库"，哪怕那个类型本身很干净。**
实测卡点：`BillingService.chargeTokens(...)` 收 `com.xander.lab.common.ModelUsage` ——
它是 `record (long, long, long)`、零注解，**但**它住在 `libs/xander-model`，那个模块为
`ModelUsage.fromJson(JsonNode)` 声明了 `jackson-databind`，所以契约 import 不到它。
正确动作是**契约自带等价 DTO**（`payment.api.TokenUsage`），不是放宽
`CONTRACT_ALLOWED_IMPORT_PREFIXES`。代价是调用方在边界上做一次三字段换算：
`new TokenUsage(u.inputTokens(), u.cachedTokens(), u.outputTokens())`。
**选端口形状时优先选与现有实现同形的**：`TokenUsage` 的访问器与 `ModelUsage` 同名，
于是 `BillingService` 的方法体一行没动，只换了参数类型。见
`docs/migration/adr-contract-boundaries.md` §4。

**⚠️ 契约 DTO 的分量顺序是**位置契约**，而且没有编译期保护。** 三个相邻的 `long`
或三个相邻的 `String` 重排不会有任何错误，只会静默错账。所以：
（1）契约侧用互不相同的字面量反射钉住分量名与顺序；（2）实现侧钉住"哪个分量落到哪一列"
—— 实测存在一处真实换位：`TokenUsage` 是 `(input, cached, output)`，而
`recordAndDeduct` 收的是 `(..., input, output, cached, ...)`，后两个正好相反，
写错只让流水的 `output_tokens`/`cached_tokens` 两列互换、**金额照旧**。
（3）端口测试钉**完整签名**（`getSimpleName()` 列表）而不只是方法名 ——
`chargeTokens` 头四个参数是 `(Long, String, String, String)`，三个相邻 `String` 换位
会把幂等键写到 `biz_type` 列上。

**⚠️ 实体不能进契约 —— 它带的是持久化注解。** `BlogMediaAsset` 带 `@TableName` /
`@TableId`（MyBatis），搬进契约等于把 MyBatis 拖进所有消费者。正确做法：实体留在
`domain`，在端口边界显式映射成 `*Ref`。映射要**逐个字段手写**，不要
`BeanUtils.copyProperties` / 反射 —— 字段名对不上时反射版本静默留 `null`，手写版本编译期报错。

**⚠️ 前端可见的类型：镜像还是收窄，是一次 §11 决定，不能顺手做。**
`BlogAgentTaskVO.illustrations` 是线上契约。13 个字段**逐一同名同类型**地镜像
⇒ 序列化 JSON 与改造前完全一致 ⇒ 这次提交可以被证明是**纯重构**，
`docs/contract-changes.yaml` **不需要**加条目。收窄（只留前端真读的 3 个）**也能做**，
但那是独立的 §11 变更，**必须**进登记表。
**选镜像，并在注释里写明为什么** —— 否则下一个人会以为那 10 个多余字段是疏忽。

**⚠️ 收窄型契约的字段集，必须每次从"消费方读取点"重推一遍再与 record 对账。**
2026-09-17 的 `automation-api` 真的漏了一个字段：`EmailReminderView` 的 Javadoc 已经把
`sendTime` 写成 `QueryEmailRemindersTool` 要读的字段，但 record 的**分量列表里没有它** ——
写 Javadoc 时想到了，写分量时漏了。症状不是编译错误，而是**工具输出静默少一个键**。

推法（对每个消费方文件跑，取并集）：

```bash
for f in <消费方文件…>; do grep -oE "\.(get|is)[A-Z][A-Za-z0-9]*\(" "$f" | sort -u; done
```

> **Javadoc 里列了消费方字段清单 ≠ record 里有那些分量。** 两者是分开手写的，就会分开漂移。
> 而且**不能沿用上一次抽取的结果** —— 消费方的读取点会变，字段集就得跟着重推。

**⚠️ 收窄的可以是"一个字段"，也可以是"一个维度" —— 后者要在换算层显式补值。**
2026-09-17 的 `recitation-api`：域侧 `MaterialQuery` 是
`(search, masteryLevel, dueOnly, limit, archiveFilter)`，但跨域消费方**从不写 `ArchiveFilter`**
（全部用 4 参数构造器）⇒ 归档维度是**提供方的策略**，不进契约。
换算层于是要显式写 `ArchiveFilter.ACTIVE`。

> **"少一个维度"不是"少一个字段"**：字段收窄只是不带过去，
> 维度收窄要求在**换算层补一个策略值**，而那个值以后会跟着域侧默认值变。
> 所以换算层的这一行必须被测试钉住（注入验证：把它改成 `ArchiveFilter.ALL`，3 条测试变红）。

⚠️ **静态工厂（`MaterialFilter.all()`）不用跟着端口搬** —— 它没有实现依赖，
只是给消费方的常量构造提供落脚点。要搬的是**常量**（见"常量必须跟着端口搬"）。

收窄比镜像更容易出错，因为它没有"序列化逐字节不变"这个可证明的命题兜底：
镜像时字段集是照抄的，收窄时字段集是**判断**出来的，判断就会错。
所以收窄**必须**配一对互补的测试：契约侧 `RecordShapeTest` 钉形状，
应用侧 `*PortMappingTest` 用互不相同的字面量钉**换算那一行**（契约侧看不见域类型）。

**⚠️ 循环依赖靠"端口只收真正用到的字段"来断。** 2026-09-17 的环：
`BlogMediaService.saveAgentImage(..., GeneratedImage, ...)` 收了**下游**类型，
于是 `publishing` 反过来 import `blogagent`。拆法不是"把 `GeneratedImage` 搬进契约"
（它带 `ModelUsage`，有 `fromJson(JsonNode)`，Jackson 泄漏），而是看内容域
**真正读了哪两个字段**（`bytes()` / `contentType()`），按那两个字段收参。
**usage / model 这类"属于另一件事的口径"故意不上端口**，留在调用方 ——
`ImageGenerationTool` 自己 `chargeTokens(..., generated.usage(), generated.model(), ...)`。

**⚠️ 端口收 `byte[]` 时，上限检查的责任转移到调用方 —— 这是个真实的坑。**

```text
spring.servlet.multipart.max-file-size = 100MB
端口 MAX_IMAGE_BYTES                    =  10MB
```

调用方若先 `file.getBytes()` 再交给端口，100MB 的请求会被**整份读进内存**才被拒（10 倍放大）。
所以：端口上**公开**这个常量（`long MAX_IMAGE_BYTES = 10L * 1024 * 1024`，避免三个调用方各抄一份），
并且每个调用方**必须**在 `getBytes()` **之前**用 `file.getSize()` 先卡一道。

**⚠️ 给契约挑包名之前，先 `ls` 一下那个域，别照抄 `integration.api` 的结论。**
§18 的 8 个契约里有 4 个（`agent-api` / `automation-api` / `integration-api` / `studio-api`）
的名字同时是业务域，条目按**最长前缀**匹配，所以包名必须带点、登记也要带点
（**不要**登记裸 `integration`：那会让契约隐形，因为业务域在扫描器里优先）。

但"带点"之后**带哪一段，四个域答案不一样** —— 2026-09-17 实测踩到：

```bash
# 先做这一步，再决定包名
ls apps/core-host/src/main/java/com/xander/lab/<domain>/
```

| 域 | `<domain>.api` 是否已被占用 | 契约包 |
|---|---|---|
| `integration` | 空闲（该域按集成方式分子包：`file` / `mail` / `mcp` / `oss`） | `integration.api` |
| `agent` / `automation` / `recitation` / `blogagent` | **已被占用** —— 是这些域自己的**前端 HTTP 层**（controller / VO / request body）。2026-09-17 实测：`recitation.api` 6 个文件、`blogagent.api` 5 个文件 | `agent.spi` / `automation.spi` / `recitation.spi` / `blogagent.spi` |
| `studio` | **已被占用** | `studio.spi` |
| `content` / `core` / `payment` | 契约名与域**不同名**，包名不与任何域撞车 | `content.api` / `core.api` / `payment.api` |

硬把契约塞进 `<domain>.api` 会同时坏两件事：（a）一个包名出现在两个 jar 里；
（b）扫描器会把那个域的 **controller 当成共享内核**，于是"controller import 自己的
application 层"变成一条条 `<domain>.api -> <domain>` 的假边 —— 待还债凭空多出几十条，
外加每条一个 `shared-kernel-reverse-dependency` 违规。
**新增契约模块前必做：`ls` 那个域，确认 `.api` 是不是空的。** 不要因为
`integration.api` 能用就假定 `agent.api` 也能用。

还有第三种情况：**契约名与它所属的业务域不同名**。`payment-api` 搬的是 `billing` 域的类型，
包是 `com.xander.lab.payment.api`。`payment` 既不是业务域（域叫 `billing` / `virtualpay`）
也不在 `SHARED_KERNEL` 里 —— 同样必须是带点条目 `payment.api`。
§18 的契约名是按**能力**而不是按**现有模块**命名的（`payment` 对 `billing`），
这是 §91 的意图，所以"名字对不上现有目录"是正常的，别顺手改成 `billing.api`。
同理 `content-api` 装的是 `publishing` 域的类型 —— **建契约前先查已有契约里有没有
同一能力的类型**，扩展它比新建省掉四处接线。

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

**⚠️ `SHARED_KERNEL` 条目删错一个，五个头条指标可能一个字都不动 —— 只有门禁知道。**
2026-09-17 把 10 个条目**逐个**删掉重跑扫描（每次都从 pristine 副本还原 `scan.py`，
绝不读自己刚写的那份），结论可以用**一个谓词**概括：
**`entry.split(".")[0]` 是否在 `BUSINESS_DOMAINS` 里。**

| 删掉的条目 | 首段是业务域 | impl / cross 变化 | api 变化 | `unclassified_sources` | 失败门禁数 |
|---|---|---|---|---|---|
| `agent.spi` | **是**（`agent`） | **+4 语句 / +1 边** | 0 | 不变 | **3** |
| `integration.api` | **是**（`integration`） | 0 | **+8** | 不变 | 1 |
| `core.api` | 否（`core`） | **0** | 0 | **+core** | 1 |
| `content` | 否 | **0** | 0 | **+content** | 1 |
| `payment.api` | 否（`payment`） | **0** | 0 | **+payment** | 1 |
| `auth` / `common` / `config` / `filter` | 否 | **0** | 0 | 各 **+1** | 1 |
| `util` | 否 | **0** | 0 | **不变** | **0** ⚠️ |

机制在 `domain_of()`：

- 首段**是**业务域 ⇒ 未登记的包**回落到那个业务域**，import 重新被算成跨域边。
  `agent.spi` 那行是 4 条 impl 语句；`integration.api` 那行是 8 条 `.api` 层语句，
  只进 `api_statements` 所以 impl 不动。门禁会连报三条：
  `shared-kernel-classification`、`cross-domain-import-baseline`
  （`new cross-domain implementation edge(s): integration->agent`）、
  `deployment-unit-coupling`（`new mutually-dependent unit pair(s)`）。**这是好消息** ——
  指标在动，人看得见。
- 首段**不是**业务域 ⇒ `target not in BUSINESS_DOMAINS` 把它**静默丢掉**，
  五个数字与"正确"状态**逐字节相同**，只有 `shared-kernel-classification` 一条红，
  外加 `unclassified_sources` 多一个条目。**别因为数字好看就跳过门禁**；也别把
  "删了条目指标没动"读成"这个条目没用" —— 它恰恰是那条边被静默吞掉的证据。

⚠️ **`util` 那一行是另一个洞：没有任何东西在守"条目是不是死的"。** `util` 已经在
`SHARED_KERNEL` 里躺了很久，但**没有任何文件**住在 `com.xander.lab.util` 下
（`find . -type d -name util` 为空），所以删掉它 0 条门禁失败、0 个指标移动、
连 `unclassified_sources` 都不动。`shared-kernel-classification` 只检查
**"每个被发布的包都有条目"**，不检查**"每个条目都有包"** —— 反向判据是缺的。
（`unclassified_sources` 基线**本来就非空**：里面有 `DinQorAIApplication.java`，
来自一个直接躺在 `com/xander/lab/` 下的类。所以"列表变长"要比对**集合**，不能只看空不空。）

**⚠️ 契约包在 `SHARED_KERNEL` 里 ⇒ 消费方 import 契约**一条都不计入任何指标**，
所以 `api_statements` 也会跟着掉。** 这不是意外，而是"用契约换掉实现依赖"在数字上的样子，
但第一次见到会以为哪里算错了。

`automation-api` 那一刀：

```text
implementation_statements         50 → 44   (-6)   6 条 automation.application.* 换掉
api_statements                    25 → 16   (-9)   9 条 automation.api.* 换掉
```

`api_statements` 掉 9 的来源：6 个工具原本 import 了 `EmailReminderTaskVO` ×3、
`BlogContentPlanVO` ×3、三个请求体 ×3。**换成 `automation.spi.*` 之后这 9 条归零**
（契约包被登记进 `SHARED_KERNEL`，前缀匹配直接跳过）。
所以**看收益要同时看 impl 和 api 两列** —— 只看 impl 会低估一半。

⚠️ 写这类测量脚本时，**别用带 `\s*$` 的正则去切 YAML 块列表** —— `\s` 会匹配换行，
于是它可能吞掉整块、报出**隔壁键**的条目。本轮就因此得到过一行假的 `+util`，
换了逐行状态机之后才消失。**任何"测量结果"在写进文档前都要先用另一种解析方式复核一遍。**


**⚠️ 搬常量类 / 枚举类时，用"字面量整体 diff"验证，不要用眼睛。**
`BillingBiz` 是 final 常量类、零 import，"看起来一样"不算验证。用 `git show HEAD:<旧路径>`
取出原文，抽出字面量排序后 diff：

```bash
diff <(git show HEAD:<旧路径> | grep -oE '"[a-z0-9-]+"' | sort) \
     <(grep -oE '"[a-z0-9-]+"' <新路径> | sort)
# 无输出 = 字面量完全一致
```

**漏抄一个常量**和**抄错一个字母**都不会被编译器发现（少一个常量：只要没人用就编译得过；
抄错字母：字符串而已），只有这一步能抓到。

**⚠️ 常量目录的契约测试要"整体比对"，不要逐条 `assertEquals`。**
用反射取出**全部** `public static final String` 字段，与预期 `Map` 整体 `assertEquals`：

```java
for (Field f : Catalogue.class.getDeclaredFields()) {
    if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) continue;
    if (f.getType() != String.class) continue;
    found.put(f.getName(), (String) f.get(null));
}
assertEquals(EXPECTED, found);
```

理由：逐条断言**漏掉"新增了一个常量但没人回来补断言"**，而那恰恰是最可能发生的 ——
新增业务项时人会记得改常量、不会记得改测试。整体比对让新增 / 改名 / 删除**全部**失败，
不依赖任何人记得补一行。（先例：`BillingBizTest` / `ModelGatewayTest`。）

**⚠️ 契约的依赖边界（JDK-only）遇到 Jackson 时不要顺手放宽 —— 两处都已按"改设计"解决。**
放宽 `CONTRACT_ALLOWED_IMPORT_PREFIXES` 是**设计决策**，不是重构的顺带产物。两个先例各给了一种解法：

| 卡点 | 错误解法 | 实际解法 |
|---|---|---|
| `BillingService.chargeTokens(...)` 收 `ModelUsage`（住在 `libs/xander-model`，带 Jackson） | 放宽白名单 | 契约**自带等价 DTO** `payment.api.TokenUsage`，调用方在边界换算 |
| `AgentTool.parametersSchema()` 返回 `JsonNode` | 搬 `AgentTool` 进契约 | 另立**窄端口** `agent.spi.RemoteTool`，JSON 一律走 `String` |

两张表的共同点：**被挡在门外的那个类型一行都不用改**，代价只是边界上一个适配器。
判断标准是"契约侧能不能只描述**数据**，把框架细节留给适配器"。
见 `docs/migration/adr-contract-boundaries.md` §4 与 §6，以及
`docs/migration/lib-extraction.md` §3quinquies.5 / §3decies。

**零接线扩展：往已有契约模块加类型，比新建模块便宜得多，优先找这条路。**
抽契约前**先查已有契约里有没有同一能力 / 同一方向的类型** —— 有就是零接线扩展。
"零接线"的具体含义：**不新增模块 ⇒ 四处构建接线（根 POM `<modules>`、应用 POM 依赖、
`scan.py` 的 `SHARED_KERNEL`、Dockerfile）一处都不用动**，于是改动里没有"接线"可以出错，
风险全部收敛到语义上，而语义有反射测试守着。
本项目实例：`core-api` 加 `UserLookupPort`、`automation-api` 加 `NotificationPort`
（后者见 `adr-contract-boundaries.md` §8）。

**⚠️ 常量落库时，契约测试要钉字面量；只有消费方测试是不闭合的。**
若一个契约常量的值会写进数据库列 / 前端按它分流，那它的字面量**是数据不是代码**，
改它等于数据迁移。此时：

```java
// 契约侧：钉值
assertEquals("KNOWLEDGE_FILE", NotificationPort.RESOURCE_KNOWLEDGE_FILE,
        "这个字面量落库（blog_notification.resource_type）。改它不是重构，是数据迁移。");

// 消费方侧：钉"传下去的就是这个常量"
verify(port).notifyResource(eq(7L), anyString(), anyString(), anyString(),
        eq(NotificationPort.RESOURCE_KNOWLEDGE_FILE), eq(500L));
```

**两处合起来才闭合。** 只有消费方测试的话，生产代码与测试引用**同一个**常量 ——
常量值错了两边一起错，测试永远绿。反过来只有契约测试也不够：它管不了消费方传了别的字面量。
（先例：`NotificationPortTest` + `KnowledgeIngestionServiceTest`，2026-09-17。）

**⚠️ 端口方法的返回值不要为了"窄"而收窄，先看会不会逼实现侧改名。**
Java **不允许**同名同参只差返回值，所以把 `Long notifyResource(...)` 收窄成 `void`
会让实现侧**必须改名** —— 那是与这条缝无关的改动，白增风险。
与实现**同形**的端口，提供方的改动退化成"加 `implements` + 加 `@Override`"，
可证明行为不变。返回值是 JDK 类型、又是操作的自然结果（"写一条通知，得到它的 id"）时，
这点富余是**惰性**的，不必急着砍。（先例：`NotificationPort`；对照：`PublicationPort`
也是零改动形状，见 `lib-extraction.md` §3nonies.5。）

**⚠️ 端口方法"转调同类富方法"时，必须自己带 `@Transactional` —— 属性要与富方法完全一致。**
这是抽端口最容易埋的**语义**缺陷，而且**编译通过、26 条门禁全 PASS、指标一个不动**。

```java
// 富方法：事务在这里
@Transactional(propagation = Propagation.REQUIRES_NEW)
public EmailReminderView create(Long userId, EmailReminderRequest request) {
    reminderMapper.lockUserForReminderCreation(userId);   // SELECT ... FOR UPDATE
    ...
}

// 端口方法：自调用绕过代理 ⇒ 上面那个事务根本不存在
@Override
@Transactional(propagation = Propagation.REQUIRES_NEW)   // ← 必须补上，且属性要一致
public EmailReminderView create(Long userId, EmailReminderDraft draft) {
    return toView(create(userId, toRequest(draft)));
}
```

**Spring 的 `@Transactional` 走代理，`this.xxx()` 不经过代理。**
所以"端口方法只是转调"这句话在事务上**不成立** —— 转调的那一层**自己就是一个事务边界**。

为什么是实质缺陷而不是洁癖：`lockUserForReminderCreation` 的实现是 `SELECT ... FOR UPDATE`，
**它的全部作用就是串行化并发创建**。没有事务 ⇒ 锁在语句结束就释放 ⇒
幂等检查与插入之间出现窗口 ⇒ 两个并发请求各插一条。
**症状是"偶尔重复"**，比崩溃难查得多。

⚠️ **不能只写 `@Transactional`**：富方法写的是 `REQUIRES_NEW` / `readOnly = true` 时，
端口方法写裸 `@Transactional` 会**静默改变传播语义**。

**这条规则没有门禁守**（`scan.py` 只看 import 边，看不见注解）⇒ 由
`apps/core-host/src/test/java/com/xander/lab/migration/PortDelegationTransactionTest.java` 守，
三条断言：①**完整性**（扫描到的端口实现 ↔ 手工清单双向比对）；
②**委托必须带注解**（判据是"方法体是否只有一次同类自调用"，不是"名字是否在某张表里"）；
③**反向 sanity**（确认真的扫到了类）。
⚠️ 完整性那条**当场就还了本钱**：加 `RecitationService` 时忘了列进清单，全量跑时正是它变红。
**第二次付费**（2026-09-18 第十六刀）：`CsdnBlogSyncService` / `JuejinBlogSyncService` 加上
`implements <契约端口>` 之后，还没跑注入就先被它拦住 ——
报"端口实现类的清单与实际不符"。**动作**：把新实现类加进 `PORT_IMPLEMENTATIONS`
（含 import），并同步类注释里那句"全仓 N 个端口实现"的计数。

（先例：2026-09-17 第十刀，`EmailReminderService` 2 处 + `BlogContentPlanService` 3 处共 5 个方法缺注解。
详见 `adr-contract-boundaries.md` §9.8 / §9.9。）

**⚠️ 端口收窄会删掉消费方的表达力 —— 收窄时必须问"这条断言以后谁来写"。**
本项目实例：`QueryKnowledgeToolTest` 原来断言搜索 SQL 含 `archived_at IS NULL`，
但契约侧 `MaterialFilter` **没有归档维度**（那是提供方的策略）⇒ 工具侧再也写不出这条断言。
答案只能是"提供方的测试"（**搬家**）或"契约侧的换算测试"（换形状），**不能是"没人写"**。
⚠️ 搬家时**先写新家、再删旧家** —— 反过来会让不变量在两次编辑之间**无人看守**。

**⚠️ 一个字段的类型是 `Object`（没有形状）时：契约带**文本**，解析留给消费方。**

域侧那个字段反序列化自一个 JSON 列，所以它的运行时类型是"任意 JSON" ——
**那一列里可能有旧版本写下的内容**。三种做法，只有第三种对：

| 做法 | 结果 |
|---|---|
| 把 `Object` 搬进契约 | 等于把"随便什么"写进契约。契约要能回答"这个分量是什么形状"，`Object` 回答不了 |
| 收窄成一个新的契约 record | 契约会开始**假装知道**历史行里存的是什么形状。旧行可能缺字段、也可能多字段，而读取端**没有做兼容的地方** |
| **契约带 `String`（如 `itemsJson`），解析上移到消费方** | ✅ 契约只承诺"这是一串 JSON 文本" |

**这不是绕路，是 ADR-1 直接给出的答案**：契约不许带 JSON 库 ⇒ 契约**没法**解析 ⇒
只能带文本。前几次 ADR-1 都表现为"要绕一下"，这一次它是**答案本身** ——
一个"什么都不知道"的契约，恰好是唯一不会对历史行撒谎的契约。
（先例：`QuizAttemptView.itemsJson`，2026-09-17 第十一刀；解析落在
`QuizKnowledgeTool.parseItems`，空/非法/`null` 一律退化成**空数组**而不是 `null` ——
单条历史坏掉不该把整场操作带下水。）

**⚠️ 一份实体投影成两份类型时，必须配一条"两个投影在共享字段上相等"的断言。**

同一个实体同时被投影成"前端可见类型（已解析）"与"契约类型（文本）"时，
**两边各自的测试都不会红**：前端测试只断言解析后的结构，契约测试只断言文本，
谁都不看"这两个投影在共享字段上是否一致" ⇒ 它们会**静默漂移**。

判据：两个投影**共同拥有**的每个字段都要有一条 `assertThat(portSide.x()).isEqualTo(frontendSide.x())`。
⚠️ 这条断言有**独立**价值，不是重复劳动：把契约侧换算的 `questionCount` 与 `correctCount`
对调时，它会和"逐字段落点"测试**同时**变红，而工具侧与前端侧的测试全绿
（2026-09-17 第十一刀实测）。**凡新增一份投影都要配一条**（`ContentPost` / `MediaAssetRef`
收窄时会再次遇到）。

**⚠️ 契约不许依赖契约 ⇒ 契约自带等价 DTO，换算落实现边界。**

同一个形状在**两个契约模块**里各有一份时（如 `content.api.PublishResult` 与
`integration.api.BlogSyncResult`，分量逐字相同：`platform` / `externalId` / `url`），
**不许**让 `integration-api` 把 `content-api` 写进 POM —— 那会让两个契约绑成一个，
任一方的服务要独立升级都得连带另一方（ADR-2）。

规矩：

1. **换算只允许有一处**，住在**实现类**里（`CsdnBlogSyncService.toContract`）。
2. **分量逐个点名，不要靠位置**：三个都是 `String`，位置写错编译器不会有任何意见。
   ```java
   // ✅
   return new BlogSyncResult(result.platform(), result.externalId(), result.url());
   // ❌ 靠位置 ⇒ platform 与 url 对调不会有任何编译错误
   ```
3. **"两个投影在共享字段上相等"的断言住在实现类的测试里** ——
   **只有实现同时看得见两份类型**，契约侧只能钉自己那一半（分量名与顺序）。
   ⚠️ 测试常量必须**互不相同**，否则断言退化成"永远通过"。
4. ⚠️ 这类重复是 ADR-2 的**已知代价**（ADR 待办第 2 项：契约能否依赖契约，未拍板）。
   **每多一份就多一处必须成对改的地方。**

**⚠️ 抽端口可能逼实现类改返回类型，于是同域消费方要跟着改 —— 判断是不是"纯重构"看线上 JSON。**

实现类一旦 `implements` 端口，它的公开方法返回类型就必须是**契约类型**
（不能继续返回域侧类型）。于是同域消费方（含 HTTP 控制器）跟着换类型：

```java
public Result<PublishResult> sync(...)  →  public Result<BlogSyncResult> sync(...)
```

⚠️ **这仍然是镜像、不进 `docs/contract-changes.yaml`**，只要两个 record 的**分量名与顺序逐字相同**
（Jackson 按分量名序列化 ⇒ HTTP 响应体一个字段都没变）。
**判据是"线上 JSON 变了没有"，不是"Java 类型名变了没有"。** 动手前先 diff 两份 record 的分量表。

**⭐ "一个能力一个端口" ≠ "一个平台一个端口"；当平台名是本域词汇表时，就是两个端口。**

第十六刀（站外同步）在这条上犹豫过，第一直觉是"一个 `BlogSyncPort` + `platform` 参数"。
**判据是参数表**：掘金的 `sync` 多收分类 id 与标签 id，CSDN 没有这两个概念 ⇒
合并端口只有两条路 —— **要么让 CSDN 收下它永远不用的参数（端口变宽），
要么砍掉掘金的能力（静默降级，V9 §2.3 禁止）**。加上域自己就是两套
（两套凭证 / 两个 controller / 两个 MCP 应用服务），所以是 `CsdnBlogSyncPort` + `JuejinBlogSyncPort`。

附带收益：两个端口的方法表与各自实现**逐字相同** ⇒ 实现类只加一句 `implements`，
**不需要一个按平台字符串分派的中间类**，也就没有"分派写错只在运行时才炸"的新缝。

⚠️ **但这个附带收益有前提，第十九刀把它拿掉了。** 上面那两个同步端口的**参数表不同**
（掘金多收分类与标签 id），所以"接收者写反"会**编译失败** —— 这是顺带挡住的。
第十九刀的两个凭证端口 `CsdnCredentialPort` / `JuejinCredentialPort` 的
`isAuthorized(Long) -> boolean` **逐字相同**，写反**编译通过**。
⇒ 结论：**"零适配器"这个收益要看清它同时放弃了什么**；参数表逐字相同的同形端口，
必须配**不对称桩**的行为测试（见测试章"桩取的值分不开两个被测对象"那条）。

⚠️ **反面判据**：只有当合并后的签名对**每一个**实现都是它真正需要的形状时，才该合并。

⚠️ **侦察阶段的端口草稿只写能力名，不要写死个数。** `lib-extraction.md` 的草稿写过单数
`CredentialPort` / `BlogSyncPort`，实际两样都落成**两个**端口。**个数由调用点的参数表决定**，
而那要等数清跨域表面之后才知道 —— 写死个数会让后来者以为"草稿已经定了"。

⚠️ **动手第一件事是数清跨域表面，不是想端口形状。** 第十九刀的凭证服务各有 6 个公开方法，
跨域位置只用到一个 `isAuthorized`。**先列表：每个公开方法 × 有没有跨域调用点。**
只有被跨域调用的那些才上端口 —— 这一刀只搬了 1/6，
**一半的价值来自没搬的那五个**（它们端出去会让消费方的适配器被迫实现四个它从不调用的成员）。

**⭐⭐ "整个类碰不得" ≠ "其中一条方法碰不得" —— 窄端口的判据是"这条边流过了什么"。**

第二十刀的目标边是一条**被错记成"卡在框架类型上"**的边：`AdminNotificationController`
注入 `BlogPlanSseService`，而那个类的 `subscribe` 返回 `SseEmitter`（框架类型）、
`push` 收 `planId` / `runId`（另一个域的概念）⇒ 上一刀的结论是"契约不许带框架类型，
所以形状要先设计"，于是这条边在边表上多躺了一刀。

实测**跨域位置唯一用到的那个方法**的签名是：

```java
public int broadcast(String type, String title, String message, Long planId, Long runId)
```

**三个 `String`、两个 `Long`、返回 `int`，一个框架类型都没有。**
⇒ "这个类碰不得"是真的，但"它里面**每一条**方法都碰不得"是假的。
**窄端口的判据从来不是"这个类长什么样"，而是"这条边上流过了什么"。**
动手前先 `grep` 出**跨域位置到底调了哪个方法**，再看**那个方法**的签名。

⚠️ 这类错记会**自我加固**：一旦注释里写下"要的是 `SseEmitter`…单独一刀"，
后来者就会照着那句话跳过这条边（第二十刀在 `package-info.java` 与 `NotificationPort`
两处各找到一句，都已订正）。**注释里的"做不到"要和代码一样接受复核。**

⚠️ **"跨域消费方当前传什么"不是端口宽度的判据。**
两个跨域消费方传的都是 `(null, null)`（用文章自己的分类与标签名），但那两个参数**仍留在端口上**：
同域的前端 HTTP 层会传真实值，拆服务后端口是那条路径唯一能穿过的形状。
**按"没人用"砍参数 = 让能力在拆服务时静默消失。** 问的是"**它有没有具名消费方**"，
不是"**它现在有没有跨域消费方**"。

⚠️ **"与实现同形"是好性质，不是目标；当同形要求调用方撒谎时，同形让位。**
分清端口上"多出来的东西"是哪一种：

| 多出来的东西 | 调用方要做什么 | 该不该留 |
|---|---|---|
| 多余的**返回值**（第十六刀的 `Long`、第十九刀的 `int`） | 忽略它 | 可以留（同形 ⇒ 实现类只加 `implements` + `@Override`） |
| 多余的**参数**（第二十刀的两个 `Long`） | 每次**伪造一个值** | **不该留** |

第二十刀的 `planId` / `runId` 是 blog-plan 域的概念，唯一调用方（admin 的诊断端点）
**永远传 `null`** ⇒ 端口只收三个 `String`，转发给 `push` 的两个值由"调用方传 `null`"
变成"实现里写死 `null`"。**可证行为保持**：`push` 本来就把 `null` 序列化成空串，
出站载荷逐字节不变 —— 但**必须专门写一条断言钉住**（`verify(spy).push(id, type, title, msg, null, null)`），
否则这条"可证"没有任何东西在守（第二十刀注入 E 实测：把写死的 `null` 换成 `0L`，
**除那一条断言以外全绿**）。

⚠️ 与上面那条不矛盾：两条问的是**同一个问题** —— 它有没有**具名消费方**。
有（掘金分类 / 标签 id：同域前端会传真实值）就留；没有（`planId` / `runId`：
连域内的调用点都只有 admin 那一个诊断端点，且它永远传 `null`）就砍。

**⚠️ "同名不同签名"只在**真的冲突**时才需要处理，不要为了对称去改没冲突的东西。**

端口方法要拿规范名、而域侧富方法同名同参只差返回值时，Java 不允许 ⇒ 富方法加
`ForView` 后缀（`getMaterialForView` / `updateStatusForView`）。
但**先实测调用方再决定**：

```bash
grep -rn "\.openQuiz(\|\.recordQuiz(\|\.listQuizzes(" apps/core-host/src --include=*.java \
  | grep -v "<服务类>.java"
```

- 调用方**只有**要抽的工具（+ 服务自己的测试）⇒ **直接换返回类型**，不保留富方法
  （先例：`KnowledgeQuizService.openQuiz` / `recordQuiz`）。
- 调用方**还有**控制器或别的域 ⇒ 保留富方法并加 `ForView` 后缀。
- 域侧**另有**同名的重载（如 `openQuiz(Long, Long)`）而端口只要另一个签名
  ⇒ **不用改名**，端口不收它即可。**冲突 ≠ 同名**，只有"同名同参"才冲突。

## API 与异常契约

- 所有错误响应：`{ "code", "message", "data" }`；code 必须与真实 HTTP 语义一致（400/401/403/404/5xx 不被 HTTP 200 包装）。
- 登录缺失/过期/无效：真实 HTTP 401 + `{ "code": 401, "message": "未登录或登录已过期", "data": null }`。
- 服务层抛 `ResponseStatusException`（明确 4xx）或 `IllegalArgumentException`；控制器不写业务逻辑；`GlobalExceptionHandler` 统一转换。
- 禁止用 `catch (Exception)` 返回空对象/通用成功；有意的降级要记录日志 + 归一化原因。
- 定时/后台长任务返回任务/运行 ID，不把远端完成前的时间挂在请求上。
- 新增私有接口：必须在 `AuthInterceptor` 补齐鉴权并同步测试。

### ⚠️ 新增一个 HTTP 路由要动**三份**登记，少一份门禁就红

2026-09-17 加 `GET /health/ready` 时实测：

| 文件 | 谁维护 | 不动会怎样 |
|---|---|---|
| `docs/route-inventory.yaml` | **`scan.py` 生成** | `route-inventory-drift` FAIL（提示 `re-run scripts/migration/scan.py`） |
| `docs/api-migration.yaml` | **`scan.py` 生成**（合并时保留人工字段） | `api-migration-key-coverage` FAIL |
| `docs/auth-policy.yaml` | **手工** | `auth-policy-coverage` FAIL |

`auth-policy-coverage` 的判据值得记：**每条 GET 路由都必须被一条显式规则命中**，
因为 v1 拦截器对未匹配的 GET **fail-open**。而 `rule_matches` 里 `path:` 是
**精确相等**（`rule_match["path"] == path`），所以 `/health` 这条规则**不会**覆盖
`/health/ready` —— 必须各写一条。写新规则时优先用精确 `path` 而不是 `path_pattern`：
`/health/*` 会把将来任何新增的 `/health/...` 路由顺带公开。

顺带：改任何 Java 文件的行号都会让 `scheduled-ownership.yaml` 的 `scan_ref` 失效，
所以跑 `scan.py` 是常规动作，不是特例。

## 存活探针 vs 就绪探针（V11 §27.1）

- **存活与就绪必须是两个端点。** `GET /health` 是存活，`docker-compose.yml` 的
  healthcheck 正在用它；把"未就绪即 503"塞进去，一次正常启动会被判为不健康并**反复重启**。
  就绪另开一个端点（本项目是 `GET /health/ready`）。
- **端口监听 ≠ 可以接流量。** 内嵌 Servlet 容器在 Spring 容器 refresh **期间**就开始监听，
  而 `ApplicationRunner` 在 refresh **之后**才跑。所以"某个初始化完成前不接流量"
  **只能**由负载均衡侧的就绪探针保证，不能靠端口是否打开来判断，也不能靠
  `@PostConstruct` 的时序。
- 需要"接流量前必须完成"的初始化，用 `implements ApplicationRunner`：此时全部 bean 就绪、
  事务代理可用。**不要**用 `@PostConstruct`（bean 创建期，与其它 bean 的依赖顺序脆弱）。
- 初始化失败的默认选择是 **fail-fast（抛异常让进程退出）**，而不是"起来但标记未就绪"：
  后者只在就绪探针真的接线了才等价于 fail-closed。取舍要写在类注释里，并登记部署侧待办。

## SQL 与迁移

- 每张表、每一列都要有**中文 `COMMENT`**，解释枚举/外键/单位/归属/生命周期，不重复字段名。
- 新建表：写 baseline `db/*_schema.sql`（`CREATE TABLE IF NOT EXISTS`）；
- 存量表任何改动：`db/*_migration.sql`（线上需手工执行），并保持 baseline 同步；
- 禁止用建表语句悄悄更新存量表、禁止启动时代码自动改表。
- 归属过滤、任务扫描、幂等键、外键、稳定分页都要加索引。
- UTC 存调度时刻，展示边界才转用户时区。
- **`apps/core-host/src/main/resources/db/` 下已有的脚本是归档，不许改。** 它们不会被重放
  （Flyway 从线上 schema baseline），所以改了**不会让任何构建或测试失败**，只会让"生产库
  当初执行了什么"与仓库记录静默分叉。凭据是 `migrations/legacy/SHA256SUMS`，
  由 `ci_gates.py flyway-validation` 校验。改了要跑 `audit_legacy_sql.py` 重新生成清单。
- 新增迁移放 `migrations/versioned/V<版本>__<描述>.sql`，版本号不得重复（Flyway 启动时才会报）。

## 事务 / 并发 / 幂等 / 调度

- 事务声明在应用服务用例边界；纯 DB 读用 `readOnly = true`。
- AI/CSDN/OSS/SMTP/MCP 等网络调用必须在事务之外；先落意图、再调远端、结果单独事务持久化。
- 可重试变更要有幂等键（DB 唯一约束或原子 Redis）；状态机用条件更新（含期望前态），0 行更新 = 竞争或已完成。
- 状态/阶段定义成常量或枚举并文档化转移，禁止散落字符串字面量。
- 调度器：轮询原子认领、有界批量、多实例不重复；单条失败不中断整批。
- **按游标增量拉取的游标，其"下界"必须偏小、不能偏大。** 2026-09-17 做启动
  Bootstrap 时用到的推理：`account_security_state.change_seq` 由单行序列表
  `UPDATE ... SET seq = LAST_INSERT_ID(seq + 1)` 分配，该语句对那一行加**排他锁并持有到
  事务提交** ⇒ **分配顺序 = 提交顺序**。所以"快照读到的最大 `change_seq`"是一个安全的
  下界：快照之后提交的变更必然大于它。反过来取"表里/序列里的最大值"就可能在
  "已分配未提交"的窗口里跳过一条变更 —— 而下游会**安静地**收敛到"没有变化"。
  推广：任何"先快照再增量"的恢复逻辑，都要能说清下界为什么不会跳过在途事务。
- **快照与水位必须来自同一次读。** 两次读之间会落进"两次读之间提交的变更"。
  最稳的强制方式是**只发一条 SELECT**（本项目把水位取为快照行的最大 `change_seq`，
  偏小但绝不会偏大），这样"同一一致性点"由**构造**保证，与隔离级别、
  是否读副本都无关；退而求其次才是在一个显式 REPEATABLE READ 事务里读两次。

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
- **"删掉它不会有别的症状"的约束必须有专门测试。** 判断方法：把那段代码删掉，
  问"除了某个测试之外还有别的东西会红吗"。若没有，就**必须**有一个测试专门钉它。
  本项目实例（2026-09-17）：`SecurityStateReconciler` 的两个 `@Scheduled` 方法上的
  `if (!readiness.isReady()) return;` —— 删掉它，全量 1214 个用例里只有
  `SecurityStateReconcilerTest` 的 2 个会红（`*DoesNothingBeforeThePlaneIsReady`），
  而这条约束是 V11 §27.1 的硬性要求。写测试前先做一次"假设删掉"的推演，能直接
  告诉你该写什么。
- **状态上不可观测的判据，用 spy 钉"有没有调用写入方法"。** 有些判据的两种分支
  产生**完全相同**的状态（例：版本闸门 `cached >= incoming` 里的同版本重放，
  写不写都是同一个版本号），靠断言状态永远区分不出来。做法是把被测的协作对象换成
  `spy(realInstance)`，断言 `verify(cache, never()).markDisabled(id, version)` ——
  spy 委托真实对象，行为不变，但调用记录可观测。
- **注入验证之后要核对"变红的正好是预期的那几个"**，不是"有红的就行"。
  本项目两次注入的期望值与实测：去掉 reconciler 闸门 ⇒ 2 红（两个
  `*DoesNothingBeforeThePlaneIsReady`）；去掉版本闸门 ⇒ 3 红（`SecurityStateApplierTest` 2 +
  `SecurityStateBootstrapTest` 1）。数量或名字对不上，说明测试之间串了或判据写歪了。
  ⚠️ **对不上时先怀疑测试本身。** 2026-09-17 给 `NotificationPort` 注入一个**无参方法**，
  预期 1 红，实测 1 FAIL + **1 ERROR** —— 那个 ERROR 是测试自己的缺陷（见下条）。
  注入验证的价值有一半在于它会顺带照出测试的毛病。

### ⚠️⚠️ 契约用 `null` 表示失败时，测试里**必须显式 stub 成 `null`**

**Mockito 对未 stub 的返回值给的是"包装类型的零值"，不是 `null`。**
Mockito 5.7.0 源码 `org.mockito.internal.util.Primitives`：

```java
PRIMITIVE_OR_WRAPPER_DEFAULT_VALUES.put(Long.class, 0L);
PRIMITIVE_OR_WRAPPER_DEFAULT_VALUES.put(Integer.class, 0);
PRIMITIVE_OR_WRAPPER_DEFAULT_VALUES.put(Boolean.class, false);
// String / 自定义对象 → null
```

所以当契约写成 `Long resolveUserId(String token)` 且**用 `null` 表示"答不上来"**时：

```java
// ❌ 没有这条 stub 时，resolveUserId(任何未匹配的实参) 返回 0L —— 一个"合法"的用户 id
when(accessTokenLookupPort.resolveUserId("web-token")).thenReturn(null);
// 于是被测代码里的 `if (userId == null) throw ...` 一次都不会触发，测试却照常绿。
```

**2026-09-18 实测（`McpOAuthServiceTest`）**：注入"消费方忘了剥掉 `Bearer ` 前缀"之后，
实参从 `"web-token"` 变成 `"Bearer web-token"` ⇒ 窄 stub 失配 ⇒ mock 返回 `0L` ⇒
`null` 守卫静默失效 ⇒ 两处红。**这正是"守卫从未执行过、而门禁全绿"那一族缺陷。**

判据：
- 契约方法**用 `null` 表达失败**（返回 `Long` / `Integer` / `Boolean` / 对象）时，
  每个相关用例都要有**显式**的 `thenReturn(null)`（或 `thenReturn(<对象>)`）；
- **不要**靠"没写 stub 所以是 null"—— 对包装类型这是错的；
- 断言守卫**真的生效**时，别只断言"抛了异常"，把**错误码/状态**也断言上
  （例：`assertEquals(401, error.status())`）—— 状态码也是协议的一部分。

### ⚠️ 断言"查询条件里带上了某个值"时，必须先调一次 `getSqlSegment()`

MyBatis-Plus 的 `LambdaQueryWrapper` / `LambdaUpdateWrapper` 是**惰性**收集参数的：
`eq(...)` / `set(...)` 只把参数存成一个待求值的 lambda，**真正的 `formatParam` 要等 SQL 片段
被渲染时才跑**。而 mapper 是 mock，永远不会渲染 ⇒ 直接读 `getParamNameValuePairs()`
拿到的是**空 Map**，断言会以"参数不同"的形式失败，看起来像业务逻辑错了。

```java
// ❌ 拿到空 Map，必然红（2026-09-18 在 McpOAuthServiceTest 上实测）
verify(grantMapper).selectList(argThat(w ->
        w.getParamNameValuePairs().containsValue(777L)));

// ✅ 先渲染一次，再读 Map —— 仓里既有 8 处同类断言都是这个形状
ArgumentCaptor<LambdaQueryWrapper<McpOAuthGrant>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
verify(grantMapper).selectList(captor.capture());
captor.getValue().getSqlSegment();   // ⚠️ 这一行是必须的，调用它有副作用（填充参数表）
assertTrue(captor.getValue().getParamNameValuePairs().containsValue(777L));
```

先例：`KnowledgeIngestionServiceTest`、`KnowledgeQuizServiceTest`、`Img2ThreeServiceTest`、
`RecitationWorkerTest`。**这类断言的用途**：钉住"用的是端口/方法返回的那个值，
而不是写死的常量"—— 只断言"调用发生了"抓不到写死常量。

### ⚠️ 用反射钉契约形状时：按**名字**取方法，不要按下标

```java
// ❌ 顺序一变就静默换检查对象。getDeclaredMethods() 的顺序 JVM 并不保证。
Method m = SomePort.class.getDeclaredMethods()[0];

// ❌ 退一步"按名字取第一个"也**不够** —— 端口上一旦出现重载，它同样静默换检查对象。
Method m = null;
for (Method each : SomePort.class.getDeclaredMethods()) {
    if (each.getName().equals("notifyResource")) m = each;   // 取的是"最后一个"，同样没断言个数
}

// ✅ 先数一遍、断言**恰好一个**、再返回
private static Method methodNamed(String name) {
    Method found = null;
    int count = 0;
    for (Method each : SomePort.class.getDeclaredMethods()) {
        if (each.isSynthetic() || !name.equals(each.getName())) continue;
        found = each;
        count++;
    }
    assertEquals(1, count,
            "端口上应当恰好有一个方法叫 " + name + "，实际 " + count
                    + " 个。重载会让按名字取方法的断言悄悄检查错对象。");
    return found;
}
```

2026-09-17 实测：给 `NotificationPort` 加一个方法后，`getDeclaredMethods()[0]` 变成了新方法，
那条"返回值必须是 `Long`"的断言就开始检查**另一个方法** —— 它变红只是**碰巧**（新方法返回
`String`）。如果新方法也返回 `Long`，它会继续绿着而检查对象早已换人。
同款写法在 `contracts/core-api/UserLookupPortTest` 里也有一处（已一并修）。
**同文件里"只有一个方法"那条断言救不了它** —— 两条断言是独立的。

⚠️ **2026-09-20 补：光"按名字取"还不够，要断言"恰好一个"。**
端口上出现**重载**时，"取第一个（或最后一个）"会**按 JVM 的方法顺序**挑一个，
后面的断言开始检查另一个方法 —— 而它们仍然**通过**。**没有失败信息，因为根本没有失败。**
这类失败无法从"红了的测试"里发现，只能靠"这条断言本身会不会红"来发现。

⚠️ **改造时先数一遍，不要照清单做。** 当时 ADR 里写着"三处仍是旧写法"，
实测是**七处**：`EmailReminderPortTest` **根本没有这个写法**（多写了），
而 `blogagent-api` 两个 / `recitation-api` 两个 / `integration-api/MailPortTest`
（收齐了同名方法却 `.findFirst()`）**四处没列**。
⇒ **凡文档里写"某处/几处还有 X"，动手前先 `grep` 数一遍** ——
清单通常是**推**出来的，不是**数**出来的（这个动作连续两刀都栽过）。

### ⚠️ 读 `getParameterTypes()[n]` / `getParameters()[n]` 之前，先查参数个数

```java
for (Method method : Port.class.getDeclaredMethods()) {
    // 无参方法直接读 getParameterTypes()[0] 会抛 ArrayIndexOutOfBounds，
    // 报出来的是"下标越界"，而不是"这个端口方法没收调用方 id"。
    assertTrue(method.getParameterCount() > 0,
            method.getName() + " 没有参数：端口方法必须显式收调用方 id。");
    assertEquals(Long.class, method.getParameterTypes()[0], ...);
}
```

**断言失败的信息必须指向真正的问题**，否则下次有人看到"下标越界"会去查数组而不是查契约。
`automation-api` 的三个端口测试都补了这个守卫（2026-09-17）。

⚠️ **这条规则对"定点断言"同样适用，而定点断言才是容易漏的那种**（2026-09-20 实测：
`grep` 命中 28 处读数，13 处已守卫、**11 处没守卫**，分布在 5 个测试方法 / 4 个文件里）：

```java
// ❌ 参数表缩短 ⇒ ArrayIndexOutOfBoundsException，报的是"下标越界"
assertEquals(ContentPlanDraft.class, methodNamed("create").getParameterTypes()[1]);

// ✅ 守卫要**恰好够宽**，而且必须排在读数**之前**
Method create = methodNamed("create");        // 顺带存局部变量：连写两次会让
assertTrue(create.getParameterCount() > 1,    // "恰好一个同名方法"那条断言跑两遍
        "create 的参数表变了：下面读的是第 2 个参数（幂等键 ContentPlanDraft），"
                + "现在只有 " + create.getParameterCount() + " 个。");
assertEquals(ContentPlanDraft.class, create.getParameterTypes()[1]);
```

**两条容易做错的判据：**

1. **用下界 `> n`，不用精确值 `== n`。** 守卫的职责只是"让失败信息指到真正的原因"，
   而"下标越界"只在**参数表比读数需要的短**时出现 ⇒ 下界就是恰好够宽的那条。
   精确值会**多出一条主张**（"参数表恰好 n 个"—— 同一文件里的逐字签名清单已经有了），
   还会对"末尾追加参数"产生**假阳性**：给 `openQuiz` 追加第 4 个参数，第 3 个仍然是会话 id，
   那条断言的主张仍然成立，它却会红。
   ⇒ 与"fail-soft 的 `try` 只包一条语句"是同一条原则：**守卫的范围恰好和它要保护的东西一样宽。**
   （⚠️ 唯一的例外是**移位**：既有断言原来就是精确值，移动它时**文本逐字保留**，
   顺手改性质会让这次改动不再是可证明的纯加固。）

2. **守卫必须排在读数之前。** 判据**在**、但排在症状后面，**等于没有** ——
   参数表缩短时先抛 `ArrayIndexOutOfBoundsException`，那条写好的消息根本轮不到说。

⚠️ **"顺序"这个主张用注入测不出来**（只把守卫的绑定值抬高一点看它变红是**无效**的）：
"守卫通过 ⇒ 读数必然在范围内"是**逻辑蕴含**，任何只改守卫的注入都分不开两者。
**要换掉被测方法本身**，让它变成参数更少的方法，再分别跑两种顺序：

| 注入 | 期望 |
|---|---|
| `methodNamed("runStream")` → `methodNamed("publish")`（两参），**守卫在前** | 具名消息，**无越界** |
| 同上，**守卫挪回读数之后** | `ArrayIndexOutOfBoundsException`，**守卫那句话一个字都没出现** |

2026-09-20 实测：前者 `Failures: 1` + 「runStream 是四参形式…`expected: <4> but was: <2>`」，
后者 `Errors: 1` + `Index 2 out of bounds for length 2`。

### ⚠️ 断言"第一个参数是调用方 id"时，**类型**不够，要断言**参数名**

```java
// ❌ 这个断言对 publish(Long taskId, Long userId) 同样成立 —— 而那个形状正是它要禁止的
assertEquals(Long.class, method.getParameterTypes()[0]);

// ✅ 参数名进了 class 文件（本项目 javac 开了 -parameters）
assertEquals("userId", method.getParameters()[0].getName(),
        "第一个参数必须叫 userId：只断言\"类型是 Long\"不够，"
        + "publish(Long taskId, Long userId) 同样满足那个断言。");
```

**这是"判据只朝一个方向看"的第三次实例**（前两次：`SHARED_KERNEL` 的 `util` 死条目、
`deployment-unit-coupling` 判据 2 的根成了单元素集合）。三次的共同点：

> **判据断言的是一个弱化后的性质（"是 `Long`" / "有包" / "有成员"），
> 而它的名字声称的是强性质（"是调用方 id" / "有反向条目" / "会变红"）。**

⚠️ 这类缺陷**注入验证发现不了**。注入只能证明"已写的判据会红"，
证明不了"判据的强度够不够"。要靠**反着用一次**：把判据拿去断言一个它本该拒绝的形状
（这里是 `publish(Long taskId, Long userId)`），看它是否真的红。

⚠️ 代价与自证：这条守卫依赖 `-parameters`。把它从编译配置里删掉后
`getParameters()[0].getName()` 退化成 `arg0` ⇒ 守卫**变红**（而不是静默放过），
所以是一个会自己喊出来的依赖，可以接受。

```bash
# 确认 -parameters 生效
javap -v -p contracts/<x>-api/target/classes/com/xander/lab/<x>/spi/<X>Port.class | grep MethodParameters
```

### ⚠️ "端口收不收 `userId`"必须是一对守卫：正面一条，反面一条

只写正面那条（"第一个参数是调用方 id"）时，**"所有端口都收 `userId`"是一个
没人反对就会漂移的一致性论点** —— 下一位读者看到别的端口都收，就会给新端口也加一个。

而那个参数可能**没有任何一步会去读它**。`BlogAgentImagePort` 就是这种端口：
提供方是一次**无状态的模型调用**（不落库、不看 `UserContext`、不分片），
真正需要身份的两步在**别的端口**上，且都显式收了：

```java
mediaAssetPort.recordGenerated(userId, ...);   // 存素材
billingPort.chargeTokens(userId, ...);         // 扣积分
```

⚠️ **加一个没人读的参数不是"一致性"，而是把"这里需要身份"这个错误信号写进契约。**
它会误导下一个实现者去猜这个参数该做什么，而契约里没有任何东西能纠正他。

所以反面守卫是必需的：

```java
// ContractShape
static void assertNoParameterIsNamed(Class<?> type, String name) {
    for (Method method : type.getDeclaredMethods()) {
        if (method.isSynthetic()) continue;
        for (Parameter parameter : method.getParameters()) {
            assertFalse(name.equals(parameter.getName()), ...);
        }
    }
}

// BlogAgentImagePortTest
@Test void takesNoCallerIdAndThatIsDeliberate() {
    assertNoParameterIsNamed(BlogAgentImagePort.class, "userId");
}
```

**判据**：每新增一个端口，都要显式回答"收不收身份"，并把答案写成守卫 ——
收就正面断言参数名，不收就反面断言不存在。**没有第三种状态。**

⚠️ 验证方式与正面守卫相同：注入一个 `default void track(Long userId)`，
期望**这条反面守卫变红**（2026-09-18 实测红了 3 处，其中一处正是它）。

### ⚠️ 消费方不得读提供方的 `@ConfigurationProperties`

端口如果只回最少的载荷（比如只回 `byte[]`），消费方可能还得知道 `model` / `size`
才能把元信息写进自己的记录 —— 而它唯一的来源是提供方的 `@ConfigurationProperties`。

⚠️ **一旦消费方去读提供方的配置，提供方的"配置"就变成了跨域契约。**
配置可以改名、可以给默认值、可以整段不存在（网关路径下 `blog.agent` 就不配），契约不行。
而且这会让 `blog.agent.*` 的任何一个键变成**事实上的公共 API**。

```java
// ❌ 消费方 import 提供方的配置类
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
String model = properties.getImageModel();

// ✅ 提供方的适配器把"这次实际用了什么"填进返回值
return new BlogAgentImage(generated.bytes(), ..., generated.model(), properties.getImageSize());
```

**判据**：`grep -rn "ConfigurationProperties" <consumer-package>` 在消费方一侧应为空。
**默认做法是让提供方把它填进返回值**，而不是让消费方去读配置。

⚠️ 同族陷阱：**提供方报的值与消费方配置的值同名时，必须明确谁是真相。**
`ImageGenerationTool` 曾把素材 `meta.model` 记成 `properties.getImageModel()`（配置里的**首选**模型），
而客户端会在多个候选端点间**回退重试** —— 兜底端点生成的图被记成首选模型的名字，
网关路径下配置值还可能是空白。真相永远是**提供方报的那个**（只有它知道这次实际发生了什么）。

### ⚠️ 借"配置"不是借"能力"：先判它是**契约问题**还是**运维问题**

上面那条讲的是"抽了端口之后，消费方不要绕回去读配置"。反过来还有一类边，
**它从一开始就不是能力调用，而是"另一个域的一个值"**（凭证 / 端点 / 开关 / 限额）：

```java
private final Img2ThreeProperties properties;          // 本域，prefix = img2three
private final BlogAgentProperties blogAgentProperties; // ❌ 另一个域的配置类

private String resolveApiKey() { return hasText(properties.getApiKey()) ? properties.getApiKey()
                                                                        : blogAgentProperties.getApiKey(); }
```

⚠️ **这类边不要抽端口。** 它要的是"同一个值在两个服务里都要有"，不是"一个服务向另一个服务要东西"。
为"借凭证"立一个端口，等于把"两个域共用一套上游凭证"**合法化** —— 而配置清单里
（`docs/config-ownership.yaml`）它们往往记成**两家各自的** `service_private` / `secret`。
真正的解法是**搬到配置层**（嵌套占位符），拆服务后由运维 / Nacos 承担：

```yaml
img2three:
  agent:
    api-key: ${IMG2THREE_AGENT_API_KEY:${blog.agent.api-key:}}   # ✅ 回落的是**属性名**
    # api-key: ${IMG2THREE_AGENT_API_KEY:${BLOG_AGENT_API_KEY:}} # ❌ 环境变量名，本地部署回落不到
```

⚠️ **写属性名不是环境变量名** —— 这条**写错了不会报错**：profile 文件（如 `application-local.yml`）
是把值直接写在 `blog.agent.api-key` 上的，写环境变量名 ⇒ 那份配置**回落不到**，
而没有任何东西会报错。必须有测试钉住（用 `YamlPropertySourceLoader` 加载**真实交付的**
`application.yml` + `PropertySourcesPropertyResolver` 解析，再叠一个模拟的 profile 源）。

**判据**：一条跨域边问自己 —— 它在"**借能力**"还是"**借配置**"？
借能力 ⇒ 抽端口（`contracts/`）；借配置 ⇒ 搬进 YAML 占位符（**零 Java 契约改动**）。

⚠️ **代价：可测性会丢。** 直接 `new XxxProperties()` 不经过任何 YAML ⇒ "回落真的存在"在单测里
**无法表达**。⇒ **守卫必须跟着一起搬**（同"一份实体两份投影必须配跨投影断言"）：
搬到哪里，就在哪里补一条读**真实配置文件**的测试，否则搬完就没人看了。

⚠️ **顺带检查这个盲区**：扫描器数的是 Java import ⇒ 一行 import 换成一行 YAML 占位符之后，
指标如实下降，而**耦合只是换了地方**（`cross-domain-imports.yaml` 里一个字都看不到）。
`config-ownership.yaml` 是唯一可能承载这类引用的地方，但它今天只记 `owner` / `classification` /
`nacos_group`，**不记"这个键的默认值引用了哪个键"**。

### ⚠️ 提供方替消费方"渲染" ⇒ 提供方依赖消费方：先问"这段逻辑留在这里，提供方要知道什么"

第三类跨域边，既不是"借能力"也不是"借配置"，而是**提供方把消费方的业务逻辑焊进了自己的方法里**。
2026-09-18 第十八刀的样本：

```java
// integration.mail.MailService —— 提供方
public void sendReminderMail(String to, String subject, String userMessage,
                             Instant scheduledAt, String contentType, String templateId)
```

六个参数里只有 `to` / `subject` 是**投递**需要的；后四个**只服务于渲染**。而渲染需要：

- `automation.domain.EmailReminderMailTemplates` —— 另一个域的**业务类型**（ADR-2 禁止）；
- `email-reminder.display-zone` —— `config-ownership.yaml` 里 **owner 记在
  `content-automation` 名下**的键。⭐ **台账是对的，代码是错的**：读它的人与拥有它的人不是同一个。

⇒ 这闭成一个**真环**（automation 调 `MailService`，`MailService` 回头调 automation 渲染）。

**判据**：

> **"这封提醒长什么样"** 是消费方的业务概念 ⇒ 渲染留在消费方；
> **"把它送出去"** 才是提供方的能力 ⇒ 端口只收**渲染好的成品**。

```java
void sendHtmlMail(String to, String subject, String plainText, String html);  // ✅ 两份成品
```

⚠️ **这类边不要抽"把原参数表搬过去"的端口** —— 那只是把违规换个地方。
`sendReminderMail` 的六个参数**一个都不该上端口**：端口上出现 `scheduledAt` / `contentType` /
`templateId`，提供方就要重新 import 业务域的模板类、重新读别人家的配置键，**反向边原样复活**。

⚠️ 落地时会发现"这不是加个端口就行，是改 API"：调用方要自己渲染。
**先查它有没有测试** —— 第十八刀的 `MailService` **一个测试类都没有**（它没实现任何契约，
方法体只在消费方的 mock 里出现过），那段渲染逻辑也**零断言**（调度器的测试把邮件服务整个 mock 掉了）。
抽端口会让原本不重要的东西变重要，而"没人写那条断言"不会有任何东西报错。

⚠️ 顺带一条：**两处行为细节必须在搬家时逐字保留** —— ①`ZoneId.of` 原本就在 HTML 分支
**里面**（所以时区配错不影响纯文本提醒）；②选了"不要版式"时纯文本兜底里**不带时间与时区**。
前者决定新函数收 `String displayZoneId` 而不是 `ZoneId`（收 `ZoneId` 会把解析提到调用点）。

### ⚠️ 断言"某段文本出现在 HTML 正文里"不能按原文子串判

邮件 / 报表这类**逐位渲染**的正文会把连续串打散。2026-09-18 第十八刀实测：
`LoginVerificationMailTemplates.buildDigitCells` 把验证码的**每一位**放进自己的 `<span>`，
于是 HTML 原文里**根本不含**连续子串 `246810`，`contains("246810")` 断言会红 ——
**红得对**，红的是断言而不是代码。

```java
String collapsed = html.replaceAll("<[^>]*>", "").replaceAll("\\s+", "");
assertTrue(collapsed.contains("246810"));   // ✅ 剥掉标签与空白后判
```

**判据**：凡对 HTML 正文做"包含"断言，先问一句"它是不是被拆开渲染的"。
⚠️ 纯文本那一份仍按**连续子串**判 —— 两份正文的判法**本来就不同**，不要"统一"掉。

### ⚠️ "归一参数顺序"会产生静默换位点：新重载的参数表可能与旧方法逐字相同

端口把提供方的 `(taskId, userId)` 归一成 `(userId, taskId)` 时，
如果提供方**本来就有**一个参数表逐字相同的重载，那么所有原来转发到旧方法的调用点
会**静默解析到新方法**，两个 `Long` 直接换位 —— 编译器一个字都不说。

```java
runStream(Long taskId, Long userId, BiConsumer<String,String> onEvent)                    // 同域
runStream(Long userId, Long taskId, BiConsumer<String,String> onEvent, BooleanSupplier)   // 端口

// 旧写法：静默解析到端口方法，两个 Long 换位
return runStream(taskId, userId, onEvent, () -> false);
// ✅ 必须显式点名
return runStreamForView(taskId, userId, onEvent, () -> false);
```

**判据**：改完参数顺序后，`grep` 出所有转发到旧方法的调用点，逐个确认解析目标；
每一处都要有测试（`verify(service, never()).runStream(any(), any(), any(), any())`
这类反向断言最直接）。与"相邻同类型参数"（§5.6）、`TokenUsage` 换位（§4.6）同族：
**两个同类型参数挨在一起时，编译器永远帮不上忙。**

### ⚠️ 守卫的失败信息必须指向真正的原因（第三次踩，已成规则）

第一版"两个 `Long` 换位"的测试写成"用具体字面量打桩，写反了自然匹配不上"，
结果症状是 `null` 一路传到断言里变成 **NPE** —— 报的是"某处为 null"，不是"两个 Long 换位了"。

```java
// ✅ 在 thenAnswer 里断言实收的两个值，失败信息就是 expected: 9L but was: 42L
when(port.publish(anyLong(), anyLong())).thenAnswer(inv -> {
    assertEquals(USER_ID, inv.getArgument(0), "第一个参数必须是 userId");
    assertEquals(TASK_ID, inv.getArgument(1), "第二个参数必须是 taskId");
    return view;
});
```

⚠️ 同时注意**分工**：用 `any(), any()` 打桩的"逐字段落点"测试**抓不到换位**，
换位只能由上面这条专门的测试抓。**"有一个测试覆盖了这个方法"不等于
"这个方法的所有性质都被覆盖了"。**

⚠️ **第三次踩：只能"间接断言"时，错因会伪装成外部故障。**
`Img2ThreeModelClient.endpoints()` 是私有方法 ⇒ 测试只能通过"请求落到了哪个地址"间接断言 baseUrl。
把 baseUrl 写错之后，用例**不是断言失败**，而是**真去请求了公网**、24 秒后以网络错误结束
（`Errors: 1` 而非 `Failures: 1`）。**红是红了，但红的原因看起来像"网络问题"** ⇒ 下次很容易被
当成 flaky 忽略。改法二选一：把那段逻辑抽成**可直接调用**的小函数，或给客户端注入固定的短超时。
⇒ **判据：一条测试的失败信息里，出现"网络 / 超时 / IO"这类字眼时，先问一句
"它真的在测外部依赖吗"，还是"我在间接断言某个本地值"。**

### ⚠️ 静默换位点的**第三类**：同形不同源（接收者写反）

前两类是"同名同参的重载"（上面那条）与"归一参数顺序"。第三类是
**两个不同提供方（不同平台、不同服务）的对象，方法名与签名逐字相同** ——
于是把接收者写反**编译得过**：

```java
// CsdnCredentialPort 与 JuejinCredentialPort 都声明 boolean isAuthorized(Long userId)
if (!csdnCredentialPort.isAuthorized(plan.getUserId()))   // ← 写成 juejinCredentialPort，编译器没有意见
```

编译通过、耦合指标一动不动、门禁全绿，症状是"CSDN 发布前检查了掘金的授权"，
而两边都返回 `boolean`，**类型层面没有任何信号**。

⚠️ **关键区分：同为"一个平台一个端口"，参数表不同与参数表相同，代价完全不同。**

| | 参数表**不同**（如 `*BlogSyncPort.sync`） | 参数表**逐字相同**（如 `*CredentialPort.isAuthorized`） |
|---|---|---|
| 实现类 | 只加一句 `implements`（零适配器） | 只加一句 `implements`（零适配器） |
| "接收者写反" | **编译失败**（方法解析不到） | **编译通过** |

⇒ **"零适配器"这个收益要看清它同时放弃了什么。** 端口方法表与实现逐字相同时，
你得到的是"实现类不用改"，代价是"调用点写错也没人管"。
**修法只有一条：不对称桩的行为测试**（见下一条）。

⚠️ **但"参数表不同"要按**方法**问，不能按**端口**问。** 第二十二刀实测：
`CsdnBlogSyncPort` / `JuejinBlogSyncPort` 上 `sync` 的参数表不同（2 vs 4）⇒ 写反编译不过，
**而 `findSucceeded(Long, Long) -> BlogSyncResult` 在两个端口上逐字相同** ⇒
那一个方法写反**编译通过**。一个端口"部分同形"就够了。
⇒ 判据：把两个端口的方法**逐个对签名**，得出**可互换的方法集**并钉住它
（`BlogSyncPortInterchangeabilityTest`，**双向** —— 变大是新静默换位点，变小说明提示文本该删）。
⚠️ 而且"参数表恰好不同"是**副产品**，不是设计出来的保护 ——
谁给掘金砍掉一个参数，那道编译期保护就没了。

⚠️ **"可互换的方法集"这件事本身要有守卫，因为"有没有调用方同时持有两者"要**数**不能**推**。**
ADR 待办 54 原来的结论是"没有调用点会写反"，理由是从**注入类型**推出来的
（一个消费方只注入掘金端口、另一个"同理"）。实测：`PublishPostTool` **同时持有两个端口**，
两个 `findSucceeded` 调用**相邻**（131 / 135 行）、参数表逐字相同。
⇒ 坑一直存在，只是没有东西会报错。

**可复用手法**：把"这个坑存不存在"写成一条**会自己去找**的判据
（扫类路径找出同时持有两者的类 / 扫方法表算出可互换集合），
然后**在还没修的状态下先跑一次**。它红了 ⇒ 你相信的那句话是错的，而且证据被直接点名。
本刀那条守卫在基线还是**空**的时候第一次跑就打印出 `[PublishPostTool]` ——
**先写判据、再用判据去测自己相信的话**，代价十几分钟。

⚠️ **既有用例可能挡得住写反，但症状指不到错因。** 实测：既有那条红的是
`NoSuchElementException: No value present` —— 读起来像环境故障（"没查到值"），
不像"接收者写反"，很容易被当 flaky 忽略。
⇒ 不对称桩那条**还要加一句 `verify(other, never())`**：写反时先红的是它，
失败信息直接说"这个 mock 从未被调用"。
⚠️ 同一个写反，另一个方向可能**完全没有用例**（实测：掘金分支的 `reconcile` 一条都没有）
⇒ 两个方向各写一条不是保险，是必需。

### ⚠️ 桩取的值分不开两个被测对象时，对该性质的覆盖是**零**

```java
// ❌ 这条覆盖了 publishJuejin 的授权分支，但**抓不到接收者写反**：
//    csdnCredentialPort 没被打桩 ⇒ Mockito 默认 false ⇒ 写反后仍走"未授权"分支，断言照样过
when(juejinCredentialPort.isAuthorized(7L)).thenReturn(false);

// ✅ 不对称桩：一个平台授权、另一个不授权，写反必红
when(csdnCredentialPort.isAuthorized(7L)).thenReturn(true);
when(juejinCredentialPort.isAuthorized(7L)).thenReturn(false);
```

⇒ **凡有两个"同形"的协作者，它们的桩必须取不同的值**；两个桩取同一个值的用例
一律抓不到"选错对象"。两个方向各写一条（A 真 B 假、A 假 B 真）更稳。

⚠️ **"某个地方有测试挡着" ≠ "这个错误形状被守住了"。** 实测过同一个写反：
消费方甲红 **3** 条（其中 2 条是既有用例**碰巧**挡住的 —— 它们给一个协作者打桩 `true`、
另一个默认 `false`，正好把两者分开），消费方乙只红新加的 **1** 条（那里真的没人守）。
⇒ 别拿"另一个地方有测试"当这个形状被守住的证据。

⚠️ **注入验证与"判据强度"的边界要说清**（这条此前写得太宽，已收窄）：

| | 注入能不能发现 | 例子 |
|---|---|---|
| **位置**问题 —— 某个具体地方没人守 | **能**（注入落在那里就红） | `toVO` 的派生字段没人断言 |
| **判据**问题 —— 判据的**形状**不够强 | **不能**，只能**反着用一次** | 只断言参数**类型**不断言**参数名** |

⇒ 一条判据写完要问两个问题：**①它守的位置有人管吗？②它的形状够强吗？**
①靠注入，②靠"拿判据去断言它本该拒绝的形状"。

### ⚠️ 契约里的布尔查询返回**原语** `boolean`，不要装箱

`isAuthorized` 这类"是 / 否"查询，契约上**没有"不知道"这个状态**：没有凭证就是 `false`。
返回 `Boolean` / `Optional<Boolean>` 会把一个不存在的状态写进契约，
而调用方两处都写 `if (!port.isAuthorized(id))` —— 一旦为 `null`，
这一行**自动拆箱直接 NPE**，而不是走"未授权"分支。跨进程之后"字段没填"比同进程时更常见。
由反射断言 `assertEquals(boolean.class, method.getReturnType())` 钉住。

### ⚠️ fail-soft 还是 fail-closed：判据是**语义角色**，不是名字

一个"查一下"的调用失败了（库不可用 / 提供方不可达），该吞还是该抛？
**不要按"它叫什么"决定，按它在这条路径里承担什么决定。** 逐条问四件事：

| 它参与了吗 | 结论 |
|---|---|
| 鉴权（`userId == null` → 401） | 参与 ⇒ **fail-closed** |
| 租户隔离（`requireOwned*` / `ownedWrapper(userId)` → 404） | 参与 ⇒ **fail-closed** |
| 执行身份判定（这次操作以谁的身份跑） | 参与 ⇒ **fail-closed** |
| 决定是否允许操作（真正的门在服务端哪一层） | 参与 ⇒ **fail-closed** |
| **以上都不参与（只填展示字段）** | **fail-soft**：降级 + 留信号，请求照常成功 |

⚠️ **"它叫凭证 / 它是安全相关的"不是判据。** 同一个 `isAuthorized` 端口，
在 `BlogContentPlanService.toVO` 里只填两个展示字段（⇒ fail-soft），
在 `BlogPlanRunService` 里决定"是否跳过同步"（⇒ 执行决策，另说）。
**换一条路径就要重新判一次。**

四条实现规矩：

1. **容错的范围必须与"外部调用失败"这个语义严格等宽。**
   最省事也最危险的是把整个方法体包进 `try` —— 那会让"记录不存在"变成一个静默的成功。
   做法：**单语句 try**，那一句就是外部调用本身。范围由**形状**保证，不由自觉保证。
   ⚠️ 另一个同样危险的形状是**把两个提供方的调用合进同一个 try**：
   第一个失败会让第二个的结果一起降级，而"两个都是 false / 都是空"看起来完全正常。
   **一个提供方一个 try，一个包装一个方法。**
2. **捕 `RuntimeException`，不要捕具体类型。** 契约不许点名框架异常（ADR-1），
   而"查询机制失败抛什么"取决于实现：今天是 MyBatis 的 `DataAccessException`，
   拆服务后是 HTTP 客户端的异常。**捕具体类型 ⇒ 这条 fail-soft 会在拆服务那天静默失效**，
   而那正是它存在的理由。
3. **降级方向要想清楚**：fail-soft **不能**变成 fail-open。查不到授权 ≠ 已授权，
   降级值取"最保守的那个"（通常是 `false` / 空集）。
4. **降级路径必须有信号，否则是 fail-silent。**
   一条永远不报错的降级路径与"绿着撒谎的判据"同族。
   ⚠️ 但**不要为了留信号加一个没有出口的计数器**（`LongAdder` 字段 + 无人读的 getter）：
   它看起来像可观测性，实际上没有任何人读得到。**先看仓里有没有指标基础设施**
   （本仓没有 Micrometer / Actuator，指标是 Wave 2 的事）——
   没有就把**日志标记取成将来那条指标的名字、逐字相同**，并把 tag 字段一起打进去。
   接入 Micrometer 那天是"加一个同名 Counter"，不是"再想一个名字"。

⚠️ **顺带算一笔常被漏掉的账**：`@Transactional` 方法里抛出的异常会**回滚已经写成功的变更**。
所以"一个展示字段的查询失败"的后果不止"返回 500" —— 它能让一次已经落库的状态变更**回退**。
评估这类缝时要把这一档算进去。

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

⚠️ **`boot/plexus-classworlds-*.jar` 的版本必须与 maven 版本配套**（3.9.6 → 2.7.0，
3.9.9 → 2.8.0）。机器上两个 maven 都在（`/d/soft/apache-maven-3.9.6`、`/c/apache-maven-3.9.9`），
**混搭 `-classpath` 与 `-Dmaven.home` 会报 classworlds 相关的怪错**。先 `ls <maven>/boot/` 确认。

⚠️ **`-Dtest=X` 会让整个反应堆失败。** 没有匹配测试的模块会报
`No tests matching pattern ... were executed!` 并把 BUILD 打成 FAILURE ——
看起来像"你的测试没跑起来"，其实是别的模块在抗议。必须同时加：

```bash
... Launcher -o -Dtest='A,B' -Dsurefire.failIfNoSpecifiedTests=false test
```

⚠️ **`-pl <module>` 必须配 `-am`。** 否则兄弟模块的 SNAPSHOT jar 不在本地仓库里，
报 `Could not find artifact com.xander:xander-model:jar:0.0.1-SNAPSHOT`：

```bash
... Launcher -o -pl apps/core-host -am -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false test
```

⚠️ **注入验证要单独跑被测模块，不能在反应堆里跑。** 反应堆在**第一个失败模块处直接中断**，
后面的模块根本不跑。所以"注入只影响那一个测试类"这句话，只能靠
`-pl contracts/content-api test`（不带 `-am` 也行，依赖已在本地仓库）单独证明；
在反应堆里跑只能证明"没有别的模块先炸"。

迁移相关的机器门禁（清单一致性 / 依赖方向 / 配置归属等）另有一套，改动后也要跑：

```bash
python scripts/migration/ci_gates.py
```

**改了任何 POM 或新增模块时，先跑门禁再跑 Maven。** `xml-authoring-safety` 与
`dockerfile-module-coverage` 覆盖的失败都是"反应堆在编译任何类之前整体失败"，
跑一次门禁几秒钟就能拿到精确到行号的报错，而 `mvn` 要先走完一轮启动。

### ⚠️ 在 `@Scheduled` 方法**上方**动行数（加**或删**），都会让清单失效

`docs/scheduled-inventory.yaml` 记录每个 `@Scheduled` 的**行号**。2026-09-17：
在 `Img2ThreeService.create` 里加了约 16 行 ⇒ `@Scheduled` 从第 90 行挪到 106 行 ⇒
门禁 `scheduler-lock-scope-consistency` FAIL：

```
stale scheduled-inventory.yaml for ['img2three-service'] -- re-run scripts/migration/scan.py
```

⚠️ **2026-09-20 补充：删行同样会红，而且"只改注释"也躲不掉。**
`SecurityStateReconciler.java` 只是**删掉一行 javadoc**，文件里两个 `@Scheduled`
就从 96 / 147 变成 95 / 146，门禁照红。⇒ **不要以为"没动代码"就不用重生成清单。**

修法：`python scripts/migration/scan.py`（同时刷新 `scheduled-ownership.yaml` 里的
`scan_ref` 指针）。**注意它会一并重写 `docs/cross-domain-imports.yaml`** ——
那个文件同时是**基线**，所以扫描完**必须看 diff**，别当成纯噪声提交。
反过来也有用：这一条 FAIL 恰好证明门禁在守，不是恒真。

⇒ 通用规则：**凡是仓库里存在"按行号索引生成物"的清单，改完源文件就要重生成**，
然后**重跑门禁**再宣布完成。顺序是「改源码 → `scan.py` → `ci_gates.py`」。

### ⚠️ 不要用 `**/target/surefire-reports` 数测试

仓库根有一个**单模块时代的残留 `target/`**（148 个 `TEST-*.xml`，含已搬走的
`identity.security.JwtUtilTest` / `UserContextTest` 等旧包名）。任何
`glob("**/target/surefire-reports/TEST-*.xml")` 都会把 `apps/core-host/target/`
和根 `target/` **一起数进去**，虚增约 1074，而结果看起来完全正常。

正确做法二选一：按 mtime 过滤到本次运行，或者直接读 maven 输出里的 `Results:` 行
（**逐模块打印，总数要相加**）。同理，`apps/core-host/target/` 里也可能留着**改名前的**
旧包测试报告（如 `identity.security.*` → `auth.core.*`），数出来会比实际多。

### 门禁本身也要验证：注入法

新增或修改一条门禁，**必须**证明它真的会拦。方法是注入：
造出违规 → 跑门禁观察 FAIL（且失败信息指向你造的那处）→ 还原 → 观察 PASS。
只写不验的门禁和没有门禁一样 —— 它可能恒真，也可能判据写反了还一路 PASS。
若某条判据在**当前代码状态下无法触发**（例如"不得有新单元加入"需要第六个单元才成立），
就临时造出那个前提（把某个域临时指到新单元），验完再还原 —— 不要因为"现在触发不了"
就跳过验证，也不要删掉这条前瞻护栏。

**⚠️ 注入验证的「变红数目」是一个测量结果 —— 测法错了会给假分数，而假分数看起来完全正常。**
2026-09-18 第十六刀实测两条：

1. **`-fae` 救不了「依赖方」。** 注入同时落在 `contracts/integration-api` 与
   `apps/core-host` 时，契约模块在反应堆里排在**前面** ⇒ 契约侧一红 maven 就中断，
   `core-host` 的测试**一个都没跑**，记下的红数是**下界**。`mvn -fae test`（fail at end）
   **仍然不够**：它只续跑"**不依赖**失败模块"的模块，而 `core-host` 恰恰依赖
   `integration-api`，照样被整块跳过。
   **修法**：把 `-Dtest` 收窄到**依赖方自己的测试类**，让契约模块**一个测试都匹配不到**
   （因此永远不会红）。⚠️ 此时契约模块连 `Tests run` 行都不打印 ⇒
   **"没有汇总行"必须判成"0 个测试"，不是"编译失败"**（第一版脚本把这两件事混在一起，
   报了个假的 `-1`）。实测：一条注入在契约侧红 4 个，补测后 `core-host` 侧还有 7 个。
2. **`sha1sum` ≠ `git hash-object`。** 前者对**原始字节**算，后者对 `blob <len>\0` + 内容算
   且**先做 CRLF→LF 归一** ⇒ 同一个文件两个值（实测 `60a4fe6b…` / `5c7408d4…`），
   会把**一个字节都没改**的文件误判成"内容不对了"，白查一轮。
   **还原判据要写清用的是哪一个算法，且全程只用同一个**（本仓统一用 `git hash-object`）。
   这一条比"还原锚点不唯一"更隐蔽：**它不会报错，只会让你去查一个不存在的问题。**

⚠️ 另：**注入脚本不要自己起 `bash`。** 沙箱里 `subprocess("bash", ...)` 里的 `bash`
解析成了 WSL 的 `bash.exe`，被程序黑名单拦掉、脚本以 `EXIT=1` **静默退出**（日志只有前半段，
看起来像"跑完了"）。让脚本只负责**改文件 / 还原文件**，`mvn` 由外层 shell 跑。

**⚠️ 更阴的一种：判据的「根」是一个会变的量，于是它悄悄空转了。**
`deployment-unit-coupling` 判据 2 原本取 `component_of(WAVE2_UNITS[0])`，
而 `WAVE2_UNITS[0]` 正是 `dinqor-agent`。`automation-api` 那一刀把最后一条指向 agent
的实现边消掉之后，agent 只剩出边、**脱离**了强连通分量：

```text
component_of(dinqor-agent)              = ['dinqor-agent']              ← 单元素
component_of(dinqor-content-automation) = ['dinqor-content-automation',
                                           'dinqor-core-host',
                                           'dinqor-platform-integration']
```

于是"没人指向它的单元"成了判据的根 —— 它的分量**永远只含自己**，`joined` 恒为空，
**这条判据再也不会红**。新的单元加入真正的分量会被静默放过。

实测（构造 `dinqor-newbie ↔ dinqor-platform-integration`，两套判据各跑一次）：

```text
OLD  joined = []                -> PASS      ← 空转
NEW  joined = ['dinqor-newbie'] -> FAIL      ← 抓到了
```

> **教训：判据的根不能是一个会变的量。** 写"取第一个"时隐含假设了"第一个一定在分量里"，
> 而这个假设没有被任何东西守住。与 `shared-kernel-classification` 缺反向判据
> （`util` 是死条目）同源：**两条都是"判据只朝一个方向看，另一个方向没人看"。**

顺带两条同类的 pass 消息缺陷，一起修了：
- `detail` 里打印的是 `len(WAVE2_UNITS)` 这个**常量**，不是实测分量大小 ——
  会把"agent 已脱离"这个**进度**盖掉。改成实测，并新增"哪些单元已可单独抽取"的提示。
- 失败消息原本指向 `components[WAVE2_UNITS[0]]`，而那个根可能已经是单元素，
  会把读者引到一个和新增边无关的单元上。改成指向**真正获得了新成员**的那个分量。

**⚠️ 同一门禁里的「互依对基线」必须双向检查：新出现的要拦，消失的也要拦。**
`deployment-unit-coupling` 的 `MUTUAL_UNIT_PAIRS` 是手工维护的**有向对**集合。
2026-09-17 第十刀把 `automation → recitation`（content-automation 指向 core-host 的**最后一条**边）
消掉之后，那对不再互依 —— 门禁主动报
`1 pair(s) no longer mutual, delete both directions`。

> **这是双向判据的价值，而且正好是 §"判据的根"那处的反面。**
> 同一个门禁里，判据 2 是**单**向的（只看"有没有新成员加入"，于是空转），
> `MUTUAL_UNIT_PAIRS` 是**双**向的（多了要报、少了也要报），所以它救了自己。
> **过期条目会让图看起来比实际更乱** —— 于是"还剩几对互依"这个进度数字会失真。

⚠️ 删条目时**两个方向都要删**（`(A,B)` 与 `(B,A)` 各一条），否则剩下的那条会继续被当成互依。

⚠️ 还原后**不要**用"原始字节 sha256 相等"证明还原成功：本仓库 `core.autocrlf=true`，
`git checkout -- <file>` 会把 LF 重写成 CRLF，字节数会变（实测 `scan.py` 差 1347 字节
= 正好 1347 行，看起来像丢了内容，其实 `git diff` 是空的）。正确判据是 **blob 级相等**：

```bash
P=path/to/File.java
[ "$(git hash-object "$P")" = "$(git rev-parse HEAD:$P)" ] && echo RESTORED
```

**⚠️ 期望的"变红处数"必须实测，不能从上一刀继承。** 两次踩：
`recitation` 测验刀按直觉估 `3/1/2/1`、实测 `2/2/1/3/1`；
`blogagent-api` 图片刀按直觉估"`model`/`size` 对调只红 1 处"、实测红 **6** 处 ——
因为换算测试、两条专门测试、三条消费方测试**都读这两个字段**。
注入前先写下期望值，跑完**如实记录实测值并解释差异**；不要为了让数字对上而改判据。

**⚠️ 注入脚本自己会失败，而且失败时可能把注入留在工作区。** 2026-09-18 实测：
脚本的 revert 段引用了一个未定义的变量（函数参数名是 `$to`/`$from`，revert 里写成 `$dst`/`$src`），
`set -u` 让脚本在**跑完测试之后、还原之前**直接退出 —— 于是注入 A 的
`default boolean isDisabled()` 留在源码里，差一点被当成正式代码提交。

```bash
# 脚本尾部必须自检，且失败时人工兜底
git status --short          # 出现你没打算改的文件 ⇒ 注入没还原
tail -8 <被注入的文件>       # 直接看注入是否还在
```

**判据**：注入脚本的**最后一步**是"对每个被注入文件做 blob 级比对并打印 OK"；
脚本一旦中途退出（`set -u` / 非零退出），**必须手动查工作区再继续** ——
`git add -A` 之前先看 `git status --short`。

**⚠️ 第二次踩（同族、不同因）：还原用的锚点也必须唯一，不只是正向锚点。**
2026-09-18 把"实现永远返回 null"注入成 `            return null;`，
还原时拿**这同一行**去反向替换 —— 而 `AuthService` 里有**三处** `return null;`，
`count(src) == 1` 的断言直接失败，于是**还原没执行**。更阴的是脚本接着跑下一条注入：
它的正向锚点 `return Long.valueOf(...)` 已经被上一条改掉了，`count` 变 0，
断言再次失败 ⇒ **那条注入根本没生效**，而它的"结果"其实是上一条的结果。
最终 blob 比对报 `FAIL: 还原不完整`，工作区里留着一条注入。

```bash
# ❌ 锚点太短：return null; 在文件里有多处
patch "$SERVICE" '            return null;' '            return Long.valueOf(...);'
# ✅ 锚点取"整个方法体块"（含 validateAccessToken(accessToken) 这种只此一处的调用）
patch "$SERVICE" '        if (!validateAccessToken(accessToken)) {
            return null;
        }
        try {
            return Long.valueOf(jwtUtil.getSubject(accessToken));
        } catch (RuntimeException exception) {'  '<注入后的同一整块>'
```

**判据**：每条注入**在动手前**先用脚本数一遍正向锚点与还原锚点的出现次数，
两个都必须是 1；**每条注入跑完立刻做一次 blob 级还原校验**，不要等脚本收尾统一查 ——
否则一条还原失败会**污染后面所有注入的结果**（它们的"变红"来自前一条的残留）。
这也说明"六次注入各红了几处"这张表，只有在**每条都独立还原成功**时才成立。

**⚠️ 第三次踩（同族、不同因）："删除式注入"的还原锚点必然是正向锚点的子串。**
2026-09-18 把"丢掉 `null` 守卫"建模成**删掉一行**，于是
正向锚点 = `resolve 行 + 守卫行`、还原锚点 = `resolve 行` ——
而 `resolve 行` 本来就在文件里（它是正向锚点的一部分）⇒
"还原锚点在注入前出现 0 次"这条前置断言立刻失败，脚本**主动中止**（断言生效了，
但规则需要细化）。两条路：

```python
# ① 给每条注入声明"还原锚点在注入前的期望次数"（普通注入 0、删除式注入 1），
#    再用**施加后**的计数消歧：施加后必须 fwd==0 且 mut==1，
#    否则说明还原锚点在别处也出现，替换会打到错误的位置。
INJECTIONS = [(name, fwd, mut, mut_before), ...]
if src.count(fwd) != 1: abort()
if src.count(mut) != mut_before: abort()
write(src.replace(fwd, mut, 1))
if applied.count(fwd) != 0 or applied.count(mut) != 1: abort()   # ← 消歧点

# ② 或者改用**非子串**的变异：把 `userId == null` 改成 `authorization == null`
#    （"守错了变量"），语义等价但两个锚点互不为子串，规则不用放宽。
```

**⚠️ 注入脚本用 Python 写时：`mvn.sh` 是 shell 脚本，Windows 的 `CreateProcess`
不能直接 exec 它**（`OSError: [WinError 193] %1 不是有效的 Win32 应用程序`）。
必须在脚本里**内联 `mvn.sh` 里那条完整 java 命令行**（`-classpath plexus-classworlds` +
`-Dclassworlds.conf` + `-Dmaven.home` + `-Dmaven.multiModuleProjectDirectory` + 主类），
并注明"内容需与 `mvn.sh` 保持同步"。Python 版的好处是可以把前置/后置断言写成
`return` 早退，而不是 bash 的 `set -e` + 事后人工检查。

⚠️ `mvn` 的控制台在 UTF-8 管道里是 GBK 乱码（`ÊÇ default ·½·¨`），
但**测试名与 ASCII 断言值可读**（`expected: <size> but was: <model>`），
足够给每次注入归因，不要因为乱码就放弃归因。

**⚠️ 第四次踩（同族、不同因）：注入锚点必须对"行尾已归一"的文本匹配。**
本仓工作区 **CRLF / LF 混杂**（2026-09-18 实测同一刀的两个目标文件：
`apps/core-host/src/main/resources/application.yml` 是 **LF**、
`.../studio/infrastructure/Img2ThreeModelClient.java` 是 **CRLF**）。
锚点通常以 `\n` 结尾写 ⇒ 在 CRLF 文件里 `…));\r\n` **不含**子串 `…));\n`，
`text.count(anchor)` 得 **0**，脚本报"锚点出现 0 次，期望 1 次"并中止 ——
**看起来像"锚点写错了"，实际是行尾不匹配。**

```python
def read_exact(path):
    data = path.read_bytes()
    crlf = b"\r\n" in data
    return data, (b"\r\n" if crlf else b"\n")

def write_exact(path, text, eol):
    path.write_bytes(text.encode("utf-8").replace(b"\r\n", b"\n").replace(b"\n", eol))
# 读时归一成 \n 再匹配；写回时按**记录下来的原行尾**还原，保持文件逐字节不变
```

⚠️ **顺带一条读数坑**：**Git Bash 里 `grep -c $'\r'` 判 CRLF 不可信** —— 对纯 LF 文件也会报
满行数（实测 `application.yml` 是 LF，却报 CRLF=284）。要**用 Python 数 `b"\r\n"` 的字节数**：

```python
d = pathlib.Path(p).read_bytes()
print(d.count(b"\r\n"), d.count(b"\n") - d.count(b"\r\n"))   # CRLF 数 / 裸 LF 数
```

**⚠️ 第五次踩：注入脚本不要在前台跑 —— 被 SIGTERM 会把目标文件留在注入态。**
六条注入约 **4.5 分钟**，超过默认前台超时 ⇒ 前台跑会在中途被 SIGTERM，
而脚本**恰好在"已注入、未还原"之间被杀**，目标文件留在**注入态**（实测残留了其中一条）。

**规矩**：
1. 注入脚本一律 `run_in_background: true`。
2. **任何中断（超时、手动 kill、报错）之后，第一件事是比 blob**，不是重跑：
   `git hash-object <file>` 与基线逐字节比（⚠️ 不要用 `sha1sum`，见下）。
3. 残留的注入要**用脚本自己的 restore 分支**还原（只有它知道归一后的锚点），然后**再比一次 blob**。
4. 至此"脚本跑完了 ≠ 注入生效了、也还原了"已有**六个变体**：①还原锚点不唯一（污染其后所有注入）；
   ②脚本自身 bug 导致回滚段不执行；③脚本自起 `bash` 被黑名单拦掉、静默退出；
   ④锚点行尾不匹配、静默 0 命中；⑤脚本被 SIGTERM、留在注入态；
   ⑥**"交换两个接收者"这类注入会把目标文本在文件里复制成两份** ⇒
   还原锚点在注入后出现 **2** 次，脚本主动中止（第二十二刀，与待办 48 同族）。
   ⚠️ 修法：锚点**带上一行上下文**（例如 `if ("csdn".equals(platform)) {`），
   而不是只取那一行调用本身。
5. **脚本写完先空跑一遍 `apply` + `restore` 并比 blob，再跑 mvn。**
   七条注入的空跑只要几秒，能把所有锚点问题一次性挡在 mvn 之前（第二十二刀的做法）。

**⚠️ `sha1sum` ≠ `git hash-object`（同一文件两个值）。** 前者对**原始字节**算；
后者对 `blob <len>\0` + 内容算，且**先做 CRLF→LF 归一**。2026-09-18 实测同一个文件两个值
（`60a4fe6b…` / `5c7408d4…`）⇒ 会把**没被改过**的文件误判成"内容不对了"，白查一轮。
**还原判据必须写清用哪个算法，且全程只用同一个**（本仓统一 `git hash-object`）。
这条比"还原锚点不唯一"更隐蔽：**它不会报错，只会让你去查一个不存在的问题**。

**⚠️ 注入验证要**专门测 pass 路径**，不能只测 FAIL 路径。**
只证明"能拦"是不够的：门禁的**通过消息**也可能一直在说谎，而它不影响判定，
所以会一直藏在"全绿"里 —— 但人读到的东西决定了下一步做什么。

2026-09-16 实测一处：`cross-domain-import-baseline` 的通过消息把**基线文件**里的总数
和**实时扫描**的跨单元数拼在一句话里：

```python
# 错的：两个数来自两个不同时刻
detail = f"{recomputed} implementation statements across {len(base_edges)} edges " \
         f"({live['counts']['cross_deployment_unit_statements']} cross a deployment unit)"
# 打印出 "98 implementation statements ... (64 cross a deployment unit)"，
# 而当时真实状态是 90/64 —— 这句话不对应任何一个真实时刻，
# 而且它永远显示不出"缩减"，可缩减正是这条门禁 docstring 说要追踪的东西。
```

**规矩：门禁的 pass 消息里所有数字必须取自同一时刻。**
要对比就显式写成两个字段（`X; baseline was Y`），不要在一个句子里混。
验证方法同上：临时把基线改成一个"看起来缩了"的值，跑一次看 pass 消息是否如实。

**⚠️ 注入验证要区分"门禁该拦的"和"只有测试能拦的"，并且把后者明确记为门禁盲区。**
有些不变量的破坏**编译通过、指标不动、26 条门禁全绿** —— 它们只被反射测试守着。
2026-09-17 实测三例，结论不同：

| 注入 | 结果 | 说明 |
|---|---|---|
| 从 `SHARED_KERNEL` 删掉 `agent.spi` | **3 条门禁红** | 门禁真的在守这次抽取 |
| `AgentToolRegistry` 里把"先读版本、后取工具"调换 | **0 条门禁红**，1 个测试红 | 顺序错误**没有任何状态可见** |
| 给 `RemoteTool` 加一个 `default String unusedCapability()` | **0 条门禁红**，2 个测试红 | 端口变宽无编译错误、无指标变化 |

所以：**"注入后门禁没红"不等于"这个注入没意义"**，也可能是"这个不变量本来就不归门禁管"。
两种情况都要写进记录 —— 前者的证据是门禁名，后者的证据是**测试方法名 + 行号**。

### ⚠️ 注入必须让**目标断言**先红，否则红数分不清是谁在起作用

一条注入常常**同时**踩中不止一条判据。若"更早"的那条先红，
红数里就看不出目标判据到底有没有接着线 —— 那是一次**假测量**（与"通过消息失真"同族）。

实测（2026-09-20，第二十三刀 / 待办 57）：往端口上加一个**重载**，
既该踩中"按名字取方法要**恰好一个**"，也必然踩中"**完整签名清单**"那条断言
（端口多一个方法它一定红）。
⇒ 注入必须**两处一起改**：端口加重载 **+** 签名清单加上那个签名
（清单按**字典序**整体比对，见 `ContractShape.declaredSignatures` 的 `.sorted()`）。
这样清单断言绿着，唯一红的就是目标那条（实测 2 fail / 1 fail，报的都是具名断言）。

**规矩**：注入施加后先问一句"**除了我想验的那条，还有哪条会红？**"
处理方式二选一：①把别的判据**同步改到通过**；②把 `-Dtest` 收窄到只跑目标断言所在的类。
⚠️ 省掉这一步，得到的是"红了 N 条"这种**无法归因**的数字。
反过来说，凡是"只有测试能守"的不变量，测试**必须**存在且必须钉到**完整签名**
（见前面端口宽度那条），否则这个不变量实际上无人看守。

⚠️⭐ **实测补充（2026-09-20，待办 53 注入 D）：Mockito 的严格桩本身也是一条守卫，
而且它会让实测红数多于纸面预期。**
把"两次独立的调用"合并成"一次调用"这种变异，会让**第二个提供方的桩从来没被用到**
⇒ `MockitoExtension` 报 `UnnecessaryStubbingException`，红数比预期多 2 条。
⇒ 两条结论：
1. **不要看到红数多于预期就以为"注入打歪了"** —— 先逐条看红的是什么。
   本例多出来的两条红是**同一个根因的第二个症状**，而且报的错因（"这个桩没被用到"）
   恰好**指向根因**。
2. **严格桩是免费的守卫**：凡"某次调用必须发生 / 必须不发生"的性质，
   `@ExtendWith(MockitoExtension.class)` 默认就会替你守（桩没被用到 = 那次调用没发生）。
   ⇒ 写注入预期时把它算进去，别用 `lenient()` 把它关掉（关掉的理由必须写出来）。

### ⚠️ 注入脚本的前置断言必须按注入的**形状**分开写

「锚点必须唯一」「每条注入后立刻校验 blob」已经成规矩，但 2026-09-18 第十八刀又踩到一个变体：
**`fwd` 与 `mut` 会互为子串，而且方向不同。**

| 形状 | 例子 | `fwd` 与 `mut` 的关系 | 施加后 `n_fwd` | 单靠 `n_fwd == 1` 能发现"已施加"吗 |
|---|---|---|---|---|
| 换位式 | 两个相邻 `String` 互换 | 不相交 | 0 | ✅ 能 |
| 删除式 | 删掉一个早退分支 / 删掉 `implements` | `mut` ⊂ `fwd` | 0 | ✅ 能 |
| **插入式** | 在方法后面**追加**一个重载 / 追加一行 import | `fwd` ⊂ `mut` | **仍是 1** | ❌ **不能** |

⇒ 只写 `n_fwd == 1` 会漏掉"插入式已施加"；而补一条"`n_mut == 0`"又会在**删除式的还原方向**上
恒假（`mut` 是 `fwd` 的子串，还原时 `n_mut` 恒为 1），把一次正常还原判成"状态不明"。
**两种错法各踩过一次** —— 第十七刀是"删除式的还原锚点是正向锚点的子串"，
第十八刀是"插入式的正向锚点是还原锚点的子串"。

```python
src, dst = (fwd, mut) if mode == "apply" else (mut, fwd)
if text.count(src) != 1:
    abort("锚点不唯一")
if mode == "apply" and fwd in mut and text.count(dst) != 0:
    abort("插入式：目标态已存在 ⇒ 文件可能已是注入态")
# 换位式 / 删除式不需要第二条：施加后 src 计数变 0，第一条已经覆盖
```

⚠️ 另：**注入脚本不要在前台跑。** 第十八刀八条约 8.5 分钟，前台会被 SIGTERM
并把目标文件**留在注入态**（第十七刀实测残留 E）。必须后台跑；任何中断后第一件事是比 blob。

### ⚠️ 比 blob 之前先统一**算法**：同一次验证里混用两种哈希，会造出一个不存在的"还原失败"

2026-09-20 第二十刀踩到。注入脚本自己打印一个哈希、驱动脚本用 `git hash-object` 打印另一个，
**两条输出还挨着**，于是同一个文件、同一个时刻出现两个值：

```text
restore E OK  BlogPlanSseService.java  blob=1ae8d38b…   ← 脚本自己的 blob()
…
git hash-object BlogPlanSseService.java  →  8df1c761…   ← 驱动脚本
```

看起来像"还原没到位"，白查一轮。实测同一文件（12695 字节、267 个 CRLF、0 个裸 LF）三个值：

| 算法 | 值 |
|---|---|
| 原始字节 + `blob <len>\0` 头（脚本的 `blob()`） | `1ae8d38b…` |
| `sha1sum`（原始字节，**不带**头） | `87578e00…` |
| `git hash-object`（**先 CRLF→LF 归一**；本仓 `core.autocrlf=true`） | `8df1c761…` |

⇒ `1ae8d38b` **就是**还原后基线的哈希。**没有残留。**

**判据（写进脚本，别靠记性）**：脚本里的 `blob()` 必须先归一 CRLF 再哈希，
与 `git hash-object` 逐字等价：

```python
def blob(path):
    data = path.read_bytes().replace(b"\r\n", b"\n")   # core.autocrlf=true ⇒ 必须先归一
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()
```

⚠️ 这是"`sha1sum` ≠ `git hash-object`"那条（第十六刀）的**第三种形态**，也是最难发现的一种：
前两种是**两个外部工具**给出不同值（会让人去怀疑工具），这次是**同一个脚本的两条代码路径**
各自打印一个（看起来像同一个算法，因为它们写在同一个输出里）。

⚠️ **通用规则**：**"脚本打印了一个陌生的哈希" ≠ "文件被改坏了"。**
分辨它们的是 `git diff` —— 差异是否**逐行都是本次意图**；不是哈希比对。

### ⚠️ 注入脚本从 Python 起 Maven：四个会造假信号的坑

2026-09-20 第二十二刀踩到前两个，Wave 0B 第二十六刀（`xander-webmvc-support`）踩到后两个。
四个坑都在"脚本跑完了"和"结果可信"之间：

1. **别用 `subprocess.run(["bash", "mvn.sh", ...])`。** Windows 上 PATH 查找会先命中
   `C:\Windows\System32\bash.exe`（**WSL 启动器**），它在本机被安全策略拦掉 ⇒
   **拿到的是空输出**，于是"没有 `Failures: 0, Errors: 0`"被误判成**"变红"**。
   ⇒ 照抄 `mvn.sh` 里那条 java 命令行**内联**起 JVM（与 `mvn.sh` 保持同步），不要经过 shell。
2. **别从 stdout 抓断言消息。** Maven 往 Windows 控制台写的字节是 **GBK**，
   用 `encoding="utf-8", errors="replace"` 解出来中文全被抹掉 ⇒
   明明红的用例看起来"变红了但消息不对"。
   ⇒ 断言消息从 `contracts/<m>/target/surefire-reports/TEST-*.xml` 里读（那份是 UTF-8）。
3. **⚠️ `-pl <module>` 必须配 `-am`，否则依赖解析就失败。** 上游模块只在反应堆里、
   不在本地仓库（这个仓从来 `install` 过）⇒ `mvn -pl libs/xander-webmvc-support test`
   报 `Could not resolve dependencies`，**一次测试都没跑**，而脚本把
   "输出里没有 `Failures: 0`"读成了红。实测：D0–D3 四条全部报"变红: True"，
   而真相是一条都没跑。⇒ 加 `-am`。
4. **⚠️ 跑之前先删掉上一轮的 `surefire-reports/TEST-*.xml`。** 不删就会**读到旧 XML**
   并把上一次通过的用例名当成本次的证据 —— 上面那次假红里，脚本还打印出了
   "全部 33 条通过"的用例清单，**看起来像证据**。

```python
subprocess.run(
    ["C:/jdk-17.0.2/bin/java",
     "-classpath", "C:/apache-maven-3.9.9/boot/plexus-classworlds-2.8.0.jar",
     "-Dclassworlds.conf=C:/apache-maven-3.9.9/bin/m2.conf",
     "-Dmaven.home=C:/apache-maven-3.9.9",
     "-Dmaven.multiModuleProjectDirectory=D:/blog/xander-lab-backend",
     "org.codehaus.plexus.classworlds.launcher.Launcher",
     "-o", "-pl", module, "-am", "test",
     "-Dtest=" + test_class, "-DfailIfNoTests=false",
     "-Dsurefire.failIfNoSpecifiedTests=false"],
    capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=REPO)
```

⚠️ 四个坑的**共同形状**：**"红"这个信号本身是假的**（两个是没跑，一个是消息读不出来，
一个是读了别人的报告）。⇒ 注入脚本里"变红"**不能**作为唯一判据：

```python
# 1) 先确认这一轮真的跑了（有汇总行），再谈红不红
ran = re.search(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+)", out)
# 2) 失败的方法名从**新写的**报告里取；`<failure>` 与 `<error>` 都要算
failed = re.findall(r'<testcase name="([^"]+)"[^>]*>\s*<(?:failure|error)', out)
red = bool(failed) and ran and int(ran.group(2)) + int(ran.group(3)) > 0
```

⚠️ **必须配对照组**（不注入跑一遍，断言"真跑且全绿"）。对照组不成立时，
后面所有"变红"都没有意义。⚠️ 还要断言**失败的是哪一个方法** ——
"有东西红了"与"我想测的那条断言红了"是两件事（第二十五刀的 A5a/A5b 就靠这个分辨顺序）。

### ⚠️⭐ 判据做"全文子串匹配"时，**注释掉的那一行也算数** —— 会假绿

Wave 0B 第二十六刀实测：`dockerfile-module-coverage` 的第一条判据是

```python
if f"{module}/pom.xml" not in docker:      # docker = 整个 Dockerfile 的文本
```

把新模块那行 `COPY` **注释掉**之后判据仍然 **PASS**（报 "13 module(s) all COPYed"），
而**"注释掉一行"正是有人要禁用某行时会写的形状**。第二条判据虽然行首锚定
（`^COPY libs ./libs`），但它查的是**顶层目录**，`libs` 那行还在，所以新模块缺不缺
COPY 完全没人管。

**修法**：匹配前逐行丢掉注释行，两条判据都用同一份"活行"文本。

```python
docker = "\n".join(line for line in dockerfile.read_text(encoding="utf-8").splitlines()
                   if not line.lstrip().startswith("#"))
```

**通用规则**：凡"某段文本在不在文件里"的判据，动手前先问一句**"注释算不算？"**。
同族未修的还有两处（ADR 待办 63）：`gate_gateway_stream_proxy_config`
（读 YAML 的 `token not in text`）与 `stream-ban-boundary-coverage`
（`"ConnectionRegistry" not in text` 读 Java 文件）。

⚠️ **这一条是"反着用一次"抓到的，不是推出来的** —— 而且它属于"注入验证也发现不了"的
那一类里最坏的一种：**注入让它变红**（本该如此），只是**修之前它不会红**。
⇒ 凡是给门禁加/改判据，**必须拿判据去断言它本该拒绝的形状**，不能只看"干净树上 PASS"。


### ⚠️ 多行锚点：除了"唯一"，还要查"它是不是真的相邻"

第十九刀踩到：G 这条注入要把两个 import 一起换掉，于是锚点写成**相邻的两行**：

```python
fwd = ("import com.xander.lab.integration.api.CsdnCredentialPort;\n"
       "import com.xander.lab.integration.api.JuejinCredentialPort;\n")
```

但实际文件里这两行**中间夹着** `JuejinBlogSyncPort` ⇒ **锚点 0 命中**，
脚本按"锚点不唯一"中止（判据本身没错，是锚点写错了）。

⇒ **判据**：写多行锚点前，先 `Read` 那几行确认它们**逐行相邻**；
或者干脆拆成两条单行注入（本刀最终就是这么修的）。
"锚点必须唯一"这条规则要连**"它是不是真的存在于那个形状里"**一起查。

### ⚠️ "指标类注入"收尾必须无条件重跑 `scan.py`

第十九刀的 G 注入跑的是 `scan.py` + `ci_gates.py`（而不是 maven）。它会把
**`scan.py` 的产物**（`docs/cross-domain-imports.yaml` / `docs/migration-scan-report.md`）
改成注入态的数字。⚠️ 而那个产物**同时是 `cross-domain-import-baseline` 的基线** ——
⇒ **脚本收尾必须无条件重跑一次 `scan.py`**，否则仓库里留下一份与代码不符的产物，
而且**没有任何门禁会因此变红**（基线等于产物，两者一致）。
这与"判据的基线必须来自判据之外"是同一件事的两面：
**产物既是基线，就绝不能带着注入态落盘。**

### ⚠️ 门禁的"进度提示"也会只朝一个方向看 —— 图形状变了它就失真，而它仍然报 PASS

「判据只朝一个方向看」已记过三次（`util` 死条目、`deployment-unit-coupling` 判据 2 根取
`WAVE2_UNITS[0]`、端口首参只断言类型）。2026-09-18 第十八刀发现**第四种**：
这次出问题的不是判定，而是**通过消息里的那句话**。

```python
wave2_component = max(components.values(), key=len)          # 挑"那个分量"
outside = sorted(set(WAVE2_UNITS) - wave2_component)
detail = f"{len(wave2_component)} of {len(WAVE2_UNITS)} Wave 2 unit(s) in one component ..."
```

这段代码假设"存在一个非平凡分量"。当**所有分量都是单元素**时，`max` 会按迭代顺序任选一个，
于是打印 **"1 of 4 Wave 2 unit(s) in one component"** 并把另外 3 个列进 `outside` ——
读起来像"还有一个单元缠着"，而事实是**四个都可以单独抽取**。门禁照旧 PASS。

**判据**：任何形如 `max(...)` / `[0]` / `len(x) > 1` 的**摘要式**提示，都要问一句
"它假设了什么形状？这个假设还成立吗？"。真值改变了就**显式分支**，
不要让它继续用旧假设去总结新状态。

⚠️ 同族教训：**判据的失败信息要指向真正的原因**（前面那条），
这一条说的是**通过信息** —— 它不影响判定，所以永远不会有人因为"它红了"去修它。

### ⚠️ 判据的"基线"必须来自判据之外，否则它永远绿

**拿被测方自己的产物当基线，等于让它给自己打分。** 这与"门禁不复用生成方的哈希函数"
是同一条忌讳，也是"判据的根不能是一个会变的量"的同源问题。

2026-09-17 实测（`cross-domain-import-baseline`）：该门禁的 `baseline` 读的是
`docs/cross-domain-imports.yaml`，而 `scan.py` **会覆盖这个文件**。
于是按既定流程（**先 `scan.py` 再 `ci_gates.py`**）跑，基线恒等于实时值：

```python
baseline = load("cross-domain-imports.yaml")     # scan.py 的产物
live = scan.build_cross_domain_imports()          # 同一次扫描
```

实验：把一个常量放回 `BlogNotification` 实体、让 `knowledgebase` 重新 import 它 ⇒
扫描器如实报 **42 → 43** 条语句、边 **18 → 19**，而该门禁仍报 **PASS**。
它今天实际只检测"改了代码没重跑 `scan.py`"，不是它 docstring 声称的"任何边增长就 FAIL"。

**判据**：读一个门禁的实现，问"它的 baseline / 期望值从哪来？"
若答案是被测流程自己生成的，那这条门禁**不可作为证据**。
`capability-baseline.yaml` + `capability-baseline.lock` 是正例（人工维护，机器不覆盖）。

**当下怎么做**：这条门禁绿了**不能**当作"存量没长"的证据，
要**手工把 detail 行里的语句数与上一次比对**。

### ⚠️⭐ 归零之后，"= 0"本身成了头条成果 —— 于是必须给它一个判据

2026-09-20 第二十刀把跨单元实现边降到 **0**。此前它不是 0，任何人读
`docs/cross-domain-imports.yaml` 都能看到实际值；归零之后，**"0"成了这一轮重构的头条成果，
而它是无人看守的**：

| 门禁 | 新增一条**单向**跨单元实现边（A → B，无回边）时会怎样 |
|---|---|
| `deployment-unit-coupling` 判据 1 | 通过 —— 它只管 `bloghtml` 有没有边 |
| `deployment-unit-coupling` 判据 2 | 通过 —— 单向边**不会让任何单元进入强连通分量** |
| `deployment-unit-coupling` 判据 3 | 通过 —— 没有产生新的互依对 |
| `cross-domain-import-baseline` | 通过 —— 基线是 `scan.py` 的产物（上一条） |

**修法**（第二十一刀落地）：把期望值写成 `ci_gates.py` 里的一个**常量**，与
`MUTUAL_UNIT_PAIRS` 同一个模式（基线来自判据之外）：

```python
EXPECTED_CROSS_UNIT_IMPLEMENTATION_EDGES = 0
```

并加一条判据。三条设计约束：

1. **放在三条形状判据之后** —— 判据函数里每条失败都 `return` 早退，
   所以"它红"这件事**同时证明**三条形状判据都通过了（读消息的人能立刻知道是哪一类回归）。
   反过来，若放在最前，一条同时踩中 `bloghtml` 前置条件的边会丢掉那条更具体的提示。
2. **双向** —— 高于常量报"新增"，低于常量报"这是进展，请把常量改小"。
   0 处"低于"不可达，但常量以后被抬高时判据不会失真。
3. **不必再加一个常量管语句数** —— 边表是按单元对累加语句数并跳过 0 的，
   所以 `len(edges) == 0` ⟹ `sum(edges.values()) == 0`，可证等价。

⚠️ **这个常量在归零之前写不出来**（数字一直在动，写死就得每刀改一次）⇒ 是"归零"**解锁**的。

⭐⭐ **验证方式是直接对照，不是推演。** 同一个注入（消费方退回具体类，重新造出那条边），
只改常量值、各跑一次门禁：

| 常量 | 门禁 | detail |
|---|---|---|
| `1`（等价于没有这条判据） | **24 pass / 0 fail / 2 skip** | 仍印着 `1 cross-unit edge(s) / 1 statement(s) left` |
| `0`（判据在位） | **23 pass / 1 fail / 2 skip** | 唯一红的就是它 |

⇒ **第一行是这一条最值得记的读数：门禁全绿，而它自己的通过消息里写着那条回归。**
形状判据不是"漏了"，是**结构上看不见**。
⚠️ 改常量跑一次再改回来，比论证"没有它就会绿"更便宜也更硬 —— **能直接测就别推演**。

⚠️ **通用规则**：把某个指标推到 0（或推到某个整数）之后，先问一句
"**这个数是有人守的，还是只活在文档里？**" 一个**没有判据守着**的数
会在下一次有人加代码时悄悄变掉，而所有门禁照旧 PASS。
⚠️ **不要把基线写进产物文件**（`docs/cross-domain-imports.yaml` 是 `scan.py` 的产物）——
那等于把判据的基线又交回给被测物，与上一条是同一个错。

### ⚠️ `cross_deployment_unit_edges` 只数 impl > 0 的边，与列表长度不等

`docs/cross-domain-imports.yaml` 的 `counts` 是从**过滤后**的列表算的（`scan.py`）：

```python
implementation = [e for e in ordered if e["implementation_statements"]]   # 丢掉 impl=0 的边
crossing = [e for e in implementation if e["crosses_deployment_unit"]]
```

后果：**`edges` 列表里的跨单元条目数 ≠ `counts.cross_deployment_unit_edges`。**
2026-09-17 实测：列表 15 条，计数 14 —— 差的是一条 `api_statements: 1,
implementation_statements: 0` 的边（`agent -> publishing`，由 `PublishPostTool` 还
import 着 `publishing.api.BlogPostVO` 造成）。

所以**核对收益时不要数列表长度**，要按字段算：

```python
e = [x for x in d['edges'] if x['crosses_deployment_unit']]
print(len(e),                                      # 列表里的跨单元边
      sum(1 for x in e if x['implementation_statements'] > 0),   # 门禁报的那个数
      sum(x['implementation_statements'] for x in e))
```

一条边"归零"的判断也要分开看：`implementation_statements` 归零 ≠ 边消失。
`api_statements` 还可能挂着（那是"契约面"，按本仓库自己的判据不算待还的债）。

### 门禁校验「生成物清单」时：算法要与生成方一致，判据要查双向

适用于 `migrations/legacy/SHA256SUMS`、各种 `*.lock`、以及任何"由脚本生成、
用来证明某个东西没被改过"的清单。三条规矩：

**1. 哈希算法必须与生成方逐字节一致 —— 否则在 CRLF 检出上会报一片假失败。**

```python
path.read_text(encoding="utf-8", errors="replace").encode("utf-8")   # ✅ 走通用换行
path.read_bytes()                                                    # ❌ 磁盘原始字节
```

2026-09-17 实测：归档的 80 个脚本里 **30 个含 CRLF**，而这 30 个正好就是两种算法
结果不同的全部 —— 用 `read_bytes()` 会报 **30 个假失败**，看起来像"归档被大规模篡改"。
（和上面那条"还原后不要用原始字节 sha256"是同一个坑的两个方向。）

**2. 不要复用生成方的哈希函数，要独立实现。** 复用（`import audit_legacy_sql` 再调 `audit()`）
会让"生成方改了算法"这件事在清单侧和校验侧**同步变化**，门禁永远发现不了；
独立实现则会让它 FAIL，逼人显式重新生成清单 —— 那正是你想要的摩擦。

**3. 判据必须双向，只比哈希会漏掉"新增了一个文件"。**
`audit_legacy_sql.py` 是**手工运行**的，不会自己再跑一遍，所以新增归档脚本不会有任何症状。
三个方向都要查：

1. 清单里的每个文件都在磁盘上；
2. 磁盘上的每个同类文件都在清单里；
3. 逐个比对哈希。

**4. 注入验证要覆盖全部分支。** 一个四分支的判据至少要注入四次：

| 注入 | 期望 |
|---|---|
| 改一个被清单跟踪的文件的内容 | 哈希不符 |
| 新建一个清单外的同类文件 | 清单缺条目 |
| 移走一个被跟踪的文件 | 清单多条目 |
| 移走清单本身 | 清单缺失 |

⚠️ 注入时**挑一个带 CRLF 的文件改** —— 这样第 1 条同时验证了算法选择是对的，
否则它会先报一个假失败，把真正的信号淹掉。

**5. 合法修改归档内容时，重新生成清单，不要手改哈希。**
`python scripts/migration/audit_legacy_sql.py` 会同时重写 `AUDIT.md` 与 `SHA256SUMS`。

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