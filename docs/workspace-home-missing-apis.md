# 工作台首页缺失接口清单

> 状态：工作台首页（`WorkspaceHomePage`）已完成已有接口的接入，以下为**后端尚未提供**的接口与功能。
> 关联页面：`xander-lab-frontend/src/features/workspace/pages/WorkspaceHomePage.jsx`

## 已接入的接口（对照参考）

| 页面区块 | 接口 |
|---|---|
| 通知铃铛 | `GET /api/notifications/unread-count` + SSE `/api/notifications/events` |
| 最近对话 | `GET /api/agent/conversations` |
| 最近知识库 | `GET /api/recitations/materials?limit=3` |
| 最近计划 | `GET /api/blog-plans?page=1&size=3` |
| 已发布文章数 | `GET /api/blog/posts/mine?status=1&size=1`（取 total） |

## 缺失接口

### 1. 今日概览统计（4 项中 3 项无接口，当前显示 `—`）

| 统计项 | 缺失接口 | 现状 |
|---|---|---|
| 知识点总数 | `GET /api/recitations/stats` 或 `count` 字段 | `/api/recitations/materials` 上限 100 条且无 total，无法精确计数 |
| 待复习数 | `GET /api/recitations/stats`（dueOnly 计数） | 同上，`dueOnly=true` 仍受 100 条上限 |
| 今日学习时长 | 需新建时长记录表 + 统计接口 | **完全无数据源**，无学习会话时长记录 |
| 已发布文章数 | 已接入 `GET /api/blog/posts/mine` | ✅ 已完成 |
| 趋势（较昨日 +32/-5） | 需每日快照表或聚合查询 | 无历史数据，全部显示 `—` |

建议：仿照 `ProfileOverviewService` 聚合模式，新增 `GET /api/workspace/stats` 一次性返回四项统计与环比。

### 2. 待办与提醒（右侧栏，当前为 mock 数据）

- 后端**无 Todo 实体 / 表 / 接口**，需新建：
  - 表 `todo`：标题、截止时间、完成状态、关联计划 ID（`blog_content_plan.id`）
  - `GET /api/todos`（今日待办列表）
  - `PATCH /api/todos/{id}/status`（勾选完成）
  - `POST /api/todos`（新建）
- 提醒：可复用 `/api/notifications`，但"待办到期提醒"无独立机制。

### 3. AI 建议（右侧栏，当前为 mock 数据）

- 无任何推荐接口。需新建 `GET /api/workspace/suggestions`，覆盖三类：
  - 复习间隔提醒（依据 `recitation_material.next_review_at`）
  - 学习路径推荐
  - 博客灵感
- 或复用 agent 工具侧内部建议逻辑（`QueryKnowledgeTool` 等）暴露为 REST。

### 4. 全局搜索（顶栏搜索框，纯 UI）

- 无统一搜索接口，需跨知识 / 对话 / 文章聚合：
  - `GET /api/search?q=...&scope=all|knowledge|conversations|posts`

## 建议实施顺序

1. `GET /api/workspace/stats`（统计 + 趋势，工作量小、收益明显）
2. `todo` 表 + CRUD（待办与提醒）
3. `GET /api/workspace/suggestions`（AI 建议）
4. `GET /api/search`（全局搜索）
5. 学习时长记录（需先定时长口径：仅录音测试 / 含对话与写作）

## 注意

- 所有新表、新列需中文 `COMMENT`；新基线进 `db/*_schema.sql`，存量改动提供 `db/*_migration.sql`。
- 新私有接口需同步注册到 `AuthInterceptor` 并补充鉴权测试。
- 前端新接口统一走 `src/api/http.js` 封装；新增 i18n key 需同步 6 种语言。
