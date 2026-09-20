# 全域 → 部署单元映射（V9 §3）

本文是 `table-ownership.yaml`、`scheduled-ownership.yaml`、路由归属与数据库 GRANT
的**前置输入**：先确定"哪个域最终住在哪个部署单元"，后面三份清单才有依据。

对应 V9 章节：§2.2（唯一 Owner）、§3（全域 → 部署单元映射）、§4（Core Host 生命周期）、
§52（长连接宿主联动）、§89 / §90 PASS 1。

---

## 1. 一句话结论

**Wave 0 与 Wave 1 的部署单元是同一个进程。** 映射表里 15 个域在 Wave 0 / Wave 1
两列写的都是"同一个东西"，只是 Wave 1 给它换了个名字（`dinqor-core-host`）。

```
Wave 0    xander-lab-backend:30002      拥有全部 15 个域
Wave 1    同一宿主注册为 dinqor-core-host  仍拥有全部 15 个域（减去 bloghtml）
Wave 2    开始减法：agent / integration / publishing / blogagent / automation 搬走
```

所以这张表的真正作用**不是描述现在**，而是把"将来谁搬到哪里"在 Wave 0 就固定下来，
让后面每一份清单（表 / 调度 / 路由 / GRANT）都有同一个坐标系。

---

## 2. 映射表（§3）

| 业务域 | Wave 0 | Wave 1 | Wave 2 | 后续目标 |
|---|---|---|---|---|
| identity | `xander-lab-backend` | `dinqor-core-host` | `dinqor-core-host` | `dinqor-core` / 可选 `identity-service` |
| billing | `xander-lab-backend` | `dinqor-core-host` | `dinqor-core-host` | `dinqor-core` / `commerce` |
| admin | `xander-lab-backend` | `dinqor-core-host` | `dinqor-core-host` | `dinqor-core` |
| profile | `xander-lab-backend` | `dinqor-core-host` | `dinqor-core-host` | `dinqor-core` |
| agent | `xander-lab-backend` | `dinqor-core-host` | **`dinqor-agent`** | `dinqor-agent` |
| integration | `xander-lab-backend` | `dinqor-core-host` | **`dinqor-platform-integration`** | `platform-integration` / 可选 `mcp-gateway` |
| publishing | `xander-lab-backend` | `dinqor-core-host` | **`dinqor-content-automation`** | `dinqor-content` |
| blogagent | `xander-lab-backend` | `dinqor-core-host` | **`dinqor-content-automation`** | `dinqor-content` |
| automation | `xander-lab-backend` | `dinqor-core-host` | **`dinqor-content-automation`** | `dinqor-automation` |
| bloghtml | `xander-lab-backend` | **`dinqor-bloghtml-preview`** | `dinqor-bloghtml-preview` | `dinqor-bloghtml-preview` |
| knowledgebase | `xander-lab-backend` | `dinqor-core-host` | `dinqor-core-host` | `dinqor-knowledge` |
| recitation | `xander-lab-backend` | `dinqor-core-host` | `dinqor-core-host` | `dinqor-knowledge` |
| studio | `xander-lab-backend` | `dinqor-core-host` | `dinqor-core-host` | `dinqor-studio` |
| components | `xander-lab-backend` | `dinqor-core-host` | `dinqor-core-host` | `dinqor-studio` |
| virtualpay | `xander-lab-backend` | `dinqor-core-host` | `dinqor-core-host` | `dinqor-payment` |

**两处容易被读错的地方：**

- **`bloghtml` 是唯一在 Wave 1 就搬走的域**，早于所有其它域。它搬到
  `dinqor-bloghtml-preview`，与 Agent / Content-Automation 无关。
- **`components` 跟着 `studio` 走**（`dinqor-studio`），不跟着 `publishing`。
  它是 studio 的组件域，不是内容域的。

### 映射对应的实际包位置

15 个域与仓库包一一对应，可以直接核对：

```
apps/core-host/src/main/java/com/xander/lab/
  admin/  agent/  automation/  billing/  blogagent/  bloghtml/
  components/  identity/  integration/  knowledgebase/  profile/
  publishing/  recitation/  studio/  virtualpay/
```

⚠️ 旧式分层还留下 `controller/` `dto/` `entity/` `mapper/` `service/` `util/`
六个**空目录**（`dto/` 下另有 6 个空子目录，合计 0 个文件）。它们不属于任何域，
**不要**因为"看起来像基础设施"而给它分配 Owner。

⚠️ 更要紧的是：**这六个目录 Git 从来没跟踪过** —— Git 不跟踪空目录，
所以"删除它们"从来不是一个可提交的动作，它们只存在于本地工作区。
Wave 0B 已从工作区清理；`type-aliases-package` 里指向 `com.xander.lab.entity`
的那一项也一并删除（该包并不存在）。详见
[`lib-extraction.md`](./lib-extraction.md) §7。

---

## 3. API 归属前缀（§3）

部分域的对外前缀与其包名不一致，必须显式记录，否则 Gateway 路由会写错：

| 域 | API 前缀 |
|---|---|
| knowledgebase | `/api/v2/knowledge/**` |
| recitation | `/api/v2/learning/**` |
| studio | `/api/v2/studio/**` |
| components | `/api/v2/studio/components/**` |
| virtualpay | `/api/v2/payments/**` |

⚠️ `components` 的前缀是 `/api/v2/studio/components/**`，
**嵌在 studio 的前缀里面**。所以 Gateway 的 studio 路由必须把 components
的更具体路径排在前面（或使用 `order` 显式控制），否则 components 的请求会被
studio 的规则先匹配走。

⚠️ `recitation` 的包名是 `recitation`，前缀却是 `learning`。
两者不一致是历史原因；`route-inventory.yaml` 里的 id 由路径推导，
所以按 id 搜索时要用 `learning` 而不是 `recitation`。

---

## 4. 长连接宿主联动（§52）

映射表有一列隐含信息：**哪个域持有 SSE / WS**。它决定 Wave 2 的 Drain 范围，
也是 §52 宿主清单的来源。

| 域 | 是否持有 SSE/WS | Wave 1 宿主 | Wave 2 宿主 | Wave 3+ 宿主 |
|---|---|---|---|---|
| agent | 是（对话 SSE / WS） | `dinqor-core-host` | `dinqor-agent` | `dinqor-agent` |
| automation | 是（自动化 SSE） | `dinqor-core-host` | `dinqor-content-automation` | `dinqor-content-automation` |
| blogagent | 是（BlogAgent SSE） | `dinqor-core-host` | `dinqor-content-automation` | `dinqor-content` |
| studio | 是（Img2Three SSE） | **`dinqor-core-host`** | **`dinqor-core-host`** | `dinqor-studio` |
| components | 否 | `dinqor-core-host` | `dinqor-core-host` | `dinqor-studio` |

**studio 那一行是本表最容易看漏的地方。** 按 §3 的主表，studio 在 Wave 2 仍属于
`dinqor-core-host`；但很容易把它和"Wave 2 拆走的 agent / content-automation"混在一起，
从而认为 Wave 2 之后 core-host 不再需要安全平面。

**Wave 2 之后、Wave 3 之前，`dinqor-core-host` 仍然是 Img2Three SSE 的宿主** ——
这就是 §52 必须把 `dinqor-core-host` 列进长连接宿主清单、且必须让它实现
`LocalSecurityStateCache` 与连接注册表的原因。详见
[`stream-security.md`](./stream-security.md) §2。

---

## 5. 这份映射的落地载体

映射表本身是**人读的**；机器执行的是三份清单。三者必须一致：

| 清单 | 承载映射的哪个维度 | 门禁 |
|---|---|---|
| `table-ownership.yaml` | 每张表的 `logical_owner` + `target_deployment_unit` | `table-ownership-drift` |
| `scheduled-ownership.yaml` | 每个 `@Scheduled` 的 `logical_owner` + `current_deployment_owner` | `scheduled-ownership-coverage` |
| `route-inventory.yaml` | 每条路由的归属与 `stream` 标记 | `inventory-integrity` |

当前 `table-ownership.yaml` 里 66 张表的目标部署单元分布：

```
dinqor-core-host             36
dinqor-content-automation    16
dinqor-agent                 10
dinqor-platform-integration   7
shared                        1
migration-runner              1
```

⚠️ `shared` 与 `migration-runner` 两个值**不在 §3 的 15 个域里**，
它们不是业务域：

- `shared` 目前只有一张表：`shedlock`（`logical_owner: migration`）——
  多实例互斥锁表，所有部署单元共用，所以不能归给某一个域；
- `migration-runner` 目前只有一张表：`xander_schema_migration` ——
  Flyway 的迁移历史，只有迁移账号写（见 `flyway-strategy.md`）。

它们出现在清单里是**正确的**（清单必须覆盖 100% 的表），但不要把它们
当成第 16、17 个业务域去分配 Owner。

---

## 6. 实例数假设

映射表决定"哪个单元承载哪个域"，而 §4 单独规定**每个单元跑几个实例** ——
这直接决定 §54 Drain 的 `active = 0` 判据怎么算：

```text
Wave 0 / Wave 1：core-host 单实例
Wave 2：core-host 允许 2 实例（滚动发布需要）
```

⚠️ Wave 2 一旦是多实例，`active = 0` 就必须按**逐实例**判定而不是看聚合值 ——
详见 [`stream-drain.md`](./stream-drain.md) §4。

---

## 7. 已知缺口

| 缺口 | 影响 | 状态 |
|---|---|---|
| 映射未进入机器门禁 | 本文是 §3 的人工转写。三份清单各自有门禁，但**没有门禁校验"清单里的部署单元名是否都在 §3 的集合里"** —— 写错一个单元名（如 `dinqor-content` 写成 `dinqor-contents`）不会失败 | Wave 0B |
| `bloghtml` 的 Wave 1 进程拆分 | 已落地 `apps/bloghtml-preview`，保留 Redis TTL/限流/CSP；它不持有 SSE，表归属仍由 `table-ownership.yaml` 管理 | ✅ 代码与 Wave 1 Compose/Nginx 模板已完成；生产 DNS/流量切换待执行 |
| 空目录已清理（不可提交） | 见 §2 的六个空包。Git 不跟踪空目录，所以仓库里本就没有它们，清理只影响本地工作区 | 已处理 |
| `components` 前缀嵌套 | 见 §3，Gateway 路由 `order` 需要在 Wave 1 实际配置时验证 | Wave 1 |

---

## 8. 怎么验证

```bash
# 三份清单的一致性 + 覆盖率 + 唯一 Owner（§2.2）
python scripts/migration/ci_gates.py
```

人工核对的最小流程：

1. 按 §2 的表确认 15 个域仍在 `apps/core-host/src/main/java/com/xander/lab/` 下各有一个包；
2. 确认 `table-ownership.yaml` 的 `target_deployment_unit` 取值集合
   等于 §2 表里出现过的单元名 ∪ `{shared, migration-runner}`；
3. 确认 §4 的长连接宿主表与 `stream-hosts.yaml` 的 `deployment_unit` 字段一致
   （**studio 行必须写 `dinqor-core-host`**）。

⚠️ 第 2 步和第 3 步目前都只能人工做（见 §7 第一条）。
