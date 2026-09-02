# 知识库文件中心 后端接口文档

模块：`com.xander.lab.knowledgebase`（`api` / `application` / `domain` / `infrastructure`）
基址：`/api/knowledge-base`
状态：后端接口已全部实现并通过单元测试；前端尚未接入。

## 1. 通用约定

### 1.1 响应包装

所有接口都返回项目统一包装体（`com.xander.lab.common.Result`）：

```json
{ "code": 200, "message": "success", "data": { } }
```

失败时 `data` 为 `null`，`code` 与 HTTP 状态码一致，`message` 是可直接展示给用户的中文说明。

| HTTP | 含义 | 典型场景 |
| --- | --- | --- |
| 400 | 参数或业务前置条件不合法 | 根目录直传文件、扩展名不允许、分片大小不符、色值格式错 |
| 401 | 未登录或登录已过期 | 缺失/失效 token |
| 404 | 资源不存在或不属于当前用户 | 越权访问按「不存在」处理，不返回 403，避免泄露他人资源是否存在 |
| 409 | 与当前状态冲突 | 同级同名文件夹、非空文件夹未加 `recursive`、分片未传齐、合并后校验不一致 |
| 500 | 服务端内部失败 | 分片落盘/合并失败 |

### 1.2 鉴权

`AuthInterceptor` 对 `/api/knowledge-base/` 整个前缀要求登录，没有任何公开路由。
所有查询都以 `user_id` 收窄，接口层再次校验资源归属；跨用户访问统一 404。

### 1.3 分页与时间

分页返回统一形状 `KnowledgePageResult`：

```json
{ "records": [], "total": 0, "page": 1, "size": 20 }
```

`page` 从 1 开始，`size` 服务端夹到 `[1, 100]`。时间字段为 `LocalDateTime`（服务端时区），
格式 `yyyy-MM-dd HH:mm:ss`。

### 1.4 状态枚举

| 枚举 | 取值 | 说明 |
| --- | --- | --- |
| `structureStatus`（文件/章节） | `PENDING` | 待生成（章节维度：这一章还没点开过） |
| | `READY` | 已生成 |
| | `CHAPTER_ON_DEMAND` | 篇幅大，整本未自动出图，按章按需生成 |
| | `UNSUPPORTED` | 该格式抽不出正文，只存文件不出图 |
| | `FAILED` | 知识化失败，文件本身仍可用 |
| 标签 `source` | `MANUAL` / `AUTO` | 手动打的 / 章节标题自动生成的 |
| 上传会话 `status` | `UPLOADING` / `COMPLETED` / `ABORTED` / `EXPIRED` | 见第 5 节 |

## 2. 文件夹 `/api/knowledge-base/folders`

文件夹是一棵树，**根目录只放文件夹、不放文件**：上传时 `folderId` 必须是一个真实存在的
非根文件夹，否则 400「根目录不能直接上传文件，请先选择一个文件夹」。
表里用 `parent_id = 0` 表示根目录下的一级文件夹（不用 NULL，否则同级同名唯一索引不生效）。

### 2.1 列出全部文件夹

`GET /folders`

返回**扁平列表**，按物化路径 `path` 升序（等价于先序遍历），由前端按 `parentId` 装树。
不做嵌套 JSON 是为了让前端能局部刷新某一层。

```json
{ "code": 200, "message": "success", "data": [
  { "id": 3, "parentId": 0, "name": "计算机", "path": "/3/", "depth": 1,
    "fileCount": 0, "createdAt": "2026-09-01 10:00:00", "updatedAt": "2026-09-01 10:00:00" },
  { "id": 17, "parentId": 3, "name": "操作系统", "path": "/3/17/", "depth": 2,
    "fileCount": 4, "createdAt": "2026-09-01 10:01:00", "updatedAt": "2026-09-01 10:01:00" }
] }
```

`fileCount` 只算**直属**文件，不含子文件夹里的文件。

### 2.2 新建文件夹

`POST /folders`

```json
{ "parentId": 3, "name": "操作系统" }
```

`parentId` 传 `null` 或 `0` 建在根目录下。名称非空、最长 120 字符、不能含斜杠。

| 失败 | 状态 | message |
| --- | --- | --- |
| 同级重名 | 409 | 同级目录下已有同名文件夹 |
| 超过层级上限 | 400 | 文件夹层级最多 N 层 |
| 超过数量上限 | 400 | 文件夹数量已达上限 N |
| 父文件夹不存在 | 404 | 文件夹不存在 |

返回单个 `KnowledgeFolderView`。

### 2.3 重命名

`PUT /folders/{folderId}/name`，body `{ "name": "新名字" }`，同级重名 409。

### 2.4 移动文件夹

`PUT /folders/{folderId}/parent`

```json
{ "parentId": 9 }
```

`parentId` 为 `null` 或 `0` 表示移到根目录下。移动会重写整棵子树的 `path` 与 `depth`。

| 失败 | 状态 | message |
| --- | --- | --- |
| 移到自己下面 | 400 | 不能把文件夹移动到自己下面 |
| 移到自己的子孙下面（成环） | 400 | 不能把文件夹移动到它自己的子目录下 |
| 移动后子树超深 | 400 | 移动后层级会超过上限 N 层 |
| 目标下已有同名 | 409 | 同级目录下已有同名文件夹 |

### 2.5 删除文件夹

`DELETE /folders/{folderId}?recursive=false`

- `recursive=false`（默认）：只删空文件夹；有子文件夹或文件时 409。
- `recursive=true`：连子文件夹与其中所有文件一并删除（含正文、章节、结构图、标签关联，
  并在事务提交后删对象存储里的原文件）。

`data` 是**一并删掉的文件数**：

```json
{ "code": 200, "message": "success", "data": 7 }
```

## 3. 标签 `/api/knowledge-base/tags`

标签是用户私有词表；文件与标签的关联接口在 `/files/{fileId}/tags`（见 4.11–4.13）。
手动标签与自动标签共用一张关联表，靠 `source` 区分——重新识别只整体替换 `AUTO` 那一批，
用户手打的标签不会被抹掉。

### 3.1 列表

`GET /tags`

按名称排序，`usageCount` 是引用该标签的文件数，此场景 `source` 为 `null`。

```json
{ "code": 200, "message": "success", "data": [
  { "id": 10, "name": "读书笔记", "color": "#2F6FED", "description": null,
    "usageCount": 12, "source": null,
    "createdAt": "2026-09-01 10:00:00", "updatedAt": "2026-09-01 10:00:00" }
] }
```

### 3.2 新建标签

`POST /tags`

```json
{ "name": "读书笔记", "color": "#2F6FED", "description": "可选说明" }
```

- 名称必填、最长 60 字符，内部连续空白会折叠成一个空格后再判重。
- `color` 允许空串或 `#RRGGBB`，其他格式 400「色值格式应为 #RRGGBB」。
- 同名 409「标签已存在」；数量超限 400「标签数量已达上限 N」。

### 3.3 修改标签

`PUT /tags/{tagId}`

```json
{ "name": null, "color": "", "description": null }
```

**局部更新语义：`null` = 不改这个字段，空串 = 清空**（颜色、说明）。
改名撞上别的标签 409「标签已存在：X」，改成自己原来的名字是允许的。

### 3.4 删除标签

`DELETE /tags/{tagId}`

同时解除它在所有文件上的关联（否则关联表会留下指向不存在标签的僵尸行），文件本身不受影响。
`data` 为 `null`。

## 4. 文件 `/api/knowledge-base/files`

### 4.1 表单直传（小文件）

`POST /files/upload`，`multipart/form-data`

| 参数 | 位置 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | part | 是 | 文件本体 |
| `folderId` | query | 是 | 目标文件夹，不能是根目录 |
| `tagNames` | query | 否 | 手动标签，可重复传：`tagNames=A&tagNames=B` |

大文件请走第 5 节的分片接口；两条路最终落到同一段入库逻辑，库里的记录完全一致。

校验：扩展名须在 `knowledge-base.allowed-extensions` 内，大小不超过
`max-file-bytes`（默认 50MB），内容不能为空。

返回 `KnowledgeFileView`（字段见 4.14）。上传成功后同步做知识化处理：抽正文 → 识别章节 →
生成自动标签 → 按第 6 节的规则决定结构图是整本出还是按章按需出。

### 4.2 检索文件

`GET /files`

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `folderId` | — | 限定文件夹；不传或传 0 表示全部 |
| `recursive` | false | true 时连该文件夹的整棵子树一起搜 |
| `keyword` | — | 对 `displayName` 与 `originalName` 做模糊匹配（OR） |
| `tagIds` | — | 可重复传；**交集语义**，要求文件同时具备全部指定标签 |
| `extension` | — | 精确匹配，大小写不敏感 |
| `structureStatus` | — | `PENDING`/`READY`/`CHAPTER_ON_DEMAND`/`UNSUPPORTED`/`FAILED` |
| `page` / `size` | 1 / 20 | `size` 上限 100 |

按 `createdAt` 倒序、`id` 倒序。返回 `KnowledgePageResult<KnowledgeFileView>`，
每条记录带上该文件的标签（含 `source`）。

标签筛选先在关联表上算候选文件 id 再作为 `IN` 条件，不做 JOIN；候选为空直接返回空页。

### 4.3 文件详情

`GET /files/{fileId}` → `KnowledgeFileView`。不含正文（列表接口很频繁，大文本单独取）。

### 4.4 知识结构预览

`GET /files/{fileId}/preview`

一次把「文件信息 + 章节目录树 + 整本结构图 + 正文开头片段」都给前端，省掉打开预览页时的
三四个串行请求。

```json
{ "code": 200, "message": "success", "data": {
  "file": { "id": 500, "displayName": "操作系统导论.md", "structureStatus": "CHAPTER_ON_DEMAND",
            "structureMessage": "共识别 40 个标题、320000 字，篇幅较大未自动生成整本结构图；阅读某一章时会为该章单独生成",
            "chapterCount": 40, "tags": [] },
  "chapters": [
    { "id": 9001, "parentId": 0, "level": 1, "orderIndex": 0, "title": "第一章 导论",
      "startOffset": 0, "endOffset": 5120, "charCount": 5120,
      "structureStatus": "PENDING", "children": [
        { "id": 9002, "parentId": 9001, "level": 2, "orderIndex": 1, "title": "1.1 什么是操作系统",
          "startOffset": 120, "endOffset": 2048, "charCount": 1928,
          "structureStatus": "PENDING", "children": [] }
      ] }
  ],
  "structure": { "fileId": 500, "scopeChapterId": 0, "generated": false, "nodeCount": 0, "root": null },
  "excerpt": "……正文前 2000 字……",
  "excerptTruncated": true
} }
```

大部头文件在这里 `structure.generated=false`：前端应展示 `file.structureMessage`，
并在用户点开某一章时调 4.10 补生成那一章的图。

### 4.5 完整正文

`GET /files/{fileId}/content` → `data` 是字符串。
抽不出正文的格式（`structureStatus=UNSUPPORTED`）返回 409。

### 4.6 章节目录

`GET /files/{fileId}/chapters` → `List<KnowledgeChapterView>`（树，结构同 4.4 的 `chapters`）。

### 4.7 重命名 / 移动 / 删除

- `PUT /files/{fileId}/name`，body `{ "displayName": "新名字" }`。只改展示名，
  原始文件名与对象存储里的键都不动，扩展名也不变。
- `PUT /files/{fileId}/folder`，body `{ "folderId": 17 }`。目标必须是存在的非根文件夹。
- `DELETE /files/{fileId}`。删除文件及其章节、结构图、正文、标签关联，
  并在事务提交后删掉对象存储里的原文件。`data` 为 `null`。

### 4.8 重新识别

`POST /files/{fileId}/reidentify`

用**库里存着的正文**重新识别章节与结构图，不回源下载原文件（章节偏移本来就以抽取出的正文为
坐标系）。会先清掉旧章节、旧结构图、旧正文再重跑，因此不会留下两套章节。
自动标签整体替换，手动标签保留。没有可用正文时 409。

返回重新识别后的 `KnowledgeFileView`。

### 4.9 读取结构图

`GET /files/{fileId}/structure?chapterId=`

`chapterId` 不传读整本书维度（`scopeChapterId=0`），传了读该章按需生成的那一张。
**还没生成时不返回 404**，而是 `generated=false`、`root=null` 的空图，
前端用 `structureMessage` 提示用户。

```json
{ "code": 200, "message": "success", "data": {
  "fileId": 500, "fileName": "记忆的规律.md", "scopeChapterId": 0,
  "structureStatus": "READY", "structureMessage": "已识别 6 个标题，结构图已生成",
  "generated": true, "nodeCount": 18,
  "root": { "id": 7001, "nodeType": "ROOT", "title": "记忆的规律", "summary": "……",
            "chapterId": null, "orderIndex": 0, "children": [
    { "id": 7002, "nodeType": "CHAPTER", "title": "第一章 遗忘曲线", "summary": "……",
      "chapterId": 9001, "orderIndex": 0, "children": [
        { "id": 7003, "nodeType": "POINT", "title": "复习间隔", "summary": "……",
          "chapterId": 9001, "orderIndex": 0, "children": [] }
      ] }
  ] }
} }
```

`nodeType`：`ROOT` / `CHAPTER` / `SECTION` / `POINT`。`chapterId` 非空表示能跳回原文对应章节。

### 4.10 生成结构图

- `POST /files/{fileId}/structure/regenerate` — 整本书维度重新生成，覆盖旧图。
  没有可用正文时 409「该文件没有可用正文，无法生成结构图」。
- `POST /files/{fileId}/chapters/{chapterId}/structure` — **按章按需生成**：大部头文件上传时
  不整本出图，用户点进某一章时调这里补上。已生成过就直接返回现成的，不重复算；
  生成后该章的 `structureStatus` 变 `READY`。章节不存在 404。

两者都返回 `KnowledgeStructureView`。

### 4.11 追加手动标签

`POST /files/{fileId}/tags`

```json
{ "names": ["读书笔记", "认知科学"] }
```

标签名不存在会顺手建出来，前端不必先建标签再打标；空白名与重复项忽略；一次最多 50 个。
已经打过的标签不会产生重复关联。返回该文件当前的全部标签（含 `source`）。

### 4.12 整体替换手动标签

`PUT /files/{fileId}/tags`，body 同上。只替换 `MANUAL` 那一批，自动标签保持不动。

### 4.13 解除单个标签

`DELETE /files/{fileId}/tags/{tagId}` → 返回剩下的标签列表。

### 4.14 `KnowledgeFileView` 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` / `folderId` | Long | |
| `originalName` / `displayName` | String | 原始文件名（不变） / 展示名（可改） |
| `extension` / `contentType` / `sizeBytes` | | 小写扩展名（不含点） |
| `fileHash` | String | 整文件 SHA-256，可用于前端秒传判断 |
| `storageUrl` | String | 对象存储访问地址 |
| `textExtracted` | Boolean | 是否抽出了正文 |
| `charCount` | Integer | 正文字符数 |
| `chapterCount` | Integer | **顶层**章节数，0 表示未分章 |
| `structureStatus` | String | 见 1.4 |
| `structureMessage` | String | 面向用户的说明，可直接展示 |
| `uploadSessionId` | Long | 分片上传产生的文件才有 |
| `tags` | List | 元素含 `source`，`usageCount` 为 null |
| `createdAt` / `updatedAt` | | |

## 5. 分片上传与断点续传 `/api/knowledge-base/uploads`

### 5.1 协议要点

- **分片索引从 0 开始。**
- 除最后一片外，**每片大小必须正好等于会话返回的 `chunkSize`**；服务端逐片核对，
  对不上直接 400 拒收。这样截断的重传会在出问题的那一片被发现，而不是等到合并时
  变成一个说不清来源的哈希不一致。
- `chunkSize` 以**服务端响应**为准：客户端的建议值会被夹到
  `[min-chunk-size, max-chunk-size]`（默认 256KB ~ 16MB）。
- `totalChunks` 由服务端按 `ceil(sizeBytes / chunkSize)` 算，不采信客户端传值；
  超过 10000 片时 400，提示调大 `chunkSize`。
- `fileHash`（整文件 SHA-256，64 位十六进制）必填：它既是断点续传的识别依据，
  也用于合并后校验完整性。
- 会话有效期 `session-ttl-hours`（默认 24 小时）。

### 5.2 建会话（也是断点续传入口）

`POST /uploads/init`

```json
{ "folderId": 17, "originalName": "操作系统导论.pdf", "contentType": "application/pdf",
  "sizeBytes": 73400320, "chunkSize": 4194304,
  "fileHash": "9f2c…（64 位十六进制）" }
```

```json
{ "code": 200, "message": "success", "data": {
  "uploadId": "11111111-2222-3333-4444-555555555555",
  "folderId": 17, "originalName": "操作系统导论.pdf", "sizeBytes": 73400320,
  "chunkSize": 4194304, "totalChunks": 18, "receivedChunks": 0,
  "missingChunks": [0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17],
  "status": "UPLOADING", "fileId": null, "resumed": false,
  "expiresAt": "2026-09-02 10:00:00", "createdAt": "2026-09-01 10:00:00"
} }
```

**幂等键 = SHA-256(userId | folderId | fileHash | sizeBytes)**，表上有唯一索引。
同一用户对同一目标文件夹、同一内容重复 init 会拿回原会话（`resumed=true`）而不是新建——
这就是断线续传的入口。两个请求同时 init 时，唯一索引挡住后一个，后者拿到先建成的那个会话。

会话进入终态（COMPLETED/ABORTED/EXPIRED）时释放幂等键，同一个文件日后还能重新上传。

### 5.3 查会话进度

`GET /uploads/{uploadId}` → 同上结构。断线重连后不想重新 init 的客户端直接用它拿
`missingChunks`。会话过期时这里会把它标成 `EXPIRED`。

### 5.4 上传一片

`POST /uploads/{uploadId}/chunks/{chunkIndex}`，`multipart/form-data`，part 名 `chunk`

返回更新后的会话视图（含最新的 `receivedChunks` 与 `missingChunks`）。
重复上传同一片是允许的，后到的覆盖先到的，计数不会重复增加。

| 失败 | 状态 | message |
| --- | --- | --- |
| 序号越界 | 400 | 分片序号超出范围，应为 0 到 N-1 |
| 空分片 | 400 | 分片内容为空 |
| 大小不符 | 400 | 第 i 片大小应为 X 字节，实际 Y |
| 落盘后复核不符 | 400 | 第 i 片落盘大小不符，请重传该分片 |
| 会话已终态 | 409 | 上传会话状态为 X，不能继续上传 |
| 会话过期 | 409 | 上传会话已过期，请重新初始化 |
| uploadId 不存在/非本人 | 404 | 上传会话不存在 |

### 5.5 完成上传

`POST /uploads/{uploadId}/complete`

```json
{ "tagNames": ["教材"] }
```

body 可省略。服务端按序合并分片 → 核对大小与整文件 SHA-256 → 入库 → 做知识化处理，
返回 `KnowledgeFileView`。

**幂等**：会话已完成时直接返回上次生成的文件，不重复入库。并发调用时用
`UPLOADING → COMPLETED` 的条件更新抢占，抢不到的那个走同一条幂等返回路径。

| 失败 | 状态 | message |
| --- | --- | --- |
| 分片未传齐 | 409 | 还有 N 个分片未上传，缺失序号：… |
| 合并后大小不符 | 409 | 合并后大小与声明不一致（X / Y），会话已作废，请重新上传 |
| 合并后哈希不符 | 409 | 合并后文件校验不一致，会话已作废，请重新上传 |
| 抢占成功但还没写完 fileId | 409 | 上传正在合并中，请稍后重试 |
| 会话生成的文件已被删除 | 409 | 该会话生成的文件已被删除，请重新上传 |
| 合并/读取/入库失败 | 500 / 原状态 | 会话**退回 `UPLOADING`** 并还原幂等键，客户端可直接重试完成 |

哈希不符会作废会话并清掉暂存分片：已经无法判断是哪一片坏了，只能整个会话重来。

### 5.6 放弃上传

`DELETE /uploads/{uploadId}` — 置 `ABORTED`、删分片记录、清本地暂存。
已完成的会话不能放弃（409「该会话已完成上传，无法放弃」）。

### 5.7 客户端标准流程

```text
1. 前端算整文件 SHA-256
2. POST /uploads/init
   ├─ resumed=false → 从 missingChunks 全量传
   └─ resumed=true  → 只补 missingChunks 里的那几片（断点续传）
3. 对每个 index ∈ missingChunks：
   POST /uploads/{uploadId}/chunks/{index}  （按 chunkSize 切片，可并发）
   失败/断线 → 重试该片；重试多次仍失败 → GET /uploads/{uploadId} 重新对账
4. missingChunks 为空后：POST /uploads/{uploadId}/complete
   ├─ 200 → 拿到 KnowledgeFileView，流程结束
   ├─ 409「还有 N 个分片未上传」→ 回到第 3 步
   ├─ 409「正在合并中」→ 稍后重试 complete
   └─ 500 → 会话已退回 UPLOADING，可直接重试 complete
5. 用户取消：DELETE /uploads/{uploadId}
```

断线后**重新 init 即可续传**：同一文件同一目标文件夹会命中幂等键拿回原会话，
`missingChunks` 就是还缺的那几片。

### 5.8 会话回收

`KnowledgeUploadSessionReaper` 定时把过期的 `UPLOADING` 会话标成 `EXPIRED` 并删掉暂存目录。
**默认关闭**（涉及删本地文件），由部署方显式打开 `knowledge-base.reaper-enabled=true`。

## 6. 结构图「自动 vs 按需」规则

这是需求里最关键的一条分流，实现在 `KnowledgeIngestionService`：

```text
detected.size() > auto-structure-chapter-threshold   (默认 12)
  ||  text.length() > auto-structure-max-chars       (默认 200000)
```

| 判定 | 文件 `structureStatus` | 结构图 | 章节 `structureStatus` | 通知 |
| --- | --- | --- | --- | --- |
| 普通文档 | `READY` | 上传时整本生成 | 全部 `READY` | 不发 |
| 大部头（章节多或正文长） | `CHAPTER_ON_DEMAND` | **不生成** | 全部 `PENDING` | **发一条** |
| 格式抽不出正文 | `UNSUPPORTED` | 无 | 无章节 | 不发 |
| 知识化失败 | `FAILED` | 无 | — | 不发 |

大部头的 `structureMessage`：
「共识别 N 个标题、M 字，篇幅较大未自动生成整本结构图；阅读某一章时会为该章单独生成」。

通知走既有的 `automation` 通知表与推送通道（`blog_notification` + 现有的
`/api/notifications` 接口，知识库模块不新增通知接口）：

| 字段 | 值 |
| --- | --- |
| `type` | `KNOWLEDGE_STRUCTURE_PENDING` |
| `title` | 《文件名》未自动生成知识结构图 |
| `content` | 同 `structureMessage` |
| `resourceType` | `KNOWLEDGE_FILE` |
| `resourceId` | 知识库文件 id |

前端收到这条通知即可跳到该文件的预览页；用户点开某一章时调
`POST /files/{fileId}/chapters/{chapterId}/structure` 补生成那一章的图。

通知发送失败只记 WARN 日志，**不影响本次上传**——文件已经存好了，
状态字段里也写清楚了原因。

自动标签取顶层章标题，去掉「第N章」「Chapter 2」「3.2」这类序号前缀后作为主题词，
长度不在 `[2, 60]` 的丢弃，最多 `max-auto-tags` 个（默认 5）。识别不出章节就不生成自动标签
（胡乱切词只会污染词表）。撞上标签数量上限时安静跳过，不让整次上传失败。

## 7. 配置项 `knowledge-base`

见 `application.yml` 第 292 行起，全部可用环境变量覆盖。

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `max-folder-depth` | 8 | 文件夹最大层数 |
| `max-folders` | 2000 | 单用户文件夹数上限 |
| `max-file-bytes` | 52428800 (50MB) | 单文件大小上限 |
| `allowed-extensions` | txt md markdown html htm csv json log pdf doc docx | 允许上传 |
| `text-extensions` | txt md markdown html htm csv json log | 能抽正文、能出结构图 |
| `max-extract-chars` | 2000000 | 正文抽取字符上限，超出截断 |
| `max-chapters` | 2000 | 章节识别条数上限 |
| `auto-structure-chapter-threshold` | 12 | **超过就按章按需生成** |
| `auto-structure-max-chars` | 200000 | **超过就按章按需生成** |
| `max-points-per-node` | 8 | 单节点下最多挂几个知识点 |
| `summary-length` | 200 | 节点摘要截断长度 |
| `max-auto-tags` | 5 | 自动标签数量上限 |
| `max-tags` | 500 | 单用户标签数上限 |
| `chunk-staging-dir` | data/knowledge-base/chunks | 分片暂存目录 |
| `min-chunk-size` / `max-chunk-size` | 256KB / 16MB | 分片大小夹取区间 |
| `session-ttl-hours` | 24 | 上传会话有效期 |
| `reaper-enabled` | false | 过期会话回收，默认关闭 |
| `reaper-batch-size` | 50 | 单轮回收条数 |

| `reaper-interval-ms` | 3600000 | 回收轮询间隔（`@Scheduled` 占位符，不在 Properties 里） |
| `reaper-initial-delay-ms` | 60000 | 回收首次延迟 |

## 8. 数据库

- 新库：`db/knowledge_base_schema.sql`
- 存量环境：手工执行 `db/20260901_knowledge_base_file_center_migration.sql`
  （迁移标记键 `20260901_knowledge_base_file_center`）

九张自有表：`knowledge_folder`、`knowledge_tag`、`knowledge_file`、`knowledge_file_content`、
`knowledge_file_tag`、`knowledge_upload_session`、`knowledge_upload_chunk`、
`knowledge_chapter`、`knowledge_structure_node`。

迁移还给共享表 `blog_notification` 加了一个与业务无关的资源引用二元组
（`resource_type` / `resource_id`），让「大部头未自动生成结构图」这类通知能指回具体文件，
而不必挪用计划专用的 `plan_id` / `run_id` 两列。**依赖顺序**：`blog_notification`
必须已经存在（`blog_content_plan_schema.sql` 或其迁移）。

## 9. 路由清单（30 条）

| 方法 | 路径 |
| --- | --- |
| GET | `/api/knowledge-base/folders` |
| POST | `/api/knowledge-base/folders` |
| PUT | `/api/knowledge-base/folders/{folderId}/name` |
| PUT | `/api/knowledge-base/folders/{folderId}/parent` |
| DELETE | `/api/knowledge-base/folders/{folderId}` |
| GET | `/api/knowledge-base/tags` |
| POST | `/api/knowledge-base/tags` |
| PUT | `/api/knowledge-base/tags/{tagId}` |
| DELETE | `/api/knowledge-base/tags/{tagId}` |
| POST | `/api/knowledge-base/files/upload` |
| GET | `/api/knowledge-base/files` |
| GET | `/api/knowledge-base/files/{fileId}` |
| GET | `/api/knowledge-base/files/{fileId}/preview` |
| GET | `/api/knowledge-base/files/{fileId}/content` |
| GET | `/api/knowledge-base/files/{fileId}/chapters` |
| PUT | `/api/knowledge-base/files/{fileId}/name` |
| PUT | `/api/knowledge-base/files/{fileId}/folder` |
| DELETE | `/api/knowledge-base/files/{fileId}` |
| POST | `/api/knowledge-base/files/{fileId}/reidentify` |
| GET | `/api/knowledge-base/files/{fileId}/structure` |
| POST | `/api/knowledge-base/files/{fileId}/structure/regenerate` |
| POST | `/api/knowledge-base/files/{fileId}/chapters/{chapterId}/structure` |
| POST | `/api/knowledge-base/files/{fileId}/tags` |
| PUT | `/api/knowledge-base/files/{fileId}/tags` |
| DELETE | `/api/knowledge-base/files/{fileId}/tags/{tagId}` |
| POST | `/api/knowledge-base/uploads/init` |
| GET | `/api/knowledge-base/uploads/{uploadId}` |
| POST | `/api/knowledge-base/uploads/{uploadId}/chunks/{chunkIndex}` |
| POST | `/api/knowledge-base/uploads/{uploadId}/complete` |
| DELETE | `/api/knowledge-base/uploads/{uploadId}` |

## 10. 测试

单元测试（JUnit 5 + Mockito + AssertJ，不起 Spring 容器）：

| 测试类 | 用例数 | 覆盖 |
| --- | --- | --- |
| `ChapterDetectorTest` | 13 | Markdown/纯文本/中英文序号标题识别、层级、偏移 |
| `KnowledgeStructureBuilderTest` | 8 | 结构树装配、要点抽取、摘要截断 |
| `KnowledgeFolderServiceTest` | 13 | 层级/数量上限、同级重名、成环移动、递归删除 |
| `KnowledgeTagServiceTest` | 23 | 词表 CRUD、AUTO/MANUAL 分批替换、交集筛选、越权 404 |
| `KnowledgeUploadServiceTest` | 27 | 分片计划、断点续传、并发 init、校验失败作废、幂等完成 |
| `KnowledgeIngestionServiceTest` | 14 | 大部头分流 + 通知、章节落库、自动标签、重新识别、失败记录 |

跑法：

```bash
mvn -o -q test -Dtest='Knowledge*Test,ChapterDetectorTest'
```

## 11. 不在本次范围内

以下内容**没有实现**，因为需求里只给了「知识库文件中心」这一段的场景描述：

- **出题 / 新增题目 / 错题本接口。** 现有的 `recitation` 模块里有基于背诵材料的测验能力
  （`KnowledgeQuizService` + `knowledge_quiz_session` / `knowledge_quiz_attempt` 两张表，
  以及 `quiz_knowledge` / `grade_answer` / `emit_quiz` 三个智能体工具），但它面向的是
  背诵材料而不是知识库文件；**错题本在代码与库表里都不存在**，「新增题目」也没有 HTTP 接口。
- 前端页面（知识页面改成文件中心的界面）。
- 基于知识库文件的向量检索 / 问答。
- `pdf` / `doc` / `docx` 的正文抽取：这些扩展名允许上传，但落 `UNSUPPORTED`，
  只存文件不出结构图（`text-extensions` 决定谁能抽正文）。
