#!/usr/bin/env bash
set -euo pipefail

APP_NAME="xander-lab-backend"

# Always run Docker Compose from the repository root.
cd "$(dirname "$0")/.."

# This file is stored only on the server and supplies Docker Compose variables.
if [[ ! -f .env ]]; then
  echo "Missing .env. Copy .env.production.example to .env and provide production values."
  exit 1
fi

docker compose -p "$APP_NAME" up -d --build --remove-orphans
docker compose -p "$APP_NAME" ps
