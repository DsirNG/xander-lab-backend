# Wave 1 service images

## Local MySQL / Redis

For an isolated dependency-only environment, start
`docker-compose.local-infra.yml` first. It creates the canonical `xander_lab` schema
and does not start any application service:

```powershell
$env:DINQOR_LOCAL_MYSQL_ROOT_PASSWORD = "local-only-password"
docker-compose -f infrastructure/docker/docker-compose.local-infra.yml up -d
```

Run the single migration runner against `jdbc:mysql://mysql:3306/xander_lab` before
starting business services. Do not reuse this local password in staging or production;
service accounts and their DML-only grants come from `migrations/grants/`.

The root `Dockerfile` builds the full Maven reactor once and selects the executable jar at
the runtime stage. This keeps dependency resolution and module coverage in one place while
allowing each deployment unit to receive its own image:

```bash
# Core host (the default, equivalent to the historical image)
docker build -t dinqor-core-host:wave1 .

# Gateway
docker build \
  --build-arg APP_MODULE=apps/gateway \
  --build-arg APP_PORT=30000 \
  -t dinqor-gateway:wave1 .

# BlogHTML Preview
docker build \
  --build-arg APP_MODULE=apps/bloghtml-preview \
  --build-arg APP_PORT=30003 \
  -t dinqor-bloghtml-preview:wave1 .

# Migration Runner (one-shot job; APP_PORT is intentionally unused)
docker build \
  --build-arg APP_MODULE=apps/migration-runner \
  --build-arg APP_PORT=0 \
  -t dinqor-migration-runner:wave1 .
```

The image entrypoint remains `java -jar app.jar`; deployment supplies the service-specific
environment, `SPRING_PROFILES_ACTIVE=nacos` where Nacos is required, and the database/Redis
secrets. The migration image is run before business-service rollout and is never used as a
long-running application container.

## Wave 1 Compose template

`docker-compose.wave1.yml` is the executable deployment template for the four Wave 1 units.
It assumes Nacos, MySQL and Redis already exist outside this Compose project and keeps the
historical root `docker-compose.yml` unchanged.

Copy `.env.wave1.example` to a private `.env.wave1` file and fill in the test-environment
endpoints and separate database credentials, then run from the repo root:

```bash
docker compose --env-file .env.wave1 \
  -f infrastructure/docker/docker-compose.wave1.yml up -d --build
```

The required database variables are `MIGRATION_DB_URL`, `MIGRATION_DB_PASSWORD`, `CORE_DB_URL`
and `CORE_DB_PASSWORD`; usernames default to `dinqor_migration` and `dinqor_core`. The Nacos
variables `NACOS_SERVER_ADDR` and `NACOS_NAMESPACE` are also required. `BACKEND_ENV_FILE` may
point at a private common secret file for Redis, mail, OSS and model credentials; for a repo-root
`.env.production`, set `BACKEND_ENV_FILE=../../.env.production` because `env_file` paths are
resolved relative to this Compose file. Its database username/password are overridden by the
two service-specific pairs above. When `BACKEND_ENV_FILE` is omitted, `.env.wave1` itself is
injected into all four containers.

Wave 2 切换新 Refresh Session 协议前，在部署环境设置 `AUTH_REFRESH_ENABLED=true`，并先
完成客户端联调；Wave 1 默认值为 `false`，不会改变现有无状态 JWT refresh 行为。

Compose waits for `migration-runner` to exit successfully before starting `core-host`, then
waits for core readiness before starting Gateway. BlogHTML Preview is independent apart from
the shared Redis/Nacos environment. The core readiness probe is `/health/ready`; the other
two HTTP services use Actuator readiness.

For a multi-instance core deployment, set `AUTH_REFRESH_SECURITY_PUBSUB_ENABLED=true` for every
core-host instance and keep `AUTH_REFRESH_SECURITY_PUBSUB_CHANNEL` identical across them. Redis
Pub/Sub is the fast path; the database security reconciler remains enabled as the durable fallback.
For Agent WebSocket event fan-out, also set `AGENT_EVENT_BUS_PUBSUB_ENABLED=true` on every
core-host instance and keep `AGENT_EVENT_BUS_PUBSUB_CHANNEL` identical. Persisted Agent events
remain replayable from `agent_event`; the Pub/Sub channel is only the low-latency path.
