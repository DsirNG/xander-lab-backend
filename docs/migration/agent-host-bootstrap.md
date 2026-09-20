# Agent Host Bootstrap（V9 §94 / §95）

`apps/agent` 是 Wave 2 的真实可部署宿主，但当前只承载启动、数据源、Redis、Actuator
和服务发现边界，不承载 Agent 业务路由。这样可以先完成“新服务 deploy → health”，而不会
在 core-host 与 agent 同时注册同一批 Controller、Scheduler 或写表逻辑。

## 当前状态

- 服务名：`dinqor-agent`
- 本地端口：`30004`
- Nacos Group：`DINQOR-AGENT`
- 数据库账号：`dinqor_agent`（仅运行期 DML；GRANT 仍按 §96 第 9 步切换）
- 业务路由：仍由 `dinqor-core-host` 提供
- Agent SSE/WS：尚未切流

## 启动与验收

```text
docker compose -f infrastructure/docker/docker-compose.wave2-agent.yml build
docker compose -f infrastructure/docker/docker-compose.wave2-agent.yml up -d
curl http://localhost:30004/actuator/health/readiness
```

没有可用的 `dinqor_agent` 账号时，可以只构建镜像；不要为了让空宿主启动而给它
`root` 或全库权限。真正的 Agent 代码迁移必须先完成 V9 §95 的连接指标、重连验证和
§96 的 Drain 顺序，然后才能新增 Gateway 路由。
