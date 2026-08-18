---
name: backend-norms
description: DinQorAI Java 后端（xander-lab-backend）编码规范。Use when writing or editing Java code, SQL migrations, Spring Boot configs, or backend tests in this project. Covers module boundaries, Result/HTTP contract, SQL comments, transactions, idempotency, credentials, tests and verification commands.
---

# Java 后端规范（xander-lab-backend）

## 模块边界（最重要，违反会成环）

业务模块按能力组织：`api` / `application` / `domain` / `infrastructure` /（可选）`security`。

- 禁止往全局 `controller` / `service` / `entity` / `mapper` 包塞业务类；已迁移模块：`identity`、`agent`、`blogagent`、`publishing`、`automation`、`integration`、`studio`、`components`、`bloghtml`、`billing`、`admin`。
- 跨模块只能调**应用接口或文档化共享契约**，禁止依赖他人 mapper。
- 环形依赖是硬伤：新增 `A→B` 前先查是否已有 `B→A`（例如 admin→identity 已存在，identity 就不能依赖 admin 的表/mapper，只能由 admin 调用 identity 的应用服务）。
- 每个业务模块根目录要有 `package-info.java` 说明归属与允许依赖；架构性变更必须同步更新 `docs/ARCHITECTURE.md`。
- 每个公开业务类/接口要有一句职责式 JavaDoc；并发控制、token 处理、幂等、重试、兼容性处理前要加注释说明"为什么"。

## API 与异常契约

- 所有错误响应：`{ "code", "message", "data" }`；code 必须与真实 HTTP 语义一致（400/401/403/404/5xx 不被 HTTP 200 包装）。
- 登录缺失/过期/无效：真实 HTTP 401 + `{ "code": 401, "message": "未登录或登录已过期", "data": null }`。
- 服务层抛 `ResponseStatusException`（明确 4xx）或 `IllegalArgumentException`；控制器不写业务逻辑；`GlobalExceptionHandler` 统一转换。
- 禁止用 `catch (Exception)` 返回空对象/通用成功；有意的降级要记录日志 + 归一化原因。
- 定时/后台长任务返回任务/运行 ID，不把远端完成前的时间挂在请求上。
- 新增私有接口：必须在 `AuthInterceptor` 补齐鉴权并同步测试。

## SQL 与迁移

- 每张表、每一列都要有**中文 `COMMENT`**，解释枚举/外键/单位/归属/生命周期，不重复字段名。
- 新建表：写 baseline `db/*_schema.sql`（`CREATE TABLE IF NOT EXISTS`）；
- 存量表任何改动：`db/*_migration.sql`（线上需手工执行），并保持 baseline 同步；
- 禁止用建表语句悄悄更新存量表、禁止启动时代码自动改表。
- 归属过滤、任务扫描、幂等键、外键、稳定分页都要加索引。
- UTC 存调度时刻，展示边界才转用户时区。

## 事务 / 并发 / 幂等 / 调度

- 事务声明在应用服务用例边界；纯 DB 读用 `readOnly = true`。
- AI/CSDN/OSS/SMTP/MCP 等网络调用必须在事务之外；先落意图、再调远端、结果单独事务持久化。
- 可重试变更要有幂等键（DB 唯一约束或原子 Redis）；状态机用条件更新（含期望前态），0 行更新 = 竞争或已完成。
- 状态/阶段定义成常量或枚举并文档化转移，禁止散落字符串字面量。
- 调度器：轮询原子认领、有界批量、多实例不重复；单条失败不中断整批。

## 凭据与安全

- 平台凭据按用户×平台存储；AES-GCM 静态加密，密钥只走环境变量（先例：`MODEL_PROVIDER_ENCRYPTION_KEY`、`WECHAT_CREDENTIAL_ENCRYPTION_KEY`）。
- 实体只存密文；任何视图/接口不回传密钥明文，只回 `configured` 布尔标记。
- 绝不记录密码、验证码、JWT、cookie、API key、鉴权头、第三方请求头；日志用结构化参数化（不用 `System.out`/`printStackTrace`）。
- 登录、注册、验证码、发布、昂贵 AI 生成等必须限流。

## 配置

- 新配置组用类型化 `@ConfigurationProperties` 并校验；带凭据/调度器/外部副作用的功能默认关闭直到配置与迁移就绪。
- 生产值只能来自环境变量或本地配置（`application-local.yml` 自动加载、不得提交）。
- 只在 JDK/现有栈解决不了时加依赖。

## 测试

- 模块在 `src/test/java` 有镜像包；应用服务/安全规则/调度器/适配器必须有行为测试。
- 控制器测试覆盖校验、鉴权、归属、真实 HTTP 状态。
- 单元测试不依赖网络/生产凭据/真邮箱/真发布账号；确定性、相互独立、清理 ThreadLocal/静态状态。
- 修 bug 必须带回归测试；禁止为过构建删弱测试。

## 验证门禁（改动后必须执行并贴结果，不要直接宣布完成）

```bash
# 窄测试（相关模块）
mvn -q "-Dtest=写入你的测试类" test
# 全量
mvn -q test
# 至少确认编译
mvn -q -DskipTests compile
```

本地环境：JDK 17 = `C:\jdk-17.0.2`，Maven 3.9.9，Redis localhost:6379，MySQL 101.33.246.103:3306（xander_lab）。提交标题用中文并以「【dxd】」为前缀，正文含 `desc:`；只 stage 任务相关文件，不提交凭据与本地配置。