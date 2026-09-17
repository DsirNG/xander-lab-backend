# Flyway 策略与 MySQL 可靠性基线（V9 §31 / §61 / §62–§66）

本文是 §89 交付物里的 `flyway-strategy.md`。**迁移机制的执行细节不在本文重复** ——
目录结构、基线命令、硬规则写在 [`migrations/README.md`](../../migrations/README.md)；
历史脚本的实测审计数据写在 [`migrations/legacy/AUDIT.md`](../../migrations/legacy/AUDIT.md)。
本文回答的是**决策与当前落地状态**：为什么这么选、GRANT 什么时候收窄、
备份承诺靠什么兑现，以及**哪些部分其实还没接线**。

对应 V9 章节：§6（GRANT 生效时点）、§31（MySQL SOT 可靠性）、§54（Drain）、
§61（Refresh SOT 备份）、§62（单一 Runner）、§63（账号）、§64（历史脚本 baseline）、
§65（发布顺序）、§66（GRANT 生成）、§89 / §90 PASS 37–40。

---

## 1. 一句话结论

**Wave 0～2 是「单物理 MySQL + 多服务账号」，迁移走单一 Migration Runner，
业务服务不执行 DDL。**

```text
CI/CD Migration Job
  → migration DB account (dinqor_migration)
  → 单一 flyway_schema_history
  → xander_lab
```

业务服务一律：

```yaml
spring:
  flyway:
    enabled: false
```

⚠️ **但当前这份策略还只是"设计已定、尚未接线"**：`pom.xml` 里没有 Flyway 依赖，
`application.yml` 里没有 `spring.flyway.*` 配置，`migrations/` 下的 6 个
`V2026*` 文件也从未被执行过。详见 §7。

---

## 2. 为什么不能每服务各自跑 Flyway（§62）

```text
多份 flyway_schema_history 会互相看不见对方版本
→ 版本号冲突 / checksum 冲突
→ 且「谁先启动谁决定 schema」不可控
```

最后一条是最难排查的：多实例同时启动时，**谁先起来谁决定 schema**。
这会让"同一个镜像在不同环境跑出不同 schema"变成可能 ——
而镜像标签是一样的，所以回滚也回不到已知状态。

---

## 3. 历史 80 个脚本：为什么必须 baseline 而不是重放（§64）

`migrations/legacy/AUDIT.md` 由 `scripts/migration/audit_legacy_sql.py` 实测生成，
不是估计：

| 项 | 数量 |
|---|---|
| 脚本总数 | 80 |
| `CREATE TABLE` 无 `IF NOT EXISTS` | 8 |
| 裸 `ALTER TABLE ... ADD`（无 `IF NOT EXISTS`） | 31 |
| `DROP` 无 `IF EXISTS` | 0 |
| **被多个脚本写入的表** | **55 / 66** |
| 同一列被重复 `ADD COLUMN` | 1 |
| 含 `DELIMITER` / 存储过程 / 触发器 | 0 |
| 含 `COMMIT` / `ROLLBACK` / `SET AUTOCOMMIT` | 11 |
| 含 `COMMENT` 变更 | 79 |

**它们必须归档、不得重放**，理由有三条（AUDIT.md 原文）：

1. **无保护的 DDL**：这些脚本是"手工执行一次"写的。在已有库上重放会直接失败 ——
   这正是 §64 禁止把它们塞进 Flyway 的原因。
2. **多写者表（55/66）**：多张表被多个脚本改过，**没有单一权威 DDL**。
   所以基线必须取自**线上真实 schema**，不能从这些文件重建。
3. **重复 `ADD COLUMN`**：同一列被多个脚本添加，只有线上 schema 才知道
   实际生效的是哪一个。

⚠️ 第 4 类（含 `COMMIT` / `DELIMITER`）虽然当前是 0 / 0，
但 `COMMIT` 计数是 11 —— Flyway 在事务里执行每个迁移，
**脚本内的 `COMMIT` 会破坏 Flyway 的事务边界**。归档策略让这个问题不成立；
若将来有人想"只把几个安全的重放进 Flyway"，这 11 个必须先排除。

### 基线流程

```bash
# 1. 取线上 schema 快照（仅用于后续 drift 比对，Flyway 不执行它）
mysqldump --no-data --skip-comments --skip-add-drop-table \
  -h <host> -u dinqor_migration -p xander_lab \
  > migrations/legacy/baseline-schema.sql

# 2. 打基线标记（已有库不会被重建）
flyway baseline \
  -baselineVersion=2026091500 \
  -baselineDescription="pre-microservices monolith" \
  -url=jdbc:mysql://<host>:3306/xander_lab \
  -user=dinqor_migration

# 3. 应用基线之后的新迁移
flyway migrate -url=jdbc:mysql://<host>:3306/xander_lab -user=dinqor_migration

# 4. 业务服务以 spring.flyway.enabled=false 启动
```

`baselineVersion=2026091500` 与 `versioned/V2026091500__baseline.sql` 的版本号对齐，
所以基线标记与第一个版本化迁移是同一个编号 —— **不会出现"基线版本之后还有一段空档"**。

---

## 4. 账号模型（§63）

| 账号 | 权限 | 用途 |
|---|---|---|
| `dinqor_migration` | DDL + schema metadata 读取 | **只给 Migration Job 用，不作为任何业务服务的运行账号** |
| `dinqor_core` | 仅运行期 DML | `dinqor-core-host` |
| `dinqor_agent` | 仅运行期 DML | `dinqor-agent` |
| `dinqor_integration` | 仅运行期 DML | `dinqor-platform-integration` |
| `dinqor_content_automation` | 仅运行期 DML | `dinqor-content-automation` |
| `dinqor_bloghtml_preview` | 仅运行期 DML | `dinqor-bloghtml-preview` |

GRANT 脚本由 `docs/table-ownership.yaml` 生成（`scripts/migration/gen_grants.py`），
产出在 `migrations/grants/`：

```
dinqor_migration.sql              dinqor_core.sql
dinqor_agent.sql                  dinqor_integration.sql
dinqor_content_automation.sql     dinqor_bloghtml_preview.sql
core-host-narrowing.md            ← 按 Wave 的 REVOKE 清单
```

⚠️ 注意 `grants/` 里**没有 `dinqor_knowledge` / `dinqor_studio` / `dinqor_payment`**：
这三个域在 Wave 2 之后仍属 `dinqor-core-host`（见
[`domain-deployment-map.md`](./domain-deployment-map.md) §2），
要到 Wave 3+ 才需要独立账号。

---

## 5. GRANT 生效时点与 core-host 权限收窄（§6 / §66 / §96）

**Wave 0A：**

```text
生成 table-ownership.yaml
生成 GRANT 脚本
但暂不强制拆进程
```

**Wave 1 首次真实拆分时：**

```text
1. 为 dinqor-core-host 创建最小权限 DB Account
2. 为新服务创建独立 DB Account
3. 在同一次切流变更中收窄 core-host 权限
4. 抽出的域表从 core-host REVOKE（在 Drain 完成之后）
```

§6 明确禁止：

```text
新服务用最小权限
但 core-host 仍拿 root / 全库权限
```

否则 Owner 只是纸面概念 —— 权限上没有边界，越权写入不会被数据库拦住。

### 收窄时点：只能落在 §96 的第 9 步

`migrations/grants/core-host-narrowing.md` 的 REVOKE 清单是**按 Wave 分组**的，
当前 `wave2` 组共 33 张表：

```
dinqor-agent                  10 张   agent_*
dinqor-content-automation     16 张   blog_* / email_reminder_task
dinqor-platform-integration    7 张   csdn_* / juejin_* / mcp_oauth_*
```

REVOKE 在十步里的位置是**第 9 步**，且必须在第 7 步
「确认 `active = 0`（逐实例）」之后：

```text
7. 确认 active = 0（逐实例，见 §55.2）
8. 禁用 core-host 对应 Scheduler / Controller
9. REVOKE core-host 表权限      ← 只能在这里
10. 验证唯一 Owner
```

⚠️ **提前 REVOKE 的症状是"流突然报错"**：core-host 上的存量长连接
在收到下一个业务事件时会尝试读写已 REVOKE 的表，直接权限错误。
用户看到的是流中断而不是平滑迁移。详见 [`stream-drain.md`](./stream-drain.md) §2。

**不随 Wave 收窄的表**：identity / billing / admin / profile 以及 Wave 3+ 域的表现在
仍由 core-host 持有，只在 `core-host → dinqor-core` 正式改名时才考虑进一步拆分。

---

## 6. 发布顺序与硬规则（§65 / §64）

硬规则（`migrations/README.md`）：

1. **已应用的 migration 不允许再修改**（Flyway 每次启动校验 checksum）。
2. **新 schema 变更从 `V_NEXT` 开始**，不改动 `V2026091500` 之前的历史。
3. **业务服务不拿 DDL 权限**（§102 禁止项）。
4. **Migration failure 阻断所有依赖新 schema 的服务发布**（§65）。
5. **发布顺序**：先 migrate → 再部署服务（§65 / §96 第 1 步）。

第 5 条对应 §96 十步的第 1 步就是 `Migration Job`。
顺序颠倒的后果是"新代码跑在旧 schema 上" —— 通常表现为启动即失败，
但如果变更只是加了一个可空列，就会**静默降级**（功能不报错但不生效）。

---

## 7. ⚠️ 当前落地状态：设计已定，接线未做

这是本文最重要的一节。§89 的交付物清单里有 `flyway-strategy.md`，
容易让人以为"Flyway 已经就绪"。实际状态是：

| 项 | 期望 | 实际 |
|---|---|---|
| `pom.xml` 有 Flyway 依赖 | 有 | **无**（`grep -i flyway pom.xml` 无匹配） |
| `application.yml` 有 `spring.flyway.*` | 有 | **无** |
| 业务服务 `flyway.enabled=false` | 显式关闭 | **未配置**（因为依赖也不在） |
| `V2026091500..1505` 被执行过 | 有 | **无**（`flyway_schema_history` 尚未建立） |
| `migrations/legacy/AUDIT.md` | 有 | ✅ 已生成（实测数据） |
| `migrations/grants/*.sql` | 有 | ✅ 已生成（由 `gen_grants.py` 产出） |

也就是说：**迁移脚本、审计报告、GRANT 脚本三样都已就绪，
但 Flyway 本身还没接进构建。**

⚠️ 这个状态是**有意的**（Wave 0A 不改部署），但必须在 Wave 1 之前补齐，
否则 §96 第 1 步「Migration Job」无从执行。补齐的最小动作是：

```text
1. pom.xml 加 flyway-core + flyway-mysql
2. 建 CI/CD 的 Migration Job（用 dinqor_migration 账号，独立于业务部署）
3. 业务服务显式写 spring.flyway.enabled=false
4. 加门禁：业务服务的 application.yml 不得出现 spring.flyway.enabled=true
```

第 4 条是必要的 —— 否则某天有人为了"方便本地调试"打开它，
而 §62 说的"谁先启动谁决定 schema"就会在本地与生产同时成立。

---

## 8. MySQL 可靠性：RPO / RTO（§31 / §61）

### 8.1 承诺

Wave 2 Token 正式切流前至少完成：

```text
binlog 开启
自动备份
恢复演练
容量监控
磁盘告警
连接池告警
慢 SQL 监控
```

建议生产目标：

```text
RPO <= 5 分钟
RTO <= 30 分钟
```

若现有基础设施无法达到，**必须在 Wave 2 放行评审中明确接受实际 RPO/RTO**。
可选增强是 MySQL 主从 / 云数据库高可用，但 §31 明确：
"高可用副本"不是 Wave 0A 必须项；**可恢复性与备份验证**是必须项。

### 8.2 为什么 Refresh Session 按核心数据标准执行（§61）

`auth_refresh_session` 丢失的最坏后果是"用户需要重新登录" ——
看起来像缓存，可以不当回事。但 §61 明确要求它**按核心用户数据标准**执行，
理由是它**与核心 MySQL 共库**：

```text
共库意味着：它和用户数据的可恢复性是同一个数字
           库挂了你没法"只恢复 refresh 表"
```

所以 §61 要求 Wave 2 前必须：

```text
验证 full backup
验证 binlog/PITR
完成至少一次 restore drill
记录实际 RPO/RTO
```

### 8.3 ⚠️ 承诺必须有观测量兑现（§31 / §87）

§31 的原文很直接：**RPO/RTO 是承诺，必须有观测量兑现**，
否则该 SLO 无法被监控，只能靠人工确认。§87 的 Reliability 指标段必须包含：

```text
backup_success
last_backup_age
binlog_lag
restore_drill_date
```

⚠️ `restore_drill_date` 是这四个里最容易被漏掉的：前三个都是自动采集的连续量，
而"最近一次恢复演练是什么时候"是**事件时间戳**。没有它，
"完成至少一次 restore drill"这条要求就退化成文档里的一句话。

**当前状态**：这四个指标都未接入（Wave 2 可观测性工作）。

---

## 9. 已知缺口

| 缺口 | 影响 | 状态 |
|---|---|---|
| **Flyway 未接入构建** | 见 §7。`pom.xml` 无依赖、无配置、无 `flyway_schema_history`。§96 第 1 步无法执行 | **Wave 1 前必须补** |
| RPO/RTO 四项观测量未接入 | 见 §8.3，RPO/RTO 目前无法被监控，只能人工确认 | Wave 2 |
| 恢复演练未做 | §61 要求"至少一次 restore drill"并记录实际 RPO/RTO | Wave 2 放行前 |
| GRANT 脚本未实际执行 | `migrations/grants/*.sql` 已生成，但 Wave 0A 不拆进程，所以没有账号被创建 | Wave 1 |
| `baseline-schema.sql` 未生成 | §64 第 1 步需要线上 schema 快照用于 drift 比对，当前 `migrations/legacy/` 只有 `AUDIT.md` 与 `SHA256SUMS` | 首次 baseline 时 |
| binlog 状态未核实 | §31 把"binlog 开启"列为前置项，当前未记录实际状态 | Wave 2 |
| `flyway.enabled=true` 无门禁 | 见 §7 第 4 条。Flyway 尚未接入构建，所以这条门禁**现在无法生效**（属性还不存在）；等 Wave 1 接线时补 | Wave 1 接线时 |
| ~~`SHA256SUMS` 无门禁~~ | **已补**：`gate_flyway_validation` 现在校验归档清单（见 §10） | ✅ Wave 0B 已完成 |

---

## 10. 怎么验证

```bash
# 历史脚本审计数据（AUDIT.md 是否与 80 个脚本的实际内容一致）
python scripts/migration/audit_legacy_sql.py

# 两道判据：版本号唯一性 + 归档清单冻结（门禁 flyway-validation）
python scripts/migration/ci_gates.py flyway-validation

# GRANT 脚本与 table-ownership.yaml 的一致性
# 注意：gen_grants.py 没有 --check，它是「重写」语义 —— 必须靠 git diff 判断有无漂移
python scripts/migration/gen_grants.py && git diff --stat migrations/grants/
```

### 10.1 `gate_flyway_validation` 的两道判据

**判据一：`migrations/versioned/V*.sql` 命名合法 + 版本号唯一。**
Flyway 撞到重复版本会在**启动时**失败，而那时已经在部署窗口里了。

**判据二：归档清单仍然为真。** 这是本节的重点。`migrations/legacy/SHA256SUMS`
记录 `apps/core-host/src/main/resources/db/` 下 80 个历史脚本的 sha256，
是"归档不得被修改"的唯一凭据。这三件事都要查：

1. 清单里的每个文件都要在磁盘上；
2. 磁盘上的每个 `*.sql` 都要在清单里（否则"新加一个归档脚本"无人察觉）；
3. 逐个比对哈希。

第 2 条不能省：`audit_legacy_sql.py` 是**手工运行**的，不会自己再跑一遍，
所以只比哈希的话，新增文件不会有任何症状。

### 10.2 ⚠️ 哈希算法必须与生成方逐字节一致（否则 30 个假失败）

门禁里的哈希**必须**和 `audit_legacy_sql.py` 用同一种算法：

```python
path.read_text(encoding="utf-8", errors="replace").encode("utf-8")   # ✅
path.read_bytes()                                                    # ❌
```

前者走 Python 的**通用换行**（`\r\n` 归一成 `\n`），后者是磁盘上的原始字节。
本仓库 `core.autocrlf=true`，checkout 之后 **80 个文件里有 30 个带 CRLF**，
而这 30 个恰好就是两种算法结果不同的全部 —— 用 `read_bytes()` 会报
**30 个假失败**，看起来像"归档被大规模篡改"，实际什么都没发生。
（实测：`raw-byte hash != text-read hash` 的文件数 = 30 = 含 CRLF 的文件数。）

这与 `backend-norms` 里那条"还原后不要用原始字节 sha256 证明还原成功"是同一个坑。

### 10.3 注入验证（4 条路径都实测过）

| 注入 | 期望 | 实测 |
|---|---|---|
| 往一个归档脚本追加一行注释 | 哈希不符 | ✅ `content changed (recorded 8439f06e3a6e, now 8d722d4a8755)` |
| 在 `db/` 新建一个清单外的 `.sql` | 清单缺条目 | ✅ `zzz_injected_probe.sql: in db/ but absent from SHA256SUMS` |
| 把某个归档脚本移走 | 清单多条目 | ✅ `..._agent_quiz_card_migration.sql: in SHA256SUMS but absent from db/` |
| 移走 `SHA256SUMS` 本身 | 清单缺失 | ✅ `migrations\legacy\SHA256SUMS is missing` |

四条都逐字节还原后回到 PASS。**注入用的那个文件是带 CRLF 的**（第 1 条），
所以这一条同时证明了算法选择是对的 —— 否则它会先报一个假失败。
