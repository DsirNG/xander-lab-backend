# Agent PPT 制作

## 目标与实现

在现有 Agent 工具执行边界内提供 `create_presentation`、`get_presentation`、`revise_presentation_slide`。现有模型根据用户材料或联网工具的证据组织大纲、统一主题、每页标题/要点/来源/演讲备注；提交结构化内容后立即返回持久化任务标识。Web 卡片打开 `?presentation=<id>`，刷新可恢复。

模型创建任务前进行受限的布局适配：封面或双栏内容块过多时改为列表布局，超过页面容量的内容以及缩略前的完整文字移入演讲备注；中文宽度和显式换行按保守预算处理，保持页数、主题、来源和调用幂等键不变。若页面和备注仍无法完整容纳全部内容，则明确拒绝，不静默丢失原文。直接编辑接口和最终渲染质量门禁仍执行严格校验。

独立 Python 服务依赖锁定版本 ppt-master `b243a950e9f85998705551fb1b77c6459573ff39`。受控布局生成 SVG → 上游 `svg_quality_checker.py --quick-generate --stage final --json` → `svg_to_pptx.py --quick-generate --with-notes` → 原生 PPTX。PNG 由同一份 SVG 生成，源文件记录 SVG、页面结构、来源和质量回执。检查失败不能交付完成状态。

每个任务一个 Durable Object，所有者与对话标识不可改变。任务元数据、页面结构与版本保存在其 SQLite 持久化 storage 中，文件只进 OSS。状态为 queued / rendering / uploading / completed / failed / cancelled。状态写入、版本切换、幂等回执及 alarm 在存储事务中完成；网络渲染/上传在事务外。六分钟 watchdog 标记不确定中断，最多五次手动重试、二十个版本。单页修改检查 expectedVersion，复制其余页结构并生成新版本，保留历史附件。

公开接口均通过真实用户鉴权：

- `GET /api/agent/presentations/:id?version=N`
- `POST /api/agent/presentations/:id/revise`，携带 expectedVersion / requestId / slideIndex / slide
- `POST /api/agent/presentations/:id/retry`
- `POST /api/agent/presentations/:id/cancel`

模型只能用当前用户身份执行，不读取他人的任务或文件。创建通过用户、对话及 invocation 幂等键派生任务 ID。单页修改和其他写操作不加入子任务只读工具集合。工具调用继续记录 invocationId 事件及现有模型计费，不引入额外模型调用或另一个模型密钥。

配置、容器构建和发布顺序见 `ppt-renderer/README.md`。本地测试不能代表已上线，部署及实际 PowerPoint 客户端效果需分别验收。

## 2026-11-14 生产发布记录

渲染器已部署到现有阿里云 `dxd` 服务器，独立容器 `dinqor-ppt-renderer`，镜像 `dinqor-ppt-renderer:20261114`。HTTPS 地址为 `https://api-ppt.dsircity.top`，仅 `/v1/render` 提供服务认证后的 POST；根路径返回 404。原临时 `dsircity.top/v1/render` 路由已移除。容器不映射宿主端口，通过内部 Docker 网络接受现有 Nginx 代理，限制 1 CPU / 1 GiB、只读根目录、256 MiB 临时目录，关闭多余权限并自动重启。

服务器部署文件位于 `/opt/dinqor-ppt-renderer`，版本源码在 `releases/20261114`，服务令牌在权限为 600 的 `renderer.env`。Nginx 配置仍由现有 `/opt/aural/app/deploy/selfhost/nginx.conf` 管理；改动前备份保存在渲染器部署目录。Let's Encrypt 证书由 `certbot-renew.timer` 自动续期，并复用现有 Nginx reload hook。

Agent 配置使用 `PPT_RENDERER_URL=https://api-ppt.dsircity.top`，`PPT_RENDERER_TOKEN` 存在 Cloudflare Worker secret，不能写入前端。已发布 `presentation-task-v1` SQLite Durable Object 迁移，保留全部现有模型、搜索、OSS 和服务绑定。Agent 版本为 `055d2275-30e8-4d48-a4dd-4d5e68a11eea`，Gateway 版本为 `707566b7-a47e-4a0d-b12b-6dfd76a9071f`；Web 正式域名为 `https://xiaod.dpdns.org`，构建版本 `1794641147554`。

上线验证覆盖：独立域名 HTTPS、无令牌渲染返回 401、真实中文内容生成两页 PNG 和原生可编辑 PPTX、DinQorAI 文件元数据、Gateway 健康与就绪、PPT 私有路由未登录返回真实 401、线上 Worker 版本与原配置保留、Web 资源版本及聊天 bundle 一致。登录后从模型发起完整任务、实际 OSS 下载及 PowerPoint 客户端编辑效果仍需业务验收；这些不由渲染器直连测试代替。

同日生产修复：创建任务前增加布局适配，覆盖模型误选封面/双栏且内容块过多、长中文文字和显式换行；真实 HTTPS 六页渲染验证通过，全部 Workers 599 项测试及类型检查通过。Agent 更新为 `77607c30-2bce-4033-972b-9c90fbd4498c`，Gateway 更新为 `58aed9b4-7571-4fdd-99a2-6264eea14d89`，原服务配置及绑定保持不变。此修复不会自动重新执行此前未创建成功的请求，需在对话中重新提交制作需求。

## 2026-11-14 交互与语义版式升级发布

Renderer 更新为 `dinqor-ppt-renderer:20261114-upgrade`，新增语义版式与可编辑封面视觉；Docker 镜像包含 `slide_design.py`。线上六页中文样例经 `https://api-ppt.dsircity.top/v1/render` 验证返回 200、6 页 PNG、6 页 PPTX 页面并通过质量回执。

Agent Worker 版本为 `594f68a1-fd33-4325-9807-afc184bdc9c2`，已配置 `PPT_RENDERER_URL=https://api-ppt.dsircity.top`；Gateway Worker 版本为 `0bcb3415-fe2c-477e-bc1e-3e229138bade`；Web 正式版本为 `1794645319801`。正式域名 `https://xiaod.dpdns.org` 返回该版本，PPT 私有接口未登录返回真实 401。

## 2026-11-14 Agent 闭环修复

PPT 工具的页面语义决策移交 Agent：工具不再校验页面模块数量、要点数量、来源数量、对比页结构或图表数据组数，只保留类型、安全、链接协议、数值合法性和请求体等底层约束。创建与改单工具发生可恢复错误时，以结构化 `tool_result` 返回 Agent，主循环可根据错误调整参数后再次调用；后台渲染失败会持久化具体原因，`get_presentation` 会提示 Agent 读取任务并调用 `revise_presentation_slide` 重试。

本地 Agent 全量检查通过（92 项服务测试、239 项总测试及类型检查）。最新 Agent 版本为 `a14a2e95-f264-4d03-80ea-ca45858fa3b1`，Gateway 版本为 `ed87c78b-dc5b-4b30-bb78-cc2b85c7d1df`。
