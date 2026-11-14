# Agent PPT renderer

独立 Python 服务将 Agent 生成的页面结构转换为 SVG 预览，并由 DinQor 自有的 `native_pptx.py` 生成原生可编辑 PPTX。页面内容、版式和图表由 Agent 决策，渲染器只负责安全边界、排版和文件生成。

## 本地运行

```powershell
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt
$env:PPT_RENDERER_TOKEN = '<本地随机服务令牌>'
.venv/Scripts/python -m uvicorn app:app --host 127.0.0.1 --port 8080 --no-access-log
```

容器已安装 Noto Sans CJK 字体。本地预览也需要该字体；可用 `PPT_PREVIEW_FONT` 指向已安装的 Noto Sans CJK SC OTF 文件。缺少字体会导致预览替代字体，与 PowerPoint 的字体替代效果可能不同。PPTX 文字仍为原生可编辑对象，接收方应安装对应字体。

```powershell
.venv/Scripts/python -m unittest -v test_render
docker build -t dinqor-ppt-renderer .
```

## 部署

1. 把镜像部署到可以运行 Python 进程的容器平台，至少 1 CPU / 1 GiB 内存，允许 `/tmp` 写入。镜像使用非 root 用户。只暴露 HTTPS；限制入站到 Agent 服务路径，设置 `PPT_RENDERER_TOKEN` 为随机长令牌。
2. Agent Worker 配置 `PPT_RENDERER_URL` 与 `PPT_RENDERER_TOKEN`（后者使用 Wrangler secret）。服务令牌不能放入 Web、模型上下文或数据库明文配置。地址/token 是基础设施配置，复用现有 Agent 模型，无需另设 PPT 模型。
3. 发布 Agent Worker 时保留已有 `image-task-v1` 迁移并追加 `presentation-task-v1`，绑定 `PRESENTATION_TASKS`。这是 Durable Object SQLite 迁移，**没有新增 D1 SQL 迁移**。
4. 发布 Gateway（包含新路由与鉴权规则），随后发布 Web。OSS 沿用 Agent 附件配置。缺少渲染配置时模型工具不会暴露，接口返回 503。
5. 管理员以普通用户完整验证创建、等待完成、刷新、逐页预览、下载、单页修改、取消和重试。确认 PPTX 可以在 PowerPoint 中编辑文字。不要把 `/health` 的进程存活响应当作完整渲染验证。

`POST /v1/render` 只接受 Bearer 服务认证后的结构化 JSON，最多 128 KiB / 20 页。返回有界 base64 PPTX、PNG 预览和包含 SVG 的来源文件。拒绝任意 SVG、脚本、远程图片或可执行代码；用户文本经 XML 转义。来源 URL 仅作为备注文字，不在渲染器访问。

单进程同一时间只编译一个任务，繁忙时返回 429，由用户显式重试。每条上游子进程最多 90 秒。建议容器运行时设置 CPU/内存限制、只读根文件系统、可写受限 `/tmp`，并禁止出站网络（构建阶段仍需下载锁定上游）。按实例扩容。

当前支持 cover / bullets / columns / cards / process / comparison / chart / closing 与 navy / paper 两种统一主题。文字、形状和基础图表导出为原生可编辑对象；PNG 预览来自同一套 DinQor SVG 设计。上传模板、动画和旁白暂不支持。输入过长时会返回具体排版错误，由 Agent 修改后重试。

任务完成后下载附件 ID 持久化；每次查询重新签发 URL。关闭窗口不停止任务，取消操作通过版本和 attempt 栅栏防止旧任务覆盖。执行中断由持久化 watchdog 标记失败，不会静默重复执行。取消或失败发生在上传中时，已上传但未发布到结果的附件可能留在 OSS，后续需纳入统一附件生命周期清理。
