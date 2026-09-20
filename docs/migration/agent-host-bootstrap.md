# Agent Host Bootstrap（V9 §94 / §95）

`apps/agent` 是 Wave 2 的真实可部署宿主。Agent 的 Controller、Scheduler、持久化模型、
SSE/WS 处理器和出站 MCP 已迁入该应用；跨域工具依赖仍通过 contracts 表达。这样 Core 不再
注册第二份 Agent Controller，也不会与 Agent 同时写同一套 Agent 表。

## 当前状态

- 服务名：`dinqor-agent`
- 本地端口：`30004`
- Nacos Group：`DINQOR-AGENT`
- 数据库账号：`dinqor_agent`（仅运行期 DML；GRANT 仍按 §96 第 9 步切换）
- 业务路由：代码已由 `dinqor-agent` 承载，Gateway 仍未切流
- Agent SSE/WS：新宿主已具备，等待跨服务端口适配、连接指标和 Drain 验证

## 启动与验收

```text
docker compose -f infrastructure/docker/docker-compose.wave2-agent.yml build
docker compose -f infrastructure/docker/docker-compose.wave2-agent.yml up -d
curl http://localhost:30004/actuator/health/readiness
```

没有可用的 `dinqor_agent` 账号时，可以只编译和运行单元测试；不要为了让宿主启动而给它
`root` 或全库权限。跨域端口适配完成后，仍必须先完成 V9 §95 的连接指标、重连验证和
§96 的 Drain 顺序，然后才能把 Gateway 新连接切到 Agent。
