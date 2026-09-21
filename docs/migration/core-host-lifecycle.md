# Core Host 与旧单体生命周期（V9 §4 / §5 / §93）

本文描述 `dinqor-core-host` 从"当前单体"到"只剩四个域"的完整生命周期，
以及为什么**不能**新起一套 `dinqor-core` 与旧单体并存。

对应 V9 章节：§2.2（唯一 Owner）、§3（部署单元映射）、§4（Core Host 生命周期）、
§5（Gateway Fallback 与 Core Host 一致化）、§93（Wave 1 回滚阈值）、
§89 / §90 PASS 2。

---

## 1. 一句话结论

> **Wave 1 不新起一套 `dinqor-core` 与旧单体并存。当前单体本身通过逐步减法演进成
> `dinqor-core-host`。**

这是 §4 的原话。它排除掉了一种看起来更"标准"的做法：先起一套新的 core 服务、
再把域一个个搬过去、最后下线旧单体。那种做法在迁移期间会产生**两个进程同时写同一批表**，
也就是双 Owner —— 正是 §2.2「任何一张表只能有一个 Owner」要排除的状态。

所以本项目的迁移形态是**减法**而不是**搬迁**：

```
搬迁（被排除）: 旧单体 ──搬域──▶ 新 core        旧单体还在写 → 双 Owner 风险
减法（本项目）: 旧单体 ──减域──▶ 剩下的仍是同一个进程
```

---

## 2. 生命周期

| 阶段 | 宿主 | 拥有哪些域 | 关键动作 |
|---|---|---|---|
| **Wave 0** | `xander-lab-backend:30002` | 全部 15 个域 | 不改部署。产出全部清单与文档 |
| **Wave 1** | 同一宿主注册为 `dinqor-core-host` | 全部 15 个域 − `bloghtml` | `apps/bloghtml-preview` 已建立并承接 BlogHTML；生产流量切换与 core-host 旧入口禁用仍按发布窗口执行；其余域仍由 core-host **唯一**拥有 |
| **Wave 2** | `dinqor-core-host` + 三个新服务 | − `agent` / `integration` / `publishing` / `blogagent` / `automation` | 对应 Controller / Scheduler / Mapper 权限同时禁用或移除；长连接按 §54 Drain；**Drain 完成后**才 REVOKE 表权限 |
| **Wave 3+** | `dinqor-core-host` + 更多 | − `knowledgebase` / `recitation` / `studio` / `components` / `virtualpay` | 继续减法抽出 Knowledge / Studio / Payment |
| **最终** | 改名 `dinqor-core` | 仅 `identity` / `billing` / `admin` / `profile` | 正式改名 |

⚠️ **Wave 1 那一行是理解全篇的关键**：`dinqor-core-host` 不是新进程，
只是**当前宿主换了个注册名**。所以 Wave 1 没有数据迁移、没有 Drain、没有 REVOKE ——
它只是把 §3 映射表里的单元名落到运行时的服务名上。

---

## 3. 双 Owner 禁令

§4 用一段反例把这个约束写死了：

```text
因此不会存在：
  新 Core 写 identity 表
  +
  旧单体也写 identity 表
这种双 Owner。
```

它对应 PASS 第 3 条「v1 / v2 不存在双写 Owner」。**"v1 / v2"指同一条业务能力
同时存在新旧两套入口**（旧单体 + 新服务），而不只是接口版本号。

### 为什么 Wave 2 的第 8 步必须同时禁 Scheduler

§96 第 8 步是「禁用 core-host 对应 Scheduler / Controller」。
**只禁 Controller 是不够的**：一个域的 SSE 停了、路由切走了，
但它的 `@Scheduled` 任务还在 core-host 上跑，就会出现两个实例同时写同一份数据。

`docs/scheduled-ownership.yaml` 是这一步的对照表 —— 每个 `@Scheduled` 的
`logical_owner` + `current_deployment_owner` 明确写了它现在归谁，
所以"这个域还有哪些任务要禁"是可查的，不靠记忆。

### 唯一 Owner 的验证方式

§96 第 10 步「验证唯一 Owner」靠三份清单交叉检查：同一个表 / 任务 / 路由
不得同时挂在两个部署单元上。对应的门禁见
[`domain-deployment-map.md`](./domain-deployment-map.md) §5。

---

## 4. 实例数假设

§4 单独规定实例数，因为它是 §54 Drain 判据的前置条件：

```text
Wave 0 / Wave 1：core-host 单实例
Wave 2：core-host 允许 2 实例（滚动发布需要）
```

⚠️ **Wave 2 一进入多实例，`active = 0` 就不能看聚合值**，必须逐实例判定。
§55.2 明确禁止用「聚合为 0」代替「逐实例为 0」——
聚合会因某实例未上报而误判。详见 [`stream-drain.md`](./stream-drain.md) §4。

这也解释了为什么 §4 要把实例数写在 Core Host 生命周期这一节：
**Drain 能不能做对，取决于这里写的是 1 还是 2。**

### 4.1 滚动发布需要就绪探针，而就绪探针需要一个"安全平面已就绪"的信号

Wave 2 允许 2 实例，意味着会出现**滚动发布**：新实例起来、旧实例下线。
如果负载均衡在新实例还没建好本地 deny cache 时就把流量切过去，
那段时间里封禁对新实例是失效的（V11 §27.1 明令禁止的
「实例启动后先接流量，再慢慢填 deny cache」）。

所以滚动发布的前置条件是两件事，缺一不可：

| 前置 | 载体 | 状态 |
|---|---|---|
| 实例自己能说出"我还没准备好" | `SecurityStateBootstrap` + `SecurityPlaneReadiness`，`GET /health/ready` 在 READY 之前返回 503 | **已实现** |
| 部署侧真的去问那个端点 | `infrastructure/docker/docker-compose.wave1.yml` 的 core-host healthcheck 指向 `/health/ready` | **Wave 1 Compose 已接线；外部编排仍需复用同一端点** |

⚠️ 端口监听 ≠ 可以接流量：内嵌 Servlet 容器在 Spring 容器 refresh 期间就开始监听，
而 `SecurityStateBootstrap` 是 `ApplicationRunner`，在 refresh 之后才跑。
所以"不接流量"只能由负载均衡侧的就绪探针保证，**不能**靠端口是否打开来判断。

在就绪探针接线之前，`SecurityStateBootstrap` 用 **fail-fast** 兜底：
快照读不到就让进程退出，而不是「起来但没就绪」。这是当前唯一自证的机制，
代价是启动期数据库抖动会表现为容器重启循环。详见
[`admin-ban-security-plane.md`](./admin-ban-security-plane.md) §3.6。

---

## 5. 服务名与配置标识

Wave 1 的"改名"只改**运行时服务名**，不改配置标识 —— §911 的规则：

```text
Nacos Group 使用稳定逻辑服务名，不使用临时部署名 CORE-HOST
DataId：dinqor-shared.yaml / dinqor-core.yaml / dinqor-gateway.yaml / ...
```

因此 `core-host → core` 最终改名时：

```text
Nacos Group / DataId 不需要跟着改名
```

⚠️ 当前 `src/main/resources/application.yml` 的实际值是：

```yaml
spring:
  application:
    name: dinqorai-backend        # L7-8
```

注意它**既不等于 §3 表里的 Wave 0 单元名（`xander-lab-backend`），
也不等于 Wave 1 的 `dinqor-core-host`**。三者是不同的东西：

| 名称 | 含义 | 载体 |
|---|---|---|
| `xander-lab-backend` | 仓库名 / §3 表里的 Wave 0 列 | 目录名 |
| `dinqorai-backend` | 当前运行时服务名 | `application.yml` L8 |
| `dinqor-core-host` | Wave 1 起的注册名 | 部署时覆盖 |
| `dinqor-core` | 最终名 | 部署时覆盖 |

**改名的迁移路径**：Wave 1 时用环境变量覆盖
（`SPRING_APPLICATION_NAME=dinqor-core-host`）而不是改 `application.yml` ——
因为一旦改了文件里的值，本地开发环境也会跟着变成 core-host，
而本地并没有 Gateway / Nacos，服务发现会失败。

---

## 6. Gateway Fallback 与 Core Host 一致化（§5）

Wave 1 起，Gateway 必须有一条**兜底路由**，把所有尚未迁移的入口交给 core-host：

```yaml
- id: monolith-fallback
  uri: lb://dinqor-core-host
  predicates:
    - Path=/**
  order: 9999
```

规则：

```text
未迁移入口  → core-host
已拆服务的 v1/v2 → 对应新服务
```

### 为什么是 `order: 9999`

兜底路由必须**排在最后**。用 `Path=/**` 且没有更低 order 的话，
它会先匹配掉所有请求，已拆服务的路由永远走不到 —— 表现为
"新服务部署成功、health 通过、但流量一点没过去"。

### 为什么必须从 `http://` 切到 `lb://`

§5 允许 Wave 1 初期（尚未把旧单体注册 Nacos 时）短期写死地址：

```yaml
uri: http://xander-lab-backend:30002
```

但**完成 Nacos 接入后必须切成 `lb://dinqor-core-host`**。
写死地址的代价是：滚动发布时 IP 变化会让 Gateway 打到旧实例；
而 `lb://` 由注册中心维护实例列表，滚动发布才能正常工作。

⚠️ 这条与 §93 的回滚信号「长连接建连失败率 > 1% 持续 5 分钟」直接相关 ——
写死地址导致的长连接失败会表现为这个指标。

---

## 7. 回滚阈值（§93）

**达到阈值即回滚：**

```text
新服务 5xx > 1% 持续 5 分钟
P95 > 旧链路 2 倍 持续 10 分钟
BlogHTML 成功率 < 99% 持续 5 分钟
Gateway 未预期 404 > 0.1%
长连接建连失败率 > 1% 持续 5 分钟
```

**无条件立即回滚（不等阈值）：**

```text
越权
数据错写
路由打错 Owner
长连接被 Gateway 缓冲或提前断开
```

⚠️ 后四条之所以"立即回滚"，是因为它们**不会被上面五个指标捕捉到**：

- **越权 / 数据错写**：请求成功率可能 100%，只是写错了地方；
- **路由打错 Owner**：正是双 Owner 的入口，继续跑会持续产生错数据；
- **长连接被 Gateway 缓冲**：SSE 会"看起来正常"但事件不再实时到达 ——
  建连成功率是 100%，因为连接确实建立了。

**长连接被缓冲**这一条尤其要提前在 Gateway 配置里防住（不缓冲 / chunked /
Upgrade / idle / `Last-Event-ID` / query token 透传，见 PASS 33）。
它属于"设计上要防"，不属于"靠回滚发现"。

---

## 8. 已知缺口

| 缺口 | 影响 | 状态 |
|---|---|---|
| Gateway 代码 | 已建立 `apps/gateway`，包含 `monolith-fallback`、鉴权策略、TraceId 和流式代理部署配置；本地 Nacos smoke 已验证健康、Core 兼容读路由和 Agent 路由 | ✅ 代码完成；生产仍需部署窗口验证真实长连接 |
| Nacos 配置导入 | 三个 Wave 1 服务已提供 `nacos` profile，分别导入 `dinqor-shared.yaml` + 服务私有 DataId；本地 `dinqor-dev` namespace 已实际加载并注册到 `COMMON` | ✅ 本地 Docker 已验证；生产仍需接入受管 Nacos |
| Migration Runner | `apps/migration-runner` 使用独立 `dinqor_migration` 账号执行 Flyway；本地库已验证 9 个 migration 全部通过且 schema 为 `2026092101` | ✅ 本地 Docker 已验证；发布仍需纳入 CI/CD 阻断 |
| 回滚阈值无自动化 | §93 的五个阈值都需要指标（Micrometer）与告警规则，Wave 2 才有 | Wave 2 |
| 服务名三套并存 | 见 §5 的表：仓库名 / `dinqorai-backend` / `dinqor-core-host` 三个名字同时存在，没有门禁校验它们的一致性 | 已知，见 §5 |
| Wave 1 回滚流程未定义 | §93 只给了"何时回滚"，没给"怎么回滚"（切回路由？重启旧版本？） | Wave 1 设计 |

---

## 9. 怎么验证

本文描述的生命周期**在 Wave 0A 无可执行内容**（§4 原文："Wave 0A 不改部署"）。
当前能校验的是"减法目标"的静态一致性：

```bash
# 三份清单的 Owner 唯一性（§96 第 10 步的机器部分）
python scripts/migration/ci_gates.py

# 确认当前运行时的服务名（§5 的表第一列）
grep -n -A 1 "application:" src/main/resources/application.yml
```

⚠️ §4 的"不会存在双 Owner"这条约束，**当前没有任何门禁**。
`table-ownership.yaml` 保证每张表只有一个 `logical_owner`，
但"新旧两个进程是否都在写"是**运行时事实**，清单管不到。
Wave 1 之后必须靠 §96 第 8/9 步的显式动作（禁 Scheduler / REVOKE）
加上 GRANT 层面的约束来兑现 —— 这也是 `flyway-strategy.md` 里
"core-host 权限收窄时点"要写清楚的原因。
