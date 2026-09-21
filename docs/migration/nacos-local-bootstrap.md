# Nacos Local Bootstrap（V9 §16 / §17）

Nacos 已作为 V9 拆分部署的共享基础设施接入。它不放在根目录的旧单体
`docker-compose.yml` 中；旧文件仍保留给历史单体部署。重构后的本地入口是
`infrastructure/docker/docker-compose.local-infra.yml`，Wave 1/2 服务通过同一个
`dinqor-runtime` 网络连接 Nacos、MySQL 和 Redis。

## 本地启动

在仓库根目录执行。`DINQOR_LOCAL_MYSQL_ROOT_PASSWORD` 只从当前终端或私有 env 文件注入，
不要提交到 Git：

```powershell
$env:DINQOR_LOCAL_MYSQL_ROOT_PASSWORD = '<local-password>'
docker compose -f infrastructure/docker/docker-compose.local-infra.yml up -d
docker compose -f infrastructure/docker/docker-compose.local-infra.yml run --rm nacos-config-init
```

本地 Nacos 默认值：

- 地址：`http://127.0.0.1:8848`
- Namespace：`dinqor-dev`
- Discovery Group：`COMMON`
- 本地鉴权关闭；生产必须使用受管 Nacos 和部署侧鉴权

`nacos-config-init` 会发布共享配置和各服务私有配置，共 7 个 DataId：

```text
dinqor-shared.yaml / COMMON
dinqor-core.yaml / DINQOR-CORE
dinqor-gateway.yaml / DINQOR-GATEWAY
dinqor-bloghtml-preview.yaml / DINQOR-BLOGHTML-PREVIEW
dinqor-agent.yaml / DINQOR-AGENT
dinqor-platform-integration.yaml / DINQOR-PLATFORM-INTEGRATION
dinqor-content-automation.yaml / DINQOR-CONTENT-AUTOMATION
```

## 验收

```powershell
python scripts/migration/nacos_smoke.py
```

验收应同时确认 Nacos health、`dinqor-dev` Namespace、上述 DataId、COMMON 服务注册和
各服务 readiness，并通过 Gateway 实际验证一条 `lb://` Content 公共读路由（200）和一条
无凭据 Platform 受保护路由（401）。最近一次本地验收结果为：7 个 DataId、6 个服务注册、
readiness 全部通过，Gateway 两条路由也通过。服务启动日志还应出现
`Nacos Config ... Load config ... success` 和 `NacosServiceRegistry ... register finished`。

Wave 1/2 Compose 默认启用 Nacos Config/Discovery，并要求注入：

```text
NACOS_SERVER_ADDR=<nacos-host>:8848
NACOS_NAMESPACE=<nacos-namespace>
```

密钥、数据库密码、内部 Token 和第三方凭据不进入 Nacos 明文，继续由部署 Secret 或
环境变量提供。
