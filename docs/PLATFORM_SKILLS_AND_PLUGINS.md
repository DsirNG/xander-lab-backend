# 平台默认 Skills 与 Plugins

## 结论

平台运行时采用“内置技能是可信 Spring Bean，用户技能是 `agent_skill` 文本”的模型。
因此没有把 `C:\\Users\\xander\\.codex` 缓存目录整包复制进仓库，而是把可迁移的工作流规则改写成平台自己的默认技能。

当前默认技能：

| 平台技能 | 来源启发 | 适用场景 | 主要工具 |
| --- | --- | --- | --- |
| `visual-content` | system `imagegen` 的提示词与交付边界 | 封面、插图、配图、视觉素材 | `image_generate`、`image_resend`、`emit_artifact` |
| `interactive-artifact` | bundled `visualize` 的交互式交付原则 | 图表、数据探索器、交互演示、可下载网页 | `query_knowledge`、`query_posts`、`emit_artifact` |

它们已经注册在 `BuiltinAgentSkills`，会像现有博客、发布、学习、知识整理和个人计划技能一样进入每轮技能目录，模型需要时再通过 `load_skill` 读取正文。

## 项目插件

新增 `plugins/xander-platform`，包含：

- `xander-platform-visuals`：项目开发和 Agent 视觉交付工作流。
- `xander-platform-agent`：内置技能、工具路由、审批、持久化运行和用户技能边界。

后端本地 marketplace 已将该插件设为 `INSTALLED_BY_DEFAULT`。插件只包含 Markdown 规则和 manifest，不包含 Codex 缓存、模型密钥、浏览器运行时或外部账号连接。

## 未直接迁移的系统 Skills / Plugins

| 项目 | 处理 | 原因 |
| --- | --- | --- |
| `openai-docs` | 不入平台默认技能 | 目标是 OpenAI/Codex 官方文档检索，不是 DinQorAI 用户工作流 |
| `plugin-creator`、`skill-creator`、`skill-installer` | 保留为开发者工具 | 依赖 Codex CLI、个人目录和 marketplace，不应暴露给终端用户 Agent |
| `review-agent` | 不入平台运行时 | 是只读代码审查委派协议，当前平台 Agent 没有对应的代码审查工具面 |
| `visualize`、`product-design`、`figma` | 不复制缓存或资源 | 含专有运行时、外部连接或宿主工具；只吸收与平台交付相关的通用原则 |
| `hyperframes` | 暂不接入 | 虽然插件本身是独立项目，但视频渲染、TTS 和 CLI 依赖尚未纳入平台能力边界 |

后续若要接入外部插件，先把它的能力拆成平台自己的应用服务和审批/计费边界，再由 Agent 工具适配；不要让用户上传或安装可执行 skill。
