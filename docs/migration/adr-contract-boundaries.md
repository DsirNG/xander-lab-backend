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

- `contracts/` 下 5 个模块（`core-api` / `content-api` / `integration-api` /
  `payment-api` / `agent-api`）目前**零 Jackson 依赖**，由门禁
  `contract-dependency-boundary` 守着（JDK-only import + 无 compile/runtime 依赖）。
- `agent-api` 是这条 ADR 最难的一次应用：要抽的端口属于 agent 的工具体系，而
  `AgentTool.parametersSchema()` 返回 `JsonNode`。**没有**放宽门禁，也没有搬 `AgentTool`，
  而是让 JSON 以文本出入端口（见 §6）。

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

> 反过来说：如果哪天真的把 `MediaAssetRef` 收窄到 3 个字段（§8 待办第 3 项），
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
> 代价就是像 `StoredImage` 这样的重复类型。这个问题见 §8 待办。

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
（见 §8 第 7 项）。

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
与 `TokenUsage` 一样是"拆分后才会变成线上格式"的东西（见 §8 第 7 项）。
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
   门禁——而 §8.1 明写"不许为了过门禁而放宽"。
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

## 7. 这两条 ADR 与既有规则的交叉

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

## 8. 待办

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

### 8.1 第 1 项的边界（明确不许顺手放宽）

`CONTRACT_ALLOWED_IMPORT_PREFIXES` **不得**为了过门禁而放宽。
遇到"契约需要 Jackson 类型"时，正确动作是**改设计**（§1.4），不是改门禁。
门禁放宽的那一刻，ADR-1 就只剩文档里的一句话了。

§4 就是这条边界的实测样本：遇到 `ModelUsage` 时**没有**放宽前缀，而是让契约自带
`TokenUsage`，代价是三个调用点上各一次三字段换算。

---

## 9. 一句话记法

```text
契约不认框架，实体不出边界。
省事的写法在这里几乎总是错的那一种 —— 因为"省事"的下一步就是"再多加一个依赖"。
```
