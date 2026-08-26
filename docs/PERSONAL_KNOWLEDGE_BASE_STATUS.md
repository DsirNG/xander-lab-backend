# 个人知识库现状与待办

> 盘点日期：2026-08-24
> 更新日期：2026-08-26（补充测验/录音幂等、默认关闭录音能力、页面错误态与轮询容错测试）
> 盘点范围：`xander-lab-frontend`、`xander-lab-backend` 中的个人知识库（Knowledge Mirror / recitation）及 Agent 联动能力。

## 结论

当前已经达到"可演示的背诵型 MVP"，但还不能判定为"完整上线完成"。

- 代码层面：前端入口、知识创建、列表与详情、录音、异步转写、文本对齐评分、掌握度和复习时间、Agent 创建/查询工具均已接通。
  - **2026-08-25 第三轮**：知识的编辑、归档/恢复与彻底删除也已接通（含 attempt、测验留档与 OSS 音频的清理口径），知识不再只能新增。
- 验证层面：相关测试、前后端全量测试、前端 lint 和生产构建均通过。
- 产品层面：目前真正可测试的只有"粘贴文本 + 录音背诵"。概念类 AI 问答、数学练习题、OCR、文档导入与拆分仍未实现。
  - **2026-08-25 更新**：概念类 AI 问答与练习题的测验闭环（出题、作答、逐题判分、掌握度推进）已实现，详见下表；OCR、文档导入与拆分仍未实现。
- 上线层面：本次只核对了代码和自动化测试，没有连接生产数据库、OSS、模型配置或线上 HTTPS 页面，因此不能证明线上录音闭环已经可用。

建议将当前状态定义为：**MVP 代码完成，待完成上线门禁和核心补测；完整个人知识库仍在建设中。**

## 已完成能力

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 工作台入口 | 已完成 | 已注册 `/workspace/knowledge/:materialId?`，工作台侧栏已有入口并采用懒加载。 |
| 知识创建 | 已完成 | 支持标题、正文以及 `RECITATION`、`CONCEPT`、`MATH` 三种类型，后端有长度和非空校验。 |
| 知识列表与详情 | 已完成 | 数据按当前用户隔离，展示掌握分、掌握等级、复习次数和下次复习时间。 |
| 录音权限交互 | 已完成 | 显式说明用途并由用户触发浏览器麦克风授权，不会静默录音。 |
| 录音上传与持久任务 | 已完成 | 先创建任务，再上传 OSS；任务 ID 保存在 URL 查询参数中，刷新后可继续查询。 |
| 异步音转文 | 已完成 | 通过管理台 `recitation_asr` 模型配置接入 DashScope 异步转写，包含提交、轮询、超时和失败状态。 |
| 多实例任务处理 | 已完成 | Worker 使用条件更新和 `run_token` 认领任务，并包含陈旧认领恢复。 |
| 背诵评分 | 已完成 | 对原文和转写文本做确定性归一化、编辑距离对齐，统计正确、漏字、错字和多字。 |
| 掌握度与复习计划 | 已完成 | 根据最近一次有效分数计算 `NEW` 到 `MASTERED` 五级掌握度及 12 小时到 30 天的下次复习时间。 |
| Agent 联动 | 已完成 | Agent 可在用户明确要求时创建知识，也可查询知识、掌握度和到期复习项。 |
| 概念/数学测验闭环 | 已完成（2026-08-26 加固） | 知识页把 `materialId` 明确交给智能体；`quiz_knowledge` 创建绑定用户、知识和对话的持久化 `quizSessionId`，`grade_answer` 必须凭该会话逐题留档。服务端按 `Σcredit / 题数 × 100` 计算，同一会话重复评分复用原结果，不重复增加复习次数。 |
| 测验成绩单 | 已完成（2026-08-25 增量） | 新增 `knowledge_quiz_attempt` 表与 `GET /api/recitations/materials/{id}/quizzes`；知识页对非录音类知识展示"让智能体考我"入口和最近一次得分、答对题数、总体讲评、逐题证据。 |
| 子智能体边界 | 已完成（2026-08-25 增量） | `grade_answer` 列入 `SubAgentRunner.FORBIDDEN_TOOLS`：子智能体只拿到任务简报、看不到用户作答，让它判分等于凭空改写掌握度与复习时间。 |
| 服务端知识检索 | 已完成（2026-08-25 增量） | `RecitationService.MaterialQuery` 把搜索、掌握等级、到期过滤下推到 SQL；`query_knowledge` 与 `GET /api/recitations/materials` 共用同一条查询路径，超过 100 条不再静默漏查，并会说明"还有 N 条没有返回"。 |
| 知识编辑 | 已完成（2026-08-25 第三轮） | `PUT /api/recitations/materials/{id}` 部分更新：只写真正变了的字段，改标题不刷正文。改正文按归一化文本判断实质变化——只调标点或空白不清掌握度；换了正文或考法才把掌握度归零、重排复习时间，`review_count` 作为历史保留。只改知识类型不给考法时按新类型自动匹配考法。 |
| 知识归档与恢复 | 已完成（2026-08-25 第三轮） | `POST /api/recitations/materials/{id}/archive?archived=` 同一端点双向切换。归档后不进日常列表、不进到期复习，`query_knowledge` 的默认口径也是 `ArchiveFilter.ACTIVE`，智能体不会再拿它考你。页面有"在用/已归档"视图切换，归档因此是可逆出口而不是黑洞。 |
| 知识删除与音频清理 | 已完成（2026-08-26 加固） | `DELETE /api/recitations/materials/{id}` 在一个事务里依次删 `knowledge_quiz_attempt`、`knowledge_quiz_session`、`recitation_attempt`、`recitation_material`，再尽力删 OSS 音频；前端删除有二次确认。 |
| 六语言国际化 | 已完成 | `zh/en/fr/ja/ru/vi` 的知识库命名空间均为 56 个 key，无缺项。<br>**2026-08-25**：测验相关新增 10 个 key 并移除 `testComingSoon`，现为 6 种语言各 47 个顶层键、65 个叶子键，无缺项。<br>**2026-08-25 第三轮**：编辑/归档/删除相关新增 17 个 key 与 `view` 子对象，现为 6 种语言各 65 个顶层键、84 个叶子键，无缺项。 |
| 数据库脚本 | 已具备 | 新库基线与存量迁移脚本均存在，表和新增列带中文注释及必要索引。<br>**2026-08-26**：新增 `20260826_knowledge_idempotency_migration.sql`，创建 `knowledge_quiz_session`，回填历史评分会话，并为评分会话和录音客户端请求 ID 建唯一索引；存量环境仍需手工执行。 |

## 尚未做完或需要改进

### P0：上线前必须完成

1. **补齐并实测部署步骤**
   - 现有 `application.yml` 注释只提示执行 `recitation_migration.sql`，但存量环境还必须执行 `20260824_recitation_knowledge_mirror_migration.sql` 才具备知识类型、掌握度和复习字段。
   - **2026-08-25 增量**：存量环境还需追加执行 `knowledge_quiz_migration.sql`（新增 `knowledge_quiz_attempt` 表），否则概念/数学测验会在写库时失败。
   - **2026-08-25 第三轮**：存量环境还需执行 `20260825_knowledge_lifecycle_migration.sql`（给 `recitation_material` 加 `archived_at` 列与 `(user_id, archived_at, id)` 索引），否则列表查询会因缺列直接报错——归档口径是所有列表查询的默认条件，这条迁移是硬依赖，不是可选项。新库基线 `recitation_schema.sql` 已含该列。
   - 应把新库、存量库的 SQL 执行顺序写入部署文档，并在测试库做一次真实迁移验证。（仍未完成）

2. **修正录音功能的默认开关**
   - 当前 `RECITATION_ENABLED` 默认值为 `true`，但代码注释和项目规范都要求外部副作用能力在迁移、OSS、模型配置完成前默认关闭。
   - 建议默认改为 `false`，由部署环境显式开启。
   - **已完成（2026-08-26）**：`application.yml` 改为 `${RECITATION_ENABLED:false}`；需要录音能力的环境必须显式开启。

3. **完成真实环境冒烟测试**
   - 验证登录用户隔离、建知识、浏览器授权、WebM/Ogg 上传、OSS 可访问性、DashScope 提交与轮询、结果回写、刷新恢复和失败提示。
   - 麦克风除 localhost 外依赖 HTTPS，必须在真实域名验证。

4. **补核心业务测试**
   - 当前有对齐算法、掌握策略、ASR 适配器和 Agent 工具测试，但没有 `RecitationController`、`RecitationService`、`RecitationWorker` 的专项测试。
   - 至少覆盖鉴权、用户归属、上传失败留痕、状态竞争、陈旧任务恢复、轮询耗尽、完成时同时更新 attempt/material，以及真实 HTTP 状态码。
   - **2026-08-25 状态**：仍未完成。本轮只新增了测验侧测试（`KnowledgeQuizServiceTest`、`QuizKnowledgeToolTest`、`GradeAnswerToolTest`），Controller/Service/Worker 三处依旧没有专项测试。
   - **2026-08-25 第三轮**：`RecitationServiceTest` 已补到 23 条，但只覆盖编辑/归档/删除与查询口径这一片；`RecitationController` 仍只有 2 条测试（新增的三个端点没有 Controller 级覆盖），`RecitationWorker` 依旧零专项测试。上传失败留痕、状态竞争、陈旧任务恢复、轮询耗尽这几项仍然没测。
   - **2026-08-26 增量**：新增 `RecitationWorkerTest` 5 条，覆盖功能关闭不扫描、认领竞争不调远端、轮询耗尽、陈旧提交/查询租约恢复，以及成功时 attempt/material 同事务更新；`RecitationControllerTest` 补编辑、归档恢复与删除的用户和参数转发。Controller 的 Bean Validation/真实 HTTP 状态、提交远端失败留痕仍需继续补。

### P1：MVP 使用体验和可靠性

1. **补前端页面测试**
   - `KnowledgeMirrorPage` 目前没有组件测试。应覆盖首次加载、空态、创建、录音授权拒绝、上传、URL 中 attempt 恢复、成功/失败结果和轮询停止。
   - **2026-08-25 部分完成**：已新增 `AgentQuizPanel` 组件测试（成绩单渲染、半对不显示成答对、空态、入口回调、缺字段容错）与 `knowledgeService` 端点测试。录音授权、上传、attempt 恢复与轮询停止仍未覆盖。
   - **2026-08-25 第三轮**：再新增 `KnowledgeActions` 组件测试 5 条（删除必须二次确认、确认框点名标题并提示归档、取消不删、在用/归档分别给归档与恢复入口、编辑回调）与 `knowledgeService` 的 `update/archive/remove/归档列表` 端点测试 4 条。首次加载、录音授权、上传、attempt 恢复与轮询停止依旧没有页面级测试。
   - **2026-08-26 增量**：`KnowledgeMirrorPage` 补首次加载失败/重试、URL 中 attempt 查询失败后手动恢复、麦克风权限拒绝三条页面级测试；上传和 MediaRecorder 完整停止回调仍未覆盖。

2. **区分“加载失败”和“知识为空”**
   - 列表请求使用静默模式，失败后页面会落到空知识库状态，用户无法判断是网络错误还是真的没有数据。
   - 应增加错误态和重试入口。
   - **已完成（2026-08-26）**：首次加载失败显示独立错误态和重试按钮；已有数据刷新失败时保留旧数据并显示警告，不再伪装为空库。

3. **增强任务轮询容错**
   - 当前任意一次查询 attempt 失败都会清空结果并停止轮询；短暂网络抖动会让已提交任务在页面上消失。
   - 应保留任务卡片，采用有限重试/退避，并提供手动重试。
   - **已完成（2026-08-26）**：查询失败不再清空任务，最多自动退避重试 3 次，并保留手动立即重试入口；任务 ID 继续保存在 URL 中。

4. **展示历史与到期复习**
   - 后端已有“最近 30 次尝试”接口，前端 service 也已封装，但页面没有调用或展示历史。
   - 页面已经计算 `due` 数量，却没有展示或提供“只看待复习”入口。
   - **2026-08-25 状态**：测验侧只展示最近一次（`quizzes[0]`），录音历史与“只看待复习”入口仍未做。

5. **增加编辑、删除和音频生命周期**
   - 当前知识只能新增，不能改标题/正文、删除或归档。
   - 删除/归档时还需定义 attempt 记录和 OSS 音频的保留、清理及审计策略。
   - **已完成（2026-08-25 第三轮）**：
     - 三个端点接通并接线到页面：`PUT /materials/{id}`（部分更新）、`POST /materials/{id}/archive?archived=`（归档与恢复共用）、`DELETE /materials/{id}`。
     - 掌握度口径定死在服务端：改标题不动掌握度；改正文以**归一化文本**判断实质变化，只调标点或空白不清分；换正文或换考法才把 `mastery_score/mastery_level/next_review_at` 归零重排，`review_count` 作为历史保留；只给类型不给考法时按新类型自动匹配考法，避免概念题还让用户录音。什么都没改就不碰这一行，`updated_at` 不会被无意义地刷新。
     - 归档是可逆出口：归档项不进默认列表、不进到期复习，`query_knowledge` 默认口径同为 `ArchiveFilter.ACTIVE`，智能体不会再考已归档的知识；页面有"在用/已归档"视图切换与恢复入口，归档因此不会变成单向黑洞。
     - 删除的保留策略：一个事务里按 `knowledge_quiz_attempt → recitation_attempt → recitation_material` 顺序删净，再逐条尽力删 OSS 音频；先删库后删音频，音频删失败只记日志等生命周期规则回收，不会留下指向已消失音频的记录，也不会让用户卡在"删不掉"。前端删除强制二次确认（确认逻辑在 `KnowledgeActions` 内部，不依赖调用方自觉），确认框点名标题并提示改用归档。
     - 已补测试：`RecitationServiceTest` 由 5 条增至 23 条（标点级编辑保分、换正文清分、类型自动匹配考法、空正文与非法考法被拒、归档/恢复的 SET 与 WHERE、四种 `ArchiveFilter` 口径、删除顺序与 OSS 调用、无音频不调 OSS、跨用户 404、缺用户先拒绝再写库），`QueryKnowledgeToolTest` 补"归档知识对智能体不可见"。
     - 仍欠：`RecitationController` 层没有这三个端点的专项测试（参数绑定、`archived` 默认值、`@Size` 校验、真实 HTTP 状态码），归入 P0 第 4 条；删除动作也还没有审计日志。

6. **处理规模上限**
   - 列表固定最多返回 100 条且没有分页、搜索或筛选；Agent 查询也基于这 100 条，知识超过 100 条后会漏查。
   - 应提供服务端分页/搜索/到期过滤，并让 Agent 使用同一查询能力。
   - **已完成（2026-08-25 增量）**：`RecitationService.MaterialQuery` 把 search/masteryLevel/dueOnly 三个条件下推到 SQL（`materialFilter`），`searchMaterials` 与 `countMaterials(userId, query)` 共用同一套条件。`query_knowledge` 改为按条件直查全库，并输出 `matchedTotal` 与 `moreMatches`（"还有 N 条没有返回"），不再把一页明细当成全部证据。`GET /api/recitations/materials` 也接受同样的 `search/masteryLevel/dueOnly/limit`，页面与智能体自此共用一条查询路径。仍未做的是真正的分页（只有 limit，没有 offset/游标）。

7. **增加提交幂等或防重复策略**
   - **已完成（2026-08-26）**：录音请求携带 `clientRequestId`，数据库以 `(user_id, client_request_id)` 唯一约束复用任务；测验评分以唯一 `quiz_session_id` 收口，同一会话重复调用不会重复推进掌握度。

### P2：完整个人知识库能力

1. ~~实现 `CONCEPT -> AI_QA` 的出题、作答、判分和掌握度更新闭环。~~ **已完成（2026-08-25）**：`quiz_knowledge` + `grade_answer`，分数由服务端按逐题 credit 计算，落库路径与录音一致。
2. 实现 `MATH -> PRACTICE` 的题目生成、步骤作答、判分和错题记录。**部分完成（2026-08-25）**：练习题已复用同一套出题/判分/掌握度闭环（`test_mode` 按知识快照记录），但仍缺分步骤判分和错题本——答错的题不会成为可独立复习的对象。
3. 实现图片 OCR、PDF/Word/Markdown 导入、长文拆分和批量建知识；当前界面已明确提示这些是后续能力。（未完成。**2026-08-25 更正**：此前记录的"模型看不到图片内容"是错的——`AgentModelClient.multimodalInput` 已按 Responses 原生多模态发送 `input_image`（`image_url` + `detail:auto`）与 `input_file`（`file_url`），附件本来就参与推理，OCR 导入没有多模态前置依赖，欠的只是导入与拆分本身。同一轮另修正了附件的可见范围：`recentAttachments` 原先沿整条历史回溯，第一轮的图片会在之后每一轮的每一步被重新上传计费；现在与文本上下文共用 `contextMessageLimit` 窗口。）
4. 增加标签、目录、搜索、筛选、排序、归档、批量操作和导入/导出。（部分完成：**搜索与筛选**已在 2026-08-25 增量下推到 SQL 并被页面与智能体共用，**归档**已在第三轮完成；仍欠标签、目录、自定义排序、批量操作和导入/导出）
5. 从固定分数档位升级为基于历史表现、遗忘曲线和连续正确率的复习策略。（未完成；`MasteryPolicy` 仍只看最后一次得分，一次蒙对即可把知识排到 30 天后）
6. 增加到期复习提醒，并与站内通知或邮件计划联动。（未完成；全项目没有定时任务读取 `next_review_at`，调度基建已有 `EmailReminderScheduler`、`BlogContentPlanScheduler` 可复用）
7. 评估语义检索/RAG；当前 Agent 查询只是标题/正文的内存字符串匹配，不是向量检索。（未完成；长期记忆召回 `AgentMemoryService.score` 同为关键词子串打分，同一处欠账）

## 建议实施顺序

1. 先完成 P0：修正默认开关、补部署说明、跑真实迁移和端到端冒烟。
2. 再补 Service/Worker/Controller 与页面测试，锁住现有背诵闭环。
3. 完成错误态、轮询容错、历史记录、到期筛选、编辑/删除和分页搜索，使 MVP 可长期使用。
4. 优先实现概念 AI 问答，再实现数学练习；两者复用统一的 attempt、评分和掌握度模型。
5. 最后增加文档导入、语义检索和提醒能力，形成完整个人知识库。

> **2026-08-25 修订**：第 4 步已完成（概念问答与练习题共用一套评分与掌握度模型）。当前建议的下一步顺序为：
>
> 1. ~~服务端分页/搜索/到期过滤~~ **已完成（2026-08-25 增量）**，见 P1 第 6 条；~~知识编辑/删除~~ **已完成（2026-08-25 第三轮）**，见 P1 第 5 条。这两条剩下的欠账是真正的分页游标（现在只有 limit，没有 offset/游标）和三个新端点的 Controller 级测试。
> 2. 智能体改为原生 function calling 与角色化历史。现在 `AgentConversationService.buildContext` 把记忆、计划、整段历史拼成单个字符串，工具调用靠模型吐 JSON（决策结构由 Responses 严格 `json_schema` 保证，不是自由文本解析，所以这是形态问题而非可靠性问题）。附件已是原生多模态消息，不再是 OCR 导入的前置条件。
> 3. 复习策略升级为 SM-2/FSRS 并接入到期提醒（P2 第 5、6 条），把“该复习了”从用户自觉变成系统驱动。
> 4. 之后再做语义检索（同时替掉长期记忆的关键词召回）、文档/OCR 导入、错题本。

> **2026-08-25 逻辑错误修复**（本轮，均已补测试，`mvn -o test` 592 通过）：
>
> - `AgentConversationService.runLoop` 的未知工具分支不计入失败预算：模型可以一直点名不存在的工具，每步被静默跳过，`maxSteps` 烧完后用户只收到"步数已达上限"。现在计入 `maxToolFailures` 并如实交代是哪个工具不存在。
> - `SubAgentRunner` 同一处缺陷：拒绝越权工具后不计 `MAX_FAILURES`，子任务预算会被白烧，交回主智能体的只是一串"被拒绝"的观察。
> - `AgentMemoryService.recall` 排序时直接 `getUpdatedAt().compareTo(...)`，`updated_at` 为空即 NPE 打断整轮对话；且排完序无条件取前 N 条并全部计成命中——一句无关的话会塞进 8 条零分记忆，还污染 `hit_count` 这个淘汰信号。现在空值按最旧处理，本轮有词元时只召回真正打到分的记忆（PROFILE/PREFERENCE 带 +1 基线分，始终留得住）。
> - `AgentConversationService.resolveStale` 有一处无用的返回值赋值，顺手清掉。

## 本次验证记录

以下验证均在 2026-08-24 的本地工作区执行并通过：

```text
后端相关测试：
RecitationAlignerTest
MasteryPolicyTest
DashScopeRecitationAsrClientTest
CreateKnowledgeToolTest
QueryKnowledgeToolTest

后端全量：mvn -q test
前端专项 lint：KnowledgeMirrorPage.jsx、knowledgeService.js
前端全量测试：11 个 Node 测试 + 240 个 Vitest 测试
前端生产构建：npm run build
知识库 i18n 对齐：6 种语言各 56 个 key，缺失 0
```

构建只有既有的包体积和 Browserslist 数据过期警告，不影响本次通过结论。

### 2026-08-25 增量验证记录

```text
后端新增测试：
KnowledgeQuizServiceTest（12）
QuizKnowledgeToolTest（6）
GradeAnswerToolTest（7）
SubAgentRunnerTest（补 grade_answer 不对子智能体暴露）

后端全量：mvn -o test → Tests run: 576, Failures: 0, Errors: 0, Skipped: 0
前端专项 lint：src/features/knowledge、src/locales（无告警）
前端全量测试：38 个测试文件 / 256 个 Vitest 测试通过
知识库 i18n 对齐：6 种语言各 47 个顶层键、65 个叶子键，缺失 0；`testComingSoon` 已移除且全仓无引用
```

本轮同样只覆盖代码与自动化测试：没有执行真实迁移，也没有在生产数据库、OSS、模型配置或线上 HTTPS 页面上验证测验闭环。

### 2026-08-25 第三轮验证记录（编辑/归档/删除）

```text
后端：
RecitationServiceTest 5 → 23（编辑保分与清分、类型自动匹配考法、归档 SET/WHERE、四种 ArchiveFilter、
                            删除顺序 quiz→attempt→material→OSS、无音频不调 OSS、跨用户 404）
QueryKnowledgeToolTest +1（归档知识对智能体不可见，口径与页面默认一致）
后端全量：mvn -o test → Tests run: 611, Failures: 0, Errors: 0, Skipped: 0

前端：
knowledgeService.test.jsx +4（PUT 部分更新、archive 同端点双向、DELETE 打在单条、归档列表参数）
KnowledgeMirrorPage.test.jsx +5（KnowledgeActions：删除二次确认、确认框点名标题、取消不删、
                                在用给归档/归档给恢复、编辑回调）
前端 lint：eslint . → 0 error，1 个既有告警（EmailRemindersPanel 的 useMemo 依赖，与本轮无关）
前端全量：11 个 Node 测试 + 38 个测试文件 / 265 个 Vitest 测试通过
知识库 i18n 对齐：6 种语言各 65 个顶层键、84 个叶子键，缺失 0；`deleteWarning` 六语言均带 {{title}} 插值
```

测试强度做过反向验证，不只看绿灯：把 `contentReplaced` 改成比较原始文本（而非归一化文本）后，
`editingOnlyPunctuationKeepsTheMasteryRecord` 如预期失败；把删除按钮改成直接调 `onDelete`（跳过确认）后，
三条确认相关的前端测试如预期失败。两处改动均已还原。

本轮依旧只覆盖代码与自动化测试：没有在真实环境验证删除时的 OSS 音频清理，也没有跑迁移或线上冒烟。

## 上线验收清单

- [ ] 新库或存量库按正确顺序应用 SQL，并核对字段、索引和中文注释（含 2026-08-25 新增的 `knowledge_quiz_migration.sql` 与 `20260825_knowledge_lifecycle_migration.sql`）。
- [x] `recitation.enabled` 默认关闭，生产通过环境变量显式开启（2026-08-26 完成）。
- [ ] 管理台已配置可用的 `recitation_asr` 模型端点。
- [ ] OSS 上传目录、访问策略、跨域和音频清理策略已验证。
- [ ] HTTPS 域名下 Chrome、Edge、Safari 至少各完成一次录音闭环。
- [ ] 不同用户互相无法读取 material、attempt 或音频。
- [ ] 上传失败、模型失败、轮询超时、刷新恢复均有可理解的页面状态。
- [ ] Controller、Service、Worker 和前端页面专项测试已补齐。
- [ ] 监控可以定位 attempt ID、任务状态、失败类型和耗时，且日志不含 token、密钥或音频正文。
- [ ] 概念/数学知识在真实环境完成一次测验闭环：智能体出题、用户作答、`grade_answer` 落库、知识页成绩单与掌握度同步刷新（2026-08-25 新增）。
- [ ] 删除一条带录音的知识后，`recitation_attempt`、`knowledge_quiz_attempt` 与 OSS 音频对象都已清理；音频删除失败时只留日志、删除动作本身仍然成功（2026-08-25 第三轮新增）。
- [ ] 归档的知识确实不出现在默认列表、到期复习与智能体检索里，且能从"已归档"视图恢复（2026-08-25 第三轮新增）。
