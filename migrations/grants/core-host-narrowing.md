# core-host 权限收窄计划（V9 §6 / §54 / §66 / §96）

GENERATED FILE — source: `docs/table-ownership.yaml`, tool: `scripts/migration/gen_grants.py`.

## 硬约束

```text
1. 为新服务创建独立 DB Account
2. 在同一次切流变更中收窄 core-host 权限
3. 抽出的域表从 core-host REVOKE（在 Drain 完成之后）
```

禁止：`新服务用最小权限，但 core-host 仍拿 root / 全库权限` —— 否则 Owner 只是纸面概念。

REVOKE 顺序严格遵循 §96 的十步：

```text
1. Migration Job       2. 新服务 deploy      3. 新服务 health
4. 路由新连接到新服务   5. core-host 进入 drain
6. 等待 / 强制结束旧长连接
7. 确认 active = 0（逐实例，见 §55.2）
8. 禁用 core-host 对应 Scheduler / Controller
9. REVOKE core-host 表权限      ← 只能在这里
10. 验证唯一 Owner
```

## 按 Wave 收窄清单

### wave2

- `agent_approval → dinqor-agent`
- `agent_conversation → dinqor-agent`
- `agent_event → dinqor-agent`
- `agent_mcp_server → dinqor-agent`
- `agent_memory → dinqor-agent`
- `agent_message → dinqor-agent`
- `agent_run → dinqor-agent`
- `agent_skill → dinqor-agent`
- `agent_step → dinqor-agent`
- `agent_tool_invocation → dinqor-agent`
- `blog_agent_event → dinqor-content-automation`
- `blog_agent_message → dinqor-content-automation`
- `blog_agent_source → dinqor-content-automation`
- `blog_agent_task → dinqor-content-automation`
- `blog_agent_version → dinqor-content-automation`
- `blog_category → dinqor-content-automation`
- `blog_content_plan → dinqor-content-automation`
- `blog_media_asset → dinqor-content-automation`
- `blog_notification → dinqor-content-automation`
- `blog_plan_run → dinqor-content-automation`
- `blog_post → dinqor-content-automation`
- `blog_post_tag → dinqor-content-automation`
- `blog_post_view → dinqor-content-automation`
- `blog_publish_record → dinqor-content-automation`
- `blog_tag → dinqor-content-automation`
- `csdn_platform_credential → dinqor-platform-integration`
- `email_reminder_task → dinqor-content-automation`
- `juejin_category → dinqor-platform-integration`
- `juejin_platform_credential → dinqor-platform-integration`
- `juejin_tag → dinqor-platform-integration`
- `mcp_oauth_client → dinqor-platform-integration`
- `mcp_oauth_grant → dinqor-platform-integration`
- `mcp_oauth_token → dinqor-platform-integration`

```sql
REVOKE ALL PRIVILEGES ON `xander_lab`.`agent_approval` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`agent_conversation` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`agent_event` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`agent_mcp_server` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`agent_memory` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`agent_message` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`agent_run` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`agent_skill` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`agent_step` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`agent_tool_invocation` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_agent_event` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_agent_message` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_agent_source` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_agent_task` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_agent_version` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_category` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_content_plan` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_media_asset` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_notification` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_plan_run` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_post` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_post_tag` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_post_view` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_publish_record` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`blog_tag` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`csdn_platform_credential` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`email_reminder_task` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`juejin_category` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`juejin_platform_credential` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`juejin_tag` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`mcp_oauth_client` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`mcp_oauth_grant` FROM 'dinqor_core'@'%';
REVOKE ALL PRIVILEGES ON `xander_lab`.`mcp_oauth_token` FROM 'dinqor_core'@'%';
```

## 不随 Wave 收窄的表（`revoke_from_core_host_at: never`）

identity / billing / admin / profile 以及 Wave 3+ 域的表现在仍由 core-host 持有。
它们只在 `core-host → dinqor-core` 正式改名（§4 最终阶段）时才考虑进一步拆分。
