# Agent Host Bootstrap（V9 §94 / §95）

`apps/agent` 是 Wave 2 的真实可部署宿主。Agent 的 Controller、Scheduler、持久化模型、
SSE/WS 处理器和出站 MCP 已迁入该应用；跨域工具依赖仍通过 contracts 表达。这样 Core 不再
注册第二份 Agent Controller，也不会与 Agent 同时写同一套 Agent 表。

## 当前状态

- 服务名：`dinqor-agent`
- 本地端口：`30004`
- Nacos Group：`DINQOR-AGENT`
- 数据库账号：`dinqor_agent`（仅运行期 DML；GRANT 仍按 §96 第 9 步切换）
- 业务路由：代码已由 `dinqor-agent` 承载，Gateway 默认通过 `lb://dinqor-agent` 发现路由
- Agent SSE/WS：新宿主已具备；本地 Nacos smoke 已验证注册，生产仍需完成连接指标、重连和 Drain 验证

## 启动与验收

```text
docker compose --env-file .env.wave1 \
  -f infrastructure/docker/docker-compose.wave2-agent.yml build
docker compose --env-file .env.wave1 \
  -f infrastructure/docker/docker-compose.wave2-agent.yml up -d
curl http://localhost:30004/actuator/health/readiness
```

Wave 2 Compose 默认启用 Nacos Config/Discovery，必须提供 `NACOS_SERVER_ADDR` 和
`NACOS_NAMESPACE`，并先启动共享的 local-infra 或部署环境依赖。没有可用的
`dinqor_agent` 账号时，可以只编译和运行单元测试；不要为了让宿主启动而给它 `root`
或全库权限。生产仍必须先完成 V9 §95 的连接指标、重连验证和 §96 的 Drain 顺序，
再扩大 Gateway 到 Agent 的流量比例。

本地双实例冒烟（2026-09-21）已验证：`dinqor-agent` 在 `COMMON` 注册 2 个实例，
两个实例 readiness 均为 200；第二个实例的 `agent_sse_active`、`agent_ws_active`、
`active_sse`、`active_ws` 均带独立 `instance` 标签。该证据只覆盖服务端注册与观测，
不替代前端 SSE/WS 迁移重连验证。

同日的安全平面 staging 还以临时内部令牌启动了 Agent reconciler：Core 安全状态 API、
Agent、Content-Automation、Gateway readiness 均为 200，Agent 对 Core 的快照/增量请求返回
200，连续采样没有 `cursor 未推进` 或对账失败日志。部署时仍须把该令牌作为 Secret 注入，
不能放进 Nacos 明文。
