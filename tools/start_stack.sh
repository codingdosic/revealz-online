#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then
  echo "Create .env from legacy/node-lobby/.env.example and fill secrets first." >&2
  exit 1
fi
mkdir -p ops-data export
docker volume inspect revealz_pgdata >/dev/null 2>&1 || docker volume create revealz_pgdata >/dev/null
docker compose up -d
echo "Stack up. Check: curl -s http://127.0.0.1:8080/v1/health"
