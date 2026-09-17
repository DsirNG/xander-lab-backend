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

- `contracts/` 下 4 个模块（`core-api` / `content-api` / `integration-api` /
  `payment-api`）目前**零 Jackson 依赖**，由门禁
  `contract-dependency-boundary` 守着（JDK-only import + 无 compile/runtime 依赖）。
- 尚未清理的是**边界路径上的 Jackson 泄漏**（见 §4 待办），不是契约模块本身。

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

> 反过来说：如果哪天真的把 `MediaAssetRef` 收窄到 3 个字段（§5 待办第 3 项），
> 那**必须**进 `contract-changes.yaml` 并走 §11 流程 —— 因为它会真的删掉
> 前端能看到 10 个字段。

---

## 3. 本次落地：`BlogMediaAsset / MediaAssetPort` 这一刀

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
> 代价就是像 `StoredImage` 这样的重复类型。这个问题见 §5 待办。

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

## 4. 这两条 ADR 与既有规则的交叉

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

---

## 5. 待办

| # | 事项 | 状态 |
|---|---|---|
| 1 | 边界路径上的 Jackson 泄漏（`agent-api` / billing 端口） | **未做**，按"先 publishing 后 Jackson"的顺序排在下一刀 |
| 2 | 契约能否依赖契约 | **未拍板**。今天禁止，代价是 `StoredImage` 这类重复类型 |
| 3 | `MediaAssetRef` 收窄到前端真正读的 3 个字段 | **未做**，独立 §11 变更 |
| 4 | `agent -> publishing` 剩余 3 条（`BlogService`） | **未做**，需要为 `BlogService` 另抽端口 |
| 5 | `@JsonIgnoreProperties` 存量清单与清理时点 | **未清点** |

### 5.1 第 1 项的边界（明确不许顺手放宽）

`CONTRACT_ALLOWED_IMPORT_PREFIXES` **不得**为了过门禁而放宽。
遇到"契约需要 Jackson 类型"时，正确动作是**改设计**（§1.4），不是改门禁。
门禁放宽的那一刻，ADR-1 就只剩文档里的一句话了。

---

## 6. 一句话记法

```text
契约不认框架，实体不出边界。
省事的写法在这里几乎总是错的那一种 —— 因为"省事"的下一步就是"再多加一个依赖"。
```
