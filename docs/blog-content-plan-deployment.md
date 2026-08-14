# 定时发文计划（blog-content-plan）生产部署清单

本次改动包含：一次性计划失败/成功终态语义、调度器启用、`next_run_at` 可空迁移、
Hikari 连接池参数、前端 6 语言状态文案与 FAILED 徽标。以下按顺序执行。

## 1. 数据库迁移（先做）

在线上库（xander_lab）执行 `src/main/resources/db/blog_content_plan_next_run_nullable_migration.sql`：

```sql
ALTER TABLE `blog_content_plan`
  MODIFY COLUMN `next_run_at` TIMESTAMP(3) NULL DEFAULT NULL
    COMMENT '下次计划触发时间（UTC 绝对时刻；一次性计划完结/失败后为空）',
  MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    COMMENT '计划状态：ACTIVE/RUNNING/PAUSED/CANCELLED/FINISHED/FAILED（一次性计划执行成功进入 FINISHED，失败进入 FAILED）';

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260814_blog_content_plan_next_run_nullable');
```

原因：终态 SQL（finishOneShotPlan / failOneShotPlan / recoverStaleOneShot）会把
`next_run_at` 置 NULL，原 NOT NULL 列导致所有完成的一次性计划卡死在 RUNNING。
本迁移幂等（INSERT IGNORE），未执行前不要升级后端。

## 2. 构建并部署后端

任选其一，均已在脚本中默认注入 `BLOG_CONTENT_PLAN_ENABLED=true`：

- `all_in_one_deploy.sh`（服务器一键构建+推送+部署，可用 `.env.production` 覆盖）
- `deploy-aliyun.sh`
- `docker-compose.yml`（compose 环境：`docker compose up -d --build xander-lab-backend`）

注意：若沿用旧脚本或旧镜像，必须自行带上 `BLOG_CONTENT_PLAN_ENABLED=true` 环境变量，
否则调度器不启动，计划永远不会执行（历史事故根因之一）。

## 3. 构建并部署前端

新状态文案（待执行/运行中/成功/失败）与 FAILED 徽标需重新构建部署前端。

## 4. 验证

- `curl http://<host>:30002/health` 返回 200。
- 计划列表：ACTIVE 显示「待执行」，执行中显示「运行中」；历史一次性计划（run 已成功）
  自动恢复为「成功」，中断/失败的计划显示「失败」及错误信息。
- 检查后端日志 `[BlogPlan]`：启动后应有 `recovered stale ...` 恢复记录，
  到点计划有 `enqueued run for plan=...`，执行完成后 run 终态 SUCCEEDED/FAILED。
- 次日计划（如 8/15 起 plan 6–10）在 09:00 自动触发一次并同步 CSDN。

## 5. 回滚

- 仅关闭调度：重新部署时去掉 `BLOG_CONTENT_PLAN_ENABLED`（或置 false）即可暂停触发，
  已认领的 run 会走 stale 恢复，不影响已发布内容。
- 迁移不回滚：`next_run_at` 改可空对现有数据与查询无副作用（终态计划本就该无下次时间）。