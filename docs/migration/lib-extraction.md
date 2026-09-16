# 共享库抽取（V9 §18 / §19 / §20 / §21 / §91）

本文记录 Wave 0B「抽纯净技术库」的**实际进度与判断依据**。

对应 V9 章节：§18（Monorepo 目标布局）、§19（禁止超级 common.jar）、
§20（各 lib 边界）、§21（ArchUnit 门禁）、§80（MyBatis 配置分片）、§91（Wave 0B）。

⚠️ 本文只描述**已经落地的事实**与**必须留痕的判断**。计划里的目标清单在 §2 表里，
不要把它当成"已完成"读。

---

## 1. 一句话结论

`common` 原来是一个 12 个类的"什么都有"包，其中 5 个是纯的、4 个是 Web MVC、
1 个是 MyBatis、2 个是运维型 Redis。**WebFlux Gateway 一旦依赖它就会启动失败**，
所以 §19 必须拆。

Wave 0B 已抽出 **3 个** lib（`xander-model`、`xander-mybatis-support`、`xander-auth-core`），
`common` 从 12 类降到 6 类，另有 2 个类从**业务域包** `identity.security` 迁出。
剩下 3 个 lib 未开始。

⚠️ **Wave 0 不全仓搬目录**（§18 原话）。目录形态之所以变了，是 Maven 的硬约束，
不是审美 —— 见 §5。

---

## 2. 目标清单与进度

| lib（§20） | 状态 | 内容 |
|---|---|---|
| `xander-model` | **已完成** | `Constants` `Result` `ModelCallResult` `ModelUsage` |
| `xander-mybatis-support` | **已完成** | `MybatisPlusConfig` `PageData` |
| `xander-auth-core` | **已完成** | `UserContext`（可信身份模型）`JwtUtil`（Token 校验器） |
| `xander-webmvc-support` | 未开始 | 目标 `GlobalExceptionHandler` `ResultStatusResponseBodyAdvice` MVC 认证适配器 `WebMvcConfigurer` |
| `xander-observability-core` | 未开始 | 目标 TraceId/requestId + MDC 约定 + 指标命名约定（不含具体 exporter） |
| `xander-test-support` | 未开始 | — |

`common` / `config` / `filter` 三个包**当前剩余 11 个类**，全部还在应用模块里：

```text
common/HealthController.java              ← 运维端点，见 §4
common/RedisHealthMonitor.java            ← 运维型 Redis，见 §4
common/RedisHealthProbe.java              ← 运维型 Redis，见 §4
common/security/ConnectionRegistry.java   ← 连接注册表（§49）
common/security/SecurityPlaneConfiguration.java
common/security/SecurityStateCache.java
config/GlobalExceptionHandler.java        ← 目标是 xander-webmvc-support
config/ResultStatusResponseBodyAdvice.java← 目标是 xander-webmvc-support
config/ShedLockConfiguration.java
config/WebConfig.java                     ← 目标是 xander-webmvc-support
filter/RequestLoggingFilter.java          ← 目标是 xander-observability-core
```

---

## 3. 两个已抽出的 lib

### 3.1 `xander-model`

只依赖 `jackson-databind` + `lombok`。**无 Spring MVC / WebFlux / MyBatis / Redis / 业务域**
（§20 原文）。Gateway 可以依赖它。

包名保持 `com.xander.lab.common` 不变 —— 移动类但改包名会让 800+ 个文件的 diff 里
混进 import 变更，rename 检测就废了，评审也就看不出"到底改了什么"。

### 3.2 `xander-mybatis-support`

依赖 `mybatis-plus-extension` + `spring-context` + `lombok`。

版本号走父 POM 的 `${mybatis.plus.version}`（当前 `3.5.5`），**不要在子 POM 里内联写死**：
库和应用落在两个不同 MyBatis-Plus 版本上时，classpath 上会出现两份 MyBatis-Plus 类，
报错现场离原因很远。

### 3.3 `xander-auth-core`

依赖 `spring-context`（`@Component` / `@Value`）+ `jakarta.annotation-api`（`@PostConstruct`）
+ jjwt（`jjwt-api` 编译期、`jjwt-impl` / `jjwt-jackson` 运行期）+ `spring-boot-starter-test`（test）。
版本走父 POM 的 `${jjwt.version}`（当前 `0.12.6`），理由同 §3.2。

**这个库是 Wave 1 的 Gateway 与业务服务共享的认证面**，所以 §20 的禁令（`HandlerInterceptor` /
`WebMvcConfigurer` / Gateway Filter / `RedisHealthMonitor`）必须真的守住 —— 一旦混进 MVC
实现，Gateway 又会变成"依赖了它就没法启动"。

⚠️ **库自己拥有自己的测试**：`UserContextTest` / `JwtUtilTest` 跟着类一起进了
`libs/xander-auth-core/src/test/java/`，而不是留在应用模块的测试树里。
理由：测试留在应用模块等于"应用拥有它已经不再拥有的类的测试"，那不叫抽出来了。

---

## 4. 必须留痕的判断

### 4.1 `PageData` 放 `xander-mybatis-support`，不放 `xander-model`

**§19 与 §20 在这里互相矛盾。** §19 把 `PageData` 列进"纯共享"的 5 个类之一；
§20 把它归给 `xander-model`，同时又要求 `xander-model` "无 MyBatis"。

两者不可能同时成立：`PageData` 的全部内容就是对
`com.baomidou.mybatisplus.core.metadata.IPage` 的适配器
（构造器 `PageData(IPage<T>)` 与 `PageData.of(IPage<T>)`）。

**裁决：让"无 MyBatis"这条赢。** `PageData` 进 `xander-mybatis-support`，跟着它真正依赖的技术走。

### 4.2 §20 提到的"公共 TypeHandler"在本仓库不存在

`grep -rn "TypeHandler"` 扫全仓返回**零**结果。所以这一项**没有东西可搬**，
不是"搬了但没记录"。将来若新增，归属 `xander-mybatis-support`。

### 4.3 `HealthController` / `RedisHealthMonitor` / `RedisHealthProbe` 不进任何 lib

§19 明确："运维型 Redis 健康检查不进通用 libs"。这三个类**不能**为了"让 common 清空"
而塞进 `xander-model` 或 `xander-mybatis-support` —— 那正是 §19 要消灭的超级 jar 的做法。
它们属于 application/operations，Wave 1 跟随宿主。

### 4.4 包名要跟着归属走：`identity.security` → `auth.core`

前两个库（`xander-model`、`xander-mybatis-support`）迁移时**保留了原包名**
（`com.xander.lab.common` / `com.xander.lab.config`），为的是不把 800+ 文件的 diff 里
混进 import 变更、让 rename 检测还能看出"到底改了什么"。

**`xander-auth-core` 反过来必须改包名**，因为原来的包名是错的：
`com.xander.lab.identity.security` 里的 `identity` 是**业务域**，而 `UserContext` 被
16 个域引用。把它原样搬进 `libs/` 会同时造成两个后果：

1. **架构上是假的**：一个共享库的类挂在业务域的包下，等 `identity` 真的变成独立服务时，
   这个包名就在撒谎。
2. **机器计数上是隐形的**：跨域扫描器**按包名**判定归属（`domain_of` 取 FQCN 第 4 段）。
   保留 `identity` 的话，那 35 条 import 仍然会被算成"跨域 implementation import"，
   于是这次抽取的价值在清单上完全看不到 —— 而清单是这个项目唯一的进度依据。

所以规则是：**包名真实就保留，包名指向业务域就必须改。**
`common` / `config` 是通用名，保留；`identity.security` 是业务域名，改成 `auth.core`
（与 artifact 名 `xander-auth-core` 对齐）。

改动量：48 个文件的 import + 4 个文件的 `package` 声明。全部是字节级替换，无逻辑改动。

### 4.5 ⚠️ `UserContext` 的 `ThreadLocal` 不是 Reactive 中立的

`UserContext` 零框架依赖，所以从**依赖**角度它是 Servlet/Reactive 中立的，符合 §20。
但它是用 `ThreadLocal` 实现的，而 `ThreadLocal` 只在"一线程一请求"的 Servlet 栈上成立 ——
WebFlux 下请求会在线程间迁移，本类会静默读到 `null` 或**别的请求的身份**。

§20 把 "trusted identity model" 明确列为本库允许的内容，而这就是现存的 identity model，
所以本轮按 §20 抽出来，**并把这个约束写在类 javadoc、package-info 和库 POM 的 description 里**，
而不是靠"把它留在业务域包下"来回避。Wave 1 的 Gateway 是 WebFlux，必须用 Reactor context
传递身份，不能用本类。

---

## 5. 构建层面的两条硬约束

这两条都是踩过才发现的，**不是风格偏好**。

### 5.1 带 `<modules>` 的 POM 必须是 `packaging=pom`

第一次尝试让根 POM 同时当聚合器和应用模块（`<modules>` + 默认 `packaging=jar`），
Maven 直接拒绝：

```text
'packaging' with value 'jar' is invalid. Aggregator projects require 'pom' as packaging.
```

所以根 POM 变成 `xander-lab-parent`（`packaging=pom`），应用搬到
`apps/core-host/` 并有自己的 POM。**`src/` 搬到 `apps/core-host/src/` 的唯一原因就是这条。**

### 5.2 `spring-boot-maven-plugin` 只能声明在应用模块

声明在父 POM 里，`repackage` goal 会被所有子模块继承；lib 没有 main class，
构建报 `Unable to find main class`。所以它**只**出现在 `apps/core-host/pom.xml`。

⚠️ `apps/core-host` 的 `artifactId` **保持 `xander-lab-backend`**：
`deploy.sh` 的 `APP_NAME` 和 docker-compose 的服务名都依赖它。改模块目录名 ≠ 改 artifactId。

---

## 6. 工具链联动（搬目录时必须同步改的地方）

搬类会让"按路径找文件"的工具静默失效 —— 失效方式是**计数变小但不报错**，
所以这几处必须一起改：

| 位置 | 改动 |
|---|---|
| `scripts/migration/scan.py` | 新增 `APP_ROOT` / `LIB_ROOT` / `java_roots()` / `java_files()` / `lib_packages()`；所有 `JAVA_ROOT.rglob("*.java")` 换成 `java_files()`；**新库的包名要加进 `SHARED_KERNEL`** |
| `scripts/migration/ci_gates.py` | `gate_legacy_redis_reference_check` 的白名单路径 + **新增"白名单里的文件必须存在"判据** |
| `scripts/migration/audit_legacy_sql.py` | `DB_DIR = scan.DB_DIR`，不再硬编码 `src/main/resources/db` |
| `Dockerfile` | 多模块 `COPY`（先 COPY 各 POM 再 `dependency:go-offline`，最后 COPY 源码） |
| `.gitignore` / `.dockerignore` | `application-local.yml` 的路径。**漏改会让本地密钥变成可提交文件，并被 `COPY apps ./apps` 打进镜像** |
| `AGENTS.md` / `backend-norms` skill | 测试路径与物理布局说明 |

⚠️ **`java_roots()` 必须同时扫 `apps/core-host/src/main/java` 和 `libs/*/src/main/java`。**
只扫前者的话，搬进 lib 的类会从所有清单里消失，而清单的计数只是变小、不会报错。

⚠️ **`SHARED_KERNEL` 漏加新库的包名，后果是"数字变好、耦合没变"**：扫描器按包名判定归属，
包名不在 `SHARED_KERNEL` 也不在业务域时，指向它的 import 会被整条丢掉，跨域基线于是**下降**。
这正是 §13 要防的静默漏计，所以 `lib_packages()` 从目录树推导、`shared-kernel-classification`
门禁比对两者 —— 漏加就 FAIL，而不是悄悄少算。

---

## 7. 与 §80 的关系

§80 禁止 `classpath*:/mapper/*.xml` 全局吞入。**今天它是对的**：全仓只有一个应用模块，
classpath 上没有别人的 mapper 可吞。§80 的禁令写在"拆分后"的语境里。

本轮**刻意不改这一行配置**，因为改完无法验证 —— 五个 `@SpringBootTest` 都是切片上下文
（显式 `classes = {...}`），没有任何测试会启动 MyBatis-Plus 并解析 `mapper-locations`。

Wave 0B 的处置是**用门禁冻结现状**：`mybatis-scan-scope` 门禁有三条判据，
见 §8。Wave 1 把 MyBatis 配置搬进 Nacos Service Private DataId 时一并收窄。

同时删掉了 `type-aliases-package` 里指向 **不存在包** 的 `com.xander.lab.entity`
（旧式分层遗留的空目录）。剩下三个是真实域包，但唯一一份 mapper XML
（`mapper/BlogPostMapper.xml`）通篇用全限定类名，所以这个属性目前整体未被使用 ——
这是**发现**，本轮不据此删属性。

---

## 8. 怎么验证

```bash
# 全量测试：应用模块 + 各 lib 模块的合计必须与搬动前一致（1059）
mvn -o test

# 全部门禁（含 mybatis-scan-scope / xml-comment-double-dash / shared-kernel-classification）
python scripts/migration/ci_gates.py
```

⚠️ **`mvn -o test` 的 "Results: Tests run: N" 是逐模块打印的。**
类搬进 lib 之后，总数会拆成 `dinqorai-backend` + 各 lib 两处，
只看最后那个 `Results:` 会以为"测试变少了"（本轮 1059 → 1055，少的 4 个在
`xander-auth-core` 模块里）。**要核对的是各模块之和。**

`shared-kernel-classification`：`libs/` 实际提供的每个 `com.xander.lab.<pkg>` 包
都必须在 `SHARED_KERNEL` 里。见 §6 的说明与注入验证。

`mybatis-scan-scope` 的三条判据：

1. mapper XML 必须落在 `apps/<module>/src/main/resources/mapper/`。
   落在 `libs/` 里 = 全局吞入的"文件版"：共享 jar 在**每个**服务的 classpath 上，
   等于每个服务都加载了别的域的 SQL。
2. 每份 mapper XML 必须能被本模块自己的 `mapper-locations` 匹配到。
   匹配不到 = 死文件，运行时报 "Invalid bound statement"，看起来像代码 bug 而不是配置 bug。
3. `classpath*:/mapper/` 不得扩散到新的配置文件（`KNOWN_MYBATIS_GLOBAL_SCAN` 白名单）。

三条判据都做过注入验证（造出违规 → 门禁 FAIL → 逐字节还原 → PASS）。

---

## 9. 已知缺口

| 缺口 | 影响 | 状态 |
|---|---|---|
| 3 个 lib 未抽 | §91 的 Wave 0B 只完成 3/6（webmvc-support / observability-core / test-support） | Wave 0B 剩余 |
| §21 的 ArchUnit 规则未落地 | 目前是 Python 门禁（`ci_gates.py`）承担同等职责。ArchUnit 需要模块真实存在才有落脚点，且现有规则已能拦住回退 | 观察中 |
| 8 个 `*-api` Contract 未建 | 跨域 import 基线（`cross-domain-imports.yaml`）还只能"只减不增" | Wave 0B 剩余 |
| 空目录已清理但无法提交 | 六个旧式分层空目录（`controller` `dto` `entity` `mapper` `service` `util`）**从未被 Git 跟踪** —— Git 不跟踪空目录。清理只影响本地工作区，仓库里本来就没有它们 | 已处理 |
