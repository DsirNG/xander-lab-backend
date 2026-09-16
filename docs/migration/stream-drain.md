# Wave 2 长连接 Drain：五阶段与切换十步（V9 §54 / §55 / §96）

本文描述从 `dinqor-core-host` 抽出 agent / content-automation 时，
**已经建立的长连接**怎么收场：谁先停、谁后停、`active = 0` 怎么判定、
以及为什么 REVOKE 必须排在最后。

对应 V9 章节：§49（连接注册表只在内存）、§52（宿主清单）、
§54（Drain 五阶段）、§55（Metrics / 回滚）、§96（切换十步顺序）、§97（Staging 门槛）。

---

## 1. 一句话结论

**路由切换和连接排空是两件事，必须分两步做。** 切路由只影响新连接，
存量连接还挂在旧进程上 —— 它们不会因为路由变了就自己迁走。

```
切路由 ──▶ 新连接进新服务（立即）
           存量连接仍在 core-host（继续运行）
           ↓
        Drain  ──▶ 等它自然结束，超时则主动发迁移/重连信号
           ↓
        active = 0（逐实例）──▶ 才允许禁用旧 Handler、REVOKE 旧权限
```

禁止的两件事（§54 原文）：

```text
切路由后立即删除旧 SSE/WS Handler
REVOKE 发生在 Drain 之前
```

---

## 2. Drain 五阶段（§54）

| Phase | 做什么 | 判据 / 记录 |
|---|---|---|
| **1. Route Cut** | 新建 SSE/WS 全部进入新服务 | 新服务 health 通过后才切 |
| **2. Drain** | core-host 不再接受新连接；存量连接继续运行 | 记录 `active SSE` / `active WS` |
| **3. Graceful Drain Deadline** | 等待自然完成；**建议上限 5 分钟**；到期主动发送迁移/重连信号并关闭剩余连接 | 到点必须动作，不能无限等 |
| **4. Disable** | `active connection = 0`，或已完成强制 drain，才禁用旧 Controller / Handler | 逐实例判据，见 §4 |
| **5. REVOKE** | 再撤销 core-host 对该域表的权限 | 最后一步 |

### 为什么 Phase 2 与 Phase 1 必须分开

「不再接受新连接」与「已有连接继续运行」是**两个独立开关**。
把它们合成一步（切路由 = 同时拒绝新连接）会制造一个窗口：
路由已切走，而新服务的健康检查还没稳定 —— 此时用户重连会落到一个拒绝新连接的 core-host 上，
表现是"随机连不上"，而不是"服务还没好"。

### 为什么 Phase 3 必须有上限

SSE 的 `SseEmitter(0L)` **永不超时**，WS 也没有空闲超时兜底
（见 [`stream-security.md`](./stream-security.md) §1）。
所以"等待自然完成"在没有上限时可能**永远不结束** —— 一个挂在后台等终态的
Agent 会话可以跑很久。5 分钟是建议上限，到点必须主动发迁移/重连信号。

### 为什么 Phase 4 与 Phase 5 顺序不能换

若先 REVOKE 再 drain：core-host 上的存量连接在收到下一个业务事件时
会尝试读/写该域的表，直接报权限错误。用户看到的是"流突然报错"而不是"平滑迁移"。
所以 REVOKE 必须等 `active = 0`。

---

## 3. 切换十步（§96）

Drain 五阶段是**关于长连接**的；完整的切换顺序是十步，Drain 是其中的 5~8 步：

| # | 步骤 | 对应 Phase |
|---|---|---|
| 1 | Migration Job | — |
| 2 | 新服务 deploy | — |
| 3 | 新服务 health | — |
| 4 | 路由新连接到新服务 | Phase 1 Route Cut |
| 5 | core-host 进入 drain | Phase 2 Drain |
| 6 | 等待 / 强制结束旧长连接 | Phase 3 Deadline |
| 7 | 确认 `active = 0`（逐实例，见 §4） | Phase 4 判据 |
| 8 | 禁用 core-host 对应 Scheduler / Controller | Phase 4 Disable |
| 9 | REVOKE core-host 表权限 | Phase 5 REVOKE |
| 10 | 验证唯一 Owner | — |

**第 8 步同时要禁 Scheduler，不只是 Controller。** 一个域的 SSE 停了，
但它的 `@Scheduled` 任务还在 core-host 上跑，会出现"两个实例同时写同一份数据"
（v1/v2 双 Owner）—— 这正是 PASS 第 3 条「v1 / v2 不存在双写 Owner」要排除的状态。
`docs/scheduled-ownership.yaml` 是这一步的对照表。

**第 10 步「验证唯一 Owner」** 靠 `table-ownership.yaml` / `scheduled-ownership.yaml` /
`route-inventory.yaml` 三份清单交叉检查：同一个表 / 任务 / 路由不得同时挂在两个部署单元上。

---

## 4. `active = 0` 的判据：必须逐实例（§55.2）

连接注册表**只在本机内存**（§49），但 Phase 4 的判据必须跨实例：

```text
单实例部署：
  逐实例读本地计数即可

多实例部署：
  active = 所有实例计数之和
  由 Micrometer 标签 instance 聚合得到
  判定时要求「全部实例各自为 0 且持续 >= 1 个采样周期」
```

⚠️ §55.2 明确禁止：**不得用「聚合为 0」代替「逐实例为 0」**。

原因是聚合会掩盖两种真实状态：

- 某个实例没上报（进程已死、指标端点不通）→ 它的连接数不参与求和，
  聚合看起来是 0，但那台机器上还有活连接；
- 某个实例刚被重启，计数从 0 开始，而它上一轮的连接已被别的实例接管。

所以判据是「**全部实例各自为 0**」，而不是「和为零」。
`ConnectionRegistry.activeCount()` 提供的就是**本实例**的计数 ——
它的 Javadoc 专门写了这一条，就是为了防止有人把本机值直接当成集群值用。

同理，`activeCountByKind(kind)` 提供 `active_sse` / `active_ws` 的分类型计数，
而 §55.1 要求这两个指标**分别**观测 —— 只报总数会让"SSE 已排空但 WS 还有存量"
这种情况在数字上看起来已经完成。

---

## 5. 指标与回滚信号（§55.1 / §55.3）

### 5.1 指标

```text
legacy_agent_sse_active
legacy_agent_ws_active
legacy_content_sse_active
drain_connection_close_rate
client_reconnect_success_rate
```

前三个是**旧进程侧**的存量计数（逐实例，见 §4）；
后两个是**质量指标**：

- `drain_connection_close_rate`：Phase 3 到点后强制关闭的速率。
  这个值在正常 drain 里应当接近 0 —— 大量强制关闭说明 5 分钟上限
  对实际会话长度来说太短，或者客户端不支持迁移信号。
- `client_reconnect_success_rate`：客户端拿到迁移/重连信号后能否成功连到新服务。
  这个值是**回滚决策的主要依据**：如果重连成功率低，drain 得再快也没意义。

### 5.2 回滚信号（§55.3）

```text
存量连接不下降
新服务建连失败率异常
重连失败
大量对话中断
```

四条里最需要小心的是「**存量连接不下降**」：它可能不是 drain 失败，
而是**客户端在重连到旧服务**（路由未完全生效、或客户端缓存了旧地址）。
所以回滚前要先看 `client_reconnect_success_rate` —— 两者结合才能区分
"排不空"和"排空后又被连回来"。

---

## 6. Staging 门槛（§97 中的长连接部分）

§97 列了 24 项 Wave 2 Staging 必须测试的场景，其中与本文直接相关的：

```text
Admin Ban 已建立 SSE
Admin Ban 已建立 WS
事件只到部分 Gateway
事件只到部分 Agent / core-host
per_instance reconcile 全实例收敛
Drain SSE/WS
deny cache event 丢失后的 reconcile
```

- **「事件只到部分 Agent / core-host」** 验证的是 §5 的逐实例判据：
  只让一部分实例收到封禁事件，检查是否所有实例最终都收敛（靠各自 reconciler）。
- **「deny cache event 丢失后的 reconcile」** 验证的是手段 A 的兜底路径：
  快路径事件丢掉后，reconciler 必须在 5 秒预算内补上。
- **「Drain SSE/WS」** 就是本文的 Phase 1–4。

⚠️ 这些场景**不能只在单实例下测**。「只到部分实例」和「逐实例为 0」
在单实例部署里恒真，测了也发现不了问题 —— 而这正是 §69
「`per_instance` 加全局锁会通过验收但语义全错」那一类失败的同源风险。

---

## 7. 已知缺口

| 缺口 | 影响 | 状态 |
|---|---|---|
| 5 个指标全部未接入 | 本文的 Phase 4 判据目前只能靠日志与 `ConnectionRegistry.activeCount()` 人工读；`legacy_*_active` 需要 `instance` 标签，属 Wave 2 可观测性工作 | Wave 2 |
| Phase 3 的「迁移/重连信号」协议未定义 | 5 分钟到点后要"主动发送迁移/重连信号"，但 SSE / WS 上用什么事件名、客户端如何响应尚未设计。当前 SSE 无应用层关闭码（见 `stream-security.md` §4.1），这个信号只能走普通事件帧 | Wave 2 设计 |
| 无空闲超时兜底 | SSE `SseEmitter(0L)` 永不超时，WS 也没有空闲超时；Phase 3 的上限是唯一的时间边界 | 设计取舍 |
| 逐实例判据依赖 Micrometer | 若某实例指标端点不可达，§4 的「全部实例各自为 0」无法判定 —— 此时应视为**未完成**（fail-closed），不得当作 0 | Wave 2 放行评审 |

---

## 8. 怎么验证

本文描述的是 Wave 2 的执行流程，**当前无代码可跑**（Wave 0A 不改部署）。
能机器校验的部分是三份 Owner 清单的一致性：

```bash
# 表 / 调度 / 路由的 Owner 唯一性（第 10 步「验证唯一 Owner」的机器部分）
python scripts/migration/ci_gates.py
```

Phase 4 的判据（`active = 0` 逐实例）与 §5 的指标属于 Wave 2 可观测性工作，
接入后必须有一条断言 `instance` 标签存在性的测试 —— 否则聚合值会被
误当成逐实例值用，而**这个错误不会让任何测试失败**。
