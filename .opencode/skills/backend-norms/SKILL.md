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

**物理布局（V9 §18 起）**：应用源码在 `apps/core-host/src/`，抽出的共享库在 `libs/<name>/src/`，
根 `pom.xml` 是聚合器 + 父 POM（`packaging=pom`，`artifactId=xander-lab-parent`）。
`spring-boot-maven-plugin` 只声明在 `apps/core-host/pom.xml`，**不要**挪到父 POM
（子模块会继承 repackage 目标，而它需要 main class，库模块会直接构建失败）。

跨这两个根的路径引用要一起更新：`scripts/migration/` 下的门禁允许清单、`docs/*.yaml` 里
curated 的 `file:`/`source:`/`scan_ref`（`scan_ref` 由扫描器自动刷新，其余要手改）。
`scripts/migration/scan.py` 用 `java_roots()` 同时扫应用根与所有 `libs/*/src/main/java`，
**不要**把它写死回单根 —— 否则一旦有类被移进 libs，它会从所有机器清单里静默消失
（包括遗留 Redis 键清单，那正是"常量被搬走就没人管了"的成因）。

⚠️ **把类移进 libs 时，新库的包名必须加进 `scan.py` 的 `SHARED_KERNEL`。**
扫描器按包名判定归属：包名不在 `SHARED_KERNEL` 也不是业务域时，指向它的 import 会被整条
丢掉，跨域基线于是**下降** —— 数字变好而耦合没变，是最坏的一种"通过"。
门禁 `shared-kernel-classification` 会比对 `libs/` 实际提供的包与 `SHARED_KERNEL`，漏加即 FAIL。

⚠️ **包名要跟着归属走**：`common` / `config` 这类通用名可以跟着类一起搬（保 rename 检测），
但**业务域名不能**。`UserContext` 从 `identity.security` 搬到 `xander-auth-core` 时改成了
`auth.core` —— 共享库的类挂在业务域包下，一是架构上撒谎，二是扫描器仍按业务域算跨域依赖，
抽取的价值在清单上完全看不到。

⚠️ **库自己拥有自己的测试**：类搬进 libs，它的测试也跟着进 `libs/<name>/src/test/java`，
并在 lib 的 POM 里加 test-scope 依赖。测试留在应用模块等于应用拥有它不再拥有的类。
核对测试总数时要**各模块相加** —— `mvn -o test` 的 `Results:` 是逐模块打印的，
只看最后一个会误判成"测试变少了"。

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
- **无法验证的配置改动不要做**。改配置前先确认有没有测试真的会加载它 —— 例如
  `mybatis-plus.mapper-locations` 在本仓库**没有任何测试会解析**（五个 `@SpringBootTest`
  都是切片上下文，显式 `classes = {...}` 并 `@MockBean` 掉 mapper），所以改它属于
  「改完只会被生产环境发现」。这类情况一律**用门禁把现状钉住**，等有验证手段的那一波再改
  （参考 `ci_gates.py` 里 `KNOWN_MYBATIS_GLOBAL_SCAN` / `KNOWN_SHARED_KERNEL_VIOLATIONS`
  的「只禁新增、修好即删条目」手法）。
- MyBatis 配置（V9 §80）：mapper XML 只能放在 `apps/<module>/src/main/resources/mapper/`，
  **不要**放进 `libs/` —— 共享 jar 在每个服务的 classpath 上，等于每个服务都加载了别的域的
  SQL。新增 mapper XML 后必须确认它被本模块自己的 `mapper-locations` 匹配到，
  否则是死文件，运行时报 `Invalid bound statement`，看起来像代码 bug 而不是配置 bug。

## POM / XML

- **XML 注释里不能出现 `--`**。Maven 会在编译任何类之前整体失败：
  `Non-parseable POM pom.xml: in comment after two dashes (--) next character must be > not ...`。
  写注释时把破折号对改成逗号/冒号/「到」。门禁 `xml-comment-double-dash` 会拦住它。
- 带 `<modules>` 的 POM **必须** `packaging=pom`（`Aggregator projects require 'pom'`），
  所以根 POM 不能兼作应用模块。
- `spring-boot-maven-plugin` **只能**声明在应用模块（`apps/core-host/pom.xml`）。
  放父 POM 会让库模块继承 `repackage`，库没有 main class，报 `Unable to find main class`。
- 库与应用共用的依赖版本走父 POM 的 `<properties>`（例如 `${mybatis.plus.version}`），
  **不要**在子 POM 内联写死：同一依赖两个版本会在 classpath 上出现两份类，报错现场离原因很远。

## 测试

- 模块在 `apps/core-host/src/test/java` 有镜像包；应用服务/安全规则/调度器/适配器必须有行为测试。
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

⚠️ **在 Git Bash 里 `mvn` 直接跑不起来**（MSYS 路径没被转成 Windows 路径，
报 `ClassNotFoundException: plexus.classworlds.launcher.Launcher`）。用完整 java 命令行：

```bash
"C:/jdk-17.0.2/bin/java.exe" \
  -classpath "D:/soft/apache-maven-3.9.6/boot/plexus-classworlds-2.7.0.jar" \
  "-Dclassworlds.conf=D:/soft/apache-maven-3.9.6/bin/m2.conf" \
  "-Dmaven.home=D:/soft/apache-maven-3.9.6" \
  "-Dmaven.multiModuleProjectDirectory=D:/blog/xander-lab-backend" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o test
```

迁移相关的机器门禁（清单一致性 / 依赖方向 / 配置归属等）另有一套，改动后也要跑：

```bash
python scripts/migration/ci_gates.py
```

本地环境：JDK 17 = `C:\jdk-17.0.2`，Redis localhost:6379，MySQL 101.33.246.103:3306（xander_lab）。提交标题用中文并以「【dxd】」为前缀，正文含 `desc:`；只 stage 任务相关文件，不提交凭据与本地配置。