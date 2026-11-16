# DinQor presentation renderer

Agent 自己策划内容和视觉方向，选择图片、SVG、原生文字和形状。`canvas` 页面在 1280×720 画布上直接描述元素，不必选择固定模板；`palette` 与页面 `background` 可自定义。旧版式仍可读取已有任务。

## 生成链路

1. Agent 按需调用 `prepare_presentation_image` 生成并完整保存素材，再使用实际返回的 `assetId`；用户上传图片使用 `attachmentId`。工具正常返回后 Agent 继续制作，不结束对话。
2. Worker 通过素材或附件所属服务校验用户归属，读取有大小与时间限制的图片，去重后把字节传给渲染器。禁止由模型提供任意下载 URL；渲染器不访问网络。
3. `page_scene.py` 统一确定坐标、换行、图片裁切和矢量视口。PNG 与 PPTX 消费同一份 scene，已移除运行中的两套独立排版代码。
4. 文字与基础图形导出为可编辑对象，位图为可替换图片；SVG 经白名单验证后嵌入原始矢量和 PNG 兼容回退。PNG、JPEG、WebP、GIF 均解码为统一静态图片（GIF 使用首帧）。SVG 不接受脚本、网络、外链、foreignObject 或 CSS。
5. PPTX、来源文件及所有预览上传完毕才发布 completed。前端保持加载占位，浏览器完成图片解码后才显示预览。
6. Agent 用 `get_presentation`（可设置 waitMs 等待）查询，完成后的实际预览会作为图像输入送回模型。Agent 根据实际效果决定是否修改；`quality` 只表示技术检查通过，并明确视觉审查仍由 Agent 完成。

文字不再按固定条数裁剪或偷偷转移到备注。安全限制只约束文档大小、元素结构、资源归属与可渲染性。文字无法放入其指定容器时返回 `PPT_TEXT_FIT` 及页码、元素、所需高度，Agent 可扩大容器、调整构图或改写内容。图片与 SVG 失败返回相应错误，不发布假完成结果。

## 运行与测试

```powershell
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt
$env:PPT_RENDERER_TOKEN = '<本地随机服务令牌>'
.venv/Scripts/python -m uvicorn app:app --host 127.0.0.1 --port 8080 --no-access-log
.venv/Scripts/python -m unittest discover -v
.venv/Scripts/python examples/canvas_showcase.py <本地输出目录>
docker build -t dinqor-ppt-renderer .
```

`examples/canvas_showcase.py` 是人工 QA 输入，独立展示六种构图，不是产品运行时模板。回归测试覆盖图片解码、裁切、缺失资源、SVG 安全、文字完整性、备注、可编辑对象及两种模型接口的“素材→创建→查询→查看预览→修改”流程。

容器安装 Noto Sans CJK；本地测量可通过 `PPT_PREVIEW_FONT` 指向匹配字体。用户 PowerPoint 的字体替代仍可能改变视觉，应安装相同字体。原生文本与 SVG 的具体显示由 Office 版本决定，旧版本可使用图片兼容回退。

## 部署

`POST /v1/render` 使用 Bearer 服务认证，最大请求 30 MiB，包含最多 20 MiB 图片与 512 KiB 左右结构数据。Worker 原始文档限制为 512 KiB / 20 页 / 单页 200 元素（技术资源限制，不是内容规划规则）；单图最多 8 MiB。渲染器单进程同时编译一个任务，繁忙返回 429。使用非 root、只读文件系统、受限 /tmp、CPU/内存/进程限制，禁止渲染器主动访问网络。

先发布兼容新协议的 renderer，再发布 Agent Worker 与 Web。Worker 保留现有 D1、Durable Object、服务绑定、secret 与线上环境变量。沿用 IMAGE_TASKS 和 PRESENTATION_TASKS，无新增数据库迁移。

不要把 health 存活检查当成功能验收：用包含位图、SVG、原生文字的实际多页输入验证预览、PPTX 页数与文件内容，再验证平台普通登录用户的创建、预览、下载和修改。

## Source imagery and direct editing

`search_presentation_images` uses configured Tavily image search; `import_presentation_image` accepts only a candidate from the current turn. Worker downloads enforce public-host/redirect/type/byte limits and `/v1/normalize-image` decodes images in the isolated renderer. Imported assets retain source attribution and are not marked as AI-generated. Search results require Agent identity/licensing review; unsuccessful searches do not justify fabricated logos or product photography. Search is unavailable when the platform search provider is disabled.

Text elements may select Noto Sans CJK SC, Noto Serif CJK SC or Noto Sans Mono CJK SC. Measurement, PNG and native PPTX use the selected family. The web canvas loads matching licensed WOFF2 fonts from the frontend origin; PPTX references names without embedding font binaries.

The frontend displays scene elements directly and supports selection, drag, resize, text/font/color edits, layer ordering, undo/redo and owner-scoped replacement image upload. Save uses the existing expected-version revision API. Individual raster images and SVG illustrations remain image/vector elements; this is not path-level SVG editing or the full PowerPoint feature set. Existing legacy semantic slides retain their image preview and form editor.

The HTTPS reverse proxy must allow authenticated POST to **both** `/v1/render` and `/v1/normalize-image`, with a 30 MiB request limit and a render read timeout of at least 250 seconds. Unmatched paths remain 404. After swapping the renderer container, reload Nginx to refresh its Docker upstream address. The production proxy configuration has a pre-editor backup for rollback.
