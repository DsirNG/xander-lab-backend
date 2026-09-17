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
| `agent` / `automation` / `studio` | **已被占用** —— 是这三个域自己的**前端 HTTP 层**（controller / VO / request body） | `agent.spi` / `automation.spi` / `studio.spi` |

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

### ⚠️ 在 `@Scheduled` 方法**上方**加行，会让清单失效

`docs/scheduled-inventory.yaml` 记录每个 `@Scheduled` 的**行号**。2026-09-17：
在 `Img2ThreeService.create` 里加了约 16 行 ⇒ `@Scheduled` 从第 90 行挪到 106 行 ⇒
门禁 `scheduler-lock-scope-consistency` FAIL：

```
stale scheduled-inventory.yaml for ['img2three-service'] -- re-run scripts/migration/scan.py
```

修法：`python scripts/migration/scan.py`（同时刷新 `scheduled-ownership.yaml` 里的
`scan_ref` 指针）。**注意它会一并重写 `docs/cross-domain-imports.yaml`** ——
那个文件同时是**基线**，所以扫描完**必须看 diff**，别当成纯噪声提交。
反过来也有用：这一条 FAIL 恰好证明门禁在守，不是恒真。

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

⚠️ 还原后**不要**用"原始字节 sha256 相等"证明还原成功：本仓库 `core.autocrlf=true`，
`git checkout -- <file>` 会把 LF 重写成 CRLF，字节数会变（实测 `scan.py` 差 1347 字节
= 正好 1347 行，看起来像丢了内容，其实 `git diff` 是空的）。正确判据是 **blob 级相等**：

```bash
P=path/to/File.java
[ "$(git hash-object "$P")" = "$(git rev-parse HEAD:$P)" ] && echo RESTORED
```

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
反过来说，凡是"只有测试能守"的不变量，测试**必须**存在且必须钉到**完整签名**
（见前面端口宽度那条），否则这个不变量实际上无人看守。

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