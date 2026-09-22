#!/usr/bin/env bash
set -euo pipefail

APP_NAME="xander-lab-backend"
COMPOSE_FILES=(
  "infrastructure/docker/docker-compose.wave1.yml"
  "infrastructure/docker/docker-compose.wave2-agent.yml"
)

# Always run Docker Compose from the repository root so relative build contexts and
# service env_file paths have the same meaning locally and on the deployment host.
cd "$(dirname "$0")/.."

# V9 services need the migration database account and Nacos coordinates in addition to
# the legacy application values. Keep this file outside source control.
ENV_FILE="${BACKEND_ENV_FILE:-.env.wave1}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Copy .env.wave1.example to a deployment-only env file and provide production values." >&2
  exit 1
fi

ENV_FILE="$(cd "$(dirname "$ENV_FILE")" && pwd)/$(basename "$ENV_FILE")"
export BACKEND_ENV_FILE="$ENV_FILE"

COMPOSE_ARGS=(-p "$APP_NAME" --env-file "$ENV_FILE")
for compose_file in "${COMPOSE_FILES[@]}"; do
  COMPOSE_ARGS+=(-f "$compose_file")
done

# Wave 1/2 Compose uses one external network so service-name discovery and the
# separately managed MySQL, Redis, and Nacos endpoints share the same runtime plane.
RUNTIME_NETWORK="${DINQOR_RUNTIME_NETWORK:-dinqor-runtime}"
if ! docker network inspect "$RUNTIME_NETWORK" >/dev/null 2>&1; then
  docker network create "$RUNTIME_NETWORK" >/dev/null
fi

docker compose "${COMPOSE_ARGS[@]}" config --quiet
docker compose "${COMPOSE_ARGS[@]}" up -d --build --remove-orphans

wait_for_health() {
  local service="$1"
  local attempt container status
  for attempt in $(seq 1 40); do
    container="$(docker compose "${COMPOSE_ARGS[@]}" ps -q "$service" | head -n 1)"
    if [[ -n "$container" ]]; then
      status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container")"
      if [[ "$status" == "healthy" ]]; then
        return 0
      fi
      if [[ "$status" == "unhealthy" || "$status" == "exited" || "$status" == "dead" ]]; then
        echo "$service did not become healthy (status: $status)" >&2
        docker compose "${COMPOSE_ARGS[@]}" logs --tail=100 "$service" >&2 || true
        return 1
      fi
    fi
    sleep 3
  done
  echo "$service did not become healthy within 120 seconds" >&2
  docker compose "${COMPOSE_ARGS[@]}" logs --tail=100 "$service" >&2 || true
  return 1
}

for service in core-host gateway bloghtml-preview agent platform-integration content-automation; do
  wait_for_health "$service"
done

docker compose "${COMPOSE_ARGS[@]}" ps
