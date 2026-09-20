# 契约边界两条 ADR（V9 §11 / §18 / §19 / §91）

本文登记两条**已拍板**的架构决策。它们不是"某个模块怎么改"的实现说明，
而是**以后所有契约相关改动都要服从的约束**：新建 `*-api`、往契约里加类型、
给 DTO 加注解、跨域传对象时，先看这里。

对应 V9 章节：§11（契约冻结）、§18（monorepo 布局）、§19（禁止超级 common.jar）、
§81（跨域实现依赖存量）、§91（契约按能力命名）。

---

## 0. 一句话结论

```text
ADR-1  契约模块必须与序列化框架无关（Jackson 属于出入站适配器）
ADR-2  跨域通信必须走"消费方拥有的契约"；域实体不许跨有界上下文边界
```

两条都指向同一个失效模式：**契约模块慢慢长成"又一个框架模块"**，
于是"契约"不再是一份稳定的、谁都能依赖的词表，而是一坨需要跟着框架一起升级的代码。
那时候依赖方向会重新纠缠起来，而**没有任何门禁会报警**。

---

## 1. ADR-1：契约模块必须与序列化框架无关

### 1.1 决策原文

> **Contract modules MUST remain serialization-framework agnostic.
> Jackson belongs to inbound/outbound adapters.**

展开成可执行的四条：

1. 契约模块**不得依赖 Jackson**（`pom.xml` 里不许出现 `jackson-databind` /
   `jackson-core` / `jackson-annotations`）。
2. 契约类型上**不得有 Jackson 注解**（`@JsonProperty` / `@JsonCreator` /
   `@JsonIgnoreProperties` / `@JsonFormat` …）。
3. 契约模块**不得出现** `ObjectMapper`、`JsonNode`、`ObjectNode`、`JsonNodeFactory`。
4. **DTO 可以被 JSON 序列化**——这不是矛盾。DTO 是数据形状，谁来序列化它由
   出入站适配器决定；契约只承诺"字段叫什么、什么类型"。

### 1.2 为什么

反例链条很短，而且每一步看起来都合理：

```text
契约模块加 jackson-annotations（为了 @JsonProperty 改个字段名）
  → 加 spring-boot-starter-validation（为了 @NotNull）
  → 加 spring-web（为了 @Schema 生成文档）
  → 加 spring-boot-starter-data-jpa（为了复用实体）
  → *-api 变成"又一个框架模块"，任何想依赖契约的模块都被迫拖进整条链
```

到这一步，§19 想禁的"超级 common.jar"就换了个名字回来了。而且它比 `common.jar`
更糟：`common.jar` 至少名字可疑，`agent-api` 听起来**应该**是干净的。

### 1.3 唯一豁免：遗留的 `@JsonIgnoreProperties`

现存的 `@JsonIgnoreProperties(ignoreUnknown = true)` **如果是为了兼容历史序列化行为
而存在**，可以留在它原本的模块里，**但不得扩散进新的 `agent-api`**。

判断标准是"它是否在改变线上行为"：

- 留着它 → 老客户端多传字段时仍然不炸（**在改变行为**）→ 可以留，但要在注释里写明
  为什么留、什么时候能删；
- 想在新契约里再加一个 → 拒绝。新契约没有历史包袱，加它只是为了让"反序列化宽松"
  这件事看起来归契约管。

### 1.4 具体案例：`JsonNode metadata` 不许为了省事搬进契约

有一类字段长这样：

```java
private JsonNode metadata;   // 一个任意形状的 JSON 袋
```

把它搬进契约是**最省事**的做法，也是**最贵**的做法：契约模块立刻需要
`JsonNode`，于是需要 Jackson，ADR-1 当场破功。

正确做法二选一：

- **重构掉它**：如果这个袋子里其实一直是固定的几个键，就写成
  `record UsageMetadata(...)`，让形状显式化；
- **过渡用 `Map<String, Object>`**：形状确实不稳定时可以先这么过渡，
  但要在注释里标成过渡措施，并且**不允许**它成为新契约的默认写法。

### 1.5 当前状态

- `contracts/` 下 8 个模块（`core-api` / `content-api` / `integration-api` /
  `payment-api` / `agent-api` / `automation-api` / `recitation-api` / `blogagent-api`）
  目前**零 Jackson 依赖**，由门禁 `contract-dependency-boundary` 守着
  （8 个模块 / 58 个源文件，JDK-only import + 无 compile/runtime 依赖）。
  ⚠️ 源文件数会随每次扩契约增长（`blogagent-api` 两刀共加 6 个源文件；`core-api` 第十四刀加 1 个），
  所以**引用这个数时要写清是哪一刀之后**。这个数只算 `src/main`，不含测试。
- `agent-api` 是这条 ADR 最难的一次应用：要抽的端口属于 agent 的工具体系，而
  `AgentTool.parametersSchema()` 返回 `JsonNode`。**没有**放宽门禁，也没有搬 `AgentTool`，
  而是让 JSON 以文本出入端口（见 §6）。
- `automation-api` 是第二次撞上同一个问题：6 个工具要读 automation 的
  `EmailReminderTaskVO` / `BlogContentPlanVO`（本身是干净的 DTO，但它们住在
  `automation.api`，也就是**前端 HTTP 层**）。同样是**收窄出一份新的读模型**，
  而不是把前端类型搬进契约（见 §7）。
- `recitation-api` 是第三次：`CreateRecitationMaterialRequest` 带 `jakarta.validation` 注解，
  按 ADR-1 不能进契约，所以契约自带一份去掉注解的四字段入参（见 §9.6）。
- **第四次是最有意思的一次**：`KnowledgeQuizAttemptView.items` 的类型是 `Object`（一个**没有形状**
  的字段，因为它反序列化自 `items_json` 列，而那一列里可能有旧版本写下的任意内容）。
  契约**没法**解析它 —— 因为 ADR-1 禁止契约带 JSON 库 —— 于是契约只能带**文本**
  （`QuizAttemptView.itemsJson`），解析上移到工具里。
  **这一次 ADR-1 不是代价，而是直接给出了正确答案**：一个"什么都不知道"的契约
  恰好是唯一不会对历史行撒谎的契约（见 §10.3）。
- `blogagent-api` 是第五次，也是**第一次撞上"参数顺序"这个问题**：提供方三个方法的声明顺序
  是 `(taskId, userId)`，端口统一成 `(userId, taskId)`，提供方适配器显式换位。
  ⚠️ 关键是**旧守卫对这个形状是绿的** —— 它只断言"第一个参数的类型是 `Long`"，
  而 `publish(Long taskId, Long userId)` 同样满足。于是守卫改钉**参数名**
  （`javac -parameters` 会把参数名写进 class 文件）。见 §11.2。

---

## 2. ADR-2：跨域通信必须用消费方拥有的契约

### 2.1 决策原文

> **Cross-domain communication MUST use consumer-owned contracts.
> Domain entities MUST NOT cross bounded-context boundaries.**

"消费方拥有"的含义：契约定义在**跨域位置上**（`contracts/`），
由**提供方实现**，形状按**消费方实际需要**裁剪——而不是把提供方的内部模型原样端出去。

### 2.2 域实体不许跨界

实体带的是**持久化契约**，不是**跨域契约**。以 `BlogMediaAsset` 为例：

```java
@Data
@TableName("blog_media_asset")
public class BlogMediaAsset {
    @TableId(type = IdType.AUTO)
    private Long id;
    ...
}
```

它带 `@TableName` / `@TableId`（MyBatis）。一旦它进了 `contracts/`，
任何依赖该契约的模块都被迫依赖 MyBatis —— 与 ADR-1 是同一类错误，只是换了框架。

**所以实体留在 `publishing.domain` 不出去，跨域位置上换成契约类型。**

### 2.3 `publishing` 这一例：不要新建 `publishing-api`，扩 `content-api`

决策讨论时说的是"给 publishing 建一个 `publishing-api`"。落到代码时发现：
**`contracts/content-api` 已经是发布契约了** —— 它已经装着
`BlogPublisher` / `PublishCommand` / `PublishResult` / `PublishingLimits`，
自己的 `<description>` 写的就是 *"Service-to-service contract for the content domain"*。

而 §91 规定契约**按能力命名**、不跟当前目录走，§18 给的 8 个模块名里也没有
`publishing-api`。所以正确动作是**扩展 `content-api`**：

```text
blogagent ───→ content-api ←─── agent
                    ↑
                publishing（实现）
```

好处是**不用改构建接线**（根 POM `<modules>`、应用 POM、`scan.py` 的
`SHARED_KERNEL`、Dockerfile 都不用动）—— 与上一次扩展 `core-api` 加
`UserLookupPort` 是同一种省法。这与 `payment-api` 装 `billing` 域的类型是同一个道理：
契约名描述能力，不描述当前代码结构。

### 2.4 明确禁止 `common-model` / `shared-dto`

把跨域类型统一塞进一个共享模型包，是把 ADR-2 反过来执行：
它让每个域都依赖一个**谁都能改**的包，于是"消费方拥有"消失，
任何一方加字段都会同时影响所有消费方。

**禁止新建 `common-model` / `shared-dto`。** 每个跨域位置由消费方所在的契约
自己定义类型，需要复用就抽到对应能力的契约里。

### 2.5 `BlogMediaAsset` 的落地：镜像而不是收窄

`BlogAgentTaskVO.illustrations` 是**前端可见**的线上契约，所以这一刀有一个
§11 意义上的选择：

- **收窄**：只暴露前端真正读的字段（`AgentPreviewPanel.jsx` 只读
  `id` / `url` / `originalName`，13 → 3）；
- **镜像**：13 个字段逐一照抄。

**本次选镜像。** 理由是**可证性**：字段名与类型逐一相同时，序列化出来的 JSON
与改造前**完全一致**，于是这次提交可以被证明是**纯重构**——不需要跑前端回归，
不需要 §11 流程。收窄是**能做的**，但它是独立的 §11 契约变更，另开一次提交。

> 这条选择的代价是契约里多留了 10 个字段。代价是明摆着的，收益是"这次改动
> 不可能改坏线上行为"，值得。

### 2.6 因此 `docs/contract-changes.yaml` **故意没有**加条目

`contract-changes.yaml` 登记的是**改变了既有能力**的非路径型契约变更
（token payload / SSE event / 依赖边界 / 可用性语义 …）。本次一刀：

- 路由 path / method 未变；
- 响应形状未变（§2.5 的镜像保证了这一点）；
- 错误码未变。

**所以它不构成一次 §11 契约变更，不该进登记表。** 这一点写在这里，
是为了让后来的人知道"没登记"是判断，不是漏掉。

> 反过来说：如果哪天真的把 `MediaAssetRef` 收窄到 3 个字段（§16 待办第 3 项），
> 那**必须**进 `contract-changes.yaml` 并走 §11 流程 —— 因为它会真的删掉
> 前端能看到 10 个字段。

### 2.7 契约模块的包名：`<domain>.api` 对三个域**已经被占用**

`contracts/<x>-api` 的包名默认是 `com.xander.lab.<x>.api`，已建的四份都是这么落的。
但这条规律对 **`agent-api` / `automation-api` / `studio-api` 不成立**，因为这三个域
**已经**把 `com.xander.lab.<domain>.api` 用作自己的**前端 HTTP 层**：

```text
apps/core-host/src/main/java/com/xander/lab/
├── agent/       api  application  domain  infrastructure   ← api 已被占用
├── automation/  api  application  domain  infrastructure   ← api 已被占用
├── studio/      api  application  domain  infrastructure   ← api 已被占用
├── integration/ file  mail  mcp  oss                       ← 没有 api，所以 integration-api 能落在这里
└── publishing/  api  application  domain  infrastructure   ← 但 content-api 与它不同名，不冲突
```

把契约也放进 `<domain>.api` 会有两个后果，第二个是真正致命的：

1. **同一个包出现在两个 jar 里**（`apps/core-host` 与 `contracts/agent-api`）。
2. **扫描器会把该域自己的 Controller 也算成共享内核。** `SHARED_KERNEL` 是前缀匹配，
   登记 `agent.api` 就同时覆盖了那 12 个 Controller / VO / 请求体；而它们的
   `domain_of` 会变成 `agent.api`（不是业务域 `agent`），于是每个 Controller 引用自己的
   application 层都会产生一条 `agent.api -> agent` 的**新边**——把
   `implementation_statements` 推高几十条，并同时触发
   `shared-kernel-reverse-dependency` 违规（它只允许一条白名单里的存量）。

所以这三个契约取**下一个空着的段**：`spi`，`SHARED_KERNEL` 登记 `agent.spi` 这类带点条目
（`shared_kernel_owner()` 最长匹配，`agent.spi` 不会连带覆盖 `agent.api`）。

> **加契约模块前的第一步**：`ls apps/core-host/src/main/java/com/xander/lab/<domain>/`，
> 看 `api` 是否已被占用。占用了就换段，别改扫描器。

名字带 `spi` 也不只是躲碰撞：本端口确实是**宿主定义、插件实现**的经典 SPI 形态，
与 `BlogPublisher` 同类——agent 是宿主，integration 是插件。

---

## 3. 落地记录（一）：`BlogMediaAsset / MediaAssetPort` 这一刀

### 3.1 拆掉的环

改之前有一条环：

```text
publishing.application.BlogMediaService
    → blogagent.infrastructure.BlogAgentImageClient.GeneratedImage
blogagent.application.BlogAgentService
    → publishing.application.BlogMediaService
```

环的成因是 `saveAgentImage` 的签名收了 blogagent 的类型：

```java
public BlogMediaAsset saveAgentImage(Long userId, Long taskId, String originalName,
                                     BlogAgentImageClient.GeneratedImage generated,
                                     String generationMeta)
```

**拆法**：端口按内容域**真正用到**的字段收参 —— 它只读了
`generated.bytes()` 与 `generated.contentType()`：

```java
MediaAssetRef recordGenerated(Long userId, Long taskId, String originalName,
                              byte[] content, String contentType, String generationMeta);
```

于是 import 消失，环断。

### 3.2 `GeneratedImage` 为什么不能搬进契约

两个理由，任一都足够：

1. 它带 `com.xander.lab.common.ModelUsage`，而 `ModelUsage` 有
   `ModelUsage.fromJson(JsonNode)` —— **Jackson 泄漏**，ADR-1 直接挡住；
2. 它住在 `blogagent.infrastructure`，是 blogagent 的实现细节，
   搬进契约等于把"谁生成图片"这件事固化进契约。

所以端口**故意不携带** usage / model：它们属于**计费口径**，
留在调用方（`ImageGenerationTool` / `BlogAgentService` 自己
`chargeTokens(..., generated.usage(), generated.model(), ...)`）。

### 3.3 三个新契约类型，各自的理由

| 类型 | 位置 | 为什么需要它 |
|---|---|---|
| `MediaAssetRef` | `content.api` | 跨域位置上的资产引用。13 字段镜像实体（见 §2.5） |
| `StoredImage` | `content.api` | 见 §3.4 |
| `MediaAssetPort` | `content.api` | 调用方分散在 3 个部署单元，接口必须住在中立的契约模块 |

`MediaAssetPort` 的 6 个方法：

```java
long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
MediaAssetRef recordStored(Long userId, StoredImage stored);
MediaAssetRef recordGenerated(Long userId, Long taskId, String originalName,
                              byte[] content, String contentType, String generationMeta);
MediaAssetRef uploadImage(Long userId, byte[] content, String originalName, String contentType);
MediaAssetRef findOwned(Long userId, Long assetId);
List<MediaAssetRef> listByTask(Long userId, Long taskId);
MediaAssetRef findReusableGenerated(Long userId, String title);
```

### 3.4 `StoredImage` 存在，是因为"契约不能依赖契约"

原本最省事的写法是直接复用 `integration.api.UploadResponse` —— 字段一模一样。
但 `gate_contract_dependency_boundary` 只允许 JDK + 本包
（`CONTRACT_ALLOWED_IMPORT_PREFIXES`），并且**拒绝任何非 test 作用域的 POM 依赖**。
所以 `content-api` 不能 import `integration-api` 的类型。

于是有了 `StoredImage(String url, String originalName, String storedName, Long size, String contentType)`。

> **这暴露了一个仍未拍板的问题**：契约能不能依赖契约？今天答案是**不能**
> （Maven 无法编排 test-only 的跨契约依赖，所以门禁干脆全禁）。
> 代价就是像 `StoredImage` 这样的重复类型。这个问题见 §16 待办。

### 3.5 `byte[]` 而不是 `MultipartFile`，以及 `MAX_IMAGE_BYTES`

`MultipartFile` 是 spring-web 类型，进契约与 ADR-1 同类冲突（§19 也不允许
WebFlux Gateway 依赖拖 MVC 的 jar）。所以端口只收 `byte[]`。

代价是**上限检查的责任转移到了调用方**，而这里有一个真实的坑：

```text
spring.servlet.multipart.max-file-size = 100MB
MediaAssetPort.MAX_IMAGE_BYTES         =  10MB
```

如果调用方先 `file.getBytes()` 再交给端口，一个 100MB 的请求会被**整份读进内存**
才被拒绝 —— 10 倍内存放大。所以：

- 端口上**公开** `MAX_IMAGE_BYTES`，让三个调用方不必各自抄一份常量；
- 每个调用方**必须**在 `getBytes()` **之前**用 `file.getSize()` 先卡一道
  （`Img2ThreeService` 与 `BlogMediaService` 的同域重载都这么做）。

### 3.6 端口"变宽"没人守 → 反射钉住

`MediaAssetPortTest` 用反射钉住：6 个方法名、**没有任何 `default` 方法**、
接口不继承任何东西、参数类型不泄漏框架类型、预期方法清单无重复。

为什么必须钉：给端口加一个 `default` 方法**编译通过、指标不动、门禁不报**，
但它扩大了所有实现者的义务面 —— 这正是"端口慢慢长成新入口"的路径。

> 注意 `byte[].class.getName()` 返回 `"[B"`，所以数组参数要先
> `getComponentType()` 再判断。第一版测试就是栽在这里。

### 3.7 实测收益（重新生成基线后）

```text
agent      -> publishing    9 -> 3     （跨部署单元，仍是全仓最大剩余跨单元边）
blogagent  -> publishing    5 -> 2     （同单元）
studio     -> publishing    2 -> 0     （跨部署单元，边消失）
publishing -> blogagent     1 -> 0     （同单元，边消失 —— 环断了）

implementation_statements          86 -> 74  (-12)
implementation_edges               33 -> 31  （2 条边消失）
cross_deployment_unit_statements   62 -> 54  (-8)
cross_deployment_unit_edges        22 -> 21
```

⚠️ 12 条移除里只有 8 条是跨单元的（`blogagent` 与 `publishing` 同属
`dinqor-content-automation`，`publishing -> blogagent` 也是同单元内部）。
**不要把 12 当成"少了 12 个网络调用"。**

⚠️ **耦合图形状未变**：`deployment-unit-coupling` 仍然报 4 个单元在同一个
强连通分量里。`studio -> publishing` 归零**没有**让 studio 变成叶子 ——
studio 还有别的出边与入边撑着。

### 3.8 同域方法不上端口（先例：`FileController`）

`BlogMediaController` 仍然注入**具体的** `BlogMediaService`，仍然返回实体：

```java
public Result<List<BlogMediaAsset>> getImages(...)          // 返回实体
public Result<BlogMediaAsset> uploadImage(MultipartFile f)  // 返回实体
```

这不是遗漏，是**有意的**：端口存在是为了让**跨域**调用方不必依赖实现；
同域适配器用具体类型是既有先例（`FileController` 仍注入 `OssService`）。

由此产生一个 Java 上的细节：`uploadImage` 有两个重载，返回类型不同。

```java
@Override public MediaAssetRef  uploadImage(Long, byte[], String, String)  // 端口
         public BlogMediaAsset uploadImage(Long, MultipartFile)            // 同域
```

两者都走同一个私有实现体 `insertUploadedImage(...)`，**不复制校验逻辑**。
`requireUser` 放在两个入口而不是实现体里，是为了让"登录先于参数"这个顺序
在各入口上看得见。两个入口的检查顺序（登录 → 类型 → 大小）与改造前**逐条一致**。

---

## 4. 落地记录（二）：`BillingService / BillingPort` —— ADR-1 的第一次真实应用

第一刀（§3）碰到的都是"实体不能进契约"（`@TableName`）。第二刀碰到的是另一种：
**类型本身很干净，但它住的模块带 Jackson**。这是 ADR-1 第一次被真实地逼到墙角。

### 4.1 卡点只有一处

`BillingService` 被 **7 个域**引用（agent / integration / automation / blogagent /
studio / identity / profile），跨 **4 个部署单元**。它是具体 `@Service`、拖三个 MyBatis
mapper，所以只能**抽端口**。

端口的 7 个方法里，只有 `chargeTokens` 卡住：

```java
void chargeTokens(Long userId, String bizType, String bizKey, String bizRef,
                  ModelUsage usage, String model, String remark);   // ← 这个参数
```

`ModelUsage` 是 `record ModelUsage(long inputTokens, long cachedTokens, long outputTokens)`，
字段干净、零注解。**但**它住在 `libs/xander-model`，那个模块为
`ModelUsage.fromJson(JsonNode)` 与 `ModelCallResult.payload` 声明了 `jackson-databind`。
契约只许 import JDK（门禁 `contract-dependency-boundary`），所以契约 import 不到它。

### 4.2 两条路，选了哪条

| 选项 | 后果 | 结论 |
|---|---|---|
| 放宽 `CONTRACT_ALLOWED_IMPORT_PREFIXES`，允许 `com.xander.lab.common.` | 契约从此可依赖带框架的库；下一步就是带 MyBatis 的库（§19） | **否决** |
| 契约自带等价 DTO | 代价是调用方在边界上做一次三字段换算 | **采纳** |

选第二条的依据就是 ADR-1 本身（§1.1）。这不是"门禁太死"，而是门禁在正确的位置上生效了：
`ModelUsage` 之所以不能被契约用，不是因为它是坏类型，而是因为**它住错了模块**。

### 4.3 `TokenUsage` 的设计取舍

```java
public record TokenUsage(long inputTokens, long cachedTokens, long outputTokens) {
    public static final TokenUsage NONE = new TokenUsage(0, 0, 0);
    public boolean isEmpty() { ... }
}
```

- **分量集与顺序刻意与 `ModelUsage` 逐一同名同序**，所以换算是**总的**（三个字段一一对应，
  没有遗漏、没有猜测），调用方写的是一次直接映射而不是一段逻辑。
- **`NONE` / `isEmpty()` 不是方便方法，是端口语义**：网关没返回用量时调用方传 `NONE`，
  实现据此退回一口价（或对插图直接拒绝扣费）。"空用量"这个概念必须能跨边界表达，
  否则实现只能再加一个布尔参数，把同一个语义拆成两处。
- ⚠️ **它是三个相邻的 `long`。** 重排分量不会有任何编译错误，只会让计费按错误口径折算 ——
  而 token 计费错了不抛异常，只静默少扣或多扣。由 `TokenUsageTest` 用互不相同的字面量钉位置。

### 4.4 换算落在边界上，而且只落在三个类里

因为契约不能依赖 `ModelUsage`，换算只能由调用方做。好消息是**做的人很少**：

| 调用方 | 换算方式 |
|---|---|
| `AgentConversationService` | 内部 `UsageAccumulator.sum()` 直接返回 `TokenUsage`（全类唯一一处） |
| `BlogAgentService` | 私有静态 `billable(ModelUsage)`（覆盖 4 个调用点，含插图与合规审查） |
| `ImageGenerationTool` / `Img2ThreeService` | 调用点内联一次三字段映射 |

其余 4 个调用方（`PublishPostTool` / `EmailReminderScheduler` /
`CsdnMcpApplicationService` / `JuejinMcpApplicationService`）走的是
`chargeFlat` + `flatPoints`，**完全不涉及换算**。

写法一律是**按访问器取、按位置放**：

```java
new TokenUsage(usage.inputTokens(), usage.cachedTokens(), usage.outputTokens())
```

按访问器取意味着 `ModelUsage` 自己怎么排分量都不影响正确性；剩下的唯一风险是有人手写
实参顺序时写反，而那由 `TokenUsageTest`（钉 `TokenUsage` 的声明顺序）与
`ImageGenerationToolTest` / `AgentConversationServiceTest`（钉换算后的值）共同守住。

### 4.5 实现侧几乎没改：一次类型替换

`TokenUsage` 与 `ModelUsage` 的**访问器同名**（`inputTokens()` / `cachedTokens()` /
`outputTokens()` / `isEmpty()`），所以 `BillingService` 的方法体**一行没动**：

```java
public class BillingService implements BillingPort {
    @Override @Transactional
    public void chargeTokens(..., TokenUsage usage, String model, String remark) { /* 原样 */ }
    private long tokenToPoints(TokenUsage usage, String model) { /* 原样 */ }
}
```

这也解释了为什么这一刀比 `agent-api` 容易得多：**端口的形状可以选**，
选一个与现有实现同形的形状，实现侧就退化成类型替换。

### 4.6 ⚠️ 一处真实存在的换位：`TokenUsage` 顺序 ≠ `recordAndDeduct` 顺序

`recordAndDeduct` 的入参是 `(..., Long input, Long output, Long cached, ...)`，
而 `TokenUsage` 的分量是 `(input, cached, output)` —— **后两个正好相反**。
`chargeTokens` 里那句

```java
recordAndDeduct(..., usage.inputTokens(), usage.outputTokens(), usage.cachedTokens(), ...)
```

就是这处对调。写错不会编译报错，只会让流水里的 `output_tokens` 与 `cached_tokens`
两列互换，而**扣费金额照旧**（金额用的是另一条路径），所以对账时金额对得上、明细对不上。
`BillingServiceTest.tokenUsagePositionsLandInTheRightLedgerColumns` 用三个互不相同的值钉住三列。

### 4.7 端口"变宽"依然没人守（第二次确认）

与 §3.6 同一手法。`BillingPortTest` 钉住**完整签名**而不只是方法名，因为
`chargeTokens` 头四个参数是 `(Long, String, String, String)` —— 三个相邻的 `String`
（业务项 / 幂等键 / 业务引用）。换位同样不报错：幂等键会写到 `biz_type` 列上，
表现是"同一笔业务重复扣费"或"对账时按业务项查不到流水"。

**注入验证（2026-09-17 实测）**：给端口加 `default String debugBilling()` 之后，
反应堆编译通过、`BillingBizTest` 6/6 与 `TokenUsageTest` 9/9 全绿，
**只有 `BillingPortTest` 红 3 条**（方法数 7→8、签名表、`default` 检查）。

### 4.8 刻意不上端口的 6 个方法

`pageLedger` / `overview` / `priceList` / `listModelPrices` / `upsertModelPrice` /
`deleteModelPrice` 只被 `billing/api/PointsController` 与
`billing/api/AdminModelPriceController` 调用，返回的是前端视图
（`PointsLedgerPage` / `ModelPriceView`）。它们**留在 `BillingService` 上**，
两个 Controller 继续注入具体类型 —— 与 §3.8 的 `BlogMediaController` 同一先例。

### 4.9 实测收益

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

**7 条边全部归零**（连 3 条同单元的一起），因为 `payment.api` 已在 `SHARED_KERNEL` 里，
业务域 → 共享内核的 import 不计入跨域边。`billing` 的入边从 10 条语句降到 **0**。

⚠️ 与 §3.7 同样的提醒：**耦合图形状没变**。`deployment-unit-coupling` 仍报 4 个单元
在一个 SCC 里、4 对互相依赖。这一刀减的是**存量**，不是形状。

### 4.10 为什么没有加 `contract-changes.yaml` 条目

理由与 §2.6 相同，而且更强：本刀**没有改动任何既有契约类型的字段**。
新增的 `BillingPort` / `TokenUsage` 是**新增 API 面**，不是既有字段的变更；
`BillingService.chargeTokens` 的参数类型变了，但它是**实现类**、不是契约类型，
且没有任何线上格式（HTTP DTO / 事件 / 头）随之改变。
`capability-baseline-diff` 与 `contract-changes-completeness` 两个门禁都确认了这一点
（28 个能力 0 增 0 退；14 条变更全部可解析）。

**但这笔账要在拆分后重新算**：一旦 `payment-api` 作为独立版本化的 jar 发布，
`TokenUsage` 的分量顺序就变成了跨进程的线上格式，届时任何重排都必须走 §11
（见 §16 第 7 项）。

---

## 5. 落地记录（三）：`ContentPostPort / PublicationPort` —— 消掉 `BlogService` 的跨单元直连

### 5.1 卡点：`BlogService` 是具体实现，不是接口

`publishing.application.BlogService` 是 `@Service` + `@RequiredArgsConstructor`，注入四个
MyBatis mapper（`BlogPostMapper` / `BlogCategoryMapper` / `BlogTagMapper` / `BlogPostViewMapper`）、
`UserLookupPort` 与 `StringRedisTemplate`。按 §3.2 的判据这是**具体实现**，所以走"抽端口"而不是"搬类型"。
搬进契约等于把 MyBatis 与 Redis 拖进每个消费者 —— V9 §19 第一个就否。

### 5.2 这一刀的难点不在端口，在"同域调用点比跨域调用点多得多"

实测：`BlogController` 有 **17 个** `blogService.` 调用点，全部同域；跨部署单元的实现依赖只有
**9 条语句 / 3 条边**：

| 调用方 | 单元 | impl 语句 | api 语句 |
|---|---|---|---|
| `integration`（`BlogMcpApplicationService` / `CsdnBlogSyncService` / `JuejinBlogSyncService`） | `dinqor-platform-integration` | 5 | 11 |
| `agent`（`PublishPostTool` / `QueryPostsTool` / `QueryUnsyncedPostsTool`） | `dinqor-agent` | 3 | 3 |
| `profile`（`ProfileOverviewService`） | `dinqor-core-host` | 1 | 0 |

所以设计目标不是"改签名"，而是**让 17 个同域调用点一行都不改**：

- `BlogService` 实现 `ContentPostPort`；
- 旧的公开方法**签名全部不变**，退化成"读 `UserContext` → 转调私有重载"的薄壳；
- 端口方法显式收 `userId`。

这是 §3.8 / §4.8 同一手法的第三次应用（同域便捷入口 + 跨域端口，两者共用一个私有实现体），
但方向是**反向**的：前两次是"跨域调用点少，把域方法留在原地"，这次是"同域调用点多，把域方法留成壳"。
判断依据是同一句 —— **同域调用点不算跨域边界，不该为了端口去改它们**。

### 5.3 ⚠️ 端口必须显式收 `userId`，这不是风格问题

改造前 `BlogService` 有 7 个方法内部读 `auth.core.UserContext`（一个 `ThreadLocal`），
身份由调用方在调用前 `setUserId` 传进来。同进程里这能工作，但**跨进程没有 `ThreadLocal` 可传** ——
不收 `userId` 的端口只是在描述一个无法跨进程的调用，等于没抽。

顺带发现：`getPublishedPostId(String requestId)` 在一次请求里读了 **3 次** `UserContext`。
收 `userId` 之后自然收敛成 1 次。这是"把隐式依赖显式化"的附带收益，不是本次刻意优化的目标。

### 5.4 镜像而不是收窄（第三次，也是最需要证明的一次）

`ContentPost`（17 个分量）镜像 `BlogPostVO` 的可序列化表面，`ContentPostPage`（6 个分量）
镜像 `PageData`。理由与 §2.5 完全一致：**换类型后线上 JSON 逐字节不变**，这次抽取才是可证明的纯重构。

`PageData` 不能直接用，有两个各自足以否决的原因：

1. **契约依赖不到它。** 它住在 `libs/xander-mybatis-support`，那个模块为它声明了 MyBatis-Plus
   （构造器直接收 `IPage`），门禁 `contract-dependency-boundary` 会拦；
2. **它自己也跨不了进程。** 它的存在理由是"简化 `IPage`，只保留前端需要的字段" ——
   那是**前端**分页返回体。V9 §19 的教训就是别让一个类型同时服务两个受众。

⚠️ 与前两次不同的是，这次的镜像关系**可以机器证明**，而且证明了。`ContentPostShapeTest`
（住在 `apps/core-host`，因为契约侧看不见 `BlogPostVO`）用三条独立断言钉住：

1. 两侧的可序列化属性名集合相同（不需要实例，直接看类型）；
2. 同样的值在两侧序列化成同一棵 JSON 树；
3. **生产代码里那条转换**（`BlogService.toContract`，反射调用）的输出与源对象同树。

第 3 条是前两次没有的：它证明的不只是"两个类型长得一样"，而是"实现真的按镜像逐字段搬了"。
`toContract` 刻意**不用** `BeanUtils.copyProperties` —— 名字对不上时它会静默留 `null`，
而 `category` 与 `categoryName` 是前缀关系，会互相污染。

⚠️ 写这个测试时踩到了 `McpJsonMapperFactory` 那个缺陷的**同一个根因**：裸 `new ObjectMapper()`
序列化 `LocalDate` 会抛 `InvalidDefinitionException`。测试里显式注册了 `JavaTimeModule` 才通过 ——
这说明"注册模块"这件事在每个自建 mapper 的地方都躲不掉，不是 MCP 特有的。

### 5.5 `tagsRaw` 为什么必须"根本不存在"

`BlogPostVO.tagsRaw` 是 `BlogPostMapper` 的 `GROUP_CONCAT` 承接字段，靠 `@JsonIgnore`
挡在线上 JSON 之外。契约不能带 Jackson 注解（ADR-1），**所以契约里没有这个字段**。

这是镜像能成立的前提，方向也容易搞反：不是"因为 `@JsonIgnore` 所以可以漏掉"，
而是"**漏掉一个被忽略的字段，等于把它加回线上形状**"。
`ContentPostTest.tagsRawIsDeliberatelyAbsentSoItCannotComeBackOnTheWire` 钉住它。

### 5.6 这一刀真正消灭的是"相邻同类型参数"

§4.6 记过一次真实的换位（`TokenUsage` 的分量顺序与 `recordAndDeduct` 的相反）。
这一刀有三个地方是同一类风险，而且**旧签名全是位置参数**：

| 旧签名 | 危险 | 新类型 |
|---|---|---|
| `updateBlog(Long, String, String, String, String, String, List)` | **五个相邻 `String`**：`summary` 与 `content` 写反 → 摘要列拿到整篇正文，而摘要只影响列表页一行字，测试不专门覆盖就发现不了 | `ContentPostInput` |
| `listMyPosts(Integer, String, Boolean, Boolean, Integer)` | **两个相邻 `Boolean`**：`juejinSynced` 与 `csdnSynced` 写反 → "哪些还没同步到掘金"静默变成"哪些还没同步到 CSDN"，而**两条 SQL 都返回非空结果** | `ContentPostQuery` |
| `getBlogs` / `getMyBlogs(Integer, String, int, int)` | 两个相邻 `int`（page/size） | **保留位置参数**：分页是通用形状，且只有同域在用 |

换成具名分量之后，写错名字**编译期就红**。每个 DTO 的测试都额外断言"那几个相邻同类型分量的
字面量必须两两不同" —— 否则把它们对调测试仍然全绿，顺序就白钉了。

`ContentPostStatus` 进契约是另一回事：`QueryPostsTool` 与 `QueryUnsyncedPostsTool` 各自把
`"draft" ⇄ 0` 这段映射写了一遍。状态词表是内容域的事实，抄成几份之后改一处就漏几处。
`BlogService.STATUS_*` 保留为**别名**（前端 HTTP 契约与 `BlogPlanRunService` 都在用，
删了会把一次契约抽取变成一次前端可见的改名），别名仍是常量表达式，`switch` 的 `case` 标签照旧可用。

⚠️ 它**不能改成枚举** —— `blog_post.status` 是 `int` 列，MyBatis 投影与线上 JSON 都按数字走。
`ContentPostStatusTest` 用 `case ContentPostStatus.DRAFT ->` 把"能当 case 标签用"变成一条可执行断言。

### 5.7 `PublicationPort`：零改动的端口形状是设计出来的

`PublicationService` 的两个方法（`publish(PublishCommand, String)` /
`findSucceeded(Long, Long, String)`）**签名已经只含 `content-api` 类型**，所以端口形状照抄它，
实现侧只需要加 `implements` + `@Override`，**类体一行未改**。

这不是运气，是选形状时的判据：**优先选与现有实现同形的端口**（访问器同名 ⇒ 实现侧退化成类型替换）。
§4.5 的"实现侧几乎没改：一次类型替换"是同一件事的另一种表现。端口形状是设计自由度，
不要为了"看起来更像端口"去重构签名。

顺带把一个容易看反的分工写进了 `PublicationPort` 的注释：

- `BlogPublisher` 是**出站 SPI**，内容域**定义**、integration **实现**
  （平台适配器在 `dinqor-platform-integration`），无状态、不管幂等、不碰数据库；
- `PublicationPort` 是**编排**，内容域实现也由内容域拥有：查 `blog_publish_record` 判幂等、
  按 `platform` 选发布器、落 PENDING / SUCCEEDED / FAILED、对 CSDN 加限流。

所以 integration 要绕回来调内容域 —— 它需要的是"发过没有 + 别再发一次"，那是内容域的账，
不是适配器的事。

### 5.8 端口"变宽"依然没人守（第三次确认）

与 §3.6 / §4.7 同一手法，`ContentPostPortTest` / `PublicationPortTest` 钉住**完整签名**而不只是
方法名。理由在 §4.7 已述，这次再补一个：`update(Long userId, Long postId, ContentPostInput input)`
把两个 `Long` 对调，方法名不变、类型全对、编译通过，只会变成"用文章 id 当用户 id 去查归属" ——
结果是永远查不到，或更糟：查到别人的。

**注入验证（2026-09-17 实测）**：给 `ContentPostPort` 加
`default String debugPort() { return ""; }` 之后，反应堆**编译仍然通过**，`content-api` 其余
65 条测试全绿（`MediaAssetPortTest` / `PublicationPortTest` / 四个新 DTO 测试都照常），
**只有 `ContentPostPortTest` 红 2 条**（方法数 7→8、`default` 检查）。

⚠️ 这次注入还暴露了一个**测试组织的教训**：反应堆在 `content-api` 失败后**直接中断**，
`apps/core-host` 的 1074 条测试根本没跑。所以"注入只影响一个测试类"这句话，只能靠
**单独跑 `-pl contracts/content-api`** 来证明；在反应堆里跑只能证明"没有别的模块先炸"。

### 5.9 实测收益

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

3 条边**归零**，`integration → publishing` 连 11 条 api 语句一起消失。

⚠️ 与 §3.7 / §4.9 同样的提醒：**耦合图形状没变**。`deployment-unit-coupling` 仍报 4 个单元
在一个 SCC 里、4 对互相依赖。这一刀减的是**存量**，不是形状。

### 5.10 ⚠️ 残留：1 条 api statement，`agent → publishing` 这条边还在

`cross-domain-imports.yaml` 里还留着：

```yaml
- from: agent
  to: publishing
  crosses_deployment_unit: true
  api_statements: 1
  implementation_statements: 0
  implementation_files: []
```

来源是**唯一一处**：`PublishPostTool` 仍然 `import com.xander.lab.publishing.api.BlogPostVO`，
因为 `BlogAgentService.publish(Long, Long)` 与 `findPublishedPost(Long, Long)` 返回的是它。
（`PublishPostTool` 只读其中 5 个字段，所以本类内部把两条来源归一成私有 `LocalPost`。）

**为什么这一刀不顺手做掉**：

- 它的 `implementation_statements` 是 **0**，而按本仓库自己的判据
  （`cross-domain-imports.yaml` 的 `note`：*Only implementation_statements counts*），
  这条边已经不算存量；
- 要消掉它得改 `BlogAgentService` 的公开返回类型 **和** `BlogAgentController` 的返回类型 ——
  那是 **blogagent ↔ publishing** 这条边界的事，不是 `BlogService` 端口的事。
  混在一起会让这次提交的边界变模糊，也会让"这次到底动了什么"需要额外解释；
- `publishing.api` 是整个遗留 api 包（`BlogPostDTO` / `BlogPostVO`），它会被后续某一刀整体处理，
  不值得单独为这 1 条语句开一刀。

**⚠️ 一个容易误读的指标细节**：`cross_deployment_unit_edges` 数的是
**至少有一条 implementation statement** 的跨单元边（`scan.py`：
`crossing = [e for e in implementation if e["crosses_deployment_unit"]]`，
而 `implementation = [e for e in ordered if e["implementation_statements"]]`）。
所以 `docs/cross-domain-imports.yaml` 的 `edges` 列表里现在有 **15** 条跨单元边，
而 `counts.cross_deployment_unit_edges` 是 **14** —— 差值就是上面这条 impl=0 的边。
**数边数时不要只看列表长度。**

### 5.11 为什么没有加 `contract-changes.yaml` 条目

理由与 §2.6 / §4.10 相同，而且这次有机器证明：`ContentPostShapeTest` 的三条断言直接证明了
"换类型后线上 JSON 不变"。门禁 `capability-baseline-diff` 与 `contract-changes-completeness`
也确认（28 个能力 0 增 0 退；14 条变更全部可解析）。

**⚠️ 但这笔账要在拆分后重新算**：`ContentPost` 的 17 个分量顺序与 `ContentPostPage` 的 6 个分量顺序，
与 `TokenUsage` 一样是"拆分后才会变成线上格式"的东西（见 §16 第 7 项）。
今天不登记，契约 jar 独立版本化时必须补。

---

## 6. 落地记录（四）：`agent-api` —— 不搬 `AgentTool`，只加一个窄端口

### 6.1 拆掉的是一处**方向错误**，不是一处耦合

改之前，`integration.mcp.client.application` 里三个文件 import 了 agent 的**实现类**，
共 5 条实现语句，全部跨部署单元（`dinqor-platform-integration` → `dinqor-agent`）：

| 文件 | import 了什么 | 为什么 |
|---|---|---|
| `AgentMcpServerService` | `agent.application.AgentToolRegistry` | 配置变更后反向调它的 `invalidate()` |
| `McpToolSource` | `AgentDynamicToolSource`、`AgentTool` | 实现消费方的"动态工具来源"接口 |
| `RemoteMcpTool` | `AgentPromptSection`、`AgentTool` | 实现消费方的"工具"接口 |

这三条都不是"提供方偷懒用了对方的内部类型"，而是**提供方在实现消费方的接口**——形态上像
SPI，所以看起来理所当然。问题在方向：`AgentTool` / `AgentDynamicToolSource` 是 agent 的
**进程内抽象**，integration 实现它们等于把自己的编译单元绑在 agent 的实现类上。拆成两个
服务之后，integration 的构建要依赖 agent 的实现 jar；而且**是提供方在调消费方的实现**，
控制流是反的。

### 6.2 为什么**不能**把 `AgentTool` 搬进契约（这是这一刀的全部难点）

`AgentTool` 是"顺手搬过去"的那个显然选择，但它当不了契约类型，三条独立的理由：

1. **`parametersSchema()` 返回 `com.fasterxml.jackson.databind.JsonNode`。** ADR-1 说契约
   必须与序列化框架无关，`CONTRACT_ALLOWED_IMPORT_PREFIXES` 只有 `java.`。搬它就必须放宽
   门禁——而 §16.1 明写"不许为了过门禁而放宽"。
2. **它有 6 个 `default` 成员**（`promptSection` / `execute(AgentToolContext,…)` /
   `reconcile` / `approvalReason` / `readOnly`），外加嵌套的 `ProgressListener` 又有 5 个。
   搬过去等于把 agent 的整套工具词汇表塞进每个依赖方。
3. **50 个实现、41 个覆写 `parametersSchema()`。** 搬接口就要改这 50 个文件。

三条里任何一条单独都够否决，合起来说明"搬 `AgentTool`"这个方向本身就是错的：
**它不是一个 DTO，也不是一个为跨域设计的端口，它是 agent 内部的工具抽象。**

### 6.3 端口的形状：JSON 走文本，7 个方法正好是适配器要实现的那些

所以这一刀**不动** `AgentTool`，而是在 `contracts/agent-api` 里加一个**窄端口**，
只回答消费方真正要问的问题：

```java
public interface RemoteToolCatalog {          // 端口：消费方问，提供方答
    List<RemoteTool> tools(Long userId);
    long catalogVersion();
}

public interface RemoteTool {                 // 数据 + 一次调用
    String name();  String description();
    String parametersSchemaJson();            // ← JSON Schema 以文本给出
    String promptText();
    String approvalReason(String argsJson);   // ← 参数也是文本
    boolean readOnly();
    String invoke(Long userId, String argsJson, RemoteToolCall call);   // ← 结果也是文本
}
```

三处 JSON 全部走 `String`，这是 ADR-1 的直接后果。代价是调用侧多一次解析（适配器里缓存）、
提供方多一次 `toString`；对照的是一次真实网络往返，可以忽略。

`RemoteTool` 的 7 个方法**正好**是消费方适配器要实现的 `AgentTool` 成员里除去 `reconcile`
的那 7 个。`reconcile`（查证一次被打断的调用的持久化结果）没有对应方法，因为远端工具查不了
——副作用在第三方那里，本平台既没有记录也没有能力去问。

**这一刀的收益一半来自"没做什么"**：agent 的 50 个工具实现一个都没动，Jackson 的切口被
限制在端口表面加两侧各一个适配器（`RemoteToolDynamicSource` / `RemoteToolAdapter`）。
端口形状是设计自由——选那个让实现侧改动退化成类型替换的形状。

### 6.4 反向依赖怎么断：版本号取代 `invalidate()` 回调

三条 import 里最麻烦的是第一条。`AgentMcpServerService` 在配置变更后要清**两处**缓存：
自己域的 `McpToolSource`，以及 agent 的 `AgentToolRegistry`（合并视图）。第二处就是那条
反向依赖。

**改法：把"我变了"的通知换成"你问我变没变"的轮询。**

- `AgentDynamicToolSource` 加 `default long version()`，默认 `0` 表示"永不变"；
- `AgentToolRegistry` 每次读取时先读各来源的版本，与缓存里的比对，不一致才重建；
- `McpToolSource.invalidate()` 顺手 `incrementAndGet()` 自己的版本号；
- `AgentMcpServerService.changed()` 只剩 `toolSource.invalidate()`。

于是控制流只剩一个方向：**注册表问，来源答，来源永远不需要知道注册表是谁。**

> 为什么 `version()` 可以是 `default`：`AgentDynamicToolSource` 是**函数式接口**，测试里大量
> 用 lambda 实现它。加抽象方法会让那些 lambda 全部编译不过，而它们表达的正是"版本恒为 0 的
> 静态来源"这个合法语义——默认值 `0` 恰好就是那个语义。这不是"静默变宽"，是"默认值正确"。

**⚠️ 一个没有别的症状的顺序坑**：注册表必须**先读版本、后取工具**。反过来的顺序会留下一个
**永久性**的陈旧缓存——期间发生的变更让"新版本 + 旧工具"被一起存下来，而版本再也不会变，
那份旧工具就再也刷新不掉了。当前顺序下最坏只是多刷一次。这条只能用**调用顺序断言**钉住
（`AgentToolRegistryTest#theVersionIsReadBeforeTheToolsSoAConcurrentChangeCannotBeCachedAsFresh`）。

### 6.5 为什么准则不带 `order`

契约只给准则正文（`promptText()`），不给渲染顺序。顺序是**渲染方的策略**，不是提供方的数据：
远端工具的定义就是"调用会离开本平台"，所以它的准则天然属于
`AgentPromptSection.ORDER_SIDE_EFFECT`，适配器统一挂到那一档。

若把 `int order` 放进契约，agent 的常量表（50 / 100 / 200 / 300）就得跨模块再抄一份，
两份值可以**无声漂移**——漂移的后果是提示词里准则的排序变了，没有任何测试会红。

### 6.6 端口"变宽"依然没人守（第四次确认）

与 §3.6 / §4.7 / §5.8 同一手法，这次钉了**三个**类型而不是一个：
`RemoteToolTest` / `RemoteToolCatalogTest` / `RemoteToolCallTest`，各自断言

- 完整签名清单（名字 + 参数类型顺序 + 返回类型）逐条相等、条数相等；
- 没有任何 `default` 方法；
- 不继承别的接口（宽度不从别处漏进来）；
- 签名里不出现非 JDK / 非本契约包的类型；
- 契约类型必须住在 `com.xander.lab.agent.spi`。

`RemoteToolCall` 只有两个方法，看起来最不值得测，其实是三个里**最容易被"顺手加一个
default"**的：它是回调，而回调天然让人想给"可选能力"加默认空实现。加下去的代价不只是变宽，
还让那个能力**看起来存在**——比直接不提供更糟。

公共断言抽在 `ContractShape`（测试作用域）里。**不复用生产代码里的任何东西**：
契约模块没有生产代码可复用，而且就算有也不能用——用被测方的函数去算判据，等于让它自己
给自己打分（与门禁校验生成物清单那条规矩同源）。

### 6.7 实测收益

```text
implementation_statements      55 → 50      (-5)
implementation_edges           21 → 20
cross_deployment_unit_statements  38 → 33   (-5)
cross_deployment_unit_edges    14 → 13
api_statements                 25 → 25      (未动)
```

`integration → agent` 这条边**整条消失**（5 条语句、3 个文件）。更重要的是图形状变了：

> `deployment-unit-coupling` 从"4 个单元 4 对互依"变成"**4 个单元 3 对互依**"，
> 并且明确报出 `1 pair(s) no longer mutual, delete both directions from MUTUAL_UNIT_PAIRS:
> [('dinqor-agent', 'dinqor-platform-integration')]`。

这是这条门禁第一次报出"互依对减少"。`MUTUAL_UNIT_PAIRS` 里那两条（两个方向）已经删掉——
留着会让图看起来比实际更缠，而这个门禁的消息是 Wave 2 唯一的进度信号。

### 6.8 为什么没有加 `contract-changes.yaml` 条目

理由与 §2.6 / §4.10 / §5.11 相同，而且这次有**机器证明**：
`RemoteMcpTool` 原本就持有一个已解析的 `JsonNode` schema，改成
`toString()` → `readTree()` 得到同一棵树，字段顺序也不变（Jackson 的 `ObjectNode`
保序），所以渲染出的工具面 JSON **逐字节不变**。三条断言直接钉住这件事：

- `RemoteMcpToolTest#passesThroughTheRemoteSchemaWithItsFieldOrderIntact`（提供方侧：文本与输入逐字节相同）
- `RemoteToolAdapterTest#theSchemaRoundTripsByteIdenticallySoTheRenderedToolSurfaceDoesNotChange`（消费方侧：解析再序列化回到原文本）
- `RemoteToolDynamicSourceTest#distinctVersionPairsNeverFoldToTheSameValue`（折叠不碰撞，版本变化一定能被看见）

路由、响应形状、错误码都未变，所以不构成 §11 契约变更。

---

## 7. 落地记录（五）：`automation-api` —— 最大的目标域，收窄而不是镜像

### 7.1 侦察：10 条语句、4 条边，全部指向 `automation`

`automation` 是当时**最大的目标域**：10 条跨域实现语句从 4 个单元指进来。

| 边 | 语句 | 文件 | 处理 |
|---|---|---|---|
| `agent → automation` | **6** | 6 个智能体工具 | **本刀的目标** |
| `knowledgebase → automation` | 2 | `BlogNotificationService` + `BlogNotification` 实体 | 待办（扩 `automation-api`，零接线） |
| `admin → automation` | 1 | `BlogPlanSseService`（基础设施类） | 待办（需要 SSE 广播端口） |
| `integration → automation` | 1 | `EmailReminderMailTemplates` | 待办，且**构成真环**（见 §7.8） |

四条边**全部是"指进来"的**，`automation` 自己一条都没有往外指（它有的是
`automation → integration` 6 条，方向相反）。所以这个域的形状是"扇入枢纽"：
它越像公共设施，越说明里面的服务本来就不是 automation 的业务。

### 7.2 包名：`automation.api` 已被前端 HTTP 层占用 → 走 `automation.spi`

与 §2.7 同一手法，这次落到了第三个域上。`com.xander.lab.automation.api` 是
**20 个前端 HTTP 文件**的家（`EmailReminderController` / `EmailReminderTaskVO` /
`BlogContentPlanController` / `BlogContentPlanVO` …），和 `agent` / `automation` / `studio`
一样被占满。契约只能落在 `com.xander.lab.automation.spi`。

这不是换个名字那么轻：**同一刀里两个包并存**，`EmailReminderTaskVO`（前端）和
`EmailReminderView`（服务间）会长期住在同一个域的相邻包里，名字相似、字段大半重叠。
所以两者的 Javadoc 必须互相点名，说清"谁给前端、谁给智能体"，否则下一个人会以为
其中一个是另一个的重复而去合并它们 —— 合并的后果是把前端可见类型拖进服务间契约，
正好违反 ADR-2。

### 7.3 收窄而不是镜像：这一刀差距最大的一次

§2.5 的镜像规则适用于一个类型**替换**了前端可见类型的情形（那时"序列化逐字节不变"
是可证明的命题）。这一刀**一条都不满足**：`EmailReminderTaskVO` / `BlogContentPlanVO`
原地不动，继续由各自的控制器发给前端，前端永远看不到这两个契约读模型。

| 契约类型 | 分量数 | 对应的域 VO | 域 VO 字段数 |
|---|---|---|---|
| `EmailReminderView` | **11** | `EmailReminderTaskVO` | 20 |
| `EmailReminderDraft` | 12 | `EmailReminderCreateRequest` | 12 |
| `ContentPlanView` | **11** | `BlogContentPlanVO` | 24 |
| `ContentPlanDraft` | 10 | `BlogContentPlanCreateRequest` | 10 |
| `ContentPlanEdit` | 8 | `BlogContentPlanUpdateRequest` | 15 |

`ContentPlanView` 是差距最大的一处：**11 / 24**。镜像它意味着照抄一个 24 分量、
含四组相邻同类型字段的位置顺序，去保留一个**不存在**的序列化表面。

**每个留下的分量都有具名消费者**，字段集不是凭感觉挑的，是从工具的读取点反推的
（见 §7.4）。

### 7.4 ⚠️ 字段集必须从消费方读取点反推，再与 record 对账

这一刀里真实发生了一次漏字段：`EmailReminderView` 的 Javadoc 已经把 `sendTime`
写成 `QueryEmailRemindersTool` 要读的字段，但 record 的**分量列表里没有它** ——
写 Javadoc 时想到了，写分量时漏了。漏掉的后果不是编译错误，而是工具输出
**静默少一个键**，模型再也看不到"计划几点发"。

抓法是把字段集机械化地重新推一遍：

```bash
for f in <6 个工具>; do grep -oE "\.(get|is)[A-Z][A-Za-z0-9]*\(" "$f" | sort -u; done
```

得到的并集再与 record 的分量逐一对账。这次对账发现 `ContentPlanView` 的 11 个分量
与读取点**完全吻合**，`EmailReminderView` 少 1 个。

> **规矩**：收窄型契约的字段集**不能**沿用"上次抽取的结果"，必须每次从消费方
> 读取点重推一次再对账。Javadoc 里列了消费方字段清单不等于 record 里有那些分量 ——
> 两者是分开手写的，就会分开漂移。

### 7.5 同名不同签名：端口拿规范名，同域富方法加 `ForView` 后缀

`EmailReminderService.updateStatus` 与 `BlogContentPlanService.updateStatus` 是两处真冲突：
端口方法与同域富方法**参数表完全相同、只差返回类型**，而 Java 不允许只靠返回类型重载。

按 §5.2 定下的做法（先例 `OssService.uploadImage`）：**端口方法拿规范名
`updateStatus`，同域的富方法加 `ForView` 后缀说明它服务谁**。两个控制器改调
`updateStatusForView`，并留注释说明为什么名字带后缀。

`listAll` / `findByClientRequestId` / `delete` 没有冲突（`listAll` 与
`findByClientRequestId` 的调用方只有智能体工具和服务自己的测试），直接换返回类型。

### 7.6 状态常量必须搬到端口上，否则"断了实现依赖"是假的

`UpdateEmailReminderTool` 要判断 `PAUSE` 该写哪个状态：

```java
String targetStatus = "RESUME".equals(action)
        ? EmailReminderPort.STATUS_PENDING
        : EmailReminderPort.STATUS_PAUSED;
```

原来这里写的是 `EmailReminderService.STATUS_PENDING`。**只要还写着那个名字，
工具就仍然要 import 实现类**，实现语句一条都没少 —— 端口白抽了。所以两个常量
从实现类搬到端口上，实现类通过继承拿到它们（不再重复声明）。

> 判据：抽完端口后，**消费方的 import 列表里不许再出现提供方的实现包**。
> `grep -rn "import <提供方域>" <消费方包>` 的结果应该只剩契约包。这一刀之后
> `com/xander/lab/agent/` 下 15 条 `automation.*` 导入**全部**是 `automation.spi.*`。

### 7.7 ⚠️ "没传"与"传了空"不是一回事：record 化最容易丢的语义

`UpdatePlanTool` 的 EDIT 语义是"只改提交了的字段"：

```java
if (args.path("syncCsdn").isBoolean()) { request.setSyncCsdn(...); }   // 没传 → 保持 null
```

换成位置构造的 record 之后，这个条件很容易被写成
`optionalBoolean(...)` → 忘了它就变成 `args.path("syncCsdn").asBoolean()`，
缺省时得到 `false` —— **每次编辑都会静默关掉 CSDN 同步**，而这是用户没说的话。
同理 `topic` 不能用 `defaultText` 兜空串，否则"没说"变成"改成空"。

所以 `ContentPlanEdit` 的两个布尔分量是**装箱 `Boolean`**（不是 `boolean`），
并且 `UpdatePlanToolTest` 里加了**两条**针对性断言：
`editLeavesUnspecifiedFieldsNull`（缺省必须是 `null`）与
`editCarriesExplicitFalseBooleans`（显式 `false` 必须落成 `false`）。
缺任何一条，另一个方向就会静默错。

### 7.8 端口"变宽"依然没人守（第五次确认）

与 §3.6 / §4.7 / §5.8 / §6.6 同一手法，`automation-api` 的 22 条测试里：

- `EmailReminderPortTest`(8) / `ContentPlanPortTest`(7)：完整签名清单逐条相等、
  条数相等、无 `default`、不继承别的接口、签名里无非 JDK 类型、**每个方法的第一个参数
  是 `Long userId`**（ThreadLocal 不跨进程）。
- `RecordShapeTest`(7)：每个 record 的**分量名与顺序**逐条相等，且无 Jackson 注解
  （record 本身、分量、以及分量对应的字段三处都查）。

`EmailReminderPort` 还额外断言**端口上恰好只有 2 个字段**（那两个状态常量）——
常量也是宽度，多一个就是一个新的跨模块词。

### 7.9 实测收益

```text
implementation_statements         50 → 44      (-6)
implementation_edges              20 → 19      (-1)
cross_deployment_unit_statements  33 → 27      (-6)
cross_deployment_unit_edges       13 → 12      (-1)
api_statements                    25 → 16      (-9)
```

`agent → automation` 这条边**整条消失**。另外 `api_statements` 掉了 9 —— 这不是意外：
6 个工具原本 import 了 9 个 `automation.api.*` 类型（`EmailReminderTaskVO` ×3、
`BlogContentPlanVO` ×3、三个请求体 ×3），全部换成 `automation.spi.*`。
**契约包在 `SHARED_KERNEL` 里，所以这些导入一条都不计入任何指标** ——
这正是"用契约换掉实现依赖"在数字上的样子。

### 7.10 ⚠️ 这一刀顺带发现了一处门禁空转（已修）

`deployment-unit-coupling` 的第 2 条判据原本取
`component_of(WAVE2_UNITS[0])`，而 **`WAVE2_UNITS[0]` 正是 `dinqor-agent`**。
这一刀把最后一条指向 agent 的实现边（`platform-integration → agent`）消掉之后，
agent 只剩出边、**脱离了 Wave 2 强连通分量**：

```text
component_of(dinqor-agent)                  = ['dinqor-agent']        ← 单元素
component_of(dinqor-content-automation)     = ['dinqor-content-automation',
                                               'dinqor-core-host',
                                               'dinqor-platform-integration']
```

于是一个**没人指向它的单元**成了判据的根：它的分量永远只有自己，`joined` 恒为空，
**这条判据再也不会红**。新的单元加入真正的分量会被静默放过。

实测（构造 `dinqor-newbie ↔ dinqor-platform-integration` 后分别跑两套判据）：

```text
OLD  joined = []                -> PASS      ← 空转
NEW  joined = ['dinqor-newbie'] -> FAIL      ← 抓到了
```

修法：判据改成对**每个** Wave 2 单元取分量的并集；detail 行改成报**实测**分量大小
（原来打印的是 `len(WAVE2_UNITS)` 这个常量，会把"agent 已脱离"这个进度盖掉），
并新增一条"哪些单元脱离了分量、现在可以单独抽"的进度提示。

> 教训与 §7.11 的 `util` 同源：**判据的根不能是一个会变的量**。写"取第一个"的时候
> 隐含假设了"第一个一定在分量里"，而这个假设没有被任何东西守住。

### 7.11 为什么没有加 `contract-changes.yaml` 条目

理由与 §2.6 / §4.10 / §5.11 / §6.8 相同：这是一条**新的缝**，前端从未见过这两个类型，
路由、响应形状、错误码都未变。域 VO 留在 `automation.api` 继续发给前端，
所以没有任何前端可见类型被替换或收窄。

### 7.12 刻意没做的事

- **`knowledgebase → automation`（2 条）**：需要一个 `BlogNotificationService` 端口。
  这是 `automation-api` 的**零接线扩展**（只往已有模块加类型），下次顺手做。
- **`admin → automation`（1 条）**：`BlogPlanSseService` 是基础设施类，需要 SSE 广播端口，
  形状与前面几个不同（推送而非请求-响应），值得单独一刀。
- **`integration → automation`（1 条）**：这条**构成真环**。
  `EmailReminderScheduler`（automation）调 `MailService#sendReminderMail`（integration），
  后者回调 automation 的 `EmailReminderMailTemplates` 渲染正文。要断环必须改
  `MailService` 的 API（让调用方把渲染好的正文传进来），不是加个端口就行。
- **一处既有 bug 只记录不修**：`UpdatePlanTool` 的 schema 宣传 `triggerTime`
  "（triggerTimes 为空时使用）"，但工具**总是**传一个（可能为空的）`triggerTimes` 列表，
  于是 `BlogContentPlanService.update` 里 `else if (triggerTime != null …)` 那一支
  **从工具路径不可达**；只给 `triggerTime` 会走到 `parseSendTimes([])` 抛
  "请设置至少一个发送时间"。这是**既有行为**，本刀逐字保留 —— 顺手修会让这次改动
  不再是可证明的纯重构（"工具输出逐字段不变"这个命题会失效）。修它要单独一刀 + 单独测试。

---

## 8. 落地记录（六）：`NotificationPort` —— 同一个契约的第二刀，零构建接线

### 8.1 这一刀是 §7 早就写下的待办

§7.12 与 `contracts/automation-api` 的 `package-info` 都写着同一句话：

> `BlogNotificationService` 的端口（knowledgebase 要）与 SSE 广播端口（admin 要）。
> 它们同属这个契约、方向也相同，是**零接线**的扩展，只是不属于这一刀。

这一刀做的就是前半句。

**零接线不是"省事"，而是这一刀唯一的结构性优点。** 不新增模块 ⇒ 根 POM 的 `<modules>`、
应用 POM 的依赖、`scan.py` 的 `SHARED_KERNEL`、**Dockerfile** 这四处**一处都不用动**
（V9 §15.6 记过漏 Dockerfile 会报 `Child module ... does not exist`，而且门禁发现不了）。
于是这次改动里没有任何"构建接线"可以出错，全部风险收敛到语义上 —— 而语义有反射测试守着。

### 8.2 侦察：为什么这一刀必须是端口，不能搬

`automation` 的扇入一共四个消费方，本刀只动第二个：

| 消费方 | 用到的类型 | 处置 |
|---|---|---|
| `agent` | `automation.spi.*` | 已由 §7 清掉 |
| **`knowledgebase`** | `BlogNotificationService`（`@Service`）+ `BlogNotification`（`@TableName` 实体） | **本刀**：抽端口 `NotificationPort` |
| `admin` | `BlogPlanSseService`（要 `SseEmitter`） | **推迟**：框架类型，端口形状完全不同 |
| `integration` | `EmailReminderMailTemplates` | **推迟**：真环，要改 `MailService` 的 API 而非加端口 |

两个类型都不满足"搬进契约"的条件，而且是两条不同的理由：

- `BlogNotification` 带 `@TableName("blog_notification")` —— 是**域实体**。ADR-2 §2.2 明写实体不许跨边界；
  搬进契约等于把 MyBatis 注解拖进每个依赖方的 classpath。
- `BlogNotificationService` 是 `@Service` —— **具体实现**。按抽取判据第二种情况：抽端口，实现留在原地。

所以这一刀是"抽端口"，不是"搬"。

### 8.3 端口形状：与实现同形，返回 `Long` 而不是 `void`

唯一的消费方 `KnowledgeIngestionService` **忽略**了 `notifyResource` 的返回值，所以按"端口只收
调用方真正用到的东西"的直觉，这里应该收窄成 `void`。**但这次没收窄**，理由是形态可证性：

```java
// 端口方法
Long notifyResource(Long userId, String type, String title, String message,
                    String resourceType, Long resourceId);
```

与 `BlogNotificationService#notifyResource` **逐字同形** ⇒ 提供方那边的改动退化成
"加 `implements` + 加 `@Override`"这一处**纯类型替换**，可证明行为不变。
若在这里收窄成 `void`，Java **不允许**同名同参只差返回值，实现侧就必须**改名** ——
那是与这条缝无关的改动，白增风险。

返回的 id 也是这次操作的自然结果（"写一条通知，得到它的 id"），不是实现细节；
当前这点富余是**惰性**的，真出现第二个消费方时再讨论收窄。
契约测试专门钉了返回值是 `Long`，改它会被当成契约变更拦下来。

这是**与 §7.5「同名不同签名」相反的选择**：§7 里端口拿规范名、同域富方法加 `ForView` 后缀，
是因为两个方法**必须**同名（一个给端口、一个给控制器）。这里没有这个约束，所以优先取同形。

### 8.4 ⚠️ 常量必须跟着端口搬 —— 这一刀真正的技术点

`TYPE_KNOWLEDGE_STRUCTURE_PENDING` 与 `RESOURCE_KNOWLEDGE_FILE` 原本声明在 `BlogNotification`
**实体上**。抽端口时它们**必须一起搬走**：不搬的话，消费方为了拿这两个常量仍然要 `import`
那个实体，实现语句**一条都减不掉** —— 端口就白抽了（§7.6 是第一次踩到）。

判据（比看指标快）：

```bash
grep -rn "import com.xander.lab.automation" apps/core-host/src/main/java/com/xander/lab/knowledgebase/
# 期望：结果只剩 com.xander.lab.automation.spi.*
```

搬得干净的前提是**它们在 `automation` 内部没有任何使用者**（实测：只有 knowledgebase 的
服务与测试引用）。另外四个 `TYPE_*`（`REVIEW_REJECTED` / `CSDN_FAILED` / `JUEJIN_FAILED` /
`RUN_FAILED`）由 `BlogPlanRunService` 使用，属于 automation 自己的词表，**刻意留在实体上**，
不进端口 —— 端口只带消费方真正用到的东西。

于是"通知类型"这个词表**故意分成两半**：跨域可见的两个在端口上，automation 内部四个在实体上。
实体的类注释里写明了这一点，并留了一句"要加新的跨域通知类型请加到端口上，不要加回这里"，
防止下一次有人顺手加回来。

### 8.5 ⚠️ 落库的字面量要单独钉：常量值在这里是**数据**，不是代码

这两个常量的字面量写进 `blog_notification.type` 与 `.resource_type`，前端也按它们分流。
所以契约测试除了钉"常量存在"，还**钉字面量本身**：

```java
assertEquals("KNOWLEDGE_STRUCTURE_PENDING", NotificationPort.TYPE_KNOWLEDGE_STRUCTURE_PENDING,
        "这个字面量落库（blog_notification.type）。改它不是重构，是数据迁移。");
assertEquals("KNOWLEDGE_FILE", NotificationPort.RESOURCE_KNOWLEDGE_FILE, ...);
```

消费方测试则钉"传下去的就是这个常量"（`eq(NotificationPort.TYPE_KNOWLEDGE_STRUCTURE_PENDING)`）。
两处合起来才闭合：契约测试钉值、消费方测试钉用法。
**只有消费方测试的话是不闭合的** —— 生产代码与测试用同一个常量，常量值错了两边一起错。

### 8.6 端口"变宽"依然没人守（第六次确认）

见 §3.6 / §4.7 / §5.8 / §6.6 / §7.8。本端口同样由反射测试钉住：完整签名（含返回值）、
无 `default`、不继承、无框架类型、首参是调用方 id、**常量个数正好 2 个**。
最后一条是本刀特有的：它挡的是"把 automation 内部那四个 `TYPE_*` 也挪进端口"这种走偏。

### 8.7 实测收益

| 指标 | 改动前 | 改动后 |
|---|---|---|
| `implementation_statements` | 44 | **42** |
| `implementation_edges` | 19 | **18** |
| `cross_deployment_unit_statements` | 27 | **25** |
| `cross_deployment_unit_edges` | 12 | **11** |
| `api_statements` | 16 | **16**（不变） |

**`api_statements` 这次一条没掉，和 §7 正好相反。** 原因是消费方 import 的是
`automation.application` / `automation.domain`（两条都是**实现**语句），从来没碰过 `automation.api`。
§7 掉 9 是因为那 6 个工具原本 import 的是 `automation.api.*`。
**看收益必须两列都看**：只看 impl 会漏掉 §7 的一半，只看 api 会以为本刀什么都没做。

测试：`automation-api` **22 → 31**（新增 `NotificationPortTest` 9 条），`core-host` 1151 不变，
全反应堆 **1301 → 1310**（0 fail / 0 error）；26 门禁 → 24 pass / 0 fail / 2 skip。

### 8.8 图形状：`knowledgebase` 变成**纯汇点**

改完之后 `knowledgebase` 在 `docs/cross-domain-imports.yaml` 里**整域消失** ——
它没有任何出边了。之前它唯一的出边就是指向 `automation` 的这 2 条。

这是个比"少 2 条语句"更重要的结果：**纯汇点意味着它不欠任何域的实现依赖**，
抽取时不需要为它准备任何契约，只需要它自己依赖的那些契约就位。
（`bloghtml` 与 `dinqor-agent` 是另一种形状：孤立。纯汇点与孤立都不欠出边，
区别是纯汇点仍会被别人依赖。）

### 8.9 为什么没有加 `contract-changes.yaml` 条目

同 §2.6 / §3.4 / §4.10 / §5.11 / §7.11：这一刀**没有改任何前端可见类型**。
`automation.api.BlogNotificationVO` / `BlogNotificationListResult` 一行未动，
路由、响应形状、错误码都未变。新增的 `NotificationPort` 在**新的缝**上，前端看不到它。
两个常量是**搬家**（同一个字面量、同一个名字，只是从实体挪到端口），
不是形状变更 —— 唯一的可观测差异是 Java 里的全限定名，而它不出进程边界。

### 8.10 刻意没做的事

- **SSE 广播端口**（`admin` 要）：它必须暴露 `SseEmitter`（spring-web 类型），
  与 ADR-1 同类冲突，端口形状也和这三个完全不同。单独一刀，且大概率要改成
  "端口只回答数据问题、`SseEmitter` 留在实现里"的形状。
- **邮件模板**（`integration` 要）：反方向且构成真环，要改 `MailService` 的 API。
- **读侧四个方法**（`list` / `unreadCount` / `markRead` / `markAllRead`）：服务前端 Navbar 铃铛，
  不跨域，不上端口。
- **`RESOURCE_KNOWLEDGE_FILE` 的归属**：它语义上更像"knowledgebase 的资源类型"而不是
  "通知表的词表"。这次按"跟着端口搬"处理（纯搬家，可证）；真要改归属是**另一个决定**，
  记在 §16 待办里。

### 8.11 注入验证：四次注入，逮到三个缺陷（其中两个在测试里）

这一刀的注入验证做了四次，每次都**核对变红的正好是预期的那几个**：

| # | 注入 | 预期变红 | 实际 |
|---|---|---|---|
| 1 | 端口加一个 `default` 方法 | `noMethodIsDefault...`（+ 签名清单那条） | ✓ 2 条 |
| 2 | 把 `RESOURCE_KNOWLEDGE_FILE` 的字面量改掉 | `theTwoMovedConstantsKeepTheirExactLiteralValues...` | ✓ 1 条 |
| 3 | 端口加第三个常量 | `carriesExactlyTwoConstantsAndNothingElse` | ✓ 1 条 |
| 4 | 端口加一个**无参**方法 | `theMethodTakesTheCallerIdExplicitly...` | ⚠️ **它 ERROR 了，不是 FAIL** |

第 4 次是这次最有价值的一次，因为它逮到的是**测试自己的缺陷**：

**缺陷一：无参方法让断言抛 `ArrayIndexOutOfBounds`。**
`theMethodTakesTheCallerIdExplicitly...` 无条件读 `method.getParameterTypes()[0]`，
方法没有参数时抛的是下标越界 —— 报出来的信息**指向数组而不是指向问题**。
已加 `getParameterCount() > 0` 守卫，现在报的是
"`injectedNoArg` 没有参数：端口方法必须显式收调用方 id"。同款缺陷在
`EmailReminderPortTest` / `ContentPlanPortTest` 里都有，一并修了。

**缺陷二：按下标取方法，顺序一变就静默换检查对象。**
`returnsLongSoTheProviderSideIsAPureTypeReplacement` 读的是 `getDeclaredMethods()[0]`。
**`getDeclaredMethods()` 的顺序 JVM 并不保证。** 加了那个无参方法之后下标 0 变成了新方法，
这条断言就开始检查一个**完全不同的方法** —— 它变红是**碰巧**，因为新方法返回 `String`；
如果新方法也返回 `Long`，它会继续绿着，而检查对象早就换人了。
已改成按名字取（`methodNamed("notifyResource")`）。

**缺陷三（既有代码，不是本刀引入的）：同一个写法在 `contracts/core-api` 里也有一处。**
`UserLookupPortTest#displayNameTakesAUserIdAndReturnsANullableString` 同样读
`getDeclaredMethods()[0]`。同文件上一条断言虽然钉了"只有一个方法"，但两条断言是**独立**的：
一旦有人加了方法，这条会在检查别的方法却可能照样通过。已改成按名字取。

**没修但必须记下来的一处**：第 5 次注入（把常量放回实体、让 `knowledgebase` 重新 import）
**没有让 `cross-domain-import-baseline` 变红** —— 扫描器如实报了 42 → 43 条语句、边 18 → 19，
门禁却报 PASS。原因是该门禁拿 `scan.py` 的产物当自己的基线，在"先 scan 后 gates"的既定流程下
基线恒等于实时值。这是**本刀之外**的既有缺陷（修法要动 CI 设计），记在 §16 待办第 16 项；
本刀只先把门禁 docstring 里那句"任何边增长就 FAIL"的失实保证改成实情。

> 教训与 §7.10、`util` 死条目同源：**一个判据的"基线"必须来自判据之外。**
> 拿被测方自己的产物当基线，等于让它给自己打分 —— 和门禁复用生成方的哈希函数是同一条忌讳。

---

## 9. 落地记录（七）：`recitation-api`（材料能力）—— 逮到一处自调用失事务，以及一条不变量的搬家

### 9.1 侦察：第一个有**两个**跨域消费方的目标域

前面几刀的目标域都只有一个消费方（或消费方都在同一个部署单元里）。`recitation` 不一样：

| 方向 | 文件 | 原来 import |
|---|---|---|
| `agent → recitation` | `CreateKnowledgeTool` | `recitation.api.CreateRecitationMaterialRequest` + `.RecitationMaterialView` + `recitation.application.RecitationService` |
| `agent → recitation` | `QueryKnowledgeTool` | `recitation.api.RecitationMaterialView` + `recitation.application.RecitationService` |
| `agent → recitation` | `QuizKnowledgeTool` | `recitation.api.KnowledgeQuizAttemptView` + `recitation.application.KnowledgeQuizService` |
| `agent → recitation` | `GradeAnswerTool` | `recitation.application.KnowledgeQuizService` |
| `automation → recitation` | `BlogContentPlanService` | `recitation.application.RecitationService` |
| `automation → recitation` | `BlogPlanRunService` | `recitation.application.RecitationService` |

**侦察的第一个产出是一条纠正。** 上一刀留下的候选表把 `agent → recitation` 记成 4 条、
`automation → recitation` 记成 2 条，但没有指出它们指向同一个域、而且分属**两个不同的服务类**。
一条缝还是两条缝，取决于目标是**哪个服务**，而不是哪个域 —— 这次是 `RecitationService`
（材料）与 `KnowledgeQuizService`（测验）两条独立的缝。

**第二个产出是归档维度不跨域。** `RecitationService` 的公开面里最容易被顺手搬走的是嵌套的
`MaterialQuery`，它有五个分量，第五个是 `ArchiveFilter`（在用的 / 已归档的 / 都看）。
两个消费方**都没有**碰过它：四处调用用的全是四参数构造器（把归档维度默认成 `ACTIVE`）
与 `all()` 工厂。所以它属于"提供方自己的策略"，按 §2.6 的端口宽度规则留在原地。

### 9.2 这一刀只做材料能力，测验能力另开一刀

`agent → recitation` 的四条语句其实横跨两个能力。只做材料这一半，能同时拿到两个结果：

* `automation → recitation` **整条边消失**（两条语句都走材料端口）；
* `agent → recitation` 从 4 条降到 2 条（剩下的是测验工具）。

不把测验一起做，是因为它的面明显更大：要 `QuizSession`（15 个分量，**内嵌一个
`List<KnowledgeQuizAttemptView>`**）、`GradedItem`、`QuizOutcome`，还要一个测验记录读模型
（它有个 `Object items` 的**无形状字段**，需要单独判断怎么收）。合成一刀会让"这次是纯重构"
这句话变得难以核对 —— 而这条性质是每一刀最贵的东西。

### 9.3 包名：`recitation.api` 已被前端 HTTP 层占用 → 走 `recitation.spi`

与 `agent` / `automation` / `studio` 同一个坑：`com.xander.lab.recitation.api` 是 recitation
自己的前端 HTTP 层（5 个控制器 / VO / 请求体）。声明在那里会让一个包进两个 jar，
并把那些控制器判成共享内核，于是"控制器 import 自己的 application 层"变成几十条
`recitation.api → recitation` 存量边，每条再带一个 `shared-kernel-reverse-dependency` 违规。

`SHARED_KERNEL` 里加的是 `recitation.spi`（带点、最长匹配优先，所以不会连带吞掉
`recitation.application` / `recitation.domain`）。这一刀的**新增条目是第 11 条**，
`shared-kernel-classification` 门禁的 "10 module package(s) classified" 随之变成 10 个模块包。

### 9.4 筛选条件收窄：`MaterialQuery`（5）→ `MaterialFilter`（4）

契约侧只带消费方用到的四个分量（`search` / `masteryLevel` / `dueOnly` / `limit`），
换算走四参数构造器 ⇒ 归档维度落回 `ACTIVE` ⇒ 生成的谓词与消费方今天拿到的**逐字相同**
（`archived_at IS NULL`）。

两个细节值得单独记：

* **名字故意不叫 `MaterialQuery`。** 提供方的那个是 `RecitationService` 的**嵌套类型**，
  实现类里两个类型会同时在作用域内，而 `searchMaterials` 会有两个重载各收一个 ——
  同名会让人分不清谁收谁。`MaterialFilter` 同时更贴切：提供方自己的 Javadoc 就把它称作"筛选条件"。
* **`all()` 工厂的 `limit` 给 `Integer.MAX_VALUE`，不是硬编码提供方的分页上限。**
  统计路径根本不看 `limit`；检索路径提供方会把上限夹到自己的页大小，所以两种写法结果相同 ——
  但硬编码 100 会在提供方把页大小改成 50 的那天，与提供方自己的 `all()` 悄悄分家。
  契约里不该出现提供方可以改的策略常量。

### 9.5 收窄而不是镜像：`MaterialView` 14 → 9

`recitation.api.RecitationMaterialView` **原地不动**，仍然由 `RecitationController` 原样返回给前端。
`MaterialView` 是新缝上的新类型，前端永远看不到，所以只带跨域消费方真正读的九个分量。

字段集是从**消费方读取点**机械反推再与视图对账得到的（方法见 §7.4）：

| 消费方 | 读到的分量 |
|---|---|
| `CreateKnowledgeTool` | `id` / `title` / `knowledgeType` / `testMode` / `masteryScore` / `masteryLevel` |
| `QueryKnowledgeTool` | 上述 + `content` / `reviewCount` / `nextReviewAt` |
| `BlogPlanRunService` | `title` / `content` |
| `BlogContentPlanService` | **一个都不读** —— 只借这个方法确认"这条知识存在且属于当前用户"（不存在由提供方抛 404） |

故意**不是**分量的五个：`characterCount` / `lastReviewAt` / `archivedAt` / `createdAt` /
`updatedAt`。其中 `archivedAt` 尤其值得点名：归档维度**根本不跨域**（§9.1），
所以它在契约侧连"带着但不看"都不该出现。

### 9.6 `MaterialDraft`：去掉 `jakarta.validation` 注解的那一份

`recitation.api.CreateRecitationMaterialRequest` 带 `@NotBlank` / `@Size`，而它们生效
只是因为 `RecitationController` 在参数上标了 `@Valid`。契约不许带注解（ADR-1），
而且这条缝上的调用方**本来就没有那层校验**：工具从 JSON 取值、手工校验后才调进来，
提供方自己也在 `validateMaterialRequest` 里校验一遍。所以契约带的是同一个四字段、
去掉注解的一份，域侧那个 request 类型原样保留、继续做 HTTP 调用方的校验入口。

⚠️ `testMode` 为 `null` 是**有语义的**（"按 `knowledgeType` 自动匹配考法"），
换算层不许兜底（§7.7 的同一条纪律，这一刀把它写成两条互补断言：缺省必须 `null` +
显式给了值必须原样带过去）。

### 9.7 `getMaterial` 同名同参 → 富方法改名 `getMaterialForView`

端口要 `MaterialView getMaterial(Long, Long)`，而提供方原本有
`RecitationMaterialView getMaterial(Long, Long)` 给控制器 —— **同名同参只差返回值在 Java 里
编译不过**。按既有先例（`OssService.uploadImage`、`EmailReminderService#updateStatusForView`）
**端口占规范名、同域富方法加一个说明受众的后缀**。

另外三个方法参数类型不同（`MaterialDraft` vs `CreateRecitationMaterialRequest`、
`MaterialFilter` vs `MaterialQuery`），可以同名重载，**不需要**加后缀。
判据是"参数表是否完全相同"，不是"名字是否一样"。

改名的影响面很小：控制器 1 行 + automation 2 处（那两处本来就要改成端口调用）。

### 9.8 ⚠️ 这一刀逮到一处**既有缺陷**：端口方法自调用失事务

写端口方法时顺手给新方法标了 `@Transactional`，然后意识到：**上一刀的端口方法没有标。**

Spring 的事务是**代理**实现的，而同类自调用（`this.create(...)`）不走代理 ——
被调方法上的注解根本不生效。端口方法的标准写法恰好是"薄壳 + 换算 + 转调同域富方法"，
于是第九刀（`automation-api`）的五个端口方法全部落在无事务状态下执行：

| 方法 | 富方法上的注解 | 后果 |
|---|---|---|
| `EmailReminderService#create` | `@Transactional` | 富方法第一句是 `SELECT id FROM sys_user WHERE id = #{userId} FOR UPDATE` —— 行锁的全部意义是串行化同一用户的并发创建，无事务时它**锁不住任何东西**；后面的"先查幂等键再插入"与"先数配额再插入"一起退化成 TOCTOU |
| `EmailReminderService#updateStatus` | `@Transactional` | 条件更新的原子性丢失（单语句，实际影响小） |
| `BlogContentPlanService#create` | `@Transactional` | 同上，且富方法第一句同样是 `SELECT id FROM sys_user ... FOR UPDATE` |
| `BlogContentPlanService#updateStatus` | `@Transactional` | 同上 |
| `BlogContentPlanService#update` | `@Transactional` | 同上 |

**为什么所有防线都没响**：编译通过；耦合指标一动不动；26 条门禁全绿；
单元测试也全绿 —— 单元测试里没有代理，`@Transactional` 本来就不生效。
这是"静默回归"的教科书样本，而且它**恰恰是被这次抽取引入的**：
抽取之前那两条路径走的是控制器 → 代理 → 富方法，事务是有的。

**修法**：五个方法各补一个与被调富方法一致的注解（读方法补 `readOnly = true`）。
这不是"顺手重构"，是一处**行为修复**，单独在提交信息里点名。

### 9.9 `PortDelegationTransactionTest`：把这条不变量交给机器守

只修一次不够 —— 这个坑**每次抽端口都会重新出现一次**，因为端口方法的标准写法就是转调。
所以新增了一个守卫测试（`apps/core-host/src/test/java/com/xander/lab/migration/`），判据：

> 对每个实现了端口接口的类：若某个端口方法 M **没有** `@Transactional`，
> 而同类上存在同名的重载、或 `<M>ForView` 方法且它**带** `@Transactional`，就报缺陷。

三个设计点：

1. **保守近似。** 它不解析方法体去证明 M 真的转调了那个兄弟方法，只检查"危险区"里有没有缺注解。
   宁可要求一个用不上的注解，也不漏掉一个真的转调。实测在全仓 11 个端口实现上**零误报**
   （`OssService.upload` / `DashScopeRecitationAsrClient` 这类不碰库的端口方法本来就不该有注解，
   它们也没有同名的 `@Transactional` 兄弟，所以不会被误报）。
2. **`@Override` 不能用来判"这是端口方法"**：`java.lang.Override` 是 `SOURCE` 保留，反射看不见。
   改为拿方法签名去比它实现的那组接口方法。
3. **加了一条完整性判据**：用类路径扫描（`ClassPathScanningCandidateComponentProvider`）
   找出所有 `*Port` / `*Gateway` 的实现，断言与显式清单**双向相等**。
   没有这条，新抽一个端口之后清单会悄悄漏掉它，于是"每个端口方法都审过"这句话就是假的 ——
   与 `shared-kernel-classification` 缺反向判据、`util` 变成死条目是**同一类错误：
   判据只朝一个方向看，另一个方向没人看。**

**这条完整性判据第一次运行就逮到了作者自己的漏项**：本刀让 `RecitationService` 实现了端口，
但忘了把它加进清单，测试直接报出"类路径上多出来的是没被审到的"。这不是演示，是实测。

### 9.10 一条不变量的**搬家**：工具侧写不出来的断言，挪到换算层

`QueryKnowledgeToolTest` 原来有一个 `archivedKnowledgeStaysOutOfWhatTheAgentCanSee`：
抓取工具构造的 `MaterialQuery`，断言它的 `archive()` 是 `ACTIVE`。

契约筛选条件收窄之后，工具**连"把归档的也查出来"都表达不出来**（`MaterialFilter` 只有四个分量），
这个断言在工具层已经无处可写。处理办法不是删掉它，而是把它挪到它真正能被破坏的那一层：

* **换算层**：`RecitationMaterialPortMappingTest` 断言 `MaterialFilter → MaterialQuery`
  之后 `archive()` 是 `ACTIVE`；
* **端到端**：`RecitationServiceTest#thePortSearchLandsOnTheSameActiveOnlyPredicateAsThePagesDefaultList`
  断言端口查询生成出来的 SQL 仍带 `archived_at IS NULL`。

§9.15 的注入验证证明这条搬家是**等价**的：把换算改成五参数构造器 + `ArchiveFilter.ALL`，
变红的正好是这三条，不多不少。**"某条断言写不出来了"和"某条不变量没人守了"是两件事，
必须分别确认。**

### 9.11 端口"变宽"依然没人守（第七次确认）

照例：给 `RecitationMaterialPort` 加一个 `default` 方法 ⇒ 编译通过、耦合指标一动不动、
26 条门禁全绿、应用模块测试全绿。只有本模块的反射测试会红（§9.15 注入 A，3 条）。

这已经是第七次确认同一件事。结论不变：**端口的宽度只能靠反射测试守。**

### 9.12 实测收益

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 42 | **38** |
| `implementation_edges` | 18 | **17** |
| `cross_deployment_unit_statements` | 25 | **21** |
| `cross_deployment_unit_edges` | 11 | **10** |
| `api_statements` | 16 | **13** |

* `implementation_statements` −4：`automation → recitation` 2 条（整条边消失）+ `agent → recitation` 2 条。
* `api_statements` **也掉 3**（16 → 13）：三个被换掉的 `recitation.api.*` import
  （`CreateRecitationMaterialRequest`、`RecitationMaterialView` ×2）变成了 `recitation.spi.*`，
  而**契约包在 `SHARED_KERNEL` 里不计任何指标**。§7 掉 9、§8 掉 0、本刀掉 3 ——
  **看收益必须两列都看**，只盯一列会得出完全相反的结论。

### 9.13 图形状：`content-automation ↔ core-host` 不再是互依对

`automation → recitation` 是 content-automation 指向 core-host 的**最后一条**实现边。
它消失之后：

* content-automation 到 core-host 的实现依赖**归零**（它仍然被 core-host 反向依赖两次：
  `studio → blogagent` 与 `admin → automation`）；
* 互依对从 3 对降到 **2 对**：`content-automation ↔ platform-integration` 与
  `core-host ↔ platform-integration`；
* `MUTUAL_UNIT_PAIRS` 里那对**必须删掉**。`deployment-unit-coupling` 门禁会主动报出
  "1 pair(s) no longer mutual, delete both directions"—— 这是它**双向判据**的价值：
  过期条目会让图看起来比实际更乱。

Wave 2 强连通分量仍是 {content-automation, core-host, platform-integration}，
但注意**环里已经不需要 content-automation → core-host 这条腿**：content-automation
只通过 platform-integration 留在环里，而那条单向的 core-host → content-automation
已经不构成任何环。

### 9.14 为什么没有加 `contract-changes.yaml` 条目

同 §2.6 / §7.11 / §8.9：`contract-changes.yaml` 记的是**前端可见**的契约变更。
`MaterialView` / `MaterialDraft` / `MaterialFilter` 都是新类型、前端永远看不到；
`recitation.api.RecitationMaterialView` 与 `RecitationController` 的响应形状一个字没变。

`RecitationController#getMaterial` 那一行改成调 `getMaterialForView`，路由、响应形状、
错误码都未变 —— 纯粹是同域方法改名。

### 9.15 注入验证：四次注入，每次变红的都正好是预期的那些

| # | 注入 | 期望变红 | 实测 |
|---|---|---|---|
| A | 给端口加一个 `default` 方法 | 3 条（签名清单 / 参数个数守卫 / 无 default） | **3 条**，`RecordShapeTest` 保持绿 |
| B | `toQuery` 改成五参数构造器 + `ArchiveFilter.ALL` | 3 条（换算层 2 + SQL 端到端 1） | **3 条**，其余全绿 |
| C | `toRequest` 给 `testMode` 兜底成 `"AI_QA"` | 1 条（缺省必须 `null`） | **1 条**；显式给值那条保持绿 |
| D | `toNarrowView` 把 `title` 与 `content` 对调 | 1 条（逐字段落点） | **1 条** |
| E | 去掉 `EmailReminderService#updateStatus` 上的 `@Transactional` | 1 条（委托守卫） | **1 条**，完整性/反向 sanity 两条保持绿 |

注入 A 还顺带确认了上一刀修的那处测试缺陷是有效的：无参方法让"首参是调用方 id"的断言
**给出的是有意义的失败**（"端口方法必须显式收方 id"），而不是 `ArrayIndexOutOfBounds`。

---

## 10. 落地记录（八）：`recitation-api`（测验能力）—— 一个无形状字段，以及两份投影的一致性

### 10.1 这一刀是 §9 早就写下的待办

§9.2 把 `recitation` 拆成两半，只做了材料能力，理由写在当时：

> 四个工具两两分工：`CreateKnowledgeTool`/`QueryKnowledgeTool` 只用材料；
> `QuizKnowledgeTool`/`GradeAnswerTool` 只用测验。后者要碰 `KnowledgeQuizService` 的
> 四个嵌套 record，其中 `KnowledgeQuizAttemptView.items` 的类型是 `Object`。

这一刀做剩下的那一半：`agent → recitation` 的 2 条语句（`QuizKnowledgeTool` 与
`GradeAnswerTool` 各 1 条 `KnowledgeQuizService` 实现 import，外加
`QuizKnowledgeTool` 的 1 条 `recitation.api.KnowledgeQuizAttemptView` api import）。

### 10.2 侦察：一条边整条消失，`agent` 的出边进一步收窄

| from → to | impl | api | 文件 |
|---|---|---|---|
| `agent → recitation` | 2 | 1 | `QuizKnowledgeTool`、`GradeAnswerTool` |

`recitation` 的**跨域消费方只有 `agent`** —— 材料那一刀已经把 `automation` 那两条清掉了
（§9.13）。所以这一刀做完，`recitation` 与 `agent` 之间的**双向**依赖都不存在了：

```
grep -rn "^import com\.xander\.lab\.recitation\." apps/core-host/src/main/java --include=*.java | grep -v "/recitation/"
```

改完之后只剩 `agent` 下三个工具的 `recitation.spi.*`，没有一条指向 `.api` / `.application` / `.domain`。

### 10.3 ⚠️ 无形状字段：`items` 是 `Object`，这是这一刀唯一的设计决定

域侧 `KnowledgeQuizAttemptView` 的分量里有一个：

```java
/** 逐题证据，反序列化自 items_json 列。 */
private final Object items;
```

它的运行时类型是"从 `items_json` 那一列反序列化出来的任意 JSON"，因为**那一列里可能有
旧版本写下的内容**。三种做法，只有第三种是对的：

| 做法 | 结果 |
|---|---|
| 把 `Object` 搬进契约 | 等于把"随便什么"写进契约。契约要能回答"这个分量是什么形状"，`Object` 回答不了 |
| 收窄成一个新的契约 record（`QuizItemView`） | 契约会开始**假装知道**历史行里存的是什么形状。旧行可能没有 `comment`，也可能多一个字段 —— 契约一旦钉了形状，读取端就必须做兼容，而它没有做兼容的地方 |
| **契约带文本（`itemsJson`），解析留给消费方** | ✅ 契约只承诺"这是一串 JSON 文本"，不承诺它的内部形状 |

所以 `QuizAttemptView.itemsJson` 是 `String`。**解析因此上移到工具里**
（`QuizKnowledgeTool.parseItems`），前端那条路径继续用原来的解析版。

> **这条与 ADR-1 是同一件事的两面**：契约不带 JSON 库 ⇒ 契约**没法**解析 ⇒
> 只能带文本。而"契约没法解析"正是 ADR-1 想要的。ADR-1 在这里第一次不是"代价"，
> 而是**直接给出了正确答案**。

### 10.4 ⚠️ 一份实体、两份投影：新增一条"它们必须一致"的测试

`KnowledgeQuizAttempt` 这一张表现在被投影成两个类型：

| 投影 | 类型 | 受众 | `items` 形态 |
|---|---|---|---|
| 前端 | `recitation.api.KnowledgeQuizAttemptView` | `RecitationController` | **已解析**（`Object`） |
| 契约 | `recitation.spi.QuizAttemptView` | `agent` 的两个工具 | **文本**（`String`） |

两个投影各写一份最危险的地方是**它们会悄悄漂移**，而两边各自的测试都不会红：
前端的测试只断言解析后的结构，契约的测试只断言文本，谁都不看"这两个投影在共享字段上是否一致"。

`RecitationQuizPortMappingTest.theContractProjectionAndTheFrontendProjectionAgreeOnTheFieldsTheyShare`
把这条补上：同一条历史行，两个投影的 `questionCount` / `correctCount` / `score` / `verdict` /
`createdAt` 必须相等。**注入 D 证明它有独立价值**（见 §10.9）——
把 `toAttemptView` 的 `questionCount` 与 `correctCount` 对调时，它和逐字段落点测试**同时**变红，
而工具侧与前端侧的测试全绿。

### 10.5 端口形状：2 个方法，没有 `default`，没有多余重载

```java
int MAX_ITEMS = 20;
int MAX_VERDICT_CHARS = 1000;

QuizSessionView openQuiz(Long userId, Long materialId, Long conversationId);
QuizOutcomeView recordQuiz(Long userId, Long quizSessionId, Long conversationId,
                           String verdict, List<GradedItemDraft> items);
```

域侧 `KnowledgeQuizService` 有 7 个公开方法，端口只收 2 个。**没收进来的**：

| 域侧方法 | 为什么不上端口 |
|---|---|
| `openQuiz(Long, Long)` | 两参重载，**没有任何生产调用方**（只有服务自己的测试在用） |
| `listQuizzes(Long, Long)` | 只有 `RecitationController` 在用 —— 前端 HTTP 契约，受众不同 |
| `recentQuizzes(...)` 等私有方法 | 实现细节 |

`openQuiz` 的两参重载这次**保留在服务上**（不像 §7.5 那样改名）：它不冲突，只是端口不收它。
端口只要三参那一个，两参重载继续服务控制器路径。

### 10.6 同名不同签名这次没出现：端口方法就是实现本身

§7.5 / §9.7 两次遇到"端口方法要拿规范名，但域侧富方法同名同参只差返回值"，
于是富方法加了 `ForView` 后缀。这一刀**没有这个问题**，因为：

```java
@Override
@Transactional
public QuizSessionView openQuiz(Long userId, Long materialId, Long conversationId) { ... }
```

`openQuiz` 与 `recordQuiz` 的**调用方只有 agent 的两个工具**（加上服务自己的测试），
所以按 §7.5 的判据，它们**直接换返回类型**即可，不需要保留富方法。

> **判据是"这个方法还有没有别的调用方"，不是"它看起来像不像一个富方法"。**
> 判据的输入要实测：`grep -rn "\.openQuiz(\|\.recordQuiz(" apps/core-host/src --include=*.java`。
> 结果里除了 `KnowledgeQuizService.java` 自己，只有那两个工具与 `RecitationControllerTest`。

### 10.7 两个域内 record 被**删掉**（不是搬走）

`KnowledgeQuizService.QuizSession` 与 `KnowledgeQuizService.QuizOutcome` 是服务里的嵌套 record，
它们的**同域生产消费方为零** —— 只有这两个工具在用，加上服务自己的测试。
所以它们不是"搬进契约"，而是**被契约类型取代后删除**：

| 原类型 | 取代者 | 分量变化 |
|---|---|---|
| `KnowledgeQuizService.QuizSession` | `QuizSessionView` | 15 → 15（全带） |
| `KnowledgeQuizService.QuizOutcome` | `QuizOutcomeView` | 11 → 10（去掉 `lastReviewAt`） |
| `KnowledgeQuizService.GradedItem` | `GradedItemDraft` | 4 → 4 |
| `KnowledgeQuizAttemptView`（前端可见） | `QuizAttemptView`（新增，**收窄**） | 8 → 6 |

> **"删掉它不会有别的症状"的约束必须有专门测试**（`backend-norms` 里的推演法）。
> 这里的验证是：删掉两个 record 后全反应堆 1362 个测试绿，
> 且 `RecitationControllerTest` 一行没改 —— 说明它们真的只有那两个工具在依赖。

`QuizOutcomeView` 去掉 `lastReviewAt` 是**收窄**（按 §2.5 的分支判断）：
`GradeAnswerTool` 只读 `masteryLevel` / `previousMasteryLevel` / `reviewCount` / `nextReviewAt`，
从不读"上次复习时间"。`QuizSessionView` 则是**全带** —— 工具 15 个分量全读。

### 10.8 实测收益

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 38 | **36** |
| `implementation_edges` | 17 | **16** |
| `cross_deployment_unit_statements` | 21 | **19** |
| `cross_deployment_unit_edges` | 10 | **9** |
| `api_statements` | 13 | **12** |

**`agent → recitation` 整条边从清单里消失。** `api_statements` 掉 1（`QuizKnowledgeTool` 那条
`recitation.api.KnowledgeQuizAttemptView` import 换成了 `recitation.spi.QuizAttemptView`）——
`api` 与 `impl` 的降幅这次是 1 比 2，因为 `GradeAnswerTool` 原本只 import 了实现类。
**又一次印证：看收益必须两列都看，比例没有规律。**

**`recitation` 现在是一个"零跨域消费方"的域** —— 材料与测验两条能力都在契约上，
且没有任何域 import `recitation.*` 的实现包。

### 10.9 注入验证：五次注入，每次变红的都正好是预期的那些

| # | 注入 | 期望变红 | 实测 |
|---|---|---|---|
| A | 给端口加一个 `default` 方法 | 3 条（签名清单 / 参数个数守卫 / 无 default） | **3 条**，`RecordShapeTest` 与材料端口测试保持绿 |
| B | `MAX_ITEMS` 从 20 改成 10 | 1 条（常量值） | **1 条** |
| C | `openQuiz` 里 `title` 与 `knowledgeType` 对调 | 1 条（会话逐字段落点） | **1 条**；工具测试与服务测试保持绿 |
| D | `toAttemptView` 里 `questionCount` 与 `correctCount` 对调 | 2 条（历史落点 + 两份投影一致） | **2 条** |
| E | 把 `KnowledgeQuizService` 从守卫的 `PORT_IMPLEMENTATIONS` 里删掉 | 1 条（完整性） | **1 条**，消息点名缺的是哪个类 |

注入 A 里的"参数个数守卫"给出的是**有意义的失败**
（"injectedWidening 没有参数：端口方法必须显式收调用方 id"），
不是 `ArrayIndexOutOfBounds` —— 这是 §8.11 修掉的那处测试缺陷在连续两刀里的第二次生效。

**注入 D 是本轮信息量最大的一次**：它证明 §10.4 那条"两份投影必须一致"的测试
不是重复劳动。没有它，`toAttemptView` 的换位只有逐字段落点测试会红；
而一旦哪天有人把逐字段落点测试当成"太琐碎"删掉，两份投影就会静默漂移。

### 10.10 为什么没有加 `contract-changes.yaml` 条目

理由同 §2.6 / §7.11 / §8.9 / §9.14：**这一刀没有改变任何前端可见的形状**。

- `RecitationController` 的四个端点路由、响应形状、错误码一行未变。
- 前端可见的 `KnowledgeQuizAttemptView` **原样保留**（它继续被控制器使用），
  契约侧的 `QuizAttemptView` 是**新增的另一个类型**，不是它的替代品。
- 唯一改动的前端可见面是 `openQuiz` 的返回类型 —— 但 `openQuiz` **不是 HTTP 端点**，
  它是服务方法；控制器走的是 `RecitationController` 自己那条路径。

所以这是一次可证明的纯重构。

### 10.11 刻意没做的事

- **`listQuizzes` 不上端口**。它只服务控制器。上端口等于把"前端 HTTP 契约"塞进跨域契约，
  而 §2.7 已经把这条线划过一次。
- **`openQuiz(Long, Long)` 两参重载不上端口**。它没有生产调用方，上端口只会让端口变宽。
- **`items` 的解析没有下沉到契约侧**。见 §10.3：契约不带 JSON 库，这是 ADR-1 的直接后果。
- **`RecitationMaterialPort` 与 `RecitationQuizPort` 没有合并**。它们服务两组不同的工具、
  两组不同的能力，合起来只会得到一个更宽的端口 —— 而端口宽度是这一系列 ADR 里
  唯一"没人守"的量（§6.6 / §7.8 / §8.6 / §9.11）。
- **`KnowledgeQuizAttemptView` 没有被收窄**。它仍是前端可见类型，收窄它是一次 §11 契约变更
  （§2.5），与这一刀无关。

---

## 11. 落地记录（九）：`blogagent-api` —— 参数顺序归一，以及"守卫只朝一个方向看"的第三次实例

### 11.1 侦察：一个目标域、三条出边，但只有两条值得这一刀做

`blogagent` 是 `agent` 最大的出边目标，而且它有**三个**跨域消费方：

| 消费方 | 原来依赖 | 性质 |
|---|---|---|
| `BlogGenerationTool` | `blogagent.application.BlogAgentService`、`blogagent.api.BlogAgentTaskCreateRequest`、`blogagent.api.BlogAgentTaskVO`、`blogagent.domain.BlogAgentTask` | 工具类，走服务 |
| `PublishPostTool` | `blogagent.application.BlogAgentService`、`publishing.api.BlogPostVO` | 工具类，走服务 |
| `ImageGenerationTool` | `blogagent.infrastructure.BlogAgentImageClient`、`BlogAgentProperties` | **形状不同**：一个具体客户端 + 一个 `@ConfigurationProperties` |

`ImageGenerationTool` 被**刻意留下**（见 §11.11）。所以这一刀的目标是前两个，而它们各自还牵出一条别的边：
`PublishPostTool` 同时是 `BlogPostVO` 的**唯一跨域读者**，所以这一刀顺手让 `agent → publishing` 整条消失。

### 11.2 ⚠️ 参数顺序：端口统一 `(userId, taskId)`，而旧守卫对这个形状**是绿的**

提供方这四个方法的声明顺序是**反的**：

```java
public BlogPostVO publish(Long taskId, Long userId)
public BlogPostVO findPublishedPost(Long taskId, Long userId)
public BlogAgentTaskVO runStream(Long taskId, Long userId, ...)
public BlogAgentTask findReadyTaskByClientRequestId(Long userId, String clientRequestId)  // 这个是对的
```

端口统一成 `(userId, ...)`，理由三条：前四个契约（`EmailReminderPort`、`ContentPlanPort`、
`RecitationMaterialPort`、`RecitationQuizPort`）全都是 userId 在首位；消费方的心智模型是
"我（userId）对某个资源（taskId）做事"；以及第三条最要命 ——

**前面每一刀的端口测试里都有一条"每个方法第一个参数必须是调用方 id"的守卫，
而那条守卫只断言"第一个参数的类型是 `Long`"。这个断言对 `publish(Long taskId, Long userId)`
同样成立。** 也就是说，如果这里照抄提供方的顺序，守卫会**绿着**放过一个它自己名字所禁止的形状。

> 这是"判据只朝一个方向看"的**第三次**实例（前两次：`util` 死条目、`deployment-unit-coupling`
> 判据 2 的根成了单元素集合）。三次的共同点：**判据断言的是一个弱化后的性质
> （"是 Long" / "有包" / "有成员"），而名字声称的是强性质（"是调用方 id" / "有反向条目" / "会变红"）。**

### 11.3 于是守卫改钉**参数名**

`javac` 在本项目开了 `-parameters`，参数名会进 class 文件。已实测：

```
$ javap -v -p contracts/recitation-api/target/classes/.../RecitationMaterialPort.class | grep MethodParameters
```

⇒ `MethodParameters` 属性在。所以 `BlogAgentPortTest` 不再只断言类型，而是断言**参数名**
（`userId` 必须是第一个参数的名字）。这条判据是**强**的：`publish(Long taskId, Long userId)`
会让它红，而类型断言不会。

> ⚠️ 代价：这条守卫依赖 `-parameters`。如果哪天有人把它从编译配置里删掉，
> `MethodParameters` 消失，`getParameters()[0].getName()` 会退化成 `arg0` ⇒ 守卫**变红**
> （而不是静默放过），所以这是一个**会自己喊出来的**依赖，可以接受。

### 11.4 收窄：4 个分量与 5 个分量

| 契约类型 | 域侧来源 | 分量 | 说明 |
|---|---|---|---|
| `BlogAgentTaskDraft` | `blogagent.api.BlogAgentTaskCreateRequest` | 2 | 去掉 `@NotBlank` / `@Size`（`jakarta.validation` 也是框架，ADR-1） |
| `BlogAgentTaskView` | `blogagent.domain.BlogAgentTask` | **4** | 域实体 25 个字段且带 `@TableName` ⇒ 按 ADR-2 不许跨边界 |
| `BlogAgentPostView` | `publishing.api.BlogPostVO` | **5** | 17 个字段；两个消费方读的就是这 5 个 |

`BlogAgentTaskView` 只带 `id` / `title` / `summary` / `publishedPostId`：`BlogGenerationTool`
的 `outcome()` 读的正是这四个。⚠️ `publishedPostId` 是**装箱的 `Long`**，因为工具用
`== null ? -1 : value` 区分"没发布过"与"发布了"。

`BlogAgentPostView` 的 `status` 是**装箱的 `Integer`**：域侧 `BlogPostVO.status` 可以为 `null`，
而调用方的判据是 `status == null || status != 1`。换成 `int` 会把"没传"变成"传了 0" ——
"没传 ≠ 传了空"那条规则的又一次应用（§8.5）。

⚠️ `BlogAgentPostView` 与 `content.api.ContentPost` **形状相同但不能合并**：契约不许依赖契约
（只允许 JDK + 本包），而且一个是 blogagent 的能力面、一个是内容域的文章模型，让其中一个替
另一个发言就是把两个域的词汇表绑在一起。归一放在 `PublishPostTool` 内部（一个 5 行的 `LocalPost`）。

### 11.5 顺手收益：`agent → publishing` 整条边消失

`PublishPostTool` 是 `publishing.api.BlogPostVO` 的**唯一跨域读者**。它改走 `BlogAgentPostView`
之后，`agent → publishing`（0 impl / 1 api）整条边从清单里消失 —— 这是"端口只收调用方真正
用到的字段"的直接后果：**不为了统一返回类型把整个 VO 搬上端口，那条边就不会存在。**

### 11.6 两个富方法改名 + 一个"直接就是实现"

| 方法 | 处理 | 同域调用方 |
|---|---|---|
| `runStream(taskId, userId, BiConsumer, BooleanSupplier)` | 改名 `runStreamForView` | 三参重载 + `BlogAgentController` 的 SSE |
| `publish(taskId, userId)` | 改名 `publishForView` | `BlogAgentController#publish`（响应体就是 `BlogPostVO`） |
| `findReadyTaskByClientRequestId(userId, clientRequestId)` | 改名 `findReadyTaskByClientRequestIdForView` | `BlogAgentServiceIdempotencyTest` |
| `findPublishedPost(taskId, userId)` | **直接就是端口实现**，换签名换返回类型 | **无**（唯一调用方是跨域的 `PublishPostTool`） |

⚠️ 最后一行按 ADR §7.5 判据：**同域生产消费方为零 ⇒ 不保留富方法**，直接改。给一个没有调用方的
富方法留后缀是死代码。"删掉它不会有别的症状"由全量测试守着（`BlogAgentController` 从不调用它）。

⚠️ `create` 那一侧不用改名：端口方法收 `BlogAgentTaskDraft`，与域侧 `BlogAgentTaskCreateRequest`
**参数类型不同**，不构成"同名同参"，两个重载可以共存。

### 11.7 ⚠️ `runStream` 的静默换位：三参重载必须点名 `ForView`

`runStream` 现在有三个重载：

```java
runStream(Long taskId, Long userId, Consumer<String> onDelta)                        // 前端 SSE 用
runStream(Long taskId, Long userId, BiConsumer<String, String> onEvent)              // 同域
runStream(Long userId, Long taskId, BiConsumer<String, String> onEvent, BooleanSupplier)  // 端口
```

第三个与第二个**参数表逐字相同**（`Long, Long, BiConsumer, BooleanSupplier`），只是含义与返回值
不同 —— 所以两者不能同名，端口拿规范名，同域那个加 `ForView`。

**危险在于三参重载的转发**：它原来写的是 `runStream(taskId, userId, onEvent, () -> false)`。
如果保留这行，它会**静默解析到端口方法**（四参、同样的类型），两个 `Long` 直接换位 ——
而编译器不会说一个字。所以它必须显式写成 `runStreamForView(taskId, userId, ...)`，
并且这一点由 `BlogAgentPortMappingTest.theThreeArgumentOverloadForwardsToTheForViewMethodNotThePortMethod`
钉住。

> 一般化的教训：**当"归一参数顺序"产生了一个与旧方法参数表逐字相同的新重载时，
> 所有原来转发到旧方法的调用点都变成了静默换位点。** 数量不多（这里 1 处），但每一处都要
> 显式点名新名字，且每一处都要有测试。

### 11.8 实测收益

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 36 | **34** |
| `implementation_edges` | 16 | **16**（不变） |
| `cross_deployment_unit_statements` | 19 | **17** |
| `cross_deployment_unit_edges` | 9 | **9**（不变） |
| `api_statements` | 12 | **9** |
| `api_edges` | 8 | **6** |

`agent → blogagent` 从 **4 impl / 2 api** 降到 **2 impl / 0 api**（剩 `ImageGenerationTool`）；
`agent → publishing`（0 / 1）**整条边消失**。

⚠️ **两条边数指标一个都没动**：`agent → blogagent` 因为 `ImageGenerationTool` 还在所以边还在，
`agent → publishing` 消失但它是 api-only 边、不进 impl 计数。**"边数不变"不等于"什么都没发生"** ——
这和 §10 那次"api 与 impl 降幅 1 比 2"是同一类现象：**四个指标要一起看，任何一个单独看都会误判。**

### 11.9 为什么没有加 `contract-changes.yaml` 条目

三个契约类型都是**新缝**（不是替换某个前端可见类型），而且都**收窄**了。
按 §2.5 的分支判断：镜像才可能"序列化逐字节不变 ⇒ 可证纯重构 ⇒ 不进登记表"，
收窄是**新形状**，本应登记 —— 但它们**不是前端可见类型**：`BlogAgentTaskView` 与
`BlogAgentPostView` 只出现在跨进程端口上，前端看不到；`BlogAgentTaskDraft` 同理。
`contract-changes.yaml` 登记的是"前端可见契约的变化"，这三个不在其内。

⚠️ 反过来说：`BlogAgentTaskView` / `BlogAgentPostView` **在 Wave 2 之后会变成线上格式**
（跨进程调用）。届时它们的字段顺序就是线上格式，任何重排都要走 **V9 §11（契约冻结）**。
这一条记在 §16 待办里。

### 11.10 注入验证：五次注入，每次变红的都正好是预期的那些

| # | 注入 | 期望 | 实测 |
|---|---|---|---|
| A | 端口加一个 `default` 方法 | 2 | **2**（`declaresNoDefaultMethods` + `hasExactlyTheseFiveMethods`） |
| B | 端口方法首参改名（`userId` → `ownerId`） | 2 | **2**（两条参数名守卫） |
| C | `publish` 里两个 `Long` 对调 | 1 | **1** |
| D | `toTaskView` 里 `title` 与 `summary` 对调 | 3 | **3**（三个测试共用那个换算） |
| E | 守卫清单里删掉 `BlogAgentService` | 1 | **1**，消息点名缺哪个类 |

**注入 B 是这一刀新建立的守卫，它的失败信息本身就证明了 §11.2 的判断：**

```
BlogAgentPort.publish 的第一个参数叫 ownerId，不叫 userId。只断言"类型是 Long"是不够的：
publish(Long taskId, Long userId) 同样满足那个断言。
```

**注入 C 逮到一处测试缺陷（已修）。** 第一版把换位写成"用具体字面量打桩，写反了自然匹配不上"，
结果症状是 `null` 一路传到断言里变成 **NPE** —— 报的信息指向"某处为 null"而不是"两个 Long 换位了"。
改成 `thenAnswer` 里断言实收的两个值之后，失败信息是 `expected: 9L but was: 42L`。
**守卫的失败信息必须指向真正的原因**，这条在本项目已经踩过一次
（`getDeclaredMethods()[0]` 越界，见 §8.11）。

⚠️ 顺带确认一个**分工**：`everyPostComponentComesFromItsOwnSourceField` 用的是
`any(), any()`，所以它**抓不到换位**；换位只由那条专门的测试抓。两个测试各有职责，缺一不可 ——
"有一个测试覆盖了这个方法"不等于"这个方法的所有性质都被覆盖了"。

### 11.11 刻意没做的事

- **`ImageGenerationTool`（图片生成）**。它走 `blogagent.infrastructure`，形状与前面所有端口都不同：
  是一个具体客户端 + 一个 `@ConfigurationProperties`，而且它自带两个决定：生成的图片以
  `byte[]` 过界；用量要以三 long 的记录过界，而 `ModelUsage` 住在 `libs/xander-model`（带 Jackson）
  ⇒ 按 ADR-1 契约不能依赖它，需要契约自带等价 DTO（同 `payment.api.TokenUsage` 的先例）。
  **两个决定都不是搬家决定**，所以单独一刀。
- **`generateTopicSeries`**：唯一调用方是 `automation.BlogContentPlanService`，与 blogagent
  **同属 `dinqor-content-automation`** ⇒ 改它不动任何跨单元指标。
- **`listSessions` / `reviseStream` / `reviewCompliance`**：调用方都是 `BlogAgentController`（同域前端 HTTP 层）。
- **`BlogAgentService` 的 14 个构造依赖**没有动。端口实现是"薄壳 + 换算"，服务内部结构不变。

## 12. 这两条 ADR 与既有规则的交叉

| 既有规则 | 与 ADR 的关系 |
|---|---|
| §19 禁止超级 `common.jar` | ADR-1 是它在"契约模块"上的具体化 |
| §81 跨域实现依赖存量 | ADR-2 是消存量的**唯一**合法手法（抽端口或搬 DTO） |
| §11 契约冻结 | §2.5 的"镜像 vs 收窄"就是一次 §11 判断 |
| §91 契约按能力命名 | §2.3 解释了为什么扩 `content-api` 而不是建 `publishing-api` |
| 门禁 `contract-dependency-boundary` | ADR-1 的机器化身（JDK-only + 无 compile/runtime 依赖） |
| 门禁 `cross-domain-import-baseline` | ADR-2 的**进度表**：任何边增长就 FAIL |

**注意分工**：门禁守的是"契约模块本身干净"和"跨域存量在减"。
它**守不住**"DTO 上有没有 Jackson 注解"（`jackson-annotations` 在应用模块里是合法依赖），
也守不住"有没有人往契约里加 `JsonNode` 字段"。这两条目前**靠评审**。

§4 提供了这个分工的一个实测样本：门禁**确实**拦住了"契约 import 带 Jackson 的库"
（`contract-dependency-boundary` 会因为 `com.xander.lab.common.` 前缀而 FAIL），
但它**拦不住**"`chargeTokens` 收一个带 Jackson 模块里的类型"这个更早的设计决定 ——
那一步只能靠 ADR-1 这条人来执行的规则。

---

## 13. 落地记录（十）：`blogagent-api` 的图片能力 —— 一个不收 `userId` 的端口，与一条反向守卫

### 13.1 这一刀是 §11.11 / §16 第 27 项早就写下的待办

§11.11「刻意没做的事」只留了一条：`ImageGenerationTool`。第 27 项把它的形状写清楚了 ——
**"一个具体客户端 + 一个 `@ConfigurationProperties`，不是薄壳 + 换算"**，并预告了它的两个决定。

这一刀就是把它做完。做完之后 `agent → blogagent` 这条边**整条消失**，
`agent` 域对 `blogagent` 的依赖归零。

### 13.2 侦察：剩下那 2 条语句在同一个文件里，而它绕过了服务

`agent → blogagent` 的 2 条 impl 语句都在 `ImageGenerationTool` 里，是两个 import：

```java
import com.xander.lab.blogagent.infrastructure.BlogAgentImageClient;
import com.xander.lab.blogagent.infrastructure.BlogAgentProperties;
```

⚠️ **注意包名是 `infrastructure` 而不是 `application`。** 上一刀消费的是 `BlogAgentService`
（一个 `@Service`，方法都是域方法），而这一次工具直接拿到了**基础设施层**的两个类：

| 类型 | 形状 | 为什么不能直接搬 |
|---|---|---|
| `BlogAgentImageClient` | `@Component` 具体类。RestClient + HttpClient + 端点回退 + 下载 URL 校验 + 10MB 上限 | 具体实现 ⇒ 只能抽端口（ADR-2 判据②） |
| `BlogAgentProperties` | `@Data @Component @ConfigurationProperties(prefix = "blog.agent")` | 配置类 ⇒ **本来就不该过界**（见 13.6） |

还有一个细节决定了这一刀的收益结构：`ImageGenerationTool` 绕过了 `BlogAgentService`，
所以服务里那条**同域**的 `generateIllustrations` 路径完全不受影响 ——
它继续直接调 `imageClient`。这也是为什么 `GeneratedImage` 这个域内 record **一个字都不用改**。

### 13.3 ⚠️ 形状决定一：这是**第二个端口**，不是往 `BlogAgentPort` 加两个方法

最省事的做法是给 `BlogAgentPort` 加 `isImageEnabled()` / `generateImage(...)`。**没这么做。**

判据是端口设计那条老规则：**端口宽度 = 调用方适配器被迫实现的成员集。**
`BlogAgentPort` 现有的 5 个方法，形状是"用户拥有的资源上的域方法" —— 它们都收 `userId`，
都落到 `BlogAgentTaskMapper` / `blogService` 上。而图片能力的形状是"一个具体客户端 + 一份配置"，
既不落库也不认人。

把两种形状混进一个接口，`BlogAgentService` 就得同时扮演两种角色，
而**下一位读者会以为这 7 个方法是同一种东西**。所以另立 `BlogAgentImagePort`：

```java
public interface BlogAgentImagePort {
    boolean isEnabled();
    BlogAgentImage generate(String prompt);
}
```

⚠️ 顺带的收益：**扩契约模块加第二个端口，构建接线是零**（不新增模块 ⇒ 根 POM、
应用 POM、`scan.py` 的 `SHARED_KERNEL`、Dockerfile 四处一个都不用改）。
这与 `core-api` + `UserLookupPort`、`automation-api` + `NotificationPort`、
`recitation-api` + `RecitationQuizPort` 是同一个先例。

### 13.4 ⚠️ 形状决定二：图片以 `byte[]` 过界

图片的字节必须以 `byte[]` 过界 —— 这一点**没有别的选择**：
契约不能带 Jackson（ADR-1），也就不能带 `InputStream` / `Resource` / 任何序列化容器。
先例已经存在：`MediaAssetPort.recordGenerated(userId, ..., byte[] bytes, ...)`。

⚠️ 但它带来一条**必须写下来的**代价：10MB 上限、下载 URL 校验、内容类型嗅探
这些都留在提供方（`BlogAgentImageClient`）。契约只承诺"给你一份字节和它的元信息"。

### 13.5 ⚠️ 形状决定三：用量以三 long 过界 —— `ModelUsage` 带 Jackson，契约自带等价 DTO

`GeneratedImage` 的第 4 个分量是 `ModelUsage`：

```java
public record ModelUsage(long inputTokens, long cachedTokens, long outputTokens) { ... }
```

它**本身是干净的**（纯 record，没有注解）。但它住在 `libs/xander-model`，
而那个模块声明了 `jackson-databind` —— 因为同一个模块里还有
`ModelUsage.fromJson(JsonNode)` 和 `ModelCallResult.payload`。

⚠️ **契约不能 import 一个带框架的共享库**，哪怕要用的那个类型自己是干净的。
这是 ADR-1 从"不能 import Jackson"往前推一步的直接结果，也是 §4 已经踩过一次的坑
（`payment.api.TokenUsage` 就是这么来的）。

于是这一刀**又写了一个三 long 的 record**：

```java
public record BlogAgentImageUsage(long inputTokens, long cachedTokens, long outputTokens) {
    public static final BlogAgentImageUsage NONE = new BlogAgentImageUsage(0, 0, 0);
    public boolean isEmpty() { return inputTokens <= 0 && cachedTokens <= 0 && outputTokens <= 0; }
}
```

它与 `payment.api.TokenUsage` **逐字段同形**。**这是刻意的重复，不是疏漏** ——
契约不能依赖契约（§2.3），所以"同一形状写两遍"是这条规则的标准代价。

⚠️ 三个相邻的 `long` 是这一刀最危险的一处：**换序能编译、能过大部分测试，只会在生产上静默错账。**
由 `imageUsageHasThreeAdjacentLongs` 钉住（断言分量名 + 顺序 + 类型），注入 C 证明它有效。

⚠️ 还有一条容易写反的语义：`isEmpty()` 是 **"每一个分量都 ≤ 0"**，不是"有任意一个是 0"。
一次只命中缓存的正常调用（`outputTokens == 0`）如果被判成"没有用量"，那次调用就**静默不扣费**。
`imageUsageIsEmptyOnlyWhenEveryComponentIsNonPositive` 用三组互补字面量把两个方向都钉住。

### 13.6 ⚠️ 形状决定四（这一刀真正的设计点）：消费方不读提供方的配置

如果端口只回一份 `byte[]`，`ImageGenerationTool` 就得自己知道图片的 `model` 和 `size`
才能写进素材的 `meta`。它唯一的来源是 `BlogAgentProperties` —— 而那是 `blog.agent` 前缀的配置。

⚠️ **一旦消费方去读提供方的 `@ConfigurationProperties`，提供方的"配置"就变成了跨域契约。**
配置是可以改名、可以给默认值、可以整段不存在的（网关路径下 `blog.agent` 就不配），
契约不行。而且这会让 `blog.agent.*` 的任何一个键变成**事实上的公共 API**。

所以由**提供方的适配器**把"这次生成实际用了什么"填进结果：

```java
return new BlogAgentImage(generated.bytes(), generated.contentType(), generated.extension(),
        usage == null ? BlogAgentImageUsage.NONE
                : new BlogAgentImageUsage(usage.inputTokens(), usage.cachedTokens(), usage.outputTokens()),
        generated.model(), properties.getImageSize());
```

⚠️ 注意这两个字段的来源**不一样**，而且这是刻意的：

- `model` 取 `generated.model()` —— **实际端点**报的模型；
- `size` 取 `properties.getImageSize()` —— 客户端**不报**实际像素，只能给请求值。

### 13.7 ⚠️ 顺手修掉一个静默缺陷：`writeMeta` 记的是配置里的首选模型，不是实际端点

这是这一刀**在改动过程中发现的既有缺陷**，不是这一刀引入的。

`ImageGenerationTool.writeMeta` 原来写的是 `properties.getImageModel()` —— 配置里的**首选**模型。
而 `BlogAgentService.generateIllustrations`（同域那条路径）一直写的是 `generated.model()`。

⚠️ 为什么这会出错：`BlogAgentImageClient` 会在**多个候选端点之间回退重试**。
首选端点失败、兜底端点成功时，产物是兜底端点生成的，`meta.model` 却记成首选模型的名字。
更糟的是网关路径下 `getImageModel()` 可能是**空白**，于是 `meta.model` 是空字符串。

⚠️ 而它是**静默**的：图片已经生成、已经存进素材库，只有 `meta` 里那个字段是错的。
没有任何断言、没有任何日志会响。

修法就是把 `writeMeta` 改成收实际值：

```java
private void writeMeta(String title, String prompt, String model, String size) { ... }
```

并由 `imageModelComesFromTheGeneratedResultNotFromTheConfiguration` 专门守：
它把 `properties.getImageModel()` 打桩成 `"配置里的首选模型"`、把 `GeneratedImage.model()`
打桩成 `"实际兜底端点模型"`，然后断言素材 `meta` 里是后者。注入 F 证明它有效。

⚠️ 这条经验是通用的：**"提供方报的值"与"消费方配置的值"同名时，必须明确谁是真相。**
这里真相是提供方报的值 —— 因为只有它知道这次实际发生了什么。

### 13.8 ⚠️ 分量顺序：前五个与 `GeneratedImage` 逐位对齐，`size` 只能追加在末尾

`BlogAgentImage` 有 6 个分量，`GeneratedImage` 有 5 个。前五个**逐位对齐**：

```java
// GeneratedImage:   bytes, contentType, extension, usage, model
// BlogAgentImage:   bytes, contentType, extension, usage, model, size
public record BlogAgentImage(byte[] bytes, String contentType, String extension,
                             BlogAgentImageUsage usage, String model, String size) { }
```

⚠️ 唯一的新字段 `size` **必须追加在末尾**。把它插在 `model` 旁边（"它俩都是元信息，放一起"）
会让换算从"逐位直接映射"变成"错位映射"，**而且编译器不会响** ——
两个位置都是 `String`，换过去照样编译，只是把尺寸写成模型名。

由 `imageKeepsSizeLastSoTheFirstFiveStayPositionallyAligned` 钉住这条不变量；
注入 D（把 `model` 与 `size` 对调）实测变红 **6** 处。

⚠️ 顺带记一笔：2/3 位（`contentType` / `extension`）与 5/6 位（`model` / `size`）
是**两对相邻的 `String`**，注入 B 证明 2/3 位也有守卫。

### 13.9 ⚠️ 这个端口不收 `userId` —— 而这是一条**被断言过的决定**

`BlogAgentImagePort` 是这一刀之后**唯一一个不收 `userId` 的端口**。前面几个端口
（`UserLookupPort`、`NotificationPort`、`RecitationQuizPort`、`BlogAgentPort` …）
的第一个参数都是 `userId`，而且 §11.3 刚把这条守卫从"断言类型"改成"断言参数名"。

于是出现一个真实的风险：**下一位读者看到"别的端口都收 `userId`"，就给这个也加上。**
而那个参数**没有任何一步会去读它** —— 提供方是一次无状态的模型调用，不落库、不看 `UserContext`、
不分片。真正需要身份的两步在**别的端口**上，而且它们都显式收了：

```java
mediaAssetPort.recordGenerated(userId, ...);   // 存素材
billingPort.chargeTokens(userId, ...);         // 扣积分
```

⚠️ **加一个没人读的参数不是"一致性"，而是把"这里需要身份"这个错误信号写进契约。**
它会误导下一个实现者去猜这个参数该做什么，而契约里没有任何东西能纠正他。

所以守卫这次是**反着写**的 —— 新增 `ContractShape.assertNoParameterIsNamed(type, "userId")`：

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
**注入 E 是这一刀最有价值的一次验证**：它往端口里加一个 `default void track(Long userId)`，
期望的是"`default` 守卫会红"，实测**三处红**，其中一处正是这条反向守卫 —— 证明它真的接着线。

⚠️ 结论提升为规则：**"端口收不收 `userId`"必须是一对守卫，正面一条、反面一条。**
只有正面那条时，"所有端口都收"是一个**没人反对就会漂移**的一致性论点；
配上反面那条，每一个端口"要不要身份"才是一个**被断言过的决定**。

### 13.10 实测收益

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 34 | **32** |
| `implementation_edges` | 16 | **15** |
| `cross_deployment_unit_statements` | 17 | **15** |
| `cross_deployment_unit_edges` | 9 | **8** |
| `api_statements` | 9 | **9**（不变） |
| `api_edges` | 6 | **6**（不变） |

`agent → blogagent` 从 **2 impl / 0 api** 到**整条边消失**。
`agent` 域对 `blogagent` 的依赖归零；`blogagent` 只剩 `automation`（同单元）与 `studio`（跨单元 1 条）两个消费方。

⚠️ **与 §11.8 对照着看**：上一刀两条边数指标**一个都没动**，这一刀两条都动了。
两次的代码改动量相近（各 2 条语句），但一次"边还在"、一次"边没了"。
**这正说明六个指标要一起看，任何一个单独看都会误判** ——
上一刀如果只看 `implementation_edges`，会得出"什么都没发生"的错误结论。

### 13.11 为什么没有加 `contract-changes.yaml` 条目

三个契约类型都是**新缝**（不是替换某个前端可见类型），而且都**收窄**了。
按 §2.5 的分支判断：镜像才可能"序列化逐字节不变 ⇒ 可证纯重构 ⇒ 不进登记表"，
收窄是**新形状**。但它们**不是前端可见类型** —— `BlogAgentImage` 只出现在跨进程端口上。

⚠️ 与 §11.9 同款提醒：Wave 2 之后 `BlogAgentImage` 会变成**跨进程线上格式**，
届时它的 6 个分量顺序就是线上格式，任何重排都要走 **V9 §11（契约冻结）**。
这一条记在 §16 待办里（与第 7、26 项同类）。

### 13.12 注入验证：六次注入，每次变红的都正好是预期的那些

| # | 注入 | 实测 |
|---|---|---|
| A | 端口加一个 `default boolean isDisabled()` | **2**（`declaresNoDefaultMethods` + `hasExactlyTheseTwoMethods`） |
| B | `BlogAgentImage` 的 `contentType` 与 `extension` 对调 | **2** |
| C | `BlogAgentImageUsage` 三个 `long` 换序 | **3** |
| D | `BlogAgentImage` 的 `model` 与 `size` 对调 | **6** |
| E | 端口加一个 `default void track(Long userId)` | **3**（含反向守卫 `takesNoCallerIdAndThatIsDeliberate`） |
| F | `generate` 改回记 `properties.getImageModel()` | **2**（含 13.7 那条回归守卫） |

⚠️ **实测值与"想当然的期望值"不一致，这是第二次**（第一次是 §10.9）。
按直觉，"`model` / `size` 对调"应该只红 1 处（那条换算测试），实测红了 **6** 处 ——
因为换算测试、两条 `imageModel…` / `imageSize…` 专门测试、以及三条工具测试都读这两个字段。
**期望值必须实测，不能从上一刀继承。**

⚠️ 注入脚本第一次跑**没有回滚**（脚本自己的 bash bug：revert 段引用了未定义变量，
`set -u` 直接退出）。发现方式是 `git status --short` + 读文件末尾 ——
注入 A 的 `default boolean isDisabled()` 还在工作区里。
**注入脚本失败后必须检查工作区**，否则会把注入当代码提交。
修好之后重跑，脚本自检打印 **"OK: 5 个文件全部逐字节还原（blob sha 相同）"**。

⚠️ 另外注意 `mvn` 的控制台在 UTF-8 管道里是 GBK 中文乱码，但**测试名与 ASCII 断言值可读**
（`expected: <size> but was: <model>`），足够给每次注入归因。

### 13.13 刻意没做的事

- **`BlogAgentService.generateIllustrations`（同域那条路径）**：它继续直接调 `imageClient`。
  同域调用没有边界要守，绕一层端口只是多一层间接。这也是 `GeneratedImage` 不用改的原因。
- **10MB 上限、下载 URL 校验、端点回退、`usage` 解析**：全部留在 `BlogAgentImageClient`。
  端口只承诺"给你一份字节和它的元信息"。
- **图片风格的措辞**：`TOOL_IMAGE_STYLE` 留在工具里，博客配图的措辞留在 `BlogAgentService` 里。
  **两个措辞是刻意不同的**，端口只收组装好的 `prompt` —— 端口不带渲染方的策略常量。
- **`BlogAgentProperties` 的任何其他字段**（超时、`maxIllustrations`…）：不上端口。
  端口只暴露"这次生成实际用了什么"，不暴露"提供方怎么配置的"。
- **`BlogAgentImageClient` 本身**：一个字没改。它仍是 `@Component`，仍被同域路径使用。
  注入 F 之所以能改回去，是因为换算发生在服务里 —— 这也说明**这一刀没有动客户端的契约**。

---

## 14. 落地记录（十一）：`AccessTokenLookupPort` —— 一个**产出**身份的端口，与一条反向守卫

### 14.1 目标边：两条 import 都在 `agent/api/` 下

`agent → identity` 的 2 条 impl 语句分在两个文件里，但指向同一个类：

```java
// agent/api/AgentStreamAuthHandshakeInterceptor.java
import com.xander.lab.identity.application.AuthService;
// agent/api/AgentWebSocketConfig.java
import com.xander.lab.identity.application.AuthService;
```

拦截器用它做握手鉴权，`AgentWebSocketConfig` 只是把它转手传给拦截器。
**这是本项目第一个消费方在 `api/` 层的刀** —— 前面十三刀的消费方都是工具类或应用服务，
而这次是一个 `HandshakeInterceptor`：它不在 `@Controller` 链上，
走不到 `AuthInterceptor`，所以必须自己完成鉴权。

### 14.2 侦察：`AuthService` 有三个跨域消费方，但用的是**两种能力**

| 消费方 | 用到的 `AuthService` 方法 | 这其实是哪种能力 |
|---|---|---|
| `agent`（WS 握手） | `getUserIdFromAccessToken(String) -> Long` | **令牌 → 身份** |
| `integration`（McpOAuth） | `validateAccessToken(String) -> boolean` | **令牌 → 有效性** |
| `profile`（概览） | `getUserInfo(Long) -> TokenResponse.UserInfo` | 用户资料读取（**另一种能力**） |

⚠️ 前两个是**同一个能力**的两种投影：`getUserIdFromAccessToken` 的第一句就是
`if (!validateAccessToken(token)) return null;`。第三个不是 —— 它读的是用户资料，
而且返回的是 identity 的前端类型 `TokenResponse.UserInfo`，那是另一刀的事。

这个观察决定了这一刀的形状：**端口按能力定义，不按消费方定义**。
若为 `agent` 建一个只写 `getUserIdFromAccessToken` 的契约，
`integration` 之后就得再建一个只写 `validateAccessToken` 的契约 ——
**同一个能力被两份契约各描述一遍**，而两份 Javadoc 描述的是同一套语义
（签名 / 过期 / 非 refresh / 会话登记表仍在），迟早漂移。

### 14.3 ⚠️ 为什么落 `core-api` 而不是 `agent-api`

`core-api` 的 `package-info` 已经把判据写死了：

> 放"实现跨部署单元"的类型：一个类型属于这里，判据不是"看起来像契约"，
> 而是**它的实现跨部署单元**。

以及：

> §18 固定了 8 个契约名，里面**没有** identity。而 `core-api` 是 dinqor-core-host
> 这个部署单元的契约，identity 正好住在那里 —— 与 `ModelGateway` 在同一个单元，所以判据一致。

三条理由，按分量排序：

1. **判据一致**：实现（`identity.application.AuthService`）在 dinqor-core-host，
   §18 的 8 个名字里没有 identity ⇒ 与 `UserLookupPort` 落同一个模块。
2. **`identity` 不需要新增任何模块依赖**：它已经 `implements UserLookupPort`，
   所以 `identity → core-api` 这条编译依赖**本来就存在**。若把端口建在 `agent-api`，
   identity 就要为了同一个能力去依赖 `agent-api`；等 `integration` 也来用，
   再依赖 `integration-api` —— **提供方被迫依赖每一个消费方的契约**，方向就乱了。
3. **未来宿主能直接用**：V11 §52 判定"凡承载 SSE/WS 的部署单元都必须维护 Security Local Cache"，
   而 `core-api` 是这些单元都该依赖的模块。

⚠️ 反过来说，ADR-2 的"消费方拥有"**不等于"契约要放在消费方那个域的模块里"** ——
它说的是形状按消费方的真实需要裁剪（这里：只要一个方法、答不上来返回 `null`），
以及契约定义在 `contracts/` 这个跨域位置上。`UserLookupPort` 就是同一个先例：
它的消费方是 `publishing` / `components`，而它住在 `core-api`。

### 14.4 ⚠️ 为什么只有这一个方法：`validateAccessToken` 与 `resolveUserId != null` 是同一个判断

`integration` 现在用的是 `validateAccessToken`，看起来需要一个 `boolean` 方法。
**没有为它加第二个方法**，因为这两个判断在本系统里**可证等价**：

| 令牌类型 | 会通过 `validateAccessToken` 吗 | 依据 |
|---|---|---|
| access token | 会，且 subject 一定可解析 | `generateTokenResponse` 是**唯一**生产调用方，传 `String.valueOf(user.getId())` |
| refresh token | **不会** | `jwtUtil.isRefreshToken(token)` 为真时直接 false |
| bind token（微信绑定凭证） | **不会** | 它的 `type` 是 `bind` 不是 `refresh`，但它**不写进** `login:token:` 前缀，所以 `hasKey` 为假 |

⇒ `validateAccessToken(t)` 为真 ⟺ `t` 是仍登记在册的 access token ⟹
`Long.valueOf(subject)` 不可能抛 ⇒ `resolveUserId(t) != null`。

所以 `integration` 那一刀可以直接复用本端口（`resolveUserId(t) != null`），
不必新增方法。**这一刀没有顺手改它**，因为那是另一个消费方、另一条边，
而且 `McpOAuthService` 在 MCP OAuth 流程里、有自己的测试 —— 混进来会让这次 diff 难以证明是纯重构。

⚠️ 但要记住一个**将来的分歧点**：如果哪天出现"subject 不是数字、却仍登记在
`login:token:` 下"的令牌类型，两个判断就会分叉（`resolveUserId` 返回 `null`
而 `validateAccessToken` 返回 `true`）。分叉的方向是**更严**（拒绝），不是更松，
所以不是安全问题；但那时必须重新决定，不能再靠这条等价性。

### 14.5 ⚠️ 这是第一个**产出**调用方身份的端口 ⇒ 守卫是反的

前几个端口的第一个参数都是调用方身份，守卫是正面的：
`assertFirstParameterIsTheCallerId` 断言参数名是 `userId`（§11.3 把断言从"类型"改成"名字"）。

本端口是**反过来**的：它的整个作用就是从令牌里**得出**身份。
方法里出现一个 `userId` 参数，说明有人在让它做"校验这个令牌属不属于这个人" ——
那是另一个能力，而且会诱使实现者把归属校验和令牌校验写成一步。

所以守卫是：

```java
// AccessTokenLookupPortTest
@Test void takesNoUserIdBecauseItProducesOne() {
    // 遍历所有方法的所有参数，断言没有一个叫 userId
}
```

**注入 B（往签名里加一个 `Long userId` 参数）实测 2 处红，其中一处正是它。**

⚠️ 这一条与 §13.9 合起来才完整：**"端口收不收 `userId`"必须是一对守卫。**
`BlogAgentImagePort` 是"不该收、断言它没收"，本端口是"不该收、而且是因为它要产出"，
而其他端口是"该收、断言它收了"。三种情况各有一条断言，**没有第四种**。

### 14.6 ⚠️ 端口实现此前**一个测试都没有**

`getUserIdFromAccessToken` 在被抽成端口之前，**没有任何测试覆盖它** ——
`AuthServiceTest` 只覆盖了 `validateAccessToken` 两条。它当时只是 identity 内部的一个便捷方法，
没测试也没人发现。

⚠️ 但抽成端口之后，它就变成了 **agent 的 WebSocket 握手唯一的身份来源**：
它返回 `null` 就把所有连接挡在 401 上，它返回错的值就把别人的会话开给这个人。
**"这是契约的实现"意味着它的行为必须是钉住的**，所以这一刀补了 4 条
（活令牌返回 subject / 已登出返回 null 且短路不解析 / refresh 令牌返回 null 且不查 Redis /
subject 非数字返回 null 而不是抛异常）。

⚠️ 这正是 §9.8 那个教训的同族：**收窄/搬家之后，"原来那条断言没人写"**。
这次不是"断言被删了"，而是**从来就没有** —— 而它以前不重要，现在重要了。
**判据：把方法提升成契约实现时，先查它有没有测试；没有就当场补。**

### 14.7 ⚠️ `PortDelegationTransactionTest` 的清单必须同步

`AuthService` 现在实现了 `*Port`，于是类路径扫描会找到它。
那个测试的 `theAuditedClassListIsComplete` 是**双向**判据（多一个少一个都红），
所以漏加会**直接变红**，不会静默放过 —— 这次是判据自己把问题喊出来的。

⚠️ 顺带记一条**没被守住**的东西：那条测试的检测器只在**同一个类**里找
`@Transactional` 的同名方法。如果端口的实现是一个**独立的小 `@Service`**、
它转调另一个类的富方法，检测器**看不到**。这次选"让 `AuthService` 自己实现"
而不是新加一个适配器类，部分原因就是这个 —— 留在同类里，守卫才覆盖得到。

### 14.8 实测收益

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 32 | **30** |
| `implementation_edges` | 15 | **14** |
| `cross_deployment_unit_statements` | 15 | **13** |
| `cross_deployment_unit_edges` | 8 | **7** |
| `api_statements` | 9 | **9**（不变） |
| `api_edges` | 6 | **6**（不变） |

`agent → identity` **整条边消失**。`agent` 现在只剩**一条**出边：`agent → integration`（2 impl）。

⚠️ 与最近两刀连起来看，三次的边数表现各不相同，**这正说明六个指标要一起看**：

| 刀 | 语句 | impl 边 | 跨单元边 | 备注 |
|---|---|---|---|---|
| 第十二刀 | −2 | **不动** | **不动** | 一条边收窄、另一条 api-only 边消失 |
| 第十三刀 | −2 | −1 | −1 | 边真的没了 |
| 第十四刀 | −2 | −1 | −1 | 边真的没了 |

### 14.9 为什么没有加 `contract-changes.yaml` 条目

新端口是**新缝**（不是替换某个前端可见类型），且只有一个方法。
`contract-changes.yaml` 登记的是"**改变了既有能力**的非路径型契约变更"，
这次既没改路径、没改响应形状、也没改错误码 —— 它是把一条已有的域内调用
换成端口调用，行为逐字不变（同一个实现方法、同样的判据、同样的 `null` 语义）。

### 14.10 注入验证：六次注入，其中一次**第一遍是无效的**

| # | 注入 | 期望 | 实测 |
|---|---|---|---|
| A | 端口加一个 `default boolean isDisabled()` | 2 | **2** |
| B | 端口方法多收一个 `Long userId` 参数 | 2 | **2**（含反向守卫 `takesNoUserIdBecauseItProducesOne`） |
| C | 端口参数改名 `accessToken` → `token` | 1 | **1** |
| D | 实现永远返回 `null` | — | **2** |
| E | 实现无视令牌、固定返回 `1L` | — | **2** |
| F | 消费方无视端口结果、固定用 `42L` | 1 | **1** |

⚠️ **E 的第一遍结果是假的。** 脚本把 D 的还原锚点写成 `            return null;`，
而 `AuthService` 里有**三处**这一行 ⇒ `count(src) == 1` 的断言失败 ⇒ **D 没被还原**；
接着 E 的正向锚点（`return Long.valueOf(...)`）已经被 D 改掉了 ⇒ **E 根本没生效**，
它报出来的"2 处红"其实是 D 残留的结果。

发现方式是脚本收尾的 blob 比对打印了 `FAIL: 还原不完整`，且 `git status` 里
`AuthService.java` 仍是脏的。**用"整个方法体块"（含只此一处的 `validateAccessToken(accessToken)` 调用）
当锚点重跑后**，D 与 E 各自生效、各自还原成功，结果都是 2 处红。

⚠️ 这是**上一刀那条教训的第二次实例，但成因不同**：上一刀是脚本引用未定义变量
（`set -u` 中途退出），这一刀是**还原锚点不唯一**。
**共同点：脚本"跑完了"不等于"注入生效了、也还原了"。**
判据升级为：**每条注入跑完立刻做一次 blob 级还原校验**，不要等脚本收尾统一查 ——
否则一条还原失败会**污染后面所有注入的结果**，而表格看上去仍然"每行都有数字"。

### 14.11 刻意没做的事

- **V11 的 `ws-ticket` 机制**（`POST /api/v2/agents/ws-ticket` → 30–60s 一次性票据 → 握手用
  `wss://.../ws?ticket=`）。方案里 WS 的**目标**就是这个，而它属于 V11 附录 E 的
  "SSE / WS Token"工作流（0/5，且卡在 `apps/gateway`）。这一刀只做**行为逐字不变**的抽端口，
  不顺手改鉴权协议 —— 改协议要动前端、要发退场日期、要过 §11。
- **`integration` 改用本端口**：它用同一个能力（§14.4 已证明等价），但属于另一条边，单独一刀。
- **去掉那次 Redis `hasKey`**：V11 §28.1 的意图是"发送边界 O(1) 本地 cache 校验"，
  而 `SecurityStateCache` 已经在 `AgentWebSocketConfig` 里了。但**去掉它会改变行为**
  （登出后不再阻止新连接），那是安全决策不是重构，不在这一刀里。
- **`profile → identity` 的 `getUserInfo`**：那是用户资料读取，另一种能力，另一种形状。

### 14.12 下一刀

`agent` 只剩 `agent → integration`（2）。剩余跨单元边按语句数：

`automation → integration` 6、`agent → integration` 2、`admin → automation` 1、
`identity → integration` 1、`integration → automation` 1、`integration → identity` 1、
`studio → blogagent` 1。

1. **`integration → identity`（1）** —— **本刀的直接后续**：同一个端口、同一套判据，
   §14.4 已经把等价性证明写好了，改一行 `validateAccessToken(token)` →
   `resolveUserId(token) != null`。做完之后**跨单元的 identity 边全部归零**。
2. **`agent → integration`（2）** —— 与 `PublishPostTool` 同一文件。
3. **`automation → integration`（6）** —— 目前最大，卡在 `MailService` 的 API 变更上（模板环）。

---

## 15. 落地记录（十二）：`McpOAuthService` 接入 `AccessTokenLookupPort` —— 一个端口被第二个消费方用上，以及一条 1 语句的边锁住了整个 `dinqor-core-host`

### 15.1 目标边：只剩 import / 字段 / 调用三处

`integration -> identity` 是 **1 条实现语句**，全部落在
`integration.mcp.security.application.McpOAuthService`：

```java
import com.xander.lab.identity.application.AuthService;   // 第 7 行
private final AuthService authService;                     // 第 63 行
if (!authService.validateAccessToken(token)) throw ...;    // 第 312 行
```

这一刀是 §14 的**直接后续**：§14 把 `AccessTokenLookupPort` 建在 `core-api` 时，
就写明了"这个能力不止一个消费方"，并把 `validateAccessToken(t) ≡ resolveUserId(t) != null`
的等价性证明写进了 §14.4。本刀就是去兑现那句话。

⚠️ `profile -> identity`（1 impl，`ProfileOverviewService.getUserInfo`）**不在本刀范围内** ——
那是**另一种能力**（拿用户资料，不是验令牌），而且同单元、不跨部署单元。§14.2 已经把三个消费方
分成两类，本刀只动其中一类。

### 15.2 ⚠️ 这一刀不是"把布尔换成 null 判断"：消费方复制了一份身份解析

打开 `requireWebUser` 才发现原文是这样的：

```java
private Long requireWebUser(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) throw ...;
    String token = authorization.substring(7);
    if (!authService.validateAccessToken(token)) throw ...;   // ← 第 1 步
    return Long.parseLong(jwtUtil.getSubject(token));          // ← 第 2 步，自己又解一遍
}
```

这两步**合起来就是** `resolveUserId`。也就是说 `integration` 不只是"问了 identity 一个布尔"，
它**自己重新实现了一遍身份解析**，并且必须知道「用户 id 是 JWT 的数字形式主体」这条
**实现细节** —— 而这条细节在 §14 里刚被判定为"不该跨边界"。

所以本刀的实际形状是：**删掉一份复制品**，而不是"换一个 API"。

### 15.3 ⭐ 顺带的收获：`JwtUtil` 这个协作者从消费方整个消失

`jwtUtil` 在 `McpOAuthService` 里**只有第 313 行那一处**在用。既然那一步归端口了，
字段、import、构造参数一起去掉：

```java
// 之前
private final AuthService authService;
private final JwtUtil jwtUtil;
// 之后
private final AccessTokenLookupPort accessTokenLookupPort;
```

`@RequiredArgsConstructor` 按字段声明顺序生成构造器，`jwtUtil` 是最后一个 ⇒
**删掉它只是少一个尾参**，不影响别的注入顺序。测试侧的 `@Mock JwtUtil jwtUtil`
与 `mockWebLogin()` 里的 `when(jwtUtil.getSubject(anyString())).thenReturn("123")`
一并删掉（后者若不删，在 `Strictness.LENIENT` 下不会红，但会留下一条**指向已删协作者**的 stub）。

⚠️ **这条值得单独记**：抽端口的收益不总是"少一条 import"。这一刀里，
消费方**少了一个协作者**、少了一份解析逻辑、少了一条对"主体是数字"的隐含假设 ——
而这些都不是靠"指标降了 1"能看出来的。

### 15.4 ⚠️ 消掉一条潜在故障路径：非数字主体从 500 变成 401

旧写法：`validateAccessToken` 通过之后直接 `Long.parseLong(...)`。
若主体不是数字 ⇒ `NumberFormatException` 冒到顶层 ⇒ **500**。
新写法：端口内部 `try/catch` 后返回 `null` ⇒ 消费方抛 `McpOAuthException(401)`。

按 §14.4 的等价性证明（唯一签发方传 `String.valueOf(user.getId())`、refresh 与 bind
令牌都进不了 `login:token:` 前缀），这条路径**今天不可达**。
但端口让它在结构上不可能发生 —— 这正是把"两步"收成一个契约方法的价值：
消费方再也拿不到"半验证通过"的中间状态。

### 15.5 ⭐ 端口设计在这一刀被验证：**零构建接线**

`integration` 是 `AccessTokenLookupPort` 的**第二个消费方**（第一个是 §14 的
`AgentStreamAuthHandshakeInterceptor`）。接入它需要改的只有消费方自己：

- `contracts/core-api` **一行没动**（没有新方法、没有新类型、没有新模块）；
- `pom.xml` **一行没动**（`integration` 与 `identity` 同属 `apps/core-host`，
  而 `core-api` 早已是它的依赖）。

这就是 §14.3 里"端口建在 `core-api` 而不是 `agent-api`"那条判据的回报：
若当初建在 `agent-api`，今天 `integration` 要么依赖 `agent-api`（为一个与 agent 无关的能力），
要么再造一个同能力的新契约 —— 而两份契约描述同一个能力，Javadoc 迟早会漂移。

**判据：一个能力一个端口。** 第二个消费方接入时的成本，是对这条判据最诚实的检验。

### 15.6 实测收益

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 30 | **29** |
| `implementation_edges` | 14 | **13** |
| `cross_deployment_unit_statements` | 13 | **12** |
| `cross_deployment_unit_edges` | 7 | **6** |
| `api_statements` | 9 | 9（不动） |
| `api_edges` | 6 | 6（不动） |

`integration -> identity` **整条边消失**。至此 **`identity` 作为目标域的跨单元边全部归零**
（剩下 `virtualpay` 5 / `admin` 2 / `profile` 1 三条都是同单元边）。

### 15.7 ⭐⭐ 图形状突变：一条 1 语句的边锁住了整个 `dinqor-core-host`

这是本刀最值得记的一件事。改完之后 `deployment-unit-coupling` 门禁报：

> `2 of 4 Wave 2 unit(s) in one component (1 mutually-dependent pair(s)); ...
> ['dinqor-agent', 'dinqor-core-host'] left the component (no unit points back at them)
> and can now be extracted alone; 1 pair(s) no longer mutual, delete both directions
> from MUTUAL_UNIT_PAIRS: [('dinqor-core-host', 'dinqor-platform-integration')]`

也就是说 **`dinqor-core-host` 也脱离了 Wave 2 强连通分量**。原因只有一条：
单元级边 `platform-integration -> core-host` **全部内容就是这一条 1 语句的边**
（`McpOAuthService` 的 import）。删掉它，`core-host` 就没有任何"指回它的单元"了。

⚠️ 此前大家（包括这份 ADR）一直把图形状读成"core-host、content-automation、
platform-integration 是一团"。**实际成环的是**
`content-automation -> platform-integration`（6）与
`platform-integration -> content-automation`（1）这一对；
`core-host` 的成员资格**完全挂在一条 1 语句的边上**。

⇒ **教训（已写进 `ci_gates.py` 的常量注释）**：**强连通分量的成员资格可以挂在一条
大小为 1 的边上**。每次动跨单元边之后要**重新推导**图形状，不能沿用上一刀的形状结论。
同理，`MUTUAL_UNIT_PAIRS` 是**双向**判据：删一对要**两个方向都删**（本次按门禁提示照做，
删后重跑，清理提示消失）。

剩余分量 = `{content-automation, platform-integration}`（1 对互依）。
**`dinqor-agent` 与 `dinqor-core-host` 现在都可以被单独抽取。**

### 15.8 测试

`apps/core-host` 1191 → **1194**（`McpOAuthServiceTest` 20 → **23**，+3）：

1. `revokeClientGrantRejectsWhenThePortSaysTheTokenIsNotAnIdentity` —— 端口答不上来时
   必须是 `login_required` / **401**，且**不许碰 mapper**；
2. `revokeClientGrantRequiresABearerPrefixedHeader` —— 非 `Bearer ` 前缀在**调用端口之前**
   就被拒（`verify(..., never()).resolveUserId(anyString())`）；
3. `listClientGrantsScopesTheQueryToTheUserIdThePortReturned` —— **端口返回的 777
   必须出现在查询条件里**（钉住"用的是端口返回值，不是常量"）。

⚠️ 第 3 条一开始写成 `verify(grantMapper).selectList(argWithUserIdValue(777L))`，
**红了**。原因是 MyBatis-Plus 的 `getParamNameValuePairs()` 是**惰性填充**的：
参数只在 SQL 片段被渲染时才进 map，而 mapper 是 mock，永远不会渲染。
仓里既有的 8 处同类断言都是先显式调一次 `getSqlSegment()` 再读 map
（如 `KnowledgeIngestionServiceTest`）—— 改成那个写法后通过。

全反应堆 **1417 → 1420**（0 fail）；26 门禁 → **24 pass / 0 fail / 2 skip**；
`contract-dependency-boundary` 仍 **8 模块 / 58 源文件**。

### 15.9 注入验证：四次

| # | 注入 | 实测红 |
|---|---|---|
| A | 消费方不再调用端口，`userId` 写死 `123L` | **2** |
| B | 消费方丢掉 `null` 守卫（忘了端口可以答不上来） | **1** |
| C | 消费方把整个 `Authorization` 头传给端口（忘了剥 `Bearer `） | **2** |
| D | 消费方把 `401` 改成 `403` | **1** |

⚠️ A 红两处而不是一处：写死 `123L` 之后 `pendingAuthorization` 那条用例**恰好**也是
`123L`（`mockWebLogin` 用的就是 `123L`）⇒ 它绿着过；红的是
`listClientGrantsScopesTheQueryToTheUserIdThePortReturned` 与
`revokeClientGrantRejectsWhenThePortSaysTheTokenIsNotAnIdentity`。
**这也说明"期望值必须实测"**（本仓第三次）：直觉会说"写死 userId 只会红一处"。

⚠️⚠️ **C 也红两处，而它照出了一条更重要的规则。** 失败信息是
`Expected McpOAuthException to be thrown, but nothing was thrown` 与
`expected: <true> but was: <false>` —— 两者只能由同一件事解释：**`userId` 是非 null 的**。
可测试里没有任何 stub 匹配 `resolveUserId("Bearer web-token")`。查 Mockito 5.7.0 源码
（`org.mockito.internal.util.Primitives`）得到答案：

```java
PRIMITIVE_OR_WRAPPER_DEFAULT_VALUES.put(Long.class, 0L);   // ← 包装类型也在表里
```

⇒ **未 stub 的 `Long` 返回值是 `0L`，不是 `null`。** 而本端口的契约恰恰用 `null` 表示
"这个令牌不构成一个有效身份" —— 于是**未 stub 的 mock 会伪装成"答上来了，用户 id 是 0"**，
`if (userId == null)` 这条守卫**一次都不会被触发**，而测试**照常绿**。

⚠️ 这条与 ADR 全篇的主题是同一族：**编译过、测试绿、门禁全 PASS，而守卫从未执行过。**
判据：**凡是"用 `null` 表达失败"的契约方法，测试里必须显式 stub 成 `null`；
绝不能让"未 stub 的默认值"替它回答**（`Long` / `Integer` → `0`，`Boolean` → `false`，
`String` / 对象 → `null`）。已写进 `backend-norms`。

⚠️ D 是有意加的一条**守卫强度探针**：`401` 不是"随便一个错误码"，它是 MCP 客户端
协议的一部分（`login_required` + 401）。只断言 `assertThrows(McpOAuthException.class)`
抓不到它，必须有 `assertEquals(401, error.status())`。

本刀的注入脚本改用 **Python**（`D:/blog/.workbuddy-ai/tmp/inject15.py`）：
bash 版踩过两次（引用未定义变量、还原锚点不唯一），Python 版可以把
"两条锚点动手前数一遍出现次数"、"每条注入后立刻比对 blob"写成**前置断言 + 后置断言**，
失败即 `return`，不会带着残留继续跑。
⚠️ 但 Python 版踩到一个新坑：**`mvn.sh` 是 shell 脚本，Windows 的 `CreateProcess`
不能直接 exec 它**（`OSError: [WinError 193] 不是有效的 Win32 应用程序`），
必须在脚本里内联 `mvn.sh` 里那条完整 java 命令行，并注明"内容需与 `mvn.sh` 保持同步"。

### 15.10 刻意没做的事

- **`profile -> identity`（1 impl）**：另一种能力（`getUserInfo`），且同单元。§14.2 已分类。
- **`agent -> integration`（2 impl）**：`PublishPostTool`，另一条边、另一个消费方。
- **`automation -> integration`（6 impl）**：目前最大的跨单元边，卡在 `MailService` 的 API 变更上，
  不是纯抽端口（§3quater.5）。
- **`McpOAuthService` 里 `jwtUtil` 的其它潜在用法**：确认过**没有**（`grep` 只有那一处）。
  若将来 MCP 自己签发 JWT，它会重新出现 —— 那时它属于 `integration` 自己的令牌体系，
  与 identity 的访问令牌是两回事。

### 15.11 下一刀

剩余跨单元实现边（**6 条**）：`automation → integration` 6、`agent → integration` 2、
`admin → automation` 1、`identity → integration` 1、`integration → automation` 1、
`studio → blogagent` 1。

1. **`agent → integration`（2）** —— `PublishPostTool`；做完 **`agent` 的出边全部归零**。
2. **`studio → blogagent`（1）** —— 可能很便宜（先侦察）。
3. **`automation → integration`（6）** —— 最大，但卡在 `MailService` 的 API 变更上。
4. **`admin → automation`（1）** —— 需要 SSE 广播端口。

---

## 16. 落地记录（十三）：站外同步抽成 `CsdnBlogSyncPort` / `JuejinBlogSyncPort` —— `agent` 的出边全部归零，`dinqor-agent` 完全离开跨域边表

### 16.1 目标边：两条 import 都在同一个文件里

`agent → integration` 剩 2 条实现语句，都在
`agent/application/PublishPostTool.java` 的第 13/14 行：

```java
import com.xander.lab.integration.mcp.csdn.application.CsdnBlogSyncService;
import com.xander.lab.integration.mcp.juejin.application.JuejinBlogSyncService;
```

`integration` 域被跨域引用的 7 个类型里，这两个正是 `lib-extraction.md` §3quater.5
点名"**不能直接搬、必须先抽端口**"的那两个（具体类，内部依赖 MyBatis-Plus mapper、
`javax.crypto`、Jackson 与各自的 `infrastructure` 包）。

### 16.2 ⚠️ 形状决定一：这是**两个**端口，不是一个带 `platform` 参数的端口

第一直觉是"一个 `BlogSyncPort` + `platform` 参数" —— 仓里已有先例
（`PublicationPort.publish(command, "csdn")` 就是平台当参数），而且跨域消费方
`PublishPostTool` 本来就按 `platform` 分支。**否掉了**，三条理由：

1. **参数表不同，合并要么造假要么变宽。** 掘金的 `sync` 多收
   `juejinCategoryId` / `juejinTagIds`（掘金要求自己的分类与标签 id），而 CSDN 没有这两个概念。
   合并成一个端口，CSDN 那条路就必须收下两个它永远不用的参数（端口变宽），
   或者端口只带交集、把掘金的能力砍掉（能力静默降级，§2.3 禁止）。
2. **域自己就是这么分的。** `integration` 为两个平台各有一套
   `*CredentialService` / `*BlogSyncService` / `*BlogSyncController` /
   `*McpApplicationService` —— 两套凭证存储、两套限流、两套 MCP 应用服务。
   端口跟着域的真实分解走，而不是把域合并成它没有的样子。
3. **零适配器。** 每个端口的方法表与它对应实现类的公开方法**逐字相同**，
   实现类加一句 `implements` 就够了 —— **不需要一个按平台字符串分派的中间类**，
   也就没有"分派写错只在运行时才炸"的新缝。

代价是两个几乎同形的接口（`findSucceeded` 那一行完全一样），共享同一个
`BlogSyncResult`。

**反面判据**：只有当合并后的签名对**每一个**实现都是它真正需要的形状时，才该合并。
⚠️ 计划文档（§18 / `lib-extraction.md` §3quater.5）把名字草拟成单数的 `BlogSyncPort`，
这里**偏离了那个草稿**；`CredentialPort` 同样是草稿里的单数（也是两个平台两套）。

### 16.3 ⚠️ 形状决定二：端口留住了跨域消费方"现在一律传 `null`"的那两个参数

`PublishPostTool` 与 `BlogPlanRunService` 调掘金同步时传的都是 `(null, null)` ——
"用文章自己的分类与标签名，由发布器解析成掘金 tag_id"。
所以 `juejinCategoryId` / `juejinTagIds` 在**跨域**路径上今天是死参数。

**它们仍然留在端口上**，因为判据不是"现在有没有人用"，而是"**拆服务之后这条路还走不走得通**"：
同域的前端 HTTP 层（`JuejinBlogSyncController`）会传真实值，而跨进程之后端口是那条路径
唯一能穿过的形状 —— 砍掉参数就是让"指定掘金分类/标签"这项能力在拆服务时**静默消失**。

⇒ 记成规则：**问一个参数该不该留，问的是"它有没有具名消费方"，不是"它现在有没有跨域消费方"。**

### 16.4 ⚠️ 形状决定三：契约不许依赖契约 ⇒ `BlogSyncResult` 是 `PublishResult` 的等价 DTO

两个 SyncService 现在返回 `content.api.PublishResult`
（`record PublishResult(String platform, String externalId, String url)`）。
端口的返回类型必须是**本模块**的类型：`integration-api` 一旦把 `content-api`
写进 POM，两个契约就绑成了一个（ADR-2）。

所以 `integration.api.BlogSyncResult` 是**逐字相同的第二份**，
**换算只有一处**（`CsdnBlogSyncService.toContract` / `JuejinBlogSyncService.toContract`），
三个分量**逐个点名**而不是靠位置 —— 三个都是 `String`，位置写错编译器不会有任何意见。

⚠️ 这是本仓库**第二次**"契约自带等价 DTO"（第一次是 `blogagent.spi` 的
`TokenUsage` / `BlogAgentImageUsage`，§13.5）。它是 ADR-2 的已知代价：
**每多一份就多一处必须成对改的地方。** 判据见待办第 2 项（契约能否依赖契约，仍未拍板）。

### 16.5 ⚠️ 代价：实现类必须改返回类型，于是同域 5 个消费方跟着改

Java 不允许"同名同参只差返回值"的重载，所以实现类一旦 `implements` 端口，
它原来的 `public PublishResult sync(...)` 就必须变成 `public BlogSyncResult sync(...)`。
受影响的**同域**消费方（都在 `dinqor-platform-integration`，拆完还在一个进程里，
所以它们继续注入具体类）：

| 文件 | 改了什么 |
|---|---|
| `csdn/api/CsdnBlogSyncController` | `Result<PublishResult>` → `Result<BlogSyncResult>` |
| `csdn/application/CsdnMcpApplicationService` | `syncOwnedPost` 返回类型 |
| `juejin/api/JuejinBlogSyncController` | `Result<PublishResult>` → `Result<BlogSyncResult>` |
| `juejin/application/JuejinMcpApplicationService` | `publishArticle` / `syncOwnedPost` |
| `dual/application/DualPublishMcpApplicationService` | 局部变量类型 |

⚠️ **这仍然是镜像、不进 `contract-changes.yaml`**：两个 record 的分量名与顺序逐字相同，
Jackson 按分量名序列化 ⇒ **HTTP 响应体一个字段都没变**。
判据是"**线上 JSON 变了没有**"，不是"Java 类型名变了没有"。
（`dual` 那一处是 `grep` 出来的 —— 它不在最初的清单里，见 §16.10。）

### 16.6 ⭐ 实测收益：`dinqor-agent` 完全离开跨域边表

| 边 | 实现语句 | 跨部署单元 |
|---|---|---|
| `agent → integration` | 2 → **0** | 边**整条消失** |
| `automation → integration` | 6 → 5 | 仍跨单元（顺带接上，见下） |
| **合计** | **29 → 26** | **12 → 9**（跨单元） |

```
implementation_statements            29 → 26  (-3)
implementation_edges                 13 → 12  (-1)
cross_deployment_unit_statements     12 →  9  (-3)
cross_deployment_unit_edges           6 →  5  (-1)
api_statements                        9 →  9  (未变)
api_edges                             6 →  6  (未变)
```

⭐⭐ **本刀真正的结果不是那 3 条语句，而是 `dinqor-agent` 从边表里彻底消失了。**
它是 `agent` 唯一还指向别的单元的域，而 `agent` 自己没有入边 ⇒
现在**出入边都是 0**（impl 与 api 两层都是）。也就是说
**`dinqor-agent` 这个部署单元已经完全没有跨域实现依赖，只剩下对契约的依赖** ——
Wave 2 拆它的时候不需要再动任何一行代码，只剩装配。

`automation → integration` 的 6 → 5 是**顺带**做的：`BlogPlanRunService` 的掘金那一条
本来就要接端口（否则 `JuejinBlogSyncPort` 会变成"只有一个消费方"的端口，
与 §7「一个能力一个端口」的判据自相矛盾）。它是纯类型替换，可证。

### 16.7 测试

两个 SyncService 在改造前**没有自己的测试**（只被 `PublishPostToolTest` 间接覆盖）。
它们现在是契约端口的实现，所以补了两个测试类：

| 测试类 | 用例 | 钉住什么 |
|---|---|---|
| `CsdnBlogSyncServiceTest` | 7 | 两份投影的换算；幂等键的**负号**；取 `categoryName` 而不是 `category`；软删与不存在同路径拒绝；`postId == null` 短路 |
| `JuejinBlogSyncServiceTest` | 10 | 同上 + 标签回落（`null` 与**空列表**都回落）与分类直通 |

契约侧三个测试类（`BlogSyncResultTest` 4 / `CsdnBlogSyncPortTest` 6 / `JuejinBlogSyncPortTest` 7）
与一个共用的 `ContractShape`（与 `blogagent-api` 那个同名类是同一条判据的第二次落地 ——
两个契约模块在 test scope 上互不可见，只能各带一份）。

⚠️ `PublishPostToolTest` 原来**没有掘金分支的执行测试**（只测 CSDN），补了一条，
它同时把"跨域传的那两个 `null`"逐字钉住。

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

### 16.8 注入验证：九次，其中三次**第一遍量不到 core-host**

九次注入全部生效、全部逐字节还原（判据是 `git hash-object` 前后相同）。
注入前基线：`CsdnBlogSyncPort.java` = `5c7408d4…`（5322B）、
`JuejinBlogSyncPort.java` = `ed6b9634…`（4137B）、`BlogSyncResult.java` = `bbc7244b…`（1831B）。

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

G / H / I 落在 `contracts/integration-api`，其余六个落在 `apps/core-host`。

**⚠️ 这张表里 G / H / I 的 `core-host` 列是第一遍量不到的。** 原因不是测试写得弱，是**量法**：

- `contracts/integration-api` 在反应堆里排在 `apps/core-host` **前面**。契约模块一旦变红，
  整条反应堆就停在那里，`core-host` 的测试**一个都没跑**。
- 加 `-fae`（fail at end）**不够**。`-fae` 只续跑"**不依赖**失败模块"的那些模块；
  `apps/core-host` 恰恰依赖 `contracts/integration-api`，所以照样被跳过。
  第一遍（无 `-fae`）G/H/I 报 3 / 1 / 2，第二遍（`-fae`）报 6 / 2 / 4 —— **都只是契约模块那一半**。
- **修法**：把 `-Dtest` 收窄到 `core-host` 自己的四个测试类
  （`PublishPostToolTest` / `BlogPlanRunServiceTest` / `CsdnBlogSyncServiceTest` / `JuejinBlogSyncServiceTest`），
  于是契约模块**一个测试都匹配不到**（因此永远不会红），`core-host` 就能照常跑。
  补测结果：**G = 0、H = 0、I = 7**。
  ⚠️ 这时契约模块连 `Tests run` 行都不打印，**"没有汇总行"必须判成"0 个测试"而不是"编译失败"**
  —— 第一版脚本把这两件事混在一起，报了个假的 `-1`。

这条量法本身值得记下来，因为它给的是**假的低分**：一个只由契约模块反射测试守着的注入
（G / H）看起来"只影响 6 个 / 2 个测试"，而它真正的影响面是**所有实现方与所有消费方**，
只不过那些测试在反应堆里排在后面、根本没机会跑。

**⭐ 顺带确认了一件好事**：G / H 在 `core-host` 侧真的是 0。
端口加 `default` 方法、端口首参改名，**消费方的 Mockito 桩一个都不动**
（Mockito 按位置匹配、实现类自己声明参数名，都不看接口的参数名）——
所以这两个形状**只有契约模块的反射测试在守**，`CsdnBlogSyncPortTest` /
`JuejinBlogSyncPortTest` 里那两条断言（`assertNoDefaultMethods`、
`assertFirstParameterIsTheCallerId`）不是锦上添花，是**唯一**的守卫。

**⚠️ 两个把这次测量卡住的工具坑（都已记进 `notes/repo-workflow.md`）：**

1. **`sha1sum` ≠ `git hash-object`。** 同一个文件，前者对**原始字节**算，
   后者对 `blob <len>\0` + 内容算、并且会先做 CRLF→LF 归一。
   `CsdnBlogSyncPort.java` 两个值分别是 `60a4fe6b…` 与 `5c7408d4…`。
   手上同时有这两个值时，会把它误判成"文件被改过、内容不对了"。
   **还原判据必须写清用的是哪一个算法，且全程只用同一个。**
2. **注入脚本不要自己起 `bash`。** 在沙箱里 `subprocess("bash", ...)` 里的 `bash`
   解析成了 WSL 的 `bash.exe`，被程序黑名单直接拦掉，脚本以 `EXIT=1` 静默退出。
   改成脚本**只负责改文件 / 还原文件**、`mvn` 由外层 shell 跑，就通了。

### 16.9 为什么没有加 `contract-changes.yaml` 条目

与 §14.9 / §15.9 同一条理由：**线上形状没变**。
两个 HTTP 控制器的响应类型从 `Result<PublishResult>` 变成 `Result<BlogSyncResult>`，
但两份 record 的分量名与顺序逐字相同（`platform` / `externalId` / `url`），
Jackson 按分量名序列化 ⇒ **JSON 逐字节不变**。

`contract-changes-completeness` 仍报 14 条，`api-migration-key-coverage` 仍报
213 条覆盖全部路由 —— 两条都 PASS。

### 16.10 刻意没做的事

- **`automation → integration` 的剩下 5 条没碰。** 它卡在 `CsdnCredentialService` /
  `JuejinCredentialService` / `MailService` 上，与本刀不是同一种形状
  （凭证服务的端口形状还没设计，`MailService` 卡在
  `automation.domain.EmailReminderMailTemplates` 这个反向依赖上）。
- **两个 SyncService 的 `toContract` 私有助手没有合并成共用的工具类。**
  两个类分属 `mcp.csdn.application` 与 `mcp.juejin.application` 两个包，
  共用要么新开一个公共包、要么把可见性放宽。三行 × 2 的重复比一个新缝便宜。
- **没有把端口做成 `platform()` 键 + `List<端口>` 注入。** 那是 `BlogPublisher` 的形状，
  但它会把"按平台分派"这件事推给**每一个**消费方，而本刀的两个消费方本来就按平台分支
  （`PublishPostTool`）或写死平台（`BlogPlanRunService`）—— 分派写在消费方是重复，写在实现侧是一次。

⚠️ **`grep` 漏了一个同域消费方。** 最初按 `CsdnBlogSyncService|JuejinBlogSyncService`
找消费方，得到 4 个；编译前再 `grep PublishResult` 才多出
`dual/application/DualPublishMcpApplicationService`（它调的是 `CsdnMcpApplicationService.syncOwnedPost`，
不直接提这两个服务）。**教训：改一个返回类型时，`grep` 的目标是"那个类型"而不是"那个类"。**

### 16.11 下一刀

剩余跨单元实现边（**5 条**）：`automation → integration` 5、`admin → automation` 1、
`identity → integration` 1、`integration → automation` 1、`studio → blogagent` 1。

1. **`studio → blogagent`（1）** —— 先侦察，可能很便宜。
2. **`automation → integration`（5）** —— 目前最大；其中 2 条是凭证服务（要新设计端口形状），
   1 条是 `MailService`（卡在邮件模板的反向依赖上，要改 API 而不是加端口）。
3. **`admin → automation`（1）** —— 需要 SSE 广播端口。
4. **`identity → integration`（1）** —— 与 `integration → automation` 构成真环，要一起看。

⚠️ **`dinqor-agent` 已经可以从边表里划掉了**（§16.6）—— 它的抽取不再需要任何契约工作。

---

## 17. 落地记录（十四）：`studio → blogagent` 的凭证回落从**代码**搬到**配置** —— 一条 import 换一个 YAML 占位符

### 17.1 目标边：不是"调能力"，是**借别人的凭证**

`studio → blogagent` 是 **1 条实现语句**，落在
`studio.infrastructure.Img2ThreeModelClient`
（`dinqor-core-host → dinqor-content-automation`）：它 import 了 blogagent 的
`infrastructure.BlogAgentProperties`，用来给 img2three 的模型凭证兜底。

```java
private final Img2ThreeProperties properties;          // studio 自己，prefix = img2three
private final BlogAgentProperties blogAgentProperties; // blogagent 的，prefix = blog.agent

private String resolveApiKey()  { return hasText(properties.getApiKey())  ? properties.getApiKey()  : blogAgentProperties.getApiKey(); }
private String resolveModel()   { return hasText(properties.getModel())   ? properties.getModel()   : blogAgentProperties.getModel(); }
private String resolveBaseUrl() { return hasText(properties.getBaseUrl()) ? properties.getBaseUrl() : blogAgentProperties.getBaseUrl(); }
```

⇒ 语义是「img2three 有自己的模型端点配置，**没配就借用 blogagent 的**」。
它读的正是 ADR 待办 30 禁止的形状：**消费方读提供方的 `@ConfigurationProperties`**。

### 17.2 ⚠️ 侦察结论：这一刀不能用"抽端口"收

三个候选都被否掉，理由是**各自违反一条已经定下来的规则**：

| 做法 | 为什么不行 |
|---|---|
| 在 `blogagent-api` 加端口，把解析后的端点暴露给 studio | 正是待办 30 禁止的形状：**提供方的配置变成跨域契约**。端口返回的就是"我怎么配置的" |
| 在 `core-api` 加"默认模型端点"端口 | 看着像能力归位，但 studio 并不是在调 blogagent 的能力 —— 它在**做自己的模型调用**，只是借上游凭证。为"借凭证"立一个契约，等于把"两个域共用一套上游凭证"合法化，而 `config-ownership.yaml` 已经把它们记成两家各自的 `service_private` / `secret` |
| 让 `Img2ThreeProperties` 自己配全、删掉回落 | ⚠️ **行为变更**：只配了 `BLOG_AGENT_*` 的部署会直接报"尚未配置模型服务"。`application-local.yml` 恰好就是这种部署 |

⇒ 真正的形状是：**回落属于"配置层"，不属于"代码层"。** 它是"同一个值在两个服务里都要有"，
不是"一个服务向另一个服务要东西"。拆服务之后前者由运维 / Nacos 承担；后者要跨进程调用 ——
而**为了读一个凭证去跨进程调用**显然不是目标架构想要的。

### 17.3 ⭐ 形状决定：回落写成**属性名**，不是环境变量名

```yaml
img2three:
  agent:
    base-url: ${IMG2THREE_AGENT_BASE_URL:${blog.agent.base-url:}}
    api-key: ${IMG2THREE_AGENT_API_KEY:${blog.agent.api-key:}}
    model: ${IMG2THREE_AGENT_MODEL:${blog.agent.model:}}
```

⚠️ 这里有一个**很容易写错、而且写错了不会报错**的选择：占位符里写
`${blog.agent.api-key:}`（**属性名**）还是 `${BLOG_AGENT_API_KEY:}`（**环境变量名**）？

- `application-local.yml` 是**直接把值写在 `blog.agent.api-key` 上**的（不是设环境变量）；
- 写环境变量名 ⇒ 本地那份配置**回落不到** ⇒ 行为变了，而且没有任何东西会报错。

⇒ **必须是属性名。** `Img2ThreeCredentialFallbackTest` 里的
`alsoFallsBackToABlogAgentPropertyWrittenInAProfileFile` 专门钉这一条，注入 B 证明它接着线。

⚠️ 一个正确的巧合：本仓 `agent.model.*`（`application.yml` 第 252–255 行）**早就**是这么写的
（`api-key: ${AGENT_MODEL_API_KEY:${BLOG_AGENT_API_KEY:}}`）—— 注意它写的是**环境变量名**。
但 `agent` 与 `blogagent` **也不在同一个部署单元**（`dinqor-agent` vs `dinqor-content-automation`）。
也就是说"在 YAML 层复用另一个域的配置"在本仓是**既有做法**，只是它**不在扫描器的视野里**
（扫描器数的是 Java import）。本刀是把既有做法用到 `studio` 上，不是发明新缝 ——
但这条视野盲区本身要记下来（见 §17.10）。

### 17.4 ⚠️ 为什么这一刀不需要动任何 Java 契约

`img2three` 与 `blogagent` 之间**没有能力调用**，只有"同一个值"。所以：
无新模块、无新契约类型、无新端口、`pom.xml` 一行没改、`contracts/` 一个字没改。
Java 侧只做减法：删一个 import、删一个字段、删三个私有方法，`endpoints()` 改成直接取 `properties`。

### 17.5 ⚠️ 代价：单元测试再也构造不出那条路径 ⇒ 必须用**真实交付的** YAML 守住

把回落从代码搬到配置，代价是**可测性**：直接 `new Img2ThreeProperties()` 不经过任何 YAML，
于是"回落真的存在"这件事在单元测试里**无法表达**。

⇒ 新增 `Img2ThreeCredentialFallbackTest`：把**真实交付的** `application.yml` 用
`YamlPropertySourceLoader` 加载进来，用 `PropertySourcesPropertyResolver` 解析那三个键，
再叠一个模拟的 `application-local.yml` / 环境变量源。三条用例：

1. 只配了 `BLOG_AGENT_*` ⇒ 三个键整体回落到它；
2. 同时配了 `IMG2THREE_AGENT_*` ⇒ img2three 自己的赢；
3. `blog.agent.*` 写成**属性**（`application-local.yml` 的真实形态）⇒ 也能回落。

**这是"搬家"之后唯一能守住它的东西** —— 与 §10.4 那条"一份实体两份投影必须配跨投影断言"同源：
**东西搬走了，守卫也要跟着搬，否则搬完就没人看了。**

### 17.6 顺手补掉一条**零覆盖**的路径

`endpoints()` 的"网关关闭"分支此前**一条测试都没有**：两个既有用例的网关桩都返回非空端点，
方法在第二行就早退了。补了两条：

| 用例 | 钉住 |
|---|---|
| `buildsTheEndpointFromImg2ThreeOwnSettingsWhenTheGatewayIsOff` | 网关关闭时 baseUrl / apiKey / model **三个都取对**（起真 HTTP server 看请求体） |
| `reportsNotConfiguredWhenNeitherImg2ThreeNorTheBlogAgentFallbackIsSet` | 两端都空时抛 `IllegalStateException`，且文案同时点名 `IMG2THREE_AGENT_API_KEY` 与 `BLOG_AGENT_*` |

⚠️ 与待办 32（"把域内方法提升成契约实现时先查它有没有测试"）同族：
**改一段代码时顺手数一下它有几条测试** —— 这里答案是 **0**，而它是运维唯一会看到的提示。

### 17.7 实测收益

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 26 | **25** |
| `implementation_edges` | 12 | **11** |
| `cross_deployment_unit_statements` | 9 | **8** |
| `cross_deployment_unit_edges` | 5 | **4** |
| `api_statements` | 9 | 9（不动） |
| `api_edges` | 6 | 6（不动） |

`studio → blogagent` **整条边消失** ⇒ ⭐ **`studio` 作为来源域在实现层与 api 层都一条出边都没有了**
（它此前的另一条 `studio → admin` 已被 §3quinquies 的 `ModelGateway` 收掉）。

### 17.8 注入验证：六次

六条注入全部落在 `apps/core-host`，用 `-Dtest` 收窄到 studio 的两个测试类 ——
这样其余模块匹配不到测试、永远不红，不会出现 §16.8 那种"契约模块先红、实现模块整块被跳过"的假低分。

| # | 注入 | 落在 | 实测红 |
|---|---|---|---|
| A | 回落整条丢掉（只认 `IMG2THREE_AGENT_API_KEY`） | `application.yml` | **2** |
| B | 回落写成**环境变量名** `${BLOG_AGENT_API_KEY:}` | `application.yml` | **1** |
| C | 回落反过来**盖过** img2three 自己的值 | `application.yml` | **1** |
| D | 网关关闭时 baseUrl 写死成常量 | `Img2ThreeModelClient` | **1** |
| E | `apiKey` / `model` 两个相邻 `String` 换位 | `Img2ThreeModelClient` | **1** |
| F | 只把 `base-url` 这一行的回落丢掉 | `application.yml` | **1** |

六条全部生效，两条目标文件在跑完后**逐字节还原**（`git hash-object` 与基线相同：
`application.yml` = `2e452954…`、`Img2ThreeModelClient.java` = `6b74c9a5…`）。

⚠️ **B 是本刀最有价值的一条**：它把"占位符写属性名还是环境变量名"这个**写错了不会报错**的选择
变成一条会红的断言。如果没有 `alsoFallsBackToABlogAgentPropertyWrittenInAProfileFile`，
B 会**绿着过**，而本地部署的 img2three 会静默失去凭证。

⚠️ **D 的失败信息没有指向真正的原因（"守卫的失败信息"第三次实例）。** 它把 baseUrl 写死成
`https://api.openai.com/v1` 之后，用例不是断言失败，而是**真去请求了公网**、24 秒后以网络错误
结束（`Errors: 1` 而不是 `Failures: 1`）。原因是这条用例只能通过"请求落到了哪个地址"间接断言
baseUrl（`endpoints()` 是私有方法），而地址错了请求就飞到别处去了。
⇒ **红是红了，但红的原因看起来像"网络问题"而不是"baseUrl 取错了"。**
与 §11.2 / §13.7 那两次同族：**失败信息指向的环境越像外部故障，越容易被人当成 flaky 忽略。**
（记入待办；改法要么把端点解析抽成可测的小函数，要么给客户端注入一个固定的短超时。）

⚠️ **两个把这次测量卡住/打歪的工具坑（都已写进 `notes/repo-workflow.md`）：**

1. **⚠️ 注入锚点必须对"行尾已归一"的文本匹配。** 本仓工作区 CRLF / LF **混杂**
   （实测 `application.yml` 是 **LF**、`Img2ThreeModelClient.java` 是 **CRLF**），
   而锚点是以 `\n` 结尾写的 ⇒ 在 CRLF 文件里 `…));\r\n` 不含子串 `…));\n`，
   锚点**静默 0 命中**（第一遍 D 就是这样中止的：`锚点出现 0 次，期望 1 次`）。
   修法：读的时候把 `\r\n` 归一成 `\n` 再匹配，写回去时按记录的原行尾还原。
   ⚠️ 顺带一条读数坑：**Git Bash 里 `grep -c $'\r'` 判 CRLF 不可信**（对 LF 文件也报满行数），
   要用 Python 数 `b"\r\n"` 的字节数。
2. **⚠️ 注入脚本不要在前台跑。** 六条注入约 4.5 分钟，前台会在默认超时处被 SIGTERM，
   **把目标文件留在注入态**（实测残留了 E）。必须后台跑；**任何中断之后第一件事是比 blob**。
   这是"脚本跑完了 ≠ 注入生效了、也还原了"的第四次变体 —— 这次是"脚本被杀了"。

### 17.9 为什么没有加 `contract-changes.yaml` 条目

契约模块一个字没改，路由与响应形状都没动 ⇒ 没有契约变更要登记。

### 17.10 ⚠️ 刻意没做的事 / 残留

- **⚠️ 没有解决"谁拥有 img2three 的凭证"这个配置归属问题。** `config-ownership.yaml` 把
  `blog.agent.api-key` 记成 `owner: content-automation` + `secret` + `NACOS: DINQOR-CONTENT-AUTOMATION`，
  把 `img2three.agent.api-key` 记成 `owner: studio` + `secret` + `NACOS: DINQOR-CORE`。
  改造后 img2three 的默认值仍然指向 content-automation 的环境变量 —— 这是**运维约定**，
  不是代码依赖，但清单里没写、也没有门禁守。已记入待办。
- **⚠️ 扫描器看不见配置层的跨域引用。** 本刀把一条 import 换成一行 YAML 占位符，指标如实下降
  （跨单元实现边 5 → 4），但"studio 的配置默认值指向 content-automation 的环境变量"这件事
  **在 `cross-domain-imports.yaml` 里一个字都看不到**。这与 `SHARED_KERNEL` 漏登记那个盲区同类：
  **指标只能看见它被设计来数的那种耦合。** 已记入待办。
- **没有动 `agent.model.*` 那条同样形状的 YAML 回落**（它没有 Java 边，不在本刀范围）。
- **没有删 `Img2ThreeProperties` 里那三个 Java 默认值 `""`** —— 它们现在是"没配"的表示，
  `hasText` 判断仍然需要。

### 17.11 下一刀

剩余跨单元实现边（**4 条**）：`automation → integration` 5、`admin → automation` 1、
`identity → integration` 1、`integration → automation` 1。

⚠️ **`integration ↔ automation` 是唯一一对互相依赖的单元**（5 条出 + 1 条回）。
本刀之后它成了最大的一块，**要一起看** —— 只收一边会把环变成"单向但仍在跨单元"：

1. **`admin → automation`（1）** —— `AdminNotificationController`，需要 SSE 广播端口。
   ⚠️ 契约不许带框架类型 ⇒ 端口**不能**直接返回 `SseEmitter`，形状要先设计。
2. **`integration → automation`（1）** —— `MailService` import
   `automation.domain.EmailReminderMailTemplates`，是**域实体跨边界**（ADR-2 直接禁止的形状），
   要改 API 而不是加端口。
3. **`identity → integration`（1）** —— `AuthService`。

---

## 18. 落地记录（十五）：`MailPort` —— 一个**枢纽**上的三条边，跨单元实现图变成**无环链**

### 18.1 目标边：三条边都经过同一个类

`integration.mail.MailService` 是本刀之前唯一一个**同时参与三条跨单元边**的类型：

| 边 | 语句 | 落点 |
|---|---|---|
| `automation → integration` | 1 | `EmailReminderScheduler` 调 `sendReminderMail(...)` |
| `identity → integration` | 1 | `AuthService` 调 `sendVerificationCode(...)` |
| `integration → automation` | 1 | **反向**：`MailService` import `automation.domain.EmailReminderMailTemplates` |

⭐ 第三条与第一条闭成一个**真环**，也是当时跨单元实现图里**最后**一个环。
§7.12 当时就记下"要断环必须改 `MailService` 的 API（让调用方把渲染好的正文传进来），
不是加个端口就行"—— 本刀就是那一刀。`automation-api` 的 `pom.xml` 与 `package-info`
里也各留了一段同样的说明，本刀把它们改成"已做"。

### 18.2 ⚠️ 侦察结论：真环的成因不是"互相调用"，是"提供方替消费方渲染"

看 `MailService.sendReminderMail` 的签名就清楚了：

```java
sendReminderMail(String to, String subject, String userMessage,
                 Instant scheduledAt, String contentType, String templateId)
```

六个参数里只有 `to` / `subject` 是**投递**需要的。后四个**只服务于渲染**：
按 `contentType` / `templateId` 归一化内容类型、按 `email-reminder.display-zone`
把 `scheduledAt` 格式化成显示时区的时间、挑一个品牌化 HTML 版式、再生成纯文本兜底。

而渲染要用的两样东西**都不是 integration 的**：

- `automation.domain.EmailReminderMailTemplates` —— 另一个域的**业务类型**（ADR-2 直接禁止）；
- `email-reminder.display-zone` —— `docs/config-ownership.yaml` 里 **owner 记在
  `content-automation` 名下**的键。⭐ **台账是对的，代码是错的**：读它的人与拥有它的人不是同一个。

⇒ 所以这不是"两个服务互相调用"（拆完之后 A 调 B、B 调 A 是正常的），
而是**提供方在替消费方做消费方的业务**。判据：

> **渲染**（"这封提醒长什么样"）是 automation 的业务概念；
> **投递**（"把它送出去"）才是 integration 的能力。

⚠️ 这条判据与本仓既有布局吻合，不是本刀发明的：`integration/mail/` 里**本来就**住着
`LoginVerificationMailTemplates`（登录验证码的模板）—— "邮件模板住在邮件包里"是本仓既有做法。
真正的错位只有一处：**提醒**的模板住在 `automation`，而它的渲染却在邮件包里。

### 18.3 ⭐ 形状决定：端口只带"发什么"，不带"怎么渲染"

```java
void sendSimpleMail(String to, String subject, String content);
void sendVerificationCode(String email, String code);
void sendHtmlMail(String to, String subject, String plainText, String html);
```

`sendHtmlMail` 收的是**两份渲染好的成品正文**。于是端口上
**没有 `Instant`、没有 `contentType`、没有 `templateId`、没有 `displayZone`** ——
一旦它们回来，提供方就要重新 import 业务域的模板类、重新读别人家的配置键，
反向边与"读别人的配置"会**一起**复活。

⚠️ **没有把 `sendSimpleMail` 与 `sendHtmlMail` 合并成一个带 `html` 可选参数的方法**：
两者的**载体不同**（`SimpleMailMessage` vs MIME multipart），而且 `html == null` 时
"退回纯文本"这种隐式回退会让**"渲染失败"看起来像"这封邮件本来就没有正文"**。

### 18.4 三个方法，三个具名消费方

| 方法 | 消费方 | 为什么不能并进别的 |
|---|---|---|
| `sendSimpleMail` | `EmailReminderScheduler` 的 PLAIN 分支；同单元的 `RedisHealthMonitor` | 单段纯文本，载体是 `SimpleMailMessage` |
| `sendVerificationCode` | `identity.AuthService` | 正文版式由**邮件域自己的** `LoginVerificationMailTemplates` 生成 —— 它住在 `integration.mail`，本来就不该跨边界 |
| `sendHtmlMail` | `EmailReminderScheduler` 的 HTML 分支 | 两份正文，载体是 MIME multipart |

⚠️ `sendVerificationCode` 看似"就是 `sendHtmlMail` 的一个特例"，但它的正文**在提供方内部生成**，
所以它不是"投递"而是"发一封特定业务的邮件"。换成 `sendHtmlMail` 会让 `AuthService`
必须知道验证码邮件的版式 —— 那正是本刀要消掉的形状。

### 18.5 ⚠️ 刻意不收 `userId` —— 由**反向**守卫钉住

三个方法都不收 `userId`，而且不许加。判据是"这个参数有没有具名消费者"：

- `sendVerificationCode` 发生在**登录之前**，那时还没有身份；
- 另外两个是**传输**，归属判定（"这个提醒是不是你的"）在 `automation` 自己的库里、
  在调本端口**之前**就做完了。

⚠️ 但"别的端口都收 `userId`"这条**一致性**会诱使后来者顺手补上，而补完之后：
多一个没人读的参数、编译过、指标不动、**26 条门禁全绿**、消费方的 Mockito 桩一个都不用改。
⇒ 由 `MailPortTest.takesNoCallerIdAndThatIsDeliberate`（`assertNoParameterIsNamed`）钉住 ——
这是 §13.9 那条"一对守卫"判据的**第三个**实例。

### 18.6 ⚠️ 代价：改 API 而不是加端口 ⇒ 消费方跟着改

| 改动 | 文件 |
|---|---|
| `MailService` 收窄成只投递 + `implements MailPort` | `integration/mail/MailService.java` |
| 渲染逻辑搬回本域（纯函数、无字段、无 Spring） | `automation/domain/EmailReminderMailRenderer.java`（**新增**） |
| 调度器依赖端口、自己渲染 | `automation/infrastructure/EmailReminderScheduler.java` |
| `AuthService` 依赖端口 | `identity/application/AuthService.java` |
| 端口实现清单 +1 | `migration/PortDelegationTransactionTest.java` |

⚠️ **一处行为细节被逐字保留**：`ZoneId.of(displayZone)` 原本就在 HTML 分支**里面**，
所以 `display-zone` 配错时**纯文本提醒不受影响**。渲染器因此收 `String displayZoneId`
而不是 `ZoneId` —— 收 `ZoneId` 会把解析提到调用点，纯文本提醒会跟着一起炸。
由 `invalidDisplayZoneOnlyBreaksTheHtmlPath` 钉住。

⚠️ **另一处逐字保留**：选了 `"none"`（不要版式）时，**纯文本兜底里不带时间与时区**
（`TEMPLATE_NONE.equals(...) ? "" : formattedTime`）。这是搬家时最容易被"顺手统一"掉的一处，
由 `noneTemplateKeepsTimeOutOfThePlainTextFallback` 钉住。

⚠️ 邮件失败文案变了：原来 `MailService` 抛 `RuntimeException("定时提醒邮件发送失败", e)`，
现在走端口的 `sendSimpleMail` / `sendHtmlMail`，抛的是 `"邮件发送失败，请稍后重试"`。
**落库的错误文本没有变** —— `EmailReminderScheduler.rootMessage(e)` 取的是**根因**消息
（SMTP 异常本身），不是包装层。这正是那条 `rootMessage` 循环存在的理由。

### 18.7 顺手补掉**两条零覆盖**

| 此前 | 为什么没人发现 |
|---|---|
| `MailService` **没有测试类** | 它没实现任何契约 ⇒ 方法体只在三个消费方的测试里以 **mock** 形式出现，**真实实现一次都没被跑过** |
| `sendReminderMail` 的渲染逻辑**零断言** | 只被调度器间接走到，而调度器的测试把邮件服务整个 mock 掉了 |

⇒ 新增 `MailServiceTest`（4 条）与 `EmailReminderMailRendererTest`（6 条）。
与待办 32（"把域内方法提升成契约实现时先查它有没有测试"）同源：
**抽端口会让原本不重要的东西变重要**，而"没人写那条断言"不会有任何东西报错。

⭐ **`MailServiceTest` 第一版就红了一条，而且红得有价值**：`sendVerificationCode` 的
HTML 正文里，验证码是**逐位放进不同的 `<span>`** 的（`LoginVerificationMailTemplates.buildDigitCells`），
所以 HTML 原文里**根本不含**连续子串 `246810`。第一版对两份正文都断言 `contains("246810")` ⇒ 红。
判据随之改成"纯文本按**连续子串**判、HTML 按**剥掉标签与空白后的文本**判"，
并把"HTML 是逐位渲染的"这个既有形状一起钉住。⇒ 已记入待办 47。

### 18.8 ⭐ 实测收益：跨单元实现图变成**无环链**

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 25 | **22** |
| `implementation_edges` | 11 | **9** |
| `cross_deployment_unit_statements` | 8 | **5** |
| `cross_deployment_unit_edges` | 4 | **2** |
| `api_statements` / `api_edges` | 9 / 6 | 9 / 6（**不动**） |

三条边**全部消失**：`identity → integration` 与 `integration → automation` 归零，
`automation → integration` 5 → **4**。

⭐ 剩余跨单元实现边**只有两条**，而且构成一条**链**：

```text
dinqor-core-host ──1──▶ dinqor-content-automation ──4──▶ dinqor-platform-integration
    (admin→automation)              (automation→integration)
```

⇒ **每一个 Wave 2 单元都是单元素分量，跨单元实现图无环。**
环的作用是**否认抽取顺序**；链给了一个。
这与 §15.7 / 待办 34（"分量的成员资格可以挂在一条 1 语句的边上"）是同一张图，
只是这次它朝**好的方向**动了。

### 18.9 门禁改动：清空互依对基线 + 一个会撒谎的 detail 字符串

`deployment-unit-coupling` 的判据 3 是**双向**的，它自己报出了这条进展：

> `1 pair(s) no longer mutual, delete both directions from MUTUAL_UNIT_PAIRS:
> [('dinqor-content-automation', 'dinqor-platform-integration')]`

按提示**两个方向一起删**，`MUTUAL_UNIT_PAIRS` 变成**空集**。
⚠️ 空基线**不是**"关掉了这条判据"，而是它**最严**的形式：任何**新**出现的互依对都会报 `added` 并失败
（由本刀注入 J 实测，见 §18.10）。这条基线救过自己 —— 见待办 34。

⚠️ 同时修掉一个**会撒谎的 detail 字符串**：`wave2_component = max(components.values(), key=len)`
在**所有分量都是单元素**时会按迭代顺序任选一个，于是原来的
`"{len(wave2_component)} of 4 Wave 2 unit(s) in one component"` 会打印 **"1 of 4"**，
再把另外 3 个单元列进 `outside` —— 读起来像"还有一个单元缠着"，而事实是**四个都可以单独抽取**。
⇒ 分量大小为 1 时走单独一句（实测输出）：

> `0 of 4 Wave 2 unit(s) share a component (0 mutually-dependent pair(s)); every unit is a
> singleton and the cross-unit implementation graph is acyclic, so no unit can block
> another's extraction; dinqor-bloghtml-preview still isolated`

⚠️ 这与待办 11（判据 2 空转）同族：**判据的"进度提示"也会只朝一个方向看** ——
它假设"存在一个非平凡分量"，而这一刀恰好把它消灭了。

### 18.10 注入验证：八次

八条注入全部落在**本刀新增/改动的文件**上。⚠️ 按待办 41 的规矩，`-Dtest` 收窄到"依赖方自己的测试类"。

| # | 注入 | 落在 | 实测红 |
|---|---|---|---|
| A | `MailPort` 上加一个带 `templateId` 的 **`default`** 重载 | `MailPort` | **4**：`declaresNoDefaultMethods` / `carriesNoRenderingParameter` / `declaresExactlyTheseThreeMethods` / `sendHtmlMailTakesFinishedPlainTextAndHtml` |
| B | `MailPort` + `MailService` 把 `sendHtmlMail` 的 `plainText` / `html` 换位 | 契约 + 实现 | **1**：`sendHtmlMailTakesFinishedPlainTextAndHtml`（`expected: <plainText> but was: <html>`） |
| C | `MailService` 去掉 `implements MailPort`（连带删三个 `@Override`） | `MailService` | **2**：`theAuditedClassListIsComplete` / `theAuditedClassesReallyDoImplementSomething` |
| D | 渲染器删掉 PLAIN 早退分支 | `EmailReminderMailRenderer` | **5**：渲染器 2 失败 + 1 错误；调度器 2 失败 |
| E | 渲染器把 `wrapped` 恒置 `true` | `EmailReminderMailRenderer` | **1**：`noneTemplateKeepsTimeOutOfThePlainTextFallback` |
| F | 渲染器把 `ZoneId.of` 提到 PLAIN 分支之前 | `EmailReminderMailRenderer` | **1**：`invalidDisplayZoneOnlyBreaksTheHtmlPath`（`Errors: 1`） |
| G | 调度器把 html / plain 两个投递分支互换 | `EmailReminderScheduler` | **3**：该测试类全部 |
| J | `MailService` 重新 import automation 的域类型（不使用） | `MailService` | **门禁 2 条**（见下） |

⭐ **A 是本刀最有价值的一条**：它把"端口变宽"做成一次**编译通过、耦合指标一动不动、
26 条门禁全绿、消费方 mock 一个都不用改**的改动 —— 只有反射测试能守。
这是"注入后门禁没红 ≠ 这个注入没意义"的样本。

⭐ **B 证明了反射断言有**独立**价值**：换位之后行为测试 `MailServiceTest` **4/4 全绿**
（它断言"两份正文都在 MIME 树里"，与顺序无关），只有按**参数名**钉的
`MailPortTest.sendHtmlMailTakesFinishedPlainTextAndHtml` 红了。

⚠️ **这条结论不是第一遍就得到的。** 第一遍把 `-Dtest` 写成 `MailPortTest,MailServiceTest`，
而 `integration-api` 一红就让**依赖它的** `core-host` 整块跳过 ⇒ `MailServiceTest` **根本没跑**，
"它绿着"是**没跑**而不是**通过**。第二遍单独跑 `-Dtest=MailServiceTest` 才拿到 4/4。
⇒ 与待办 41 同源、**第二次**踩到：**跨模块注入时，"某个测试没红"必须先确认它跑了**。

⭐⭐ **J 是本刀最有价值的门禁验证，因为它把两条门禁分开了**：

| 门禁 | 注入后直接跑 | 注入后**先 `scan.py` 再**跑 |
|---|---|---|
| `cross-domain-import-baseline` | **FAIL**（`new cross-domain implementation edge(s): integration->automation`） | **PASS**（23/10/6/3） |
| `deployment-unit-coupling` | **FAIL**（`new mutually-dependent unit pair(s) [...]`） | **FAIL**（同一条） |

⇒ 前者的红**只是**"改了代码没重跑 `scan.py`"—— 待办 16 那条已知缺陷又实测到一次
（它的基线是 `scan.py` 的**产物**，刷新基线就绿了）。
后者的基线是 `ci_gates.py` 里的**常量** `MUTUAL_UNIT_PAIRS`，与 `scan.py` 无关 ⇒
**清空后的基线确实是最严的形式**：一条反向边就能让它红。
⚠️ 这两行也是"判据的基线必须来自判据之外"的反例/正例对照。

八条注入全部逐字节还原（四个文件的 `git hash-object` 与基线相同）。

⚠️ **两条工具坑**（详见 `backend-norms`）：
1. **注入脚本的前置断言必须按注入形状分开写** —— `fwd` 与 `mut` 互为子串时，
   "目标态还不存在"这条断言会在**某一个方向**上恒假。本刀第一次写成对称判据，
   结果 **J 的还原被自己的断言卡住**、`MailService` 留在注入态（第一件事是比 blob）。
   第十七刀踩的是"删除式"那半边，本刀是"插入式"那半边 —— **两种方向各一次**。
2. **C 第一版只删 `implements MailPort`，结果是编译失败**（三个 `@Override` 找不到超类型方法），
   于是"红"的是编译器而不是判据。⇒ **注入要红在判据上，不能红在编译上** ——
   改完先确认它是"能编译但测试红"，否则测的是 javac。

### 18.11 为什么没有加 `contract-changes.yaml` 条目

同 §3bis / §3nonies / … / §3vicies：`contract-changes.yaml` 登记的是**前端可见契约的变化**
（token / header / SSE / WebSocket / 存储 / 共享内核依赖边界 / 可用性语义）。
`MailPort` 是**服务间**契约 —— 路由、响应形状、错误码、前端可见类型**一个都没动**；
`MailService` 的收窄也只影响 Java 调用方。`contract-changes-completeness` 仍报 14 条。

⚠️ 与前面几刀**不同的一点**：本刀动了 `integration → automation` 这条**反向边**，
按 V9 §2.3 的元规则（"能力改变必须显式登记"）值得问一句"这算不算能力变化"。
结论是**不算**：`MailService` 的**能力**（能发邮件）没变，变的是**它的参数表**，
而它没有任何前端可见的入口。真正被削掉的是一处**架构违规**。

### 18.12 下一刀

剩余跨单元实现边 **2 条 / 5 语句**：

1. **`automation → integration`（4）** —— **全部**是凭证服务：
   `BlogContentPlanService` 与 `BlogPlanRunService` 各自 import
   `CsdnCredentialService` / `JuejinCredentialService`（2 文件 × 2 = 4 语句）。
   形状与 `OssPort` 同族（"取一份凭证"），且**已有两个现成先例**：
   `CsdnBlogSyncPort` / `JuejinBlogSyncPort`（§16.2 拍板"一个平台一个端口"）。
   ⇒ 很可能是 `CredentialPort` 的**两套**，而不是一个带 `platform` 参数的端口。
   ⚠️ `lib-extraction.md` §3quater.5 的草稿里写的是单数 `CredentialPort`，取这一刀时先复核。
2. **`admin → automation`（1）** —— `AdminNotificationController`，需要 SSE 广播端口。
   ⚠️ 契约不许带框架类型 ⇒ 端口**不能**直接返回 `SseEmitter`，形状要先设计。
   这是 `automation` 的**最后一条入边**，收掉它 `automation` 就成了**零跨域消费方**的域。

⚠️ 两条都收掉之后，**跨单元实现边归零**，`cross-domain-imports.yaml` 里只剩同单元的边。
⇒ 那时 `deployment-unit-coupling` 的三条判据全部只剩"不许变坏"的含义，
`MUTUAL_UNIT_PAIRS` 与 `WAVE2_UNITS` 的关系要重新想一遍。

---

## 19. 落地记录（十六）：`CsdnCredentialPort` / `JuejinCredentialPort` —— 跨单元实现边只剩一条

### 19.1 目标边：一个能力、一个方法、两个平台

`automation → integration`，**4 条实现语句**，落在两个文件上，每个文件各 import 两个类型：

| 文件 | import |
|---|---|
| `automation.application.BlogContentPlanService` | `CsdnCredentialService`、`JuejinCredentialService` |
| `automation.application.BlogPlanRunService` | 同上 |

⚠️ 侦察第一件事是**数清跨域表面有多大**，而不是先想端口形状。结果是两个实现类各有
**6 个公开方法**，而跨域位置只用到一个：

| 方法 | 跨域调用点 | 结论 |
|---|---|---|
| `isAuthorized(Long)` | `BlogContentPlanService.toVO`（填前端派生字段）、`BlogPlanRunService.publishCsdn` / `publishJuejin`（发布前置条件） | **上端口** |
| `isExpired` / `markExpired` / `requireCookie` / `requireCredential` / `save` / `delete` | **全在 `integration` 域内**（授权回调 `*AuthorizationService`、发布器 `*Publisher`、前端控制器 `*CatalogController`） | **一个都不上** |

⭐ **端口宽度 = 调用方适配器被迫实现的成员集。** 这一刀只搬了 6 个里的 1 个 ——
另外 5 个一旦端出去，消费方的适配器就被迫实现 4 个它从不调用的成员，
而且"凭证怎么存"会变成跨域契约。**一半的价值来自没搬的东西。**

### 19.2 形状：零换算、零新类型

两个端口各一个方法，方法表与各自实现类的那个公开方法**逐字相同**：

```java
public interface CsdnCredentialPort   { boolean isAuthorized(Long userId); }
public interface JuejinCredentialPort { boolean isAuthorized(Long userId); }
```

于是实现类只加一句 `implements` 与一个 `@Override`，消费方只换注入类型 ——
**没有新 DTO、没有新记录、没有换算、没有中间适配器**。这是本刀与前面十五刀最不一样的地方：
`OssPort` 有 `byte[] ↔ MultipartFile` 的换算，`MailPort` 把渲染整个搬走，
而这一刀**只换了一个类型名**。

⚠️ 但"没有换算"不等于"没有新缝" —— 见 §19.4。

### 19.3 为什么是两个端口：与 §16.2 同源，但**代价相反**

三条理由与 §16.2（站外同步）同源：**域自己就是这么分的**（两套 `*PlatformCredential`、
两个 mapper、两个独立的加密密钥 `CSDN_CREDENTIAL_ENCRYPTION_KEY` /
`JUEJIN_CREDENTIAL_ENCRYPTION_KEY`）、**零适配器**、**合并要造一个域里没有的词汇**
（一个跨域枚举，第三个平台出现时契约还要改）。

⚠️ 但 §16.2 有一条**附带收益**在这里**消失了**，而且是反过来的：

| | 站外同步（§16.2） | 凭证查询（本刀） |
|---|---|---|
| 两个端口的方法名 | 相同（`sync` / `findSucceeded`） | 相同（`isAuthorized`） |
| 两个端口的**参数表** | **不同** —— 掘金多收 `juejinCategoryId` / `juejinTagIds` | **逐字相同** |
| "接收者写反"的后果 | **编译失败**（参数个数不同，方法解析不到） | **编译通过** |

⇒ §16.2 说"两个端口的方法表与各自实现逐字相同 ⇒ 只加一句 `implements`"，
那句话在**参数表不同**的前提下顺带挡住了一类错误；本刀把这个前提拿掉了，
于是必须用别的东西补上。

### 19.4 ⚠️ 新缝：**同形不同源** ⇒ 静默换位点的第三类

这是本刀最值得记的一条。已有的两类静默换位点是：

1. **同名同参的重载**（§11.2 / 待办 20）：新方法的参数表与旧方法逐字相同时，
   转发点会显式点名旧名字，而编译器没有任何意见。
2. **端口归一参数顺序**（待办 20）：提供方的 `(taskId, userId)` 与调用方的
   `(userId, taskId)` 归一后，两个 `Long` 换位。

本刀是第三类：**两个不同源（不同提供方、不同平台）的对象，方法名与签名逐字相同**，
于是**接收者写反**编译得过：

```java
if (!csdnCredentialPort.isAuthorized(plan.getUserId()))     // ← 写成 juejinCredentialPort
```

编译通过、耦合指标一动不动、26 条门禁全绿。症状是"CSDN 发布前检查了掘金的授权"，
而**两边都返回 `boolean`**，所以没有任何类型层面的信号。

⚠️ **这条比预想的更有意思：同一个错误形状，在一个消费方被既有用例挡住，在另一个消费方完全没人守。**
先看实测（§19.7 的注入 C / D）：

| 消费方 | 写反的位置 | 实测红 | 其中**既有**用例 |
|---|---|---|---|
| `BlogPlanRunService` | `publishCsdn` 的接收者 | **3** | **2 条** |
| `BlogContentPlanService` | `toVO` 里 `csdnAuthorized` 的来源 | **1** | **0 条** |

`BlogPlanRunService` 那两条既有用例是**碰巧**挡住的：它们给 CSDN 桩 `true`、
而掘金桩没打桩（Mockito 默认 `false`），于是写反之后 CSDN 那条路拿不到授权、
`verify(publicationService).publish(…, "csdn")` 不满足 ⇒ 红。
而 `BlogContentPlanService` 的既有用例**根本没有读那两个派生字段的断言**，
所以那里是真的盲区 —— 注入 D 只红了**新加的那一条**。

⇒ **两个结论，都要记住：**

1. **"某个地方有测试挡着"不能推广成"这个错误形状被守住了"。**
   同一个写反，一个消费方红 3 条、另一个只红新加的 1 条。
2. ⚠️ **"注入验证发现不了判据强度不够"这句话要收窄。** 注入**能**发现
   "某个具体位置没人守"（注入 D 就发现了，它就是为此存在的）；发现不了的是
   "判据的**形状**够不够"——例如只断言参数**类型**不断言**参数名**（注入 E 那种）。
   两者的区别是：前者是**位置**问题，注入落在那里就能看见；
   后者是**判据**问题，注入永远看不见，只能**反着用一次**。

判据必须是**不对称桩**：一个平台授权、另一个不授权。本刀在两个消费方的测试里各写一条，
**方向相反**：

| 测试 | 桩 | 断言 |
|---|---|---|
| `BlogPlanRunServiceTest#theTwoCredentialPortsAreNotInterchangeable…` | CSDN=true / 掘金=false | CSDN 走通（`publish(…, "csdn")` 被调 + `CSDN_SUCCEEDED`）、掘金记 `AUTH_REQUIRED` 且**没调** `sync` |
| `BlogContentPlanServiceTest#theTwoCredentialPortsAreNotInterchangeable…` | CSDN=false / 掘金=true | `csdnAuthorized=false`、`juejinAuthorized=true` |

⚠️ 顺带：**同一个坑在 §16.2 的两个同步端口上早就存在**（`BlogPlanRunService` 只注入
`JuejinBlogSyncPort`，所以暂时没有调用点会写反；`agent` 侧同理）。本刀没有去补它 ——
那需要另一条不对称桩用例，属于独立的一刀。记进待办 54。

### 19.5 两个端口的"可用"口径不同 —— 属于实现，不属于契约

`CsdnCredentialService.isAuthorized` 额外要求 `csdnUsername` 非空（凭证是"cookie + 用户名"两段，
少一段就生成不出回链），掘金只要求加密 cookie 非空。契约上两者都只说"可用 / 不可用"，
因为跨域位置**没有任何调用方读得出这个区别** —— 差异写在端口注释里，
不写进端口签名。这是"端口按消费方真实需要裁剪"的又一次应用（ADR-2 §2.1）。

### 19.6 ⭐ 实测收益：跨单元实现边只剩 `admin → automation`

| 指标 | 前 | 后 |
|---|---|---|
| `implementation_statements` | 22 | **18** |
| `implementation_edges` | 9 | **8** |
| `cross_deployment_unit_statements` | 5 | **1** |
| `cross_deployment_unit_edges` | 2 | **1** |
| `api_statements` / `api_edges` | 9 / 6 | 9 / 6（不动） |

`automation → integration` **整条边消失**（实现层与 api 层都归零）。
`automation` 作为来源域现在只剩两条**同单元**出边：`→ blogagent`（4/2）、`→ publishing`（2/0）。

⚠️ 剩余跨单元实现边**只剩一条**：`admin → automation`（1，`AdminNotificationController`
需要 SSE 广播端口）。**跨单元实现图现在是一条链**：
`core-host ──1──▶ content-automation`。

### 19.7 注入验证：七次

落点分两类，**必须分开跑**（待办 41）：契约模块在反应堆里排在前面，它一红就让依赖它的
`core-host` 整块跳过，记下的红数会变成下界。契约侧（A / B / E / F）用
`-Dtest=CsdnCredentialPortTest`（`core-host` 匹配不到测试、不会被跳过），
消费方侧（C / D）用各自消费方的测试类。

| # | 注入 | 落在 | 实测红 | 红在哪条断言上 |
|---|---|---|---|---|
| A | 端口加一个 `default` 方法 | `CsdnCredentialPort` | **2** | 宽度 + `noMethodIsDefault` |
| B | 返回类型 `boolean` → `Boolean` | 端口 + 实现 | **2** | 宽度（签名变了）+ 原语断言 |
| C | ⭐ `publishCsdn` 的接收者换成掘金端口 | `BlogPlanRunService` | **3** | 新加的不对称桩用例 + **2 条既有用例** |
| D | ⭐ `toVO` 里 `csdnAuthorized` 改从掘金端口取值 | `BlogContentPlanService` | **1** | **只有**新加的不对称桩用例 |
| E | 首参改名 `userId` → `credentialOwnerId` | `CsdnCredentialPort` | **1** | `assertFirstParameterIsTheCallerId` |
| F | 端口 `extends` 掘金孪生接口 | `CsdnCredentialPort` | **2** | `extendsNothing` + `staysASeparateInterface…` |
| G | 消费方退回具体 `@Service` | `BlogPlanRunService` | **指标** | 见下 |

⭐ **A 是这一刀最想证明的一条**：给端口加一个带实现体的 `default` 方法 ⇒
**编译过、耦合指标一动不动、26 条门禁全绿、消费方一个 mock 都不用改**，
只有反射测试红 2 条。这条缝在端口上每加一次就开一次。

⭐ **C 与 D 的对照是本刀最有价值的实测**（见 §19.4 的表）：同一个错误形状，
`BlogPlanRunService` 红 3 条（其中 2 条是**既有**用例**碰巧**挡住的），
`BlogContentPlanService` 只红新加的 1 条（那里**真的没人守**）。
⇒ **"某个地方有测试挡着"不能推广成"这个错误形状被守住了"。**

⭐ **E 证明"断言参数名"那条守卫真的接着线**：改名之后
**没有任何其它断言变红** —— 这正是待办 11 说的"只断言类型 `Long` 会绿着过"。

**G（指标注入）**：把 `BlogPlanRunService` 退回注入两个具体 `@Service`（imports + 字段 + 两个调用点，共 5 处）后：

| | 基线 | 注入 G 后 |
|---|---|---|
| impl / edges | 18 / 8 | **20 / 9** |
| 跨单元语句 / 边 | 1 / 1 | **3 / 2** |
| 跨单元边 | `admin → automation` | `admin → automation` **+ `automation → integration`（impl=2）** |

⇒ 证明**指标确实是这一刀搬动的**，而且它搬动的是**跨域 import** 这一件事。

⚠️ **G 同时是"判据的基线必须来自判据之外"的第九次实测**：注入之后按**既定流程**
（先 scan 再 gates）跑，`cross-domain-import-baseline` 报
`PASS  20 implementation statements across 9 edges (3 cross a deployment unit)` ——
**存量涨了、门禁照样绿**，因为它的基线就是 `scan.py` 的产物。
`deployment-unit-coupling` 也 PASS（`admin → automation` 与 `automation → integration`
不成互依对）。⇒ 待办 16 仍未修，但这次拿到了一个新样本：
**"退回一刀"这种注入也骗不过去任何东西。**

⚠️ 两条工具坑（都在脚本里修掉了）：①G 第一版把两个 import 写成相邻的一块，
但它们中间夹着 `JuejinBlogSyncPort` ⇒ **锚点 0 命中**（"锚点必须唯一"这条规则要连
**"它是不是真的相邻"** 一起查）；②G 会把 `scan.py` 的产物改成注入态的数字 ⇒
**收尾必须无条件重跑一次 scan**，否则仓库里留下一份与代码不符的
`cross-domain-imports.yaml`（这正是待办 16 那条缺陷的另一面：产物既是基线，
就绝不能带着注入态落盘）。

⚠️ 七个目标文件全部逐字节还原（`git hash-object` 与基线逐个相同）。

### 19.8 为什么没有加 `contract-changes.yaml` 条目

同 §16.6 与 §18.11：本次**没有改任何线上形状**。两个端口是新类型，不是对既有 HTTP /
SSE / WebSocket 契约的改动；消费方注入的类型换了，但同进程内仍是同一个 bean
（`CsdnCredentialService implements CsdnCredentialPort`），返回值类型
`boolean` 一分没变。⇒ 可证纯重构，不进 `contract-changes.yaml`。

### 19.9 下一刀

1. **`admin → automation`（1）** —— **唯一**剩下的跨单元实现边。`AdminNotificationController`
   需要 SSE 广播能力，⚠️ 契约不许带框架类型 ⇒ 端口**不能**直接返回 `SseEmitter`，
   形状要先设计（大概率是"订阅 + 发一条事件"，而不是"给我一个 emitter"）。
   这是 `automation` 的最后一条入边。
2. **顺手补 `CsdnBlogSyncPort` / `JuejinBlogSyncPort` 的"同形不同源"守卫**（§19.4 末）——
   今天没有调用点会写反，但 `BlogPlanRunService` 已经注入了 `JuejinBlogSyncPort`，
   下一个接入 `CsdnBlogSyncPort` 的调用方就会踩到同一个坑。

⚠️ 两条都收掉之后**跨单元实现边归零**，`cross-domain-imports.yaml` 里只剩同单元的边；
`deployment-unit-coupling` 三条判据将只剩"不许变坏"的含义 ——
那时要重新想 `MUTUAL_UNIT_PAIRS` / `WAVE2_UNITS` 的关系（§18.9 已经把互依对清空）。

## 20. 落地记录（十七）：`NotificationBroadcastPort` —— 跨单元实现边**归零**

### 20.1 目标边：`admin → automation`（1），最后一条

`AdminNotificationController` 注入 `automation.infrastructure.BlogPlanSseService`，
只为调一个方法：

```java
int recipients = sseService.broadcast(
        "SSE_TEST_BROADCAST", "SSE 广播测试", "管理员发起的实时通知测试", null, null);
```

这条边在 §19.9 被记成"形状要先设计"，而且 ADR 与 `automation.spi/package-info` 里
**两处**都写着同一个理由：

> SSE 广播端口（`admin` 要）。它要的是 `SseEmitter`（框架类型），形状与本模块三个端口完全不同，单独一刀。

⚠️ **那句话对"整个类"成立，对"这条边"不成立。** `BlogPlanSseService` 的
`subscribe(Long userId)` 确实返回 `SseEmitter` —— 但 `admin` 从来没碰过它。
这条边只经过 `broadcast`，而它**收三个 `String`、返回 `int`**，本来就不带框架类型。

⇒ 这是一次**因为"整个类搬不动"而差点把"其中一条方法可以抽"一起否掉**的误判。
抽取判据第三种情况说的正是"签名带框架类型 ⇒ 连碰都不要碰，另立**窄端口**把框架类型
挡在外面" —— **窄端口本来就是给这种类准备的**，而这里连"挡"都不用做：
要露出来的那条方法本来就在门外。

### 20.2 形状：窄端口只带 `broadcast`，而且**不收** `planId` / `runId`

```java
public interface NotificationBroadcastPort {
    int broadcast(String type, String title, String message);
}
```

留在实现里的：`subscribe`（返回 `SseEmitter`）、`push`（收 blog-plan 域的
`planId` / `runId`）、连接注册表、封禁校验、心跳线程。

⚠️ **为什么是三个参数，而不是与实现同形的五个。** 实现原来的签名是
`broadcast(type, title, message, planId, runId)`，那两个 `Long` 是 blog-plan 域的概念，
而唯一的调用方永远传 `null`。

⚠️ **这里刻意没有沿用 §8 那条"与实现同形"的先例，两者情形不同：**

| | `NotificationPort`（§8） | `NotificationBroadcastPort`（本刀） |
|---|---|---|
| 富余的是什么 | **返回值** `Long` | **参数** `planId` / `runId` |
| 消费方要做什么 | 忽略它（不写任何东西） | 每次都写一个 `null` |
| 保留的代价 | 契约上多一个没人读的返回值 | **契约上多两个要求调用方撒谎的参数** |
| 收窄的代价 | Java 不允许同名同参只差返回值 ⇒ 实现侧必须**改名** | 无 —— 参数个数不同是合法重载 |
| 结论 | 保留（惰性富余） | **收窄** |

判据就是 §16.2 那句话的反面：*"合并要么让 CSDN 那条路收下它永远不用的参数，
要么砍掉掘金的能力"* —— **让调用方每次都写一个自己永远不用的值，不是"惰性富余"，
是描述错了。**

⭐ **收窄可证是行为保持的**：`broadcast` 只把参数转发给 `push`，
而 `push` 把 `null` 的 `planId` / `runId` 序列化成空串 ⇒ **线上载荷逐字节不变**。
由 `BlogPlanSseServiceTest#broadcastFansOutToPushWithoutPlanOrRunContext` 钉住
（注入 E 证明它接着线）。

### 20.3 顺手补掉一条零覆盖：`broadcast` 从来没被跑过

`broadcast` 只有**一个**调用方（admin 的控制器），而 `BlogPlanRunServiceTest`
把整个 SSE 服务 mock 掉了 ⇒ 它的方法体在测试里**一次都没执行过**。
`BlogPlanSseServiceTest` 此前三条用例全部只走 `push` / `subscribe` / `remove`。

⇒ 补两条：`broadcastReturnsTheNumberOfCurrentlyRegisteredConnections`（0 个连接返回 0；
2 + 1 个标签页返回 **3** —— 是**连接**数不是**用户**数）与上面那条扇出断言。

⚠️ **返回值的语义（连接 vs 用户）没有任何地方写着**，只有 `AdminNotificationController`
的注释说"返回接收连接数"。注入 G 把 `recipients += connections.size()` 改成
`recipients += 1`，只有这一条断言会红。

⚠️ 顺带修掉新测试里 `methodNamed` 的一个不确定性：原来"取第一个同名方法"，
一旦端口上出现**重载**，它会**按 JVM 的方法顺序**挑一个，断言就开始检查另一个方法
—— 而它们仍然"通过"（**没有失败信息，因为根本没有失败**）。
改成断言"恰好一个"。注入 A 同时踩中这条与 `noMethodIsDefault`。

### 20.4 ⭐ 实测：跨单元实现边归零

| 指标 | 前 | 后 |
|---|---|---|
| impl 语句 / 边 | 18 / 8 | **17 / 7** |
| 跨单元语句 / 边 | 1 / 1 | **0 / 0** |
| api 两列 | 9 / 6 | 9 / 6（不动） |

`admin` 作为来源域在跨单元层面**归零**（它仍有两组**同单元**出边：
`admin → identity` 2、`admin → virtualpay` 1）。`automation` 作为目标域也**归零**。

### 20.5 ⚠️ 归零之后，这条不变式**没有任何门禁守着**（新缝）

这是本刀最值得记的一条。归零之前，图里至少还有一条边；归零之后，
**"跨单元实现边 = 0"变成了这一轮重构的头条成果，而它是无人看守的**：

| 门禁 | 新增一条**单向**跨单元实现边（A → B，无回边）时会怎样 |
|---|---|
| `deployment-unit-coupling` 判据 1 | 通过 —— 它只管 `bloghtml` 有没有边 |
| `deployment-unit-coupling` 判据 2 | 通过 —— 单向边**不会让任何单元进入强连通分量** |
| `deployment-unit-coupling` 判据 3 | 通过 —— 没有产生新的互依对 |
| `cross-domain-import-baseline` | 通过 —— 它的基线是 `scan.py` 的**产物**（待办 16） |

⇒ **两条门禁都不会红。** 而这不是本刀造成的缝：`deployment-unit-coupling` 从写下来那天
起就只判**形状**（"不许出现新的环 / 新的互依对"），判**大小**从来是
`cross-domain-import-baseline` 的活 —— 而那条的基线取自被测物本身（待办 16，**已问过九次**）。

⚠️ 归零之前这条缝**被掩盖着**：那时跨单元边不是 0，任何人读 `cross-domain-imports.yaml`
都能看到实际值，门禁的通过消息也还在描述一个非空图。归零之后，
**"0"本身成了要守的东西**，而它现在只活在文档里。

修法（**已做，见 §21**）：把期望值写成 `ci_gates.py` 里的一个**常量**
（`EXPECTED_CROSS_UNIT_IMPLEMENTATION_EDGES = 0`），与 `MUTUAL_UNIT_PAIRS` 同一个模式
—— 基线来自**判据之外**。⚠️ 这个常量在归零**之前**写不出来（数字一直在动，写死就得每刀改一次），
**归零之后它才成为一个稳定常量** —— 所以这个修法是被本刀**解锁**的，而本刀**没有顺手做**：
它是一次 CI 设计变更，与待办 16 同族，应该由人拍板，不该在一次抽取里悄悄落地。
（⚠️ 后续在**独立的一节** §21 里落地了，并且这次是**直接测量**而不是推演：见 §21.3。）

### 20.6 门禁改动：让通过消息报**实测**边数

`deployment-unit-coupling` 的通过消息此前是
"…every unit is a singleton and the cross-unit implementation graph is acyclic…"。
⚠️ 图形状变了之后这句话开始**掩盖结果**：它对"空图"和"有几条单向边"读起来**一模一样**，
而这两件事的迁移含义完全不同（前者是完工，后者还有活）。而判据本身在两种形状下都成立，
所以**永远不会有人因为"它红了"去修它**。

⇒ 两个分支都补上实测值：

```text
…every unit is a singleton and the cross-unit implementation graph is acyclic
(0 cross-unit edge(s) / 0 statement(s) left), so no unit can block another's extraction…
```

这是 §18.9 那条"分量大小为 1 时另起一句"的**同族第二次**：
失真的都是**通过消息**，不是判定。
⇒ **任何通过消息里的形容词（"acyclic" / "isolated" / "singleton"），
都要问一句"它对另一种形状还成立吗？两种形状读起来一样吗？"**

### 20.7 注入验证：七次

七条注入、七个锚点**全部生效、全部逐字节还原**。判据分三类：契约形状（A / B / C / F）、
消费方接线（D，判据是**指标**不是测试）、行为保持（E / G）。

| # | 注入 | 落点 | 实测红 |
|---|---|---|---|
| A | 端口上加一个 `default` 重载 | `NotificationBroadcastPort` | **4** |
| B | 端口与实现的返回值 `int` → `Integer` | 端口 + `BlogPlanSseService` | **2** |
| C | 把 `planId` / `runId` 放回端口 | 端口 + 实现 + 消费方（三处同改） | **2** |
| D | 消费方退回具体类 `@Service` | `AdminNotificationController` | **指标** |
| E | 扇出里写死的两个 `null` → `0L` | `BlogPlanSseService` | **1** |
| F | 端口上多一个常量 | `NotificationBroadcastPort` | **1** |
| G | `recipients += connections.size()` → `+= 1` | `BlogPlanSseService` | **1** |

- **A（4 条）**：`declaresExactlyTheOneKnownMethodWithThisExactSignature`、
  `returnsIntSoTheProviderSideIsAPureTypeReplacement`、
  `takesExactlyThreeStringsAndNoPlanOrRunContext`、
  `noMethodIsDefaultBecauseThatWouldWidenTheContractSilently`。
  ⚠️ 这是本刀那个 `methodNamed` 改动的**直接受益者**：改之前"取第一个同名方法"只有签名清单
  会红，改成"恰好一个"之后**两个**用 `methodNamed` 的用例一起红。
  它同时是待办 57 的实测样本 —— 端口加 `default` 方法**在生产代码里编译得过、指标一动不动、
  26 门禁全 PASS**，只有反射测试红（与 §9.8 同族）。
- **B（2 条）**：签名清单 + `returnsInt…`。⚠️ 装箱不只是"多一个没传的态"：
  `Integer` 返回值会**逼实现类改签名**，于是"提供方侧退化成纯类型替换"这个前提当场失效。
- **C（2 条）**：签名清单 + `takesExactlyThree…`（`expected: <3> but was: <5>`）。
  ⚠️ 这条注入要**三个文件一起改**才能编译过 —— 恰好说明"**镜像**只需改端口就能编译，
  而它仍然被守卫拦住"：守卫判的是**端口自己的形状**，不是"改起来费不费劲"。
- **D（指标）**：`impl / edges / cross_stmt / cross_edges / api_stmt / api_edges`
  = `18 / 8 / 1 / 1 / 9 / 6`，且 `admin -> automation impl = 1`。
  ⇒ 本刀搬动的那一格**确实就是**这一个数字；"0"不是文档里的说法，是**扫描器实测的 0**。
- **E（1 条）**：`broadcastFansOutToPushWithoutPlanOrRunContext`。
  这是"收窄可证行为保持"的**唯一一条**断言：把写死的 `null` 换成 `0L`，
  出站载荷就从空串变成 `"0"`，而除它以外没有任何东西会红。
- **F（1 条）**：`carriesNoConstantsBecauseTheMessageTypeBelongsToTheConsumer`。
- **G（1 条）**：`broadcastReturnsTheNumberOfCurrentlyRegisteredConnections`。
  它钉的是**返回值语义（连接数 ≠ 用户数）**—— 这个语义此前只写在控制器的注释里。

还原判据统一用 `git hash-object`，三个文件的收尾 blob 与注入前**逐字节相同**：

```text
NotificationBroadcastPort.java      08d9a042da75c72bd048b6f3c77b93a9d9703f0b
BlogPlanSseService.java             8df1c761fba1d4b2843666a24661bf741857edea
AdminNotificationController.java    02806e1835b880accd02bdac0adcd8a294b2b454
```

⚠️ **一次差点被误判成"还原失败"的读数（新缝，待办 58）**：
E / G 的 `restore` 行打印 `1ae8d38b…`，而驱动脚本与收尾 `git status` 打印 `8df1c761…`
—— 同一个文件、同一个时刻，**两个哈希**，看起来像还原没到位。

实测（`git cat-file -t 1ae8d38b` ⇒ `Not a valid object name`；文件 12695 字节、267 个 CRLF、
0 个裸 LF）：

| 算法 | 值 |
|---|---|
| 原始字节（harness 的 `blob()`，带 `blob <len>\0` 头） | `1ae8d38b…` |
| `sha1sum`（原始字节，**不带**头） | `87578e00…` |
| `git hash-object`（**先做 CRLF→LF 归一**，本仓 `core.autocrlf=true`） | `8df1c761…` |

⇒ `1ae8d38b` **就是**还原后基线的原始字节哈希。**没有残留**。这是待办 42
（`sha1sum` ≠ `git hash-object`）的**第三种形态**，而且比前两种更难发现：
前两种是"两个**外部工具**给出不同值"，这次是**同一个脚本的两条代码路径**各自打印一个，
还**挨着打印**在同一个输出里。

⇒ 已把 `inj20.py` 的 `blob()` 改成先归一 CRLF 再哈希，与 `git hash-object` **逐字等价**，
复跑 E：`apply` → `0ed8dd20…`、`restore` → `8df1c761…`，两侧一致。
七条注入再全量 `apply` + `restore` 一遍，逐条 ok、收尾三文件哈希不变、`git status` 仍 12 项。

⚠️ 判据层面的教训（**不是**"还原失败"，而是"判据用了错算法"）：
**"脚本打印了一个陌生的哈希"与"文件被改坏了"是两件事；分辨它们的是 `git diff`
（差异是否逐行都是本刀意图），不是哈希比对。**

### 20.8 为什么没有加 `contract-changes.yaml` 条目

（同 §18.8 / §19.8：纯类型替换 + 一个方法，消费方调用点只少了两个 `null`，
没有字段增减、没有语义变化。）

### 20.9 下一刀

跨单元实现边已经归零 ⇒ **没有"下一刀抽取"了**。剩下的是三类**不是抽取**的工作：

1. **把"0"变成有人守的不变式**（待办 56）—— 本刀解锁的、最该做的一件。**✅ 已做，见 §21。**
2. **补 `*BlogSyncPort` 的"同形不同源"守卫**（待办 54，第十九刀发现）。
3. `BlogContentPlanService` 的 `toVO` 残留（待办 53）—— 它**会改变行为**，
   所以不是重构，要单独决定。

⚠️ 还有一件**更大**的事：`cross-domain-imports.yaml` 里剩下的 7 条边**全部同单元**。
`deployment-unit-coupling` 三条判据此后只剩"不许变坏"的含义 ——
那时要重新想 `MUTUAL_UNIT_PAIRS` / `WAVE2_UNITS` 的关系（§18.9 已把互依对清空）。

## 21. 落地记录（十八）：把「0」变成**有人守**的不变式

**这一条不是抽取，是 CI 加固** —— 它是 §20.5 发现的那条缝的修法，也是 §20 **解锁**的。
⚠️ 记录在此是因为它动了 `ci_gates.py` 的判据，不是因为它是第二十一刀抽取：
`lib-extraction.md` 里**没有**对应章节，抽取系列仍停在第二十刀。

### 21.1 缝：图形状变了之后，判据再也看不见"大小"

§20.5 已记：`deployment-unit-coupling` 三条判据**全部只判形状**，判大小从来是
`cross-domain-import-baseline` 的活 —— 而那条的基线是 `scan.py` 的产物（待办 16），
在"先 scan 再 gates"的既定流程下**不可能变红**。⇒ 归零之后，
**"跨单元实现边 = 0"这个头条成果没有任何东西守着。**

### 21.2 修法：一个常量，与 `MUTUAL_UNIT_PAIRS` 同一个模式

```python
EXPECTED_CROSS_UNIT_IMPLEMENTATION_EDGES = 0
```

- **基线来自判据之外**（字面常量，不是读 `docs/cross-domain-imports.yaml`）——
  这正是待办 16 缺的那件事，只是这里**不需要人工维护的基线文件**：
  归零之后这个数不再变。⚠️ 它在归零**之前**写不出来（数字一直在动），
  所以这个修法是被 §20 **解锁**的，而 §20 刻意没有顺手做。
- **双向**：高于常量报"新增"，低于常量报"这是进展，请把常量改小"。
  （0 处"低于"不可达，但判据照双向写，常量以后被抬高时不会失真。）
- **不加第二个常量管语句数**：`edges` 是按单元对累加 `implementation_statements`
  并跳过 0 的，所以 `len(edges) == 0` ⟹ `sum(edges.values()) == 0`，可证等价。
- **放在三条判据之后**：这样同时踩中形状判据的边仍走那条更具体的消息
  （bloghtml 的 Wave 1 前置条件 / 新分量成员 / 新互依对），
  这条只兜住**三条都看不见**的那种边。

### 21.3 ⭐ 实测：正反对照，而且这次是**直接测量**（不是推演）

注入 = 把 `AdminNotificationController` 退回注入具体类（`inj20.py` 的 D），
即重新造出 `dinqor-core-host -> dinqor-content-automation`（1 条语句）。
**按既定流程（先 `scan.py` 再 `ci_gates.py`）跑**：

| 状态 | 门禁总数 | `deployment-unit-coupling` |
|---|---|---|
| 常量 = 1（等价于**没有**这条判据） | **24 pass / 0 fail / 2 skip** | **PASS**，而 detail 仍印着 `1 cross-unit edge(s) / 1 statement(s) left` |
| 常量 = 0（本刀） | **23 pass / 1 fail / 2 skip** | **FAIL** —— 唯一红的就是它 |
| 还原 | 24 pass / 0 fail / 2 skip | PASS（`0 cross-unit edge(s)`） |

⭐ 第一行是这一刀最值得记的读数：**门禁全绿，而它自己的通过消息里写着那条回归。**
形状判据不是"漏了"，是**结构上看不见** —— 单向边不进强连通分量、不产生互依对。
⭐ 第二行的失败消息是这条判据的，这**本身**就证明三条形状判据**提前 `return` 了**（即全部通过）。

⇒ 这不是"补一条更严的判据"，是**补一条唯一能看见这个形状的判据**。
⚠️ 这次刻意做了**直接测量**而不是从结构推演"没有它就会绿" ——
改常量、跑一次、改回来，比论证更便宜也更硬。
还原后 `ci_gates.py` 与 `AdminNotificationController.java` 的 blob 都与改前逐字节相同。

### 21.4 刻意没做的事

- **待办 16 仍未修**：`cross-domain-import-baseline` 的基线仍取自 `scan.py` 的产物。
  它与本条同族，但**代价不同** —— 修它要引入一个**人工维护**的基线文件
  （照 `capability-baseline.yaml` + `.lock` 的模式）或"从上次提交读基线"，
  那是流程决定，**不是一个常量能解决的**。**已问过九次，仍未拍板。**
- 常量**没有**跟着写进 `docs/cross-domain-imports.yaml`：那个文件是**产物**，
  把基线写进产物等于把判据的基线又交回给被测物。

## 22. 待办

| # | 事项 | 状态 |
|---|---|---|
| 1 | 边界路径上的 Jackson 泄漏：`billing` 端口 | **已做**（§4）。`agent-api` 也已做（§6），两条已知泄漏都清了 |
| 2 | 契约能否依赖契约 | **未拍板**。今天禁止，代价是 `StoredImage` / `TokenUsage` 这类重复类型 |
| 3 | `MediaAssetRef` 收窄到前端真正读的 3 个字段 | **未做**，独立 §11 变更 |
| 4 | `agent -> publishing` 的 9 条 impl 语句（`BlogService`） | **已做**（§5）。边归零，只剩 1 条 api（§5.10） |
| 5 | `@JsonIgnoreProperties` 存量清单与清理时点 | **未清点** |
| 6 | `agent-api`（`AgentTool.parametersSchema()` 返回 `JsonNode`） | **已做**（§6）。结论是**不搬 `AgentTool`**，只加窄端口 `RemoteToolCatalog` / `RemoteTool` / `RemoteToolCall`，50 个实现一个没动。契约包名取 `agent.spi` 而非 `agent.api`，理由见 §2.7 |
| 7 | `TokenUsage` / `ContentPost` / `ContentPostPage` 分量顺序是否要登记为 §11 契约变更 | **未定**。今天不登记（理由同 §2.6），但拆分后契约 jar 独立版本化时它们会变成线上格式 |
| 8 | `blogagent → publishing` 的 1 条 api 语句（`BlogPostVO`） | **未做**，独立一刀（§5.10） |
| 9 | `ContentPostPort` 的 16 个不上端口方法，拆分后是否需要新的端口 | **未定**。`automation` / `blogagent` 与内容域同单元，拆完还在一个进程里 |
| 10 | `automation-api`（10 条语句指向它，当时最大的目标域） | **接近完成**（§7 + §8 + §18）。`agent → automation` 6 条（§7）与 `knowledgebase → automation` 2 条（§8，端口 `NotificationPort`，零接线扩展）已清；`integration` 1（**构成真环**，要改 `MailService` 的 API 而非加端口）**已做**（§18）。**只剩 `admin` 1**（需要 SSE 广播端口，形状不同） |
| 11 | `deployment-unit-coupling` 判据 2 **空转**：根单元 `dinqor-agent` 离开分量后 `joined` 恒为空 | **已修**（§7.10）。判据改成对**每个** Wave 2 单元取分量并集；detail 改报**实测**分量大小，并新增"哪些单元已可单独抽取"的进度提示 |
| 12 | `UpdatePlanTool` 的 `triggerTime` 分支**从工具路径不可达**：只给 `triggerTime` 会走到 `parseSendTimes([])` 抛"请设置至少一个发送时间" | **未修**，既有行为，本刀逐字保留 —— 顺手修会让这次改动不再是可证明的纯重构（§7.12） |
| 13 | `shared-kernel-classification` 缺**反向**判据：只查"每个发布的包都有条目"，不查"每个条目都有包"，于是 `util` 是死条目 | **未修**，删 `util` 触发 0 门禁 / 0 指标（见 `backend-norms`） |
| 14 | `RESOURCE_KNOWLEDGE_FILE` 的**归属**：它语义上更像 knowledgebase 的资源类型，而不是通知表的词表 | **未定**。§8 按"跟着端口搬"处理（纯搬家、可证）；改归属是另一个决定，且会动契约的公共常量 |
| 15 | `automation` 剩两条扇入：`admin`（SSE，要 `SseEmitter`）与 `integration`（邮件模板真环） | **部分已做**（§18）：`integration`（邮件模板真环）已断 —— 渲染搬回 `automation` 的 `EmailReminderMailRenderer`，`MailService` 收窄成只投递并实现 `MailPort`。**只剩 `admin`**（SSE，要 `SseEmitter`，形状要先设计） |
| 16 | **⚠️ `cross-domain-import-baseline` 在"先 scan 后 gates"的既定流程下不可能变红** —— 它把 `docs/cross-domain-imports.yaml`（`scan.py` 的产物）当作自己的基线，于是基线 == 实时值 | **未修**（高优先）。2026-09-17 实测：把 `RESOURCE_KNOWLEDGE_FILE` 放回实体并让 `knowledgebase` 重新 import 它 ⇒ 扫描器如实报 42 → **43** 条、边从 18 → **19**，而该门禁仍报 **PASS**。它今天实际只检测"改了代码没重跑 scan"。修法二选一：加人工基线文件（照 `capability-baseline.yaml` + `.lock` 的模式），或从上次提交读基线。已先改掉门禁 docstring 里那句失实的保证（§8.11） |
| 17 | 契约测试里按下标反射取方法的写法（`getDeclaredMethods()[0]`） | **已修**（§8.11）。`NotificationPortTest` 与既有的 `UserLookupPortTest` 各一处；`getDeclaredMethods()` 的顺序 JVM 不保证，加一个方法就会静默换检查对象 |
| 18 | **⚠️ 端口方法同类自调用失事务**：Spring 事务是代理实现的，`this.富方法(...)` 不走代理，被调方法上的 `@Transactional` 不生效。第九刀的 5 个端口方法因此全部在无事务下执行（其中两处的富方法第一句是 `SELECT ... FOR UPDATE`，锁不住任何东西） | **已修**（§9.8）：5 个方法各补注解；新增 `PortDelegationTransactionTest` 把这条不变量交给机器守（§9.9），并带一条**双向**完整性判据 |
| 19 | `recitation` 的**测验能力**（`KnowledgeQuizService` 的 `openQuiz` / `recordQuiz`） | **已做**（§10）。`RecitationQuizPort` + 4 个契约类型；`agent → recitation` 整条边消失。两个域内 record（`QuizSession` / `QuizOutcome`）**被删掉**而不是搬走 —— 它们的同域生产消费方为零（§10.7） |
| 20 | `KnowledgeQuizAttemptView.items` 的类型是 `Object`（**无形状字段**） | **已定**（§10.3）：契约带**文本**（`QuizAttemptView.itemsJson`），解析上移到工具。三种做法里只有这一种不假装知道历史行的形状；而"契约没法解析"正是 ADR-1 的直接后果，这次 ADR-1 不是代价而是答案 |
| 21 | `PortDelegationTransactionTest` 的端口实现清单靠人工维护 | **已知代价**，由 `theAuditedClassListIsComplete` 的类路径扫描**双向**兜住：漏加会直接变红（本刀实测，见 §9.9）。新增端口实现时把类加进 `PORT_IMPLEMENTATIONS` |
| 22 | **⚠️ 一份实体被投影成两份类型时，两个投影必须被测试钉成一致** —— 两边各自的测试都不会红，只有一条跨投影的断言能抓到漂移 | **已建立**（§10.4）：`RecitationQuizPortMappingTest` 里那条"契约投影与前端投影在共享字段上相等"。注入 D 证明它有独立价值。**后续凡新增一份投影（`ContentPost` / `MediaAssetRef` 收窄时也会遇到）都要配一条同款断言** |
| 23 | `agent` 的剩余出边：`integration` 2/0 | **部分已做**（§11 + §13 + §14）。`agent → blogagent` 4/2 → **2/0**（§11）→ **整条边消失**（§13）；`agent → publishing`（0/1）**整条消失**（§11）；`agent → identity` 2/0 → **整条边消失**（§14）。**`agent` 现在只剩一条边、2 条 impl**：`agent → integration`（与 `PublishPostTool` 同文件）。⚠️ `agent` 是 `dinqor-agent` 唯一还指向别的单元的域；`agent` 自己**没有入边**，所以它可以被单独抽取 |
| 24 | **⚠️ 端口守卫只断言"第一个参数的类型是 `Long`"** —— 而 `publish(Long taskId, Long userId)` 同样满足它，于是守卫会**绿着**放过一个它名字所禁止的形状 | **已修**（§11.2/§11.3）：守卫改钉**参数名**（javac 开了 `-parameters`，`MethodParameters` 属性在 class 文件里，已用 `javap` 实测）。这是"判据只朝一个方向看"的**第三次**实例，前两次是 `util` 死条目与 `deployment-unit-coupling` 判据 2。⚠️ 代价：这条守卫依赖 `-parameters`；删掉它守卫会**变红**（退化成 `arg0`）而不是静默放过，可以接受 |
| 25 | **⚠️ "归一参数顺序"会产生与旧方法参数表逐字相同的新重载** ⇒ 所有原来转发到旧方法的调用点都变成**静默换位点** | **已处理**（§11.7）：`runStream` 的三参重载必须显式点名 `runStreamForView`，由 `theThreeArgumentOverloadForwardsToTheForViewMethodNotThePortMethod` 用 `never()` + `thenAnswer` 双向钉住。**下次遇到"换参数顺序"时，先 grep 所有转发点** |
| 26 | `BlogAgentTaskView` / `BlogAgentPostView` / `BlogAgentTaskDraft` 在 Wave 2 之后会变成**跨进程线上格式** | **未定**。今天不登记（它们不是前端可见类型，见 §11.9）；拆分后任何重排都要走 §11。与第 7 项同类 |
| 27 | `blogagent` 的图片生成能力（`ImageGenerationTool` 走 `blogagent.infrastructure`） | **已做**（§13）。`BlogAgentImagePort` + `BlogAgentImage` + `BlogAgentImageUsage`（**契约模块的第二个端口，零构建接线**）；`agent → blogagent` **整条边消失**。四个形状决定：①第二个端口而不是往 `BlogAgentPort` 加方法（两种形状不混）；②图片以 `byte[]` 过界；③用量以三 long 过界（`ModelUsage` 带 Jackson ⇒ 契约自带等价 DTO）；④**消费方不读提供方的 `@ConfigurationProperties`**，`model` / `size` 由提供方适配器填。顺手修掉一个静默缺陷（见第 28 项） |
| 28 | **⚠️ 顺手修掉：`ImageGenerationTool.writeMeta` 记的是配置里的首选模型，不是实际端点** —— 客户端会在多个候选端点间回退，兜底端点生成的图会被记成首选模型的名字；网关路径下配置值还可能为空 | **已修**（§13.7）：`writeMeta` 改成收实际值，`model` 取 `generated.model()`。由 `imageModelComesFromTheGeneratedResultNotFromTheConfiguration` 守（注入 F 实测 2 处红）。⚠️ **通用经验：提供方报的值与消费方配置的值同名时，必须明确谁是真相** —— 真相是提供方报的那个 |
| 29 | **⚠️ 端口收不收 `userId` 必须是一对守卫** —— 只有正面那条时，"所有端口都收"是一个没人反对就会漂移的一致性论点 | **已建立**（§13.9）：`ContractShape.assertNoParameterIsNamed(type, "userId")` 是 `assertFirstParameterIsTheCallerId` 的反面；`BlogAgentImagePort` 是**唯一不收 `userId` 的端口**（提供方是无状态模型调用，真正要身份的两步在 `MediaAssetPort` / `BillingPort` 上）。注入 E 证明它接着线。**后续每新增一个端口，都要显式回答"收不收身份"并写进守卫** |
| 30 | **⚠️ 消费方不得读提供方的 `@ConfigurationProperties`** —— 一旦读了，提供方的"配置"就变成跨域契约，而配置可以改名 / 给默认值 / 整段不存在 | **已定**（§13.6）：端口返回"这次生成实际用了什么"，不返回"提供方怎么配置的"。`BlogAgentProperties` 一个字段都没上端口。**下次遇到"消费方需要提供方的某个配置值"时，默认做法是让提供方把它填进返回值** |
| 31 | `integration → identity`（1 impl，`McpOAuthService.validateAccessToken`） | **已做**（§15）。`integration` 接入 §14 建好的 `AccessTokenLookupPort`（**零构建接线**：契约模块与 POM 一行没改）。`integration -> identity` 整条边消失，**`identity` 作为目标域的跨单元边全部归零**。⚠️ 实际收益大于预期：消费方原来自己复制了一遍身份解析（`validateAccessToken` + `Long.parseLong(jwtUtil.getSubject(...))`），改完连 `JwtUtil` 这个协作者都整个消失了（§15.3） |
| 32 | **⚠️ 把域内方法提升成契约实现时，先查它有没有测试** | **本刀踩到**（§14.6）：`getUserIdFromAccessToken` 此前**一个测试都没有**（`AuthServiceTest` 只覆盖 `validateAccessToken`）。它当便捷方法时不重要，当"agent 握手唯一的身份来源"时就必须钉住。已补 4 条。**与第 18 项（端口转调失事务）同族：抽端口会让原本不重要的东西变重要，而"没人写那条断言"不会有任何东西报错** |
| 33 | **⚠️ 注入脚本的"还原锚点"也必须唯一，且每条注入跑完立刻做 blob 级校验** | **已修**（§14.10）。第一遍把 D 的还原锚点写成 `return null;`（文件里有 3 处）⇒ D 没还原 ⇒ E 的正向锚点失效 ⇒ **E 根本没生效，而它报出的"2 处红"是 D 的残留**。成因与上一刀（引用未定义变量）不同，但**共同点是"脚本跑完了"不等于"注入生效了、也还原了"**。判据：两条锚点动手前都数一遍出现次数，必须都是 1；每条注入后立刻校验 |
| 34 | **⚠️⭐ 强连通分量的成员资格可以挂在一条大小为 1 的边上** —— `platform-integration -> core-host` 的全部内容就是 `McpOAuthService` 的那一条 import，删掉它 `dinqor-core-host` 就脱离了 Wave 2 分量（§15.7） | **本刀踩到**。此前把图形状读成"core-host、content-automation、platform-integration 是一团"，实际成环的只有 `content-automation ↔ platform-integration`。⇒ **每次动跨单元边之后必须重新推导分量，不能沿用上一刀的形状结论**；`MUTUAL_UNIT_PAIRS` 是双向判据，**删一对要两个方向都删**（本次按门禁提示照做，删后重跑，清理提示消失）。已把这段写进 `ci_gates.py` 的常量注释 |
| 35 | **⚠️ MyBatis-Plus 的 `getParamNameValuePairs()` 是惰性填充的** —— 参数只在 SQL 片段被渲染时才进 map，而 mapper 是 mock，永远不会渲染 ⇒ 直接读 map 拿到空集 | **本刀踩到**（§15.8）：`verify(grantMapper).selectList(argWithUserIdValue(777L))` 红了，报"参数不同"。**修法：先显式调一次 `getSqlSegment()` 再读 map** —— 仓里既有 8 处同类断言都是这么写的（`KnowledgeIngestionServiceTest` / `KnowledgeQuizServiceTest` / `Img2ThreeServiceTest` …）。**下次要断言"查询条件里带了某个值"时，照抄那个形状** |
| 36 | **⚠️ 注入脚本的"删除式注入"：还原锚点必然是正向锚点的子串** | **本刀踩到**（§15.9）：脚本第一次跑到 B 就**主动中止**（前置断言发现"还原锚点在注入前出现 1 次"），没有带着残留继续 —— 断言生效了，但规则需要细化。⇒ 两条路：①给每条注入声明"还原锚点注入前的期望次数"（0 或 1），再用**施加后**的 `fwd==0 && mut==1` 消歧；②改用**非子串**的变异（如把 `userId == null` 改成 `authorization == null`，即"守错了变量"）。本刀选了①。另：本刀注入脚本改用 Python 后踩到新坑 —— **`mvn.sh` 是 shell 脚本，Windows 的 `CreateProcess` 不能直接 exec**（`WinError 193`），必须内联 `mvn.sh` 里那条 java 命令行并注明与它保持同步 |
| 37 | **⭐ 抽端口的收益不总是"少一条 import"：第二个消费方接入时可能顺带删掉一个协作者** | **本刀实例**（§15.3）：`McpOAuthService` 的 `jwtUtil` **只为那一行解析而存在**，改完之后字段 / import / 构造参数 / 测试里的 `@Mock` 一起去掉。⚠️ 这种收益**看不出来**：指标只降 1，而消费方少了一份复制逻辑、少了一条"主体是数字"的隐含假设。**每接一个消费方，都要问一次"这个端口让它少知道了什么"** |
| 38 | **⚠️ "一个能力一个端口" ≠ "一个平台一个端口"** —— 但当平台名是本域词汇表、且各平台的参数表不同时，就该是**两个**端口 | **本刀拍板**（§16.2）：合并 CSDN 与掘金只有两条路 —— 要么让 CSDN 收下 `juejinCategoryId` / `juejinTagIds`（**端口变宽**），要么砍掉掘金指定分类/标签的能力（**静默降级**）。⇒ **判据是参数表**：只有当合并后的签名对**每一个**实现都是它真正需要的形状时才合并。附带收益：两个端口的方法表与各自实现逐字相同 ⇒ 实现类只加一句 `implements`，**不需要按平台字符串分派的中间类**。⚠️ 这偏离了 `lib-extraction.md` §3quater.5 草拟的单数名 `BlogSyncPort`（`CredentialPort` 同样是草稿单数、也是两套） |
| 39 | **⚠️ 端口上一个参数该不该留，看"它有没有具名消费方"，不看"它现在有没有跨域消费方"** | **本刀拍板**（§16.3）：掘金那两个参数在跨域路径上**今天全是 `null`**，但同域的前端 HTTP 层会传真实值，而跨进程后端口是那条路径唯一能穿过的形状。**按"没人用"砍参数 = 让能力在拆服务时静默消失**（§2.3） |
| 40 | **⚠️ 抽端口可能逼实现类改返回类型 ⇒ 同域消费方要跟着改；判据是"线上 JSON 变了没有"，不是"Java 类型名变了没有"** | **本刀实例**（§16.4 / §16.5）：Java 不许"同名同参只差返回值"的重载 ⇒ `sync` 只能返回契约的 `BlogSyncResult`（契约不许依赖契约），5 个同域消费方跟着换类型（含 2 个 HTTP 控制器）。**仍是镜像、不进 `contract-changes.yaml`**：两份 record 分量名逐字相同 ⇒ JSON 逐字节不变。⚠️ **`grep` 的目标要选"那个类型"而不是"那个类"** —— 本刀第一遍按服务类名找只得到 4 个消费方，编译前按 `PublishResult` 再 `grep` 才多出 `DualPublishMcpApplicationService` |
| 41 | **⚠️ 多模块注入验证：`-fae` 不够，还要把 `-Dtest` 收窄到"依赖方自己的测试类"** | **本刀踩到**（§16.8）：注入同时落在 `contracts/integration-api` 与 `apps/core-host`，而契约模块在反应堆里排在**前面** ⇒ 契约侧一红 maven 就中断，`core-host` 的测试根本不跑，记下的红数是**下界**。⚠️ 加 `-fae`（fail at end）**仍然不够** —— `-fae` 只续跑"**不依赖**失败模块"的模块，而 `core-host` 恰恰依赖 `integration-api`，照样被跳过。**修法**：把 `-Dtest` 收窄到 `core-host` 自己的测试类，让契约模块**一个测试都匹配不到**（因此永远不会红）。此时契约模块连 `Tests run` 行都不打印 ⇒ **"没有汇总行"必须判成"0 个测试"，不是"编译失败"**（第一版脚本混了这两件事，报了个假的 `-1`）。另：逐模块的 `Tests run` 汇总行要**累加**，只看最后一行会漏掉另一个模块 |
| 42 | **⚠️ `sha1sum` ≠ `git hash-object`，同一文件两个值** —— 前者对**原始字节**算，后者对 `blob <len>\0` + 内容算且会先做 CRLF→LF 归一 | **第十六刀踩到**（§16.8）：`CsdnBlogSyncPort.java` 两个值分别是 `60a4fe6b…` 与 `5c7408d4…`，手上同时有这两个值时把**没被改过**的文件误判成"内容不对了"，白查了一轮。⇒ **还原判据必须写清用的是哪一个算法，且全程只用同一个**（本仓统一用 `git hash-object`）。⚠️ 与第 33 / 36 项同族：**"脚本跑完了"不等于"注入生效了、也还原了"**，但这一条是"判据本身用错了算法"，比前两条更难发现 —— 因为它不会报错，只会让你去查一个不存在的问题 |
| 43 | **⚠️ 扫描器看不见"配置层"的跨域引用** —— 一条 Java import 可以换成一行 YAML 占位符，指标如实下降，而耦合只是换了地方 | **本刀实例**（§17.10）：`studio → blogagent` 的实现边确实消失（跨单元实现边 5 → 4），但"studio 的配置默认值指向 content-automation 的环境变量"这件事在 `cross-domain-imports.yaml` 里一个字都看不到。⚠️ 而且这不是本刀发明的缝 —— `agent.model.*`（`application.yml` 第 252–255 行）**早就**在用同样的写法复用 `BLOG_AGENT_*`，而 `agent` 与 `blogagent` 也不在同一个部署单元。⇒ **指标只能看见它被设计来数的那种耦合**；`config-ownership.yaml` 是唯一可能承载这类引用的地方，但它今天只记 `owner` / `classification` / `nacos_group`，**不记"这个键的默认值引用了哪个键"**。未修 |
| 44 | **⚠️ `img2three.agent.*` 的配置归属没有结论** —— 它的默认值指向另一个域的凭证 | **未定**（§17.10）。`config-ownership.yaml` 把 `blog.agent.api-key` 记成 `owner: content-automation` + `secret` + `NACOS: DINQOR-CONTENT-AUTOMATION`，把 `img2three.agent.api-key` 记成 `owner: studio` + `secret` + `NACOS: DINQOR-CORE`。改造后 img2three 的默认值仍指向 content-automation 的环境变量 —— 这是**运维约定**而不是代码依赖（拆服务后它表现为"两个服务的环境里配同一个值"），但清单里没写、也没有门禁守。⚠️ 本刀**刻意没有替这个决定做主**：删掉回落是行为变更（`application-local.yml` 就是靠它工作的） |
| 45 | **⚠️ 守卫的失败信息必须指向真正的原因（第三次实例）** —— 这次是"只能间接断言"导致错因伪装成环境故障 | **本刀踩到**（§17.8 的注入 D）：`buildsTheEndpointFromImg2ThreeOwnSettingsWhenTheGatewayIsOff` 只能通过"请求落到了哪个地址"间接断言 baseUrl（`endpoints()` 是私有方法）⇒ 把 baseUrl 写错之后用例不是断言失败，而是**真去请求公网**、24 秒后以网络错误结束（`Errors: 1` 而非 `Failures: 1`）。**红是红了，但红的原因看起来像"网络问题"** ⇒ 容易被当成 flaky 忽略。改法二选一：把端点解析抽成可测的小函数，或给客户端注入固定的短超时。前两次同族实例见 §11.2（两个 `Long` 换位 → 症状是 NPE）与 §13.7 |
| 46 | **⚠️ 门禁的"进度提示"也会只朝一个方向看** —— `deployment-unit-coupling` 的 detail 用 `max(components.values(), key=len)` 挑"那个分量"，而**所有分量都是单元素**时它会按迭代顺序任选一个，于是打印 **"1 of 4 Wave 2 unit(s) in one component"** 并把另外 3 个列进 `outside`，读起来像"还有一个单元缠着" | **已修**（§18.9）。本刀恰好把最后一个环打断，于是这个假设（"存在一个非平凡分量"）第一次失效。分量大小为 1 时改走单独一句，明说"每个单元都是单元素、图无环、没有单元能挡住别的单元抽取"。⚠️ 与待办 11（判据 2 根取 `WAVE2_UNITS[0]` ⇒ 空转）同族：**判据本身会随图形状改变而失真，而它失真时仍然报 PASS** |
| 47 | **⚠️ 断言"某段文本出现在 HTML 正文里"不能按原文子串判** —— 逐位渲染的样式会把连续串打散 | **本刀踩到**（§18.7）：`LoginVerificationMailTemplates.buildDigitCells` 把验证码的**每一位**放进自己的 `<span>`，所以 `MailServiceTest` 第一版对两份正文都断言 `contains("246810")` 时，HTML 那份红了 —— **红得对**。判据改成"纯文本按连续子串判、HTML 按**剥掉标签与空白后的文本**判"，顺带把"HTML 是逐位渲染的"这个既有形状钉住。⇒ **凡对 HTML 正文做"包含"断言，先问一句"它是不是被拆开渲染的"** |
| 48 | **⚠️ 注入脚本的前置断言在"插入式注入"上会卡住**还原**方向** —— 插入式注入里 `fwd` 是 `mut` 的**子串**，于是"目标态还不存在"那条断言（`n_dst == 0`）在还原时恒为假 | **本刀踩到**（§18.10，待办 36 的**第二次**实例，但方向相反）：第一次是"删除式注入的还原锚点是正向锚点的子串"，这次是"插入式注入的正向锚点是还原锚点的子串"。⇒ 前置断言改成"`n_src == 1`，且 **apply 时** 目标态不存在"。⚠️ 两次踩坑的共同点：**`fwd` 与 `mut` 互为子串的注入，用"计数必须等于 0/1"这种对称判据一定会在某个方向失效** |
| 49 | `automation → integration`（4，本刀之前唯一带语句的跨单元边） | **已做**（§19）。两个端口各**一个**方法（`isAuthorized`）；实现类 6 个公开方法里的另外 5 个在跨域位置一个都没被用到 ⇒ **刻意不上端口**。`automation → integration` 整条边消失，跨单元实现语句 5 → **1**、边 2 → **1**。⚠️ `lib-extraction.md` §3quater.5 的草稿写的是单数 `CredentialPort`，实际是**两套** —— 草稿已订正 |
| 50 | **⚠️ 静默换位点的第三类：同形不同源** —— 两个**不同提供方**的对象，方法名与签名逐字相同 ⇒ **接收者写反编译得过** | **本刀引入并已守**（§19.4）：`CsdnCredentialPort` / `JuejinCredentialPort` 的 `isAuthorized(Long) -> boolean` 逐字相同，`csdnPort.isAuthorized(id)` 写成 `juejinPort.isAuthorized(id)` 编译通过、指标一动不动、26 门禁全绿。⚠️ §16.2 的两个同步端口**也是同形**，但它们靠**参数表不同**（掘金多收分类与标签 id）把这类错误挡在编译期 —— 本刀把这个前提拿掉了。**修法：不对称桩**（一个平台授权、另一个不授权） |
| 51 | **⚠️ "某个地方有测试挡着" ≠ "这个错误形状被守住了"** —— 同一个错误形状，一个消费方被既有用例挡住、另一个完全没人守 | **本刀实测**（§19.7 注入 C / D）：同一个"接收者写反"，`BlogPlanRunService` 红 **3** 条（其中 2 条是**既有**用例**碰巧**挡住的 —— 它们给一个端口打桩 `true`、另一个默认 `false`，正好把两者分开），而 `BlogContentPlanService` 只红新加的 **1** 条（那里**根本没有读那两个派生字段的断言**）。⇒ **别拿"另一个地方有测试挡着"当这个形状被守住的证据。** 与待办 11 / 46 同族 |
| 52 | **⚠️ 断言"参数必须叫 `userId`"的守卫，前提是 `-parameters` 开着** | **本刀实例**（§19.7 注入 E）：把首参从 `userId` 改名成 `credentialOwnerId`，实现类**不用**跟着改（Java 允许覆写时换参数名），处处编译通过 ⇒ 只有 `assertFirstParameterIsTheCallerId` 能抓，**没有其它断言变红**。⚠️ 反过来，断言**类型** `Long` 的判据在这种改动下**绿着过** —— 这是待办 11 那条"判据只朝一个方向看"的**正例样本**：修好之后注入能证明它接着线 |
| 53 | **⚠️ `BlogContentPlanService` 的"已知残留"：端口路径被迫做两次凭证查询，然后**把结果丢掉** | **未修**（§19.5 末）。`ContentPlanPort` 的读方法都经过 `toVO`，而 `toVO` 要调两个 `isAuthorized` 去填前端要的派生字段；随后 `toView` 把它们**收窄掉**（`ContentPlanView` 里没有这两个字段）。⇒ 一个智能体并不读的派生字段，能让一次状态变更失败。**本刀刻意没顺手修**：修了会同时改变行为（端口路径不再可能被凭证查询的失败带崩），而本次抽取要能**被证明**是纯重构。修法是把实现体改成返回实体、由两个公开方法各自包装 —— 独立的一刀 |
| 54 | **⚠️ `CsdnBlogSyncPort` / `JuejinBlogSyncPort` 缺"同形不同源"守卫** —— 它们的 `sync` / `findSucceeded` 签名也**部分同形**，今天没有调用点会写反只是巧合 | **未做**（§19.4 末）。`BlogPlanRunService` 只注入了 `JuejinBlogSyncPort`（`publishCsdn` 走的是 `publicationService.publish(command, "csdn")`，不是端口），`agent` 侧同理 ⇒ 暂时没有写反的机会。但下一个同时接入两个同步端口的调用方就会踩到，而**那一步不会有任何东西报错**。补法与 §19.4 相同：不对称桩 |
| 55 | **⚠️ 注入验证与"判据强度"的边界要说清** —— 注入**能**发现"某个具体位置没人守"，**不能**发现"判据的**形状**够不够" | **本刀澄清**（§19.4 末）。前一条此前被写成"这类缺陷注入验证发现不了"，**过宽了**：注入 D 就发现了一处真的没人守的位置。正确的区分是 —— 前者是**位置**问题，注入落在那里就能看见；后者是**判据**问题（例如只断言参数类型不断言参数名），注入永远看不见，只能**反着用一次**：拿判据去断言它本该拒绝的形状。待办 11 / 46 / 51 都是后者 |
| 56 | **⚠️ 跨单元实现边归零之后，"= 0"这条不变式没有任何门禁守着** | **已做**（§21，**不是抽取刀**）。修法：`ci_gates.py` 加常量 `EXPECTED_CROSS_UNIT_IMPLEMENTATION_EDGES = 0` + `deployment-unit-coupling` 第 4 条判据（放在三条形状判据**之后**，双向）。⭐ 实测**直接对照**：注入 D（消费方退回具体类）时，**常量=1 ⇒ 24 pass / 0 fail，而且 detail 仍印着 `1 cross-unit edge(s) left`**；**常量=0 ⇒ 23 pass / 1 fail，唯一红的就是它**。⇒ 形状判据不是漏了，是**结构上看不见**单向边。⚠️ 归零**之前**这个常量写不出来（数字一直在动）⇒ 是 §20 解锁的。⚠️ **待办 16 仍未修**（修它要人工维护的基线文件，代价不同，已问过九次） |
| 57 | **⚠️ 反射测试"按名字取方法"要断言**恰好一个**同名方法** | **本刀修了新测试、其余未修**（§20.3 末）。原来的写法是"遍历取第一个同名方法" ⇒ 端口上出现**重载**时，它会**按 JVM 的方法顺序**挑一个，后续断言开始检查另一个方法，而它们仍然**通过**（**没有失败信息，因为根本没有失败**）。⚠️ 现状：`NotificationPortTest` / `ContentPlanPortTest` / `EmailReminderPortTest` 三处仍是旧写法；风险被"恰好签名清单"那条断言**间接**挡住（端口多一个方法它先红），所以不是活缺陷 —— 但守卫不该依赖**另一条**测试先红。注入 A 同时踩中本刀新测试的这条与 `noMethodIsDefault` |
| 58 | **⚠️ 同一次注入验证里混用了两种哈希算法** —— 注入脚本自己打印一个（原始字节），驱动脚本打印另一个（`git hash-object`，先 CRLF→LF 归一），**两条输出挨着** | **本刀踩到、已修**（§20.7）。`BlogPlanSseService.java` 的 `restore` 行报 `1ae8d38b…`、收尾报 `8df1c761…`，看起来像还原没到位，白查一轮。实测三种算法同一文件三个值：原始字节带 `blob <len>\0` 头 `1ae8d38b…` / `sha1sum` 原始字节 `87578e00…` / `git hash-object` 归一后 `8df1c761…`（本仓 `core.autocrlf=true`）。⚠️ 这是待办 42 的**第三种形态**：42 是"两个**外部工具**给出不同值"，本刀是"**同一个脚本的两条代码路径**各自打印一个"。⇒ 判据已统一：`inj20.py` 的 `blob()` 改成先归一再哈希，与 `git hash-object` 逐字等价，复跑验证通过。⚠️ 通用规则：**"脚本打印了一个陌生的哈希" ≠ "文件被改坏了"**；分辨它们的是 `git diff`（差异是否逐行都是本次意图），不是哈希比对 |

### 22.1 第 1 项的边界（明确不许顺手放宽）

`CONTRACT_ALLOWED_IMPORT_PREFIXES` **不得**为了过门禁而放宽。
遇到"契约需要 Jackson 类型"时，正确动作是**改设计**（§1.4），不是改门禁。
门禁放宽的那一刻，ADR-1 就只剩文档里的一句话了。

§4 就是这条边界的实测样本：遇到 `ModelUsage` 时**没有**放宽前缀，而是让契约自带
`TokenUsage`，代价是三个调用点上各一次三字段换算。

---

## 23. 一句话记法

```text
契约不认框架，实体不出边界。
省事的写法在这里几乎总是错的那一种 —— 因为"省事"的下一步就是"再多加一个依赖"。
```
