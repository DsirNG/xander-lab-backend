# 跨域事务边界（V9 §75 / PASS 41）

本文逐个登记 §75 指定的 10 个服务在**跨域事务**上的现状，以及拆服务后每一处应当
变成什么。目的是防止「拆服务后事务语义静默丢失」——这是本次重构里最不容易被测试
发现的一类缺陷：拆完之后每个服务单独看都正常，只有并发或失败路径才暴露。

对应 V9 章节：§2.3（能力守恒）、§21（共享内核边界）、§74（服务通信）、
§75（跨域事务）、§89 / §90 PASS 41。

> 行号取自 `feat/microservices-migration` @ `81743a5`。行号会随代码变动，
> **判断依据以方法名为准**，行号只用于快速定位。

---

## 1. 一句话结论

**当前 10 个服务里，跨域事务全部是「同一个本地事务」。** 没有任何一处用了
Outbox / Event / Saga / Compensation —— 它们之所以成立，仅仅因为所有域共用一个
MySQL 库 `xander_lab`、一个 `DataSource`、一个事务管理器。

所以拆库的那一刻，下面每一行的语义都会变，而**没有一行会报编译错误**：

```
今天：A 域的 @Transactional 方法调用 B 域的 @Transactional 方法
      → REQUIRED 传播 → 一个事务 → 要么都成，要么都回滚

拆库后：同样的调用 → B 的 @Transactional 在自己的 DataSource 上
      → 两个独立事务 → A 回滚时 B 已经提交，没人回滚它
```

---

## 2. 判定口径

§75 要求每一处明确六个选项之一。选择依据是**副作用的可回滚性**与
**跨域读写的方向**，不是"哪个更先进"：

| 选项 | 适用条件 | 本仓库是否已用 |
|---|---|---|
| **继续本地事务** | 拆库后两侧仍在同一库、同一部署单元 | ✅ 全部 10 处 |
| **REST** | 同步、需要立即拿到结果、失败必须让调用方回滚 | ❌ 未用 |
| **Outbox / Event** | 异步、可接受最终一致、**不允许丢失** | ⚠️ 仅封禁链路用了（`security_event_outbox`，不在本文 10 个类内） |
| **Saga** | 多步、每步都可补偿、跨越不可回滚的外部系统 | ❌ 未用 |
| **Compensation** | 单点补偿：写一个"待处理/失败"状态，由后续流程收敛 | ⚠️ 见 §5.3（`RecitationService` 的 `UPLOADING` 状态机） |

**关键判据**：只要事务里出现了**不可回滚的副作用**（对象存储、HTTP、MCP、Redis），
"继续本地事务"就已经是错的 —— 不是拆库后才错，是**现在就错**。§4 列出两处
（本轮已修），另有第三处属于拆库后的决策点，见 §3.2。

---

## 3. 逐个登记

### 3.1 `agent/application/AgentConversationService`

| 项 | 内容 |
|---|---|
| `@Transactional` 方法 | `create(Long, String)`（L159–160）；`prepareTurn(...)` 用 `transactionTemplate.execute`（L195） |
| 事务内跨域调用 | **无** |
| 事务内不可回滚副作用 | **无** |
| 判定 | **继续本地事务** |

`prepareTurn` 的事务体（L196–206）只有 agent 域自己的
`runLifecycle.claim` / `executionJournal.startRun` / `planService.clear` /
`saveMessage` / `conversationMapper.updateById`。

值得注意的是**两个跨域调用被刻意放在事务外**：`billingService.ensureAvailable`（L188）
与 `quizService.renderSubmission`（L191）都在 `transactionTemplate.execute` **之前**执行。
这不是巧合 —— 扣费额度检查若进事务，会把 billing 的读锁带进 agent 的写事务。

⚠️ `chargeTurnQuietly` → `billingService.chargeTokens`（L667）从 `finalizeReady`（L648）
调用，**不在事务内**。方法名里的 `Quietly` 说明它刻意吞掉异常：
计费失败不回滚对话。这是一个**已经存在的**取舍，拆服务后要保留（不能改成同步强一致）。

---

### 3.2 `blogagent/application/BlogAgentService`

| 项 | 内容 |
|---|---|
| `@Transactional` 方法 | `create`（L80–81）、`findReadyTaskByClientRequestId`（L110–111，readOnly）、`findPublishedPost`（L671–672，readOnly） |
| 事务内跨域调用 | `blogService.getBlogById`（L674，publishing，**只读**）；`finalizeResult`（L293）→ `billingService.chargeTokens`（L334，billing，**写**） |
| 事务内不可回滚副作用 | **无**（模型/图像调用都在事务外，见下） |
| 判定 | **继续本地事务**（扣费是 Wave 2 决策，见下） |

**这是本次审计里"设计得最对"的一个类。** 它的长流程 `execute(...)`（L179）
用多次 `transactionTemplate` 小块（L182/187/225/228/231）包住**每一步 DB 写**，
而把外部调用全部放在事务之外：

```
updateStage(194)  → modelClient.analyze(195)      ← 外部调用，事务外
updateStage(199)  → modelClient.research(200)     ← 外部调用，事务外
updateStage(204)  → modelClient.createArticle(208-213)
generateIllustrations(218) → imageClient.generate(565)  ← 外部调用，事务外
finalizeResult(225)                               ← 事务内，且内部只有 DB 写
```

这正是"拆服务后不需要 Saga"的形态：**每一步的 DB 写是原子的，
步骤之间的推进靠状态字段（`updateStage`），不靠长事务。**

⚠️ `finalizeResult` 在事务内调 `billingService.chargeTokens`（L334）——
**这一处今天不是缺陷，而是 Wave 2 的决策点。** 扣费是同库的 DB 写、可回滚，
所以"要么都成、要么都回滚"的事务语义今天**是正确的**（用户拿到文章就一定会被扣费，
反之亦然）。它不符合 §4 的判据（事务里出现不可回滚的副作用），
所以本轮**刻意没有改它**。

拆库后它会变成"blogagent 提交了，billing 没扣费"或反之，必须选一个：
Outbox（推荐）或改成 §3.1 那种"事务外 + Quietly"的形态。
**不能**改成"REST 调用 + 靠调用方回滚" —— 那样 billing 已经扣了、
blogagent 回滚，用户被多扣。

⚠️ 顺带说清"改成 Quietly 形态"的**语义代价**：那会让"文章已交付但没扣费"
成为可能（计费失败被吞掉）。今天的事务保证了二者一致，放弃它是一次
**有意的**取舍，不是纯技术清理 —— 所以它必须走 §11 Contract 流程，不能顺手改。

`publish(...)`（L658）与 `reviewCompliance(...)`（L705）**刻意不加事务**：
它们分别调用外部发布平台与模型审核。保持这个选择。

---

### 3.3 `identity/application/AuthService` ⚠️ 风险最高

| 项 | 内容 |
|---|---|
| `@Transactional` 方法 | `bindWechat`（L267–268）、`bindWechatExisting`（L342–343） |
| 事务内跨域调用 | `mergeWechatUserInto`（L481）→ **11 张跨域表的 `UPDATE`** + `billingService.transferBalance`（L495，billing，写） |
| 事务内不可回滚副作用 | **Redis ×4 处**：`checkRateLimit`（L269）、`verifyEmailCode`（L272）、`revokeUserTokens`（L498 → L510/513/520）、`generateTokenResponse`（L291 → L646/653） |
| 判定 | **继续本地事务** —— 但这是全仓库最难拆的一处 |

`AccountMergeMapper`（L17–48）用 11 条 `@Update` 把临时账号名下的数据整体迁到目标账号：

```
agent_conversation        blog_agent_task          blog_content_plan
blog_notification         blog_publish_record      blog_media_asset
email_reminder_task       img2three_task           flow_project
flow_ai_task              studio_project
```

**这 11 张表分属 7 个业务域**（agent / blogagent / automation / publishing /
recitation / studio / flow）。也就是说：**微信账号合并是唯一一处真正的跨域写事务**，
而它跨得最广。

拆服务后这一处的正确形态**不是** Saga —— 账号合并要求"要么全迁、要么一个都不迁"
（迁一半会留下孤儿数据），而 Saga 只能保证最终一致。可行方案只有两个：

1. **把合并做成一个独立的数据迁移作业**（不放在 identity 的请求事务里），
   由它跨库执行并保证幂等 + 可重入；
2. 或者保留"合并期间所有相关域仍在同一部署单元"的约束。

⚠️ **Redis 副作用在事务内**是更隐蔽的问题：`revokeUserTokens`（L498）删的是
`login:token:*` / `login:user_tokens:*`，`generateTokenResponse`（L646/653）写新 token。
Redis 没有回滚 —— 若 DB 回滚，被删的旧 token 不会恢复，用户会**莫名掉线**。
这一处的方向本身是对的（先写库、后写 Redis，让"库回滚 + Redis 已删"表现为
"用户需要重新登录"，比反过来安全），但必须显式记录成**已知取舍**而不是缺陷。

---

### 3.4 `automation/application/BlogContentPlanService`

| 项 | 内容 |
|---|---|
| `@Transactional` 方法 | **13 个**（本组最多）：`create`(L71)、`createOneShotPlans`(L182)、`update`(L284)、`updateStatus`(L374)、`delete`(L416)、`trigger`(L429) 等，其中 7 个 `readOnly = true`。`generateTopics` 已去掉（见 §4.1） |
| 事务内跨域调用 | `recitationService.getMaterial`（L115 / L319，recitation，只读）；`csdn/juejinCredentialService.isAuthorized`（L552–553，integration，只读） |
| 事务内不可回滚副作用 | **无**（`generateTopics` 的外部模型调用与计费写库已移出事务，见 §4.1） |
| 判定 | **继续本地事务** |

另外 `trigger`（L429）→ `runService.createManualRun`（L440）→
`transactionTemplate.execute`（`BlogPlanRunService` L73）构成**同域嵌套大事务**。
同域，拆服务时不是问题，但"大事务"本身值得在 Wave 2 复核。

---

### 3.5 `knowledgebase/application/KnowledgeFileService`

| 项 | 内容 |
|---|---|
| `@Transactional` 方法 | `search`/`detail`/`chapters`/`preview`/`content`（readOnly）、`rename`(L266)、`move`(L281)、`delete`(L296) |
| 事务内跨域调用 | **无**（`structureService` / `tagService` 都是 knowledgebase 域内） |
| 事务内不可回滚副作用 | **无** —— OSS 调用被刻意移出事务 |
| 判定 | **继续本地事务**（已有补偿模式，见 §5.2） |

**`store`（L118）刻意不加事务**，注释在 L113 写明理由。它的顺序是
**外部优先**：`ossService.upload`（L127）→ `fileMapper.insert`（L144）→
失败时 `ossService.deleteByUrlQuietly`（L147）。失败模式被选为"留下孤儿对象"，
而不是"库里有一条指向不存在对象的记录" —— 后者会让用户看到永远加载不出的文件。

**`delete`（L296）的顺序相反**：先删 5 处 DB 记录（L299–305），
再 `deleteObjectAfterCommit`（L306）。这是**正确的**：DB 记录先消失，
对象稍后删；反过来会让记录指向已删对象。

---

### 3.6 `knowledgebase/application/KnowledgeIngestionService`

| 项 | 内容 |
|---|---|
| `@Transactional` 方法 | **4 个，全部 `REQUIRES_NEW`**：`ingest`(L61)、`ingestExtractedText`(L78)、`markFailed`(L118)、`resetKnowledge`(L127) |
| 事务内跨域调用 | `deferToChapters`（L146）→ `notificationService.notifyResource`（L152，automation，写） |
| 事务内不可回滚副作用 | **无** |
| 判定 | **继续本地事务**（`REQUIRES_NEW` 语义必须保留） |

`REQUIRES_NEW` 在这里是**有意的**：摄取（解析大文件、生成章节与结构图）可能耗时很长，
而调用方 `KnowledgeFileService.store` **没有事务**。用 `REQUIRES_NEW` 保证
摄取本身是一个独立的原子单元，不会因为调用方将来加了事务而被吞进去。

⚠️ `notifyResource`（`BlogNotificationService` L85–86）也是 `REQUIRES_NEW`
→ 它会**挂起**摄取的当前事务，另开一个。跨域 + 事务挂起。
拆服务后这一处变成跨进程调用，必须改成 Outbox —— 因为通知发不出去时
`deferToChapters` 只 `log.warn`（L156–159），**吞掉异常**。
吞异常在本地调用下是安全的（通知是尽力而为），在跨进程调用下会让通知永久丢失。

---

### 3.7 `publishing/application/BlogService`

| 项 | 内容 |
|---|---|
| `@Transactional` 方法 | **10 个**：`createBlog` ×3（L142/148/154）、`updateBlog`(L210)、`updateBlogFromDto`(L248)、`updateBlogStatus`(L261)、`deleteBlog`(L277)、`permanentlyDeleteBlog`(L288)、`countManagedBlogs`(L337)、`recordView`(L474) |
| 事务内跨域调用 | `resolveAuthor`（L301，内部 L303 `userMapper.selectById`）→ identity，只读 |
| 事务内不可回滚副作用 | **Redis ×2**：`createBlog` 幂等键（L190）、`recordView` 去重键读+写（L481 / L498） |
| 判定 | **继续本地事务** |

⚠️ **`createBlog` 的三层重载是自调用**：`createBlog(dto)`（L143）→
`this.createBlog(dto, true)`（L149）→ `this.createBlog(dto, publish, null)`（L155）。
**内两层的 `@Transactional` 注解不生效**（自调用不走代理）。
当前行为无害 —— 最外层本身有注解，事务照样开。但这是一个**陷阱**：
若有人把最外层注解删掉（"反正是内层负责事务"），事务会静默消失，
而三个方法都还能编译、都能通过功能测试。

同理 `updateBlogFromDto`（L249）→ `this.updateBlog(...)`（L253）。

⚠️ `recordView`（L474）在事务内读写 Redis（L481/L498）。浏览量去重进事务，
意味着 Redis 抖动会**回滚浏览量累加** —— 可接受（浏览量不是关键数据），
但要显式记录，否则 Wave 2 接可观测性时会被当成 bug 追。

---

### 3.8 `recitation/application/RecitationService`

| 项 | 内容 |
|---|---|
| `@Transactional` 方法 | 8 个，其中 6 个 `readOnly = true`（`listMaterials`/`searchMaterials`/`countMaterials`×2/`getMaterial`/`getAttempt`/`listAttempts`）；`createMaterial`（L62）可写 |
| 事务内跨域调用 | **无** |
| 事务内不可回滚副作用 | **无**（OSS 调用都在事务外） |
| 判定 | **继续本地事务**（+ 已有补偿模式，见 §5.3） |

写路径（`updateMaterial` / `archiveMaterial` / `deleteMaterial` / `createAttempt`）
**都不用注解，而用 `transactionTemplate`**（L252/267/290/335/356/365）。
这是为了把"必须原子的一小段"与"外部调用"分开 —— 与 §3.2 的思路一致。

`deleteMaterial`（L282）：模板内删 4 张表（L291–302），模板返回**之后**才
`ossService.deleteByUrlQuietly`（L304）。同样是"DB 先、对象后"。

---

### 3.9 `virtualpay/application/VirtualPaySettingsService`

| 项 | 内容 |
|---|---|
| `@Transactional` 方法 | `adminView`（L24–25，readOnly）、`save`（L33–34） |
| 事务内跨域调用 | **无** |
| 事务内不可回滚副作用 | **无** |
| 判定 | **继续本地事务**（最干净的一处） |

`save` 用 `WechatCredentialCipher.encrypt`（L40）做本地加密，不碰外部系统。

⚠️ `save`（L34）在 L52 调 `adminView()` —— **同来自调用**，`adminView` 的
`readOnly = true` 不生效，实际在 `save` 的读写事务里执行。当前无害（只是读），
但与 §3.7 是同一个陷阱模式。

---

### 3.10 `agent/tool/mcp/application/AgentMcpServerService`

| 项 | 内容 |
|---|---|
| `@Transactional` 方法 | `list`(L58，readOnly)、`create`(L66)、`update`(L86)、`delete`(L106) —— `probe` 已去掉 |
| 事务内跨域调用 | `changed()` → `toolSource.invalidate` / `toolRegistry.invalidate`（agent 域，纯内存，无 IO） |
| 事务内不可回滚副作用 | **无**（`probe` 的外部 MCP 往返已移出事务，见 §4.2） |
| 判定 | **继续本地事务** |

---

## 4. 两处「现在就错」的外部 IO 缺陷（本轮已修）

判据是**事务里出现了不可回滚的副作用**。只有符合这条的才算"现在就错" ——
因为它和拆不拆库无关：把网络往返圈进事务，今天就意味着连接被占住，
而事务**换不来任何原子性**（远端副作用已经发生，本地回滚撤销不了它）。

⚠️ 这条判据刻意把 `BlogAgentService.finalizeResult` 的"事务内扣费"**排除在外**：
扣费是同库的 DB 写、**可回滚**，今天"要么都成、要么都回滚"的事务语义是正确的，
甚至正是想要的行为。它属于拆库后才需要决策的问题，已登记在 §3.2。

下面两处本轮已修，修法与守护测试写在各自小节里。

### 4.1 `BlogContentPlanService.generateTopics`：只读事务里的写 + 外部调用 —— ✅ 已修

修复前：

```java
@Transactional(readOnly = true)
public List<String> generateTopics(Long userId, BlogContentPlanTopicsGenerateRequest request) {
    ...
    return agentService.generateTopicSeries(...);
}
```

`generateTopicSeries`（`BlogAgentService` L121）**没有注解**，所以它跑在调用方的
**只读事务**里，然后做了两件只读事务不该做的事：

1. `modelClient.planTopicSeries`（L126）—— **外部 HTTP 调用**；
2. `billingService.chargeTokens`（L142）—— **写**，`@Transactional`（REQUIRED）
   会**加入这个只读事务**。

后果：模型调用慢的时候，一个只读事务会持有连接数秒到数十秒；
而"只读事务里发生写"在 MySQL 上不会报错（`readOnly` 只是给驱动/优化的提示），
所以**没有任何症状**。

**修法**：去掉整个事务注解（不是只去掉 `readOnly`）。方法自身只读，
`requireUser` / `selectList` 各自是独立单语句、本就不需要事务；
外部调用因此彻底在事务之外，`chargeTokens` 拿到自己的事务。

**守护**：`BlogContentPlanServiceTest#generateTopicsMustNotBeTransactional`
用注解反射断言 `generateTopics` **没有** `@Transactional` ——
纯单测拿不到 Spring 代理，把注解加回去之后所有功能测试仍然全绿。
同一文件里的 `nextRunAfterStaysReadOnlyTransactional` 反向钉住"纯读方法保留只读事务"，
避免下次修订一刀切地全删或全加。两条都做过注入验证。

### 4.2 `AgentMcpServerService.probe` 在事务内做外部 MCP 往返 —— ✅ 已修

修复前 `probe` 是 `@Transactional`，事务体内第一件事就是
`connectionFactory.listTools(server)` —— 一个真实的
`java.net.http.HttpClient` 往返，超时由 MCP 客户端决定。

后果有两面：

- **远端慢** → 事务长时间持有数据库连接（连接池耗尽的经典成因）；
- **远端成功、`mapper.updateById` 失败** → 远端已经建立了连接/会话，
  而本地状态回滚。方法注释说明失败时**保留**上一次工具目录，
  所以回滚本身符合预期；但"远端副作用已发生"这件事没有记录 ——
  **事务在这里并不能把"远端 + 本地"变成一体**，它只是把锁窗口撑大。

**修法**：去掉事务注解。方法体里只有**一条**写语句（`mapper.updateById`，L154），
它本身就是原子的；`changed()` 只是内存缓存失效。单条 UPDATE 走自动提交，
已经满足 backend-norms 的"结果单独事务持久化"。

**守护**：新建 `AgentMcpServerServiceTest`，两条注解反射断言 ——
`probeMustNotBeTransactional`（外部往返方法不得有事务）
与 `localOnlyMethodsKeepTheirTransaction`（`list`/`create`/`update`/`delete`
保持事务，其中 `create` 的"查重 + 插入"必须同事务才能防并发同名）。
已做注入验证。

---

## 5. 已经存在的补偿 / 半事务模式

不是所有跨外部系统的写都需要 Saga。仓库里已有三种**刻意的**模式，
拆服务时要**保留**而不是"顺手统一"：

### 5.1 状态字段推进（`BlogAgentService.execute`）

多步流程用 `updateStage`（L238）写状态，每步一个短事务。
崩溃后能从状态字段恢复。拆服务后**不需要 Saga** —— 因为它本来就不是一个事务。

### 5.2 afterCommit 延迟删除（`KnowledgeFileService.deleteObjectAfterCommit`，L432）

```java
if (!TransactionSynchronizationManager.isSynchronizationActive()) {
    ossService.deleteByUrlQuietly(storageUrl);      // L436-438 无事务时就地删
    return;
}
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCommit() {           // L440-445 有事务时延后
        ossService.deleteByUrlQuietly(storageUrl);
    }
});
```

这是**教科书式的正确做法**：对象删除必须在 DB 提交后发生。
拆服务后若把这个类搬走，"注册在事务同步上的回调"会失效 ——
必须换成显式的事件或补偿任务。

### 5.3 状态机补偿（`RecitationService.createAttempt`，L317）

```
模板 1（L335）：插入 status = UPLOADING
OSS 上传（L355）                            ← 事务外
模板 2（L356）：成功 → status = PENDING
模板 3（L365）：失败 → status = FAILED + errorMessage
```

用"先落一条 `UPLOADING` 行"把外部上传变成可观测的两阶段。
唯一索引冲突（`DuplicateKeyException`）时复用已落库任务（L346–352），
所以并发重复请求也是安全的。

这是**本仓库唯一一处真正意义上的补偿**，而且它是靠**状态机**而不是靠回滚实现的 ——
拆服务后可以直接保留。

---

## 6. 汇总表

| 服务 | 事务方法数 | 事务内跨域调用 | 事务内不可回滚副作用 | 判定 | 备注 |
|---|---|---|---|---|---|
| `AgentConversationService` | 1 | 无 | 无 | 继续本地事务 | — |
| `BlogAgentService` | 3 | `blogService`(读)、`billingService`(写) | 无 | 继续本地事务 | 扣费需 Wave 2 决策（§3.2） |
| `AuthService` | 2 | **11 张跨域表** + `billingService` | **Redis ×4** | 继续本地事务 | ⚠️ 最难拆（§3.3） |
| `BlogContentPlanService` | **13** | `recitation`(读) | **无** | 继续本地事务 | ✅ §4.1 已修 |
| `KnowledgeFileService` | 8 | 无 | 无（已用 afterCommit） | 继续本地事务 | — |
| `KnowledgeIngestionService` | 4（全 REQUIRES_NEW） | `notificationService`(写) | 无 | 继续本地事务 | 拆后需 Outbox |
| `BlogService` | 10 | `userMapper`(读) | **Redis ×2** | 继续本地事务 | 注解陷阱（§3.7） |
| `RecitationService` | 8 | 无 | 无 | 继续本地事务 | — |
| `VirtualPaySettingsService` | 2 | 无 | 无 | 继续本地事务 | — |
| `AgentMcpServerService` | **4** | agent 缓存（内存） | **无** | 继续本地事务 | ✅ §4.2 已修 |

**10/10 都是"继续本地事务"** —— 这不是好消息，而是说：
**Wave 0A 只完成了"登记"，没有任何一处已经改造。** 改造属于 Wave 2。

⚠️ 两个 ✅ 只是**去掉了事务里的外部 IO**（这是今天就错的技术缺陷），
**并没有改变判定** —— 这 10 处仍然全部依赖"同一个库、同一个事务管理器"，
拆库那一刻每一处的语义都会变，而没有一行会报编译错误。

---

## 7. 已知缺口

| 缺口 | 影响 | 状态 |
|---|---|---|
| 10 处全部未改造 | 本文是登记，不是实现。§75 的六个选项里，Outbox / REST / Saga 都还没落地 | Wave 2 |
| ~~§4 两处外部 IO 缺陷~~ | 已修（§4.1 `generateTopics`、§4.2 `probe`），各有注解反射测试守护并做过注入验证 | ✅ 本轮完成 |
| `BlogAgentService` 事务内扣费 | 今天同库、可回滚，语义正确；拆库后必须选 Outbox 或事务外 Quietly。**不能**改成"REST + 靠调用方回滚"（billing 已扣、blogagent 回滚 → 多扣） | Wave 2 决策 |
| `AuthService` 账号合并的拆解方案未定 | 11 张跨域表 + Redis，是唯一一处"必须跨域原子"的写事务。§3.3 给了两个方向，尚未选型 | Wave 2 设计 |
| 无跨域事务的机器门禁 | 机器能扫 `@Transactional` 方法（`scheduled-inventory.yaml` 是同类先例），但"这个方法体内的调用是否跨域"需要解析注入字段与调用链 —— 目前只能人工登记 | 待评估 |
| 自调用导致注解失效无门禁 | §3.7 / §3.9 的两处陷阱当前无害，但删掉外层注解会静默丢事务。ArchUnit 可以检测"同类自调用一个带事务注解的方法" | Wave 0B |
| 外部 IO 只在两处有注解守卫 | §4 的两处已由注解反射测试钉住，但**没有全局规则**：新写的方法若在事务里做 HTTP，不会有任何门禁发现 | Wave 0B（ArchUnit） |

---

## 8. 怎么验证

本文是人工登记，**没有对应的机器门禁**（见 §7 最后两行）。
能自动化的部分是"事务方法清单"本身 —— 可以用 §13 的扫描器手法扩展：

```bash
# 现状：scheduled-inventory.yaml 由扫描器产出，事务方法清单尚未纳入
python scripts/migration/scan.py
python scripts/migration/ci_gates.py
```

§4 两处修复各有一条注解反射测试（**这是它们唯一的守护**，功能测试发现不了）：

```bash
# §4.1 generateTopics 不得有事务注解 + nextRunAfter 保留只读事务
java ... -Dtest=BlogContentPlanServiceTest test

# §4.2 probe 不得有事务注解 + 纯本地方法保留事务
java ... -Dtest=AgentMcpServerServiceTest test
```

两条都做过**注入验证**：把注解加回去 → 对应用例立刻失败。

人工复核的最小流程：

1. 按 §6 的表逐个确认"事务方法数"与"跨域调用"两列仍然成立；
2. 重点确认 §4 的两处**没有退化**（注解被加回来时，只有那两条反射测试会红）；
3. 确认 §3.2 的扣费项仍在 —— 它在拆库前**不应该**被"顺手修掉"：
   今天的事务语义是正确的，改了反而会让"文章已交付但没扣费"成为可能。

⚠️ 与 `capability-baseline.yaml` 的关系：§12 要求能力清单只增不减。
本文属于**问题清单**，允许条目被删除（问题修好了就该消失）——
两者语义相反，不要合并。

⚠️ 数 `@Transactional` 时别用裸 `grep -c "@Transactional"`：本仓库的注释里
会**引用注解原文**（例如 `BlogContentPlanService` 的 `generateTopics` 上方就写了
"不能加只读事务注解"并解释了原因）。计数要用 `grep -cE "^\s+@Transactional"`，
否则注释会被算成注解。
