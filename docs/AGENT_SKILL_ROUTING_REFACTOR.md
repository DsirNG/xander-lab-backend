# Agent Skill 路由重构：从关键词命中到模型选择并加载

> 状态：设计提案，未实施。
> 前置：`docs/AGENT_HARNESS_REFACTOR.md` §1.1 已写明"当前 Skill resolver 是刻意保守的确定性版本，
> 它收窄了模型可见面，但还不是语义意图分类器"。本文是它的后续，负责把"这一轮该有哪些能力"
> 从规则层交回模型。
> 关联提交：`2891ae1`【dxd】技能只加宽工具面，工具准则随工具一起下发。

## 1. 结论先行

把关键词意图路由替换为**技能索引 + 模型按需加载**：每轮只注入一份极薄的技能目录，
由模型自己调用基线工具 `load_skill` 把需要的技能正文和工具加载进来。分四阶段推进，
每阶段独立可回滚，默认行为切换前用配置开关并行跑一版离线对比。

三个事实决定这不是风格偏好，而是必须做：

1. **用户私有 Skill 现在根本无法生效。** `AgentUserSkillService` 只有增/查/归档，
   仓库里没有任何 `implements AgentSkill` 去读 `agent_skill` 表；而选择逻辑
   （`BuiltinAgentSkills.java:84`）是一段 `switch (id)` 硬编码的内置词表，
   `default -> 0`。用户自己建的 Skill 永远 `matchScore == 0`，进不了
   `AgentSkillRegistry.resolve`。`f4552f8` 交付的功能在当前路由下是死代码。
2. **词表决定的是"写入能力给不给"，不是"提示词写得好不好"。**
   `AgentToolExecutionPipeline.java:48` 是硬校验：不在 `allowedTools` 里的工具直接抛
   "当前 Skill 不允许调用工具 X"。所以一句"帮我把这篇复盘发到掘金"——不含
   "发布/发表/同步/publish/上线"任一词——当轮拿不到 `publish_post`，
   模型只能空口答应或绕路。这正是"我说了东西，回复我给了，但我没看见东西"的一类成因。
3. **每加一个技能都要改 Java 代码。** 加词表、加 `case`、重新编译、重新发版。
   技能本该是数据，`agent_skill` 表已经把它当数据存了，只有路由还当代码。

## 2. 现状剖析

### 2.1 一轮请求里技能是怎么起作用的

`runLoop` 在进入步骤循环**之前**算一次（`AgentConversationService.java:327`）：

```text
latestUserMessage
 └─ AgentSkillRegistry.resolve(message)              关键词打分，选 ≤2 个技能
     └─ AgentSkillSelection{skills, confidence, reason, toolNames, instructions}
         ├─ allowedTools = selection.toolNames()     循环外的局部变量，整轮不变
         └─ recordSkillSelection(...)                写 agent_run.intent_json / skills_json
for (step = nextStepNo .. maxSteps) {
    plan = planService.load(...)                     每步重读：计划是会话状态
    requestAssembler.assemble(..., allowedTools, ..., skillSelection)
     ├─ toolRegistry.describeToolSurface(allowedTools)   schema + 工具准则 → instructions 字段
     ├─ context 追加 "当前激活的 Skill 规则：" + selection.instructions()
     └─ schemaHash = sha256(toolDescriptions)            进 agent_step 请求快照
    toolExecutionPipeline.execute(..., allowedTools, toolName, ...)  硬校验 → 审批 → 落账
}
```

这里有一处刺眼的不对称：**计划每步重读，能力面整轮不变。**
`AgentConversationService.java:349` 的注释自己写着"每一步重新读计划：智能体上一步可能刚改写过它，
这是会话状态而不是一次性输入"。能力面同样是会话状态——第 3 步查完文章才知道要发布——
却被当成一次性输入定死在第 0 步。这条不对称是本次重构要抹掉的东西。

### 2.2 `2891ae1` 改了什么、没改什么

| 关注点 | 改之前 | 改之后（当前） |
| --- | --- | --- |
| 命中技能对工具表的作用 | 整体**替换**，词表即能力上限 | 只**加宽**，`BASELINE_TOOLS` 是每轮下限 |
| 一句"给我一个 sse 代码" | 当轮只剩 `emit_artifact`，除了开交付卡别无选择 | 基线 12 个工具都在，写围栏还是开卡由模型判断 |
| 工具准则写在哪 | 全局行动准则 + 工具 description 各一份，改一处漏一处 | 挂在工具上（`AgentPromptSection`），只在本轮工具表里才渲染 |
| 关键词仍然决定什么 | — | ①写入类工具给不给；②注入哪份技能正文 |

也就是说：上一次提交拆掉了关键词对**能力下限**的控制，没有拆掉它对**能力上限**的控制。
`blog_generate`、`publish_post`、`create_knowledge`、`schedule_plan`、`email_reminder`、
`quiz_knowledge`、`delegate_subtask` 这些仍然只能靠词表拿到。症状范围小了，机理没变。

### 2.3 关键词路由的四个结构性缺陷

**a. 词表和用户表述的距离无法收敛。** `BuiltinAgentSkills.java:85-90` 的六张词表各 5–8 个词。
"发到掘金""推上去""同步一下线上"——只有第三个命中。补同义词是在追一条永远追不上的长尾，
而每次漏命中的表现都是**静默失能**：模型看不到工具，于是给一个听起来完成了的回复。

**b. 用户自建技能无处挂钩。** `AgentSkill.matchScore(String)` 要求每个技能自带匹配逻辑，
而 `agent_skill` 表里只有 `name`/`description`/`instructions`/`tool_names_json`
（`AgentUserSkill`）——用户不会写词表，也不该写。要让用户技能参与关键词路由，
就得额外引入 embedding 服务与向量存储，为一个本该由模型顺手完成的判断新增一套基础设施。

**c. 打分语义站不住。** `score()` 是 `matches == 0 ? 0 : min(baseScore, matches + 1)`，
`baseScore` 全是 3；`resolve` 取 `score >= highest - 1` 的前两个；
`confidence = min(0.95, 0.55 + highest * 0.12)`。这个 confidence 会被写进
`agent_run.intent_json` 和每步请求快照，但它不是任何事件的概率，
既没有阈值可调，也没法拿它做评测或告警——只是一个看起来像概率的常数表。

**d. 技能正文没有"加载"概念，命中即注入。** `AgentRequestAssembler` 把
`selection.instructions()` 直接拼进 context。7 个内置技能各一两句尚可；
一旦用户各建十几个，正文长度与技能数线性增长，而互相冲突的 playbook 同时在场
会让模型摇摆——这是上下文预算和注意力的双重浪费。

## 3. 三条可选路线的对比

| 维度 | A 继续做规则层<br>（补同义词 / 上向量召回） | B 前置一次分类调用<br>（先选技能，再进主循环） | **C 索引 + 按需加载**<br>（推荐） |
| --- | --- | --- | --- |
| 支持用户自建技能 | 需要额外 embedding 服务与向量存储 | 支持 | 支持，天然（描述就是索引） |
| 上下文成本 | 0 | 索引进分类调用 | 每轮索引 ≈ 技能数 × 1 行；正文只在加载后进 |
| 延迟 | 最低 | **每轮固定 +1 次往返**，纯聊天也付 | 只有需要技能的轮次 +1 步 |
| 运行中纠错 | 不能，整轮定死 | 不能，分类发生在看到工具结果之前 | 能，任何一步都可再加载 |
| 漏判时的表现 | 静默失能：拿不到工具却照样答 | 整轮走错，且无补救 | 退化到基线工具，能力仍在，可当场补载 |
| 可审计 | 有：`intent_json` 的 reason/confidence | 有 | 更强：每次加载是一条 `agent_tool_invocation` |
| 可评测 | confidence 是伪概率，调不动 | 可测分类准确率 | 可测加载召回率/误载率，且每次加载有落账 |
| 代码改动面 | 小，但治不了根 | 中：新增一次模型调用与新协议字段 | 中：新增 1 个工具 + 运行态回填 + 每步重算 |

**为什么不是 A。** 它把"模型该不该有某个能力"继续留在规则层。规则层永远不知道用户下一句怎么说，
而它猜错的代价是静默失能——最难被发现、也最伤信任的那种失败。向量召回只是把词表换成更贵的词表：
仍然是在模型说话之前替它做决定。

**为什么不是 B。** 三条理由，按重要性排序：
① 分类必须在看到对话细节与工具结果**之前**完成，但"要不要发布"这类判断往往在查完文章之后才成立；
② 固定往返落在所有轮次上，"随便聊聊"也要为路由付一次模型调用，
而这与 Bug 2（"一直自检、回复很慢、体验很不好"）的方向正好相反；
③ 它把一次调用专门花在路由上，而路由本可以由主循环的第一步顺手做掉——那一步无论如何都要发生。

**为什么不是"把所有技能正文都注入"。** 见 §2.3d：成本与技能数线性增长，且冲突的 playbook
同时在场会稀释注意力。索引 + 加载就是 progressive disclosure：**索引负责让模型知道存在什么，
加载负责让它拿到细节。**

## 4. 目标形态

### 4.1 能力面分三层

```text
第 1 层  基线工具        无条件在表里，与用户说了什么无关
         AgentSkillRegistry.BASELINE_TOOLS（当前 12 个：只读查询 + 会话内交付判分 + 计划记忆）
         再加一个 load_skill
第 2 层  技能索引        每轮渲染，一行一个技能：key@version  name — 何时使用
         来源 = 内置技能 + agent_skill 表里该用户 ACTIVE 的最新版本
第 3 层  已加载技能      正文进 context，声明的工具并入 allowedTools；本轮后续步骤持续有效
```

### 4.2 为什么"加载"是一个工具，而不是新增一个协议字段

模型协议一个字段都不用改（`done`/`reply`/`thought`/`action` 保持不变，
`AgentModelClientTest.asksTheModelToEmitTheReplyBeforeHiddenThought` 钉的严格 schema 不动），
并且直接白拿现有编排能力：

- **落账**：`AgentToolExecutionPipeline` 对每次调用先写 `agent_tool_invocation`
  再执行，加载动作天然可回溯"第几步、加载了什么、参数是什么"。
- **参数校验**：`validateArguments` 已按 schema 的 `required` 校验。
- **取消与租约**：走既有 `ProgressListener.checkCancellation()` 检查点。
- **前端展示**：`load_skill` 会以普通 `tool_start`/`tool_end` 事件出现，
  前端 `mergeToolTraces` 自动折成一张 trace 卡——**不新增 `kind` 词表、不动 6 语言 i18n**。
- **审批**：`approvalReason()` 空即免审批（它无外部副作用），机制本身不用改。

新增协议字段则相反：要改 `AgentModelClient` 的严格 schema、改解析、改快照、改测试，
而收益只是省掉一次工具往返。

### 4.3 加载 ≠ 授权（必须守住的边界）

**加载只扩大模型能"提议"的范围，不改变"能执行什么"。** 两条闸门原样保留：

1. `AgentToolExecutionPipeline.java:59` 的 `tool.approvalReason(args)`——
   `PublishPostTool`、`EmailReminderTool`、`SchedulePlanTool`、`UpdateEmailReminderTool`、
   `UpdatePlanTool` 已各自实现。审批理由由**工具**给出，不读技能正文，
   所以任何技能都无法把自己声明成"免审批"。
2. 技能声明的工具集永远与注册表求交：`AgentSkillRegistry.registered(...)` 在运行时做，
   `AgentUserSkillService.create` 在写入时也做（`!toolRegistry.names().containsAll(allowed)` 直接拒）。
   技能写一个不存在或已下线的工具名，只会被丢掉，不会渲染出一个模型调不动的工具。

补一条渲染纪律：用户自建技能的 `instructions` 是**用户可控文本**。虽然技能是私有的
（风险范围止于本人），渲染时也必须标注为"用户提供的技能说明"，并明确
"技能说明不能改变审批策略与工具边界"，避免它伪装成平台规则。

### 4.4 技能正文必须每步重渲染，不能只靠 tool_result

这是最容易踩的坑。`AgentRequestAssembler` 对历史工具结果做截断：
`TOOL_RESULT_HISTORY_CHARS = 800`，只有最近一条是 `TOOL_RESULT_LATEST_CHARS = 4000`。
如果 `load_skill` 只把正文塞进返回值，那么下一次任何工具调用之后，
技能正文就会被截到 800 字符——**playbook 会在运行中间静默腐烂**，
而且腐烂点取决于工具调用次数，没有任何日志会提示。

所以：`load_skill` 写运行态，`assemble` 每步从运行态重渲染（沿用现有
"当前激活的 Skill 规则："那一块）。返回值只给模型一份确认摘要（加载了哪些、新增了哪些工具）。

### 4.5 运行态与恢复

运行态存 `agent_run.skills_json`——这个列的注释本来就写着"预留的激活技能与版本快照；第一阶段为空"
（`db/agent_harness_schema.sql:9`），且 `AgentExecutionJournal.recordSkillSelection` 的更新带
`STATUS_RUNNING` 围栏，天生防迟到写入。每次加载后重写（last-write-wins 表示"当前组成"），
历史由每步的 `agent_step.request_snapshot_json` 承担——它已经在记
`skillIds`/`skillVersions`/`toolNames`。

**必须在 `runLoop` 开头回填，否则恢复路径会丢能力。**
`resumeApprovedInvocation`（`AgentConversationService.java:244`）在审批通过后执行完那次调用，
接着调 `executeClaimedTurn(..., currentUserMessage = null, ...)` **重进 `runLoop`**。
今天重进后是重新 `resolve` 一遍关键词（结果大致稳定，所以看不出问题）；
改成模型加载后，如果不从 `agent_run.skills_json` 回填，
一个"发布"技能会在用户点完同意之后凭空消失，模型只能再加载一次或者答不下去。
陈旧运行恢复（`nextStepNo` 从中间接上）同理。

### 4.6 prompt 缓存：工具面前缀要稳

`describeToolSurface` 目前遍历按名字排序的注册表再按成员过滤。中途加载一个工具，
它会插到块的**中间**，缓存前缀从插入点起全部失效。建议渲染成两段：

```text
可调用工具：
  <基线块：固定 13 个工具，逐字节不变，跨轮跨会话都能命中缓存>
  本轮已加载技能新增的工具：
  <追加块：随加载变化>
工具使用准则（只对本轮在表里的工具有效）：
  T1. …
```

索引块同理，按 `skillKey` 稳定排序（`AgentUserSkillService.listActive` 已经
`orderByAsc(skillKey).orderByDesc(version)`）。
`schemaHash` 会随加载改变——这是正确行为，它就该记录能力面变了。

### 4.7 加载动作的语义细节

- **一次可加载多个**：`load_skill({keys: ["blog-writing", "my-weekly-report"]})`，
  省往返。单轮上限建议 3 个，并对合并后的工具总数设上限，
  防止模型把整个目录加载进来把工具表撑爆。
- **幂等**：已加载的 key 重复加载直接返回"已加载"，不重复写运行态。
- **不做卸载**：一轮之内能力只增不减，避免"刚加载又卸载"的抖动；
  跨轮由运行态是否延续来控制（见 §4.8）。
- **索引里标注已加载状态**，否则模型会重复调用。

### 4.8 延迟：把 +1 步的代价压到最小

用户对慢的容忍度已经在 Bug 2 里表达得很清楚，所以这一节是硬要求而不是优化项：

1. **只读能力不需要技能。** 查询类工具早已在 `BASELINE_TOOLS` 里，
   所以"我有哪些待办""最近写了什么"零额外往返。
2. **纯对话零额外往返。** 索引只是几行文本，不加载就不产生调用。
3. **同会话延续。** 一轮加载过的技能默认在下一轮仍然生效（运行态从上一代 run 继承），
   连续追问不必反复加载。用户换话题时模型自然不再用它，工具多几个不构成伤害
   （基线本来就有 12 个）。
4. **加载与首个业务动作可以合并**：如果模型第一步就打算调 `publish_post`，
   它需要先 `load_skill`——这一步只有一次极短的往返（参数就是几个 key，
   返回值是摘要），不产生长文本生成。

## 5. 修改路线

分四阶段。每阶段可独立发版、独立回滚，且**除 Phase 3 的默认切换外都不改变现有行为**。

### Phase 0：统一契约与目录（不改任何行为）

| 动作 | 文件 |
| --- | --- |
| 新增 `AgentSkillDescriptor`：`key/version/name/whenToUse/toolNames/instructions/source` | 新增 `application/AgentSkillDescriptor.java` |
| 新增 `AgentSkillCatalog`：`list(userId)` = 内置 + `AgentUserSkillService.listActive` 映射，按 `(source, key)` 稳定排序；`load(userId, key)` | 新增 `application/AgentSkillCatalog.java` |
| 内置技能补 `name` 与 `whenToUse`（"什么情况下用"，不是"这个技能是什么"） | `BuiltinAgentSkills.java` |
| `AgentSkill` 加 `default String whenToUse()`，`matchScore` 原样保留 | `AgentSkill.java` |

为什么先做这一步：它把"两种来源的技能"归一成一个视图，后面三阶段都依赖它；
而它本身没有调用方，出问题删掉即可。
验证：新增 `AgentSkillCatalogTest`（内置+用户混排顺序稳定、归档不出现、同 key 只出现最新版本）。

### Phase 1：索引进提示词（模型开始"知道"，还不能"加载"）

| 动作 | 文件 |
| --- | --- |
| `assemble` 在 `toolDescriptions` 之后追加"可加载技能"索引块 | `AgentRequestAssembler.java` |
| 开关 `agent.skill.index-enabled`（默认 true） | 新增 `AgentSkillProperties`（不要塞进 `AgentModelProperties`，它只管模型连接） |

`AgentModelClient` **一行都不用改**：索引拼在 `toolDescriptions` 字符串里，
`schemaHash = sha256(toolDescriptions)` 自动覆盖索引变化。
为什么单独成一阶段：它自己就有价值（模型至少能回答"我有什么技能"），
并且能先把 token 成本和缓存命中率量出来，再决定后续。
验证：`AgentRequestAssemblerTest` 加两条——索引按 key 稳定排序；无技能时不渲染空块。

### Phase 2：`load_skill` 与运行态（核心）

| 动作 | 文件 | 要点 |
| --- | --- | --- |
| 新增 `LoadSkillTool implements AgentTool` | 新增 `application/LoadSkillTool.java` | 覆写 `execute(AgentToolContext, …)`（它要写会话状态）；`approvalReason` 留空；`promptSection` 说明"缺能力先加载再答，不要凭空承诺" |
| `load_skill` 进基线 | `AgentSkillRegistry.BASELINE_TOOLS` | 它是能力入口，不能反过来依赖技能命中 |
| 运行态读写 | 新增 `application/AgentRunSkillState.java` + 复用 `recordSkillSelection` 写 `agent_run.skills_json` | 每次加载后重写；历史靠 `agent_step` 快照 |
| `runLoop` 开头回填运行态 | `AgentConversationService.java:327` 附近 | §4.5 的硬性要求，否则审批恢复丢能力 |
| `allowedTools` 由"循环外定值"改为"每步从运行态重算" | `AgentConversationService.java:341` 的循环体 | 与"每步重读计划"对齐 |
| 分两段渲染工具面 | `AgentToolRegistry.describeToolSurface` | §4.6，保住缓存前缀 |

`AgentToolExecutionPipeline` 不用改：它校验的是**调用方传入的当步** `allowedTools`。
新增 `AgentPromptSection.capability(50)` 档位，让 `load_skill` 的准则排在交付类准则之前
（先决定"有没有能力"，再决定"答案长什么样"）。
不新增数据库列、不新增事件类型、不新增消息 `kind`——因此**不触发 6 语言 i18n 与迁移契约的改动**。
验证：新增 `LoadSkillToolTest`（越权 userId 取不到、未注册工具名被丢、超上限被拒、重复加载幂等）；
`AgentConversationServiceTest` 加"加载后的下一步能调到新工具"与"审批恢复后仍持有已加载技能"。
回滚：把 `load_skill` 从基线移除，其余代码留着不生效。

### Phase 3：默认切换与离线对比

| 动作 | 说明 |
| --- | --- |
| `agent.skill.selection = keyword \| model` | `model` 模式下 `resolve()` 不再打分，只返回基线 + 通用指令，技能全靠加载 |
| 离线对比 | 同一批真实诉求跑两种模式，直接比 `agent_step.request_snapshot_json.toolNames`——不需要人工标注就能算出"该拿到的工具是否拿到" |
| 观察窗口 | 至少一个发版周期；`keyword` 保留为回滚位 |

`AgentSkillRegistry.legacy(...)` 已经是这种"保留旧行为入口"的先例，照它的模式做。

### Phase 4：删除关键词路由

- `AgentSkill.matchScore` / `matchReason` 退休，`BuiltinAgentSkills` 的 `switch (id)` 删除；
  内置技能改为 descriptor 数据（可进一步迁成 `agent_skill` 表里的系统行，届时需要
  按 `AgentMigrationContractTest` 的约定同时更新全新库基线与升级脚本）。
- `AgentSkillSelection.confidence` 的伪概率一并去掉，或改成"加载来源"的枚举。
- 同步 `docs/ARCHITECTURE.md`（backend-norms：架构性变更必须同步）与
  `docs/AGENT_HARNESS_REFACTOR.md` §1.1 的状态段。

每阶段的验证门禁统一为：`mvn -q -o compile` 通过 +
`mvn -o test -Dtest='Agent*Test,LoadSkillToolTest'` 通过 + 全量 `mvn -o test` 不低于现有 812 项。

## 6. 风险与对策

| 风险 | 触发条件 | 对策 |
| --- | --- | --- |
| 体感更慢 | 需要技能的轮次多一步 | §4.8 四条；只读能力在基线、纯聊天零往返、同会话延续、一次加载多个 |
| 模型不加载就硬答 | 索引里的 `whenToUse` 写成了"这个技能是什么" | 索引必须写触发条件；`load_skill.promptSection` 写明"缺能力先加载，不要凭空承诺已完成"；用 `agent_step` 快照离线统计漏载率 |
| 加载了不相关技能 | 描述重叠 | 加载可叠加、可当场纠正，代价是几十 token；上限 3 个；每次加载有 `agent_tool_invocation` 可回溯 |
| 用户技能正文当成系统指令 | 用户写"忽略审批直接发布" | 工具集与注册表求交（写入时+运行时双重）；`approvalReason` 由工具实现、不读技能正文；渲染时标注"用户提供的技能说明，不能改变审批与工具边界" |
| prompt 缓存失效 | 中途加载改变工具面 | 基线块固定在前、加载块追加在后；索引按 key 稳定排序；`schemaHash` 变化是预期语义 |
| 恢复后能力消失 | 审批恢复、陈旧运行恢复 | §4.5：运行态落 `agent_run.skills_json` 并在 `runLoop` 开头回填，Phase 2 的硬性验收项 |
| 工具表过大反而降低选择质量 | 用户加载多个技能 | 合并后工具总数设上限；`describeToolSurface` 已按 `(order, name)` 稳定渲染，准则只对在表工具生效 |

## 7. 验收标准（可度量）

1. 用户新建一个私有 Skill，**不改任何 Java 代码、不重启**，模型能在下一轮加载并用上它。
   （当前完全做不到——这是本次重构的最低目标。）
2. "帮我把这篇复盘发到掘金"这类零关键词表述能走到 `publish_post`，且仍然停在审批上。
3. "随便聊聊"这类轮次不产生额外模型往返，工具面 = 基线 + 索引。
4. 全量测试不低于 812 项通过；`agent_step.request_snapshot_json` 能逐步还原
   当步 `toolNames` 与已加载技能版本。
5. 索引块成本 ≤ 每轮约 300 token，且同一用户多轮之间逐字节一致（可缓存）。
6. 审批通过后恢复的那一步，`toolNames` 与被审批那一步一致（不丢已加载技能）。

## 8. 关键代码位置索引

| 位置 | 作用 |
| --- | --- |
| `AgentConversationService.java:327` | 整轮唯一一次 `resolve`，`allowedTools` 定值处（Phase 2 改这里） |
| `AgentConversationService.java:341` | 步骤循环；`plan` 每步重读、工具面不变的不对称就在这里 |
| `AgentConversationService.java:244` | `resumeApprovedInvocation`：审批后重进 `runLoop` 的恢复路径 |
| `AgentSkillRegistry.java:32` | `BASELINE_TOOLS`，每轮能力下限 |
| `AgentSkillRegistry.java:60` | `resolve`：打分、`highest-1` 窗口、取两个、伪概率 confidence |
| `AgentSkillRegistry.java:107` | `registered`：与注册表求交，丢掉未注册工具名 |
| `BuiltinAgentSkills.java:84` | `switch (id)` 关键词表（Phase 4 删除） |
| `AgentRequestAssembler.java` | 工具面渲染、`schemaHash`、"当前激活的 Skill 规则"、工具结果 800/4000 截断 |
| `AgentToolRegistry.describeToolSurface` | schema + 工具准则渲染（Phase 2 拆两段） |
| `AgentToolExecutionPipeline.java:48` | `allowedTools` 硬校验 |
| `AgentToolExecutionPipeline.java:59` | `approvalReason` 审批闸门 |
| `AgentExecutionJournal.recordSkillSelection` | 写 `agent_run.intent_json/skills_json`，带 RUNNING 围栏 |
| `AgentUserSkillService` | 用户私有技能 CRUD，写入时校验工具名 |
| `AgentTool.execute(AgentToolContext, …)` | 需要写回会话状态的工具的扩展点（`load_skill` 走这里） |

