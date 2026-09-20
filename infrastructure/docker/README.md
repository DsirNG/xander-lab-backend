# Wave 1 service images

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
