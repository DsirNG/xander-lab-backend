# Flyway 迁移策略（V9 §62 / §63 / §64 / §65）

## 结论：单一 Migration Runner

Wave 0～2 是「单物理 MySQL + 多服务账号」。正式选择 **单一 Migration Runner 模式**：
不是每个服务启动时都自己跑 Flyway。

```text
CI/CD Migration Job
  → migration DB account (dinqor_migration)
  → 单一 flyway_schema_history
  → xander_lab
```

业务服务：

```yaml
spring:
  flyway:
    enabled: false
```

生产启动不执行 DDL。

### 为什么不能每服务各自跑

```text
多份 flyway_schema_history 会互相看不见对方版本
→ 版本号冲突 / checksum 冲突
→ 且「谁先启动谁决定 schema」不可控
```

## 目录结构

```text
migrations/
├── README.md                     本文件
├── legacy/                       历史 80 脚本的归档与审计（不执行）
│   ├── AUDIT.md                  脚本审计报告（§64 step 3）
│   └── SHA256SUMS                80 个脚本的校验和
├── versioned/                    基线之后的新迁移（§64 step 4）
│   ├── V2026091500__baseline.sql
│   ├── V2026091501__auth_refresh_session.sql
│   ├── V2026091502__auth_refresh_token_history.sql
│   ├── V2026091503__account_security_state.sql
│   ├── V2026091504__security_event_outbox.sql
│   └── V2026091505__shedlock.sql
└── grants/                       由 table-ownership.yaml 生成的 GRANT 脚本（§66）
```

## 为什么历史脚本不直接塞进 Flyway

`legacy/AUDIT.md` 给出了实测数据：

| 项 | 数量 |
|---|---|
| `CREATE TABLE` 无 `IF NOT EXISTS` | 8 |
| 裸 `ALTER TABLE ... ADD`（无 `IF NOT EXISTS`） | 31 |
| 被多个脚本写入的表 | 55 / 66 |

它们是**手工执行一次的脚本**，在已有库上重放会直接失败；且 55 张表没有单一权威 DDL
（同一张表被多个脚本改过），所以基线必须取**线上真实 schema**，不能从这些文件重建。

## 基线流程

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

## 账号（§63）

| 账号 | 权限 | 用途 |
|---|---|---|
| `dinqor_migration` | DDL + schema metadata 读取 | 只给 Migration Job 用，**不作为任何业务服务的运行账号** |
| `dinqor_core` | 仅运行期 DML | dinqor-core-host |
| `dinqor_agent` | 仅运行期 DML | dinqor-agent |
| `dinqor_integration` | 仅运行期 DML | dinqor-platform-integration |
| `dinqor_content_automation` | 仅运行期 DML | dinqor-content-automation |
| `dinqor_bloghtml_preview` | 仅运行期 DML | dinqor-bloghtml-preview |

GRANT 脚本由 `docs/table-ownership.yaml` 生成，见 `grants/`。

## 硬规则

1. **已应用的 migration 不允许再修改**（Flyway 每次启动校验 checksum）。
2. **新 schema 变更从 `V_NEXT` 开始**，不改动 `V2026091500` 之前的历史。
3. **业务服务不拿 DDL 权限**（§102 禁止项）。
4. **Migration failure 阻断所有依赖新 schema 的服务发布**（§65）。
5. **发布顺序**：先 migrate → 再部署服务（§65 / §96 第 1 步）。
