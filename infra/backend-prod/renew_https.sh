#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ENV_FILE=${REVEALZ_ENV_FILE:-$ROOT_DIR/.env}

cd "$ROOT_DIR"
REVEALZ_ENV_FILE="$ENV_FILE" docker compose --profile https run --rm certbot \
  renew --webroot --webroot-path /var/www/certbot --quiet
REVEALZ_ENV_FILE="$ENV_FILE" docker compose exec nginx nginx -t
REVEALZ_ENV_FILE="$ENV_FILE" docker compose exec nginx nginx -s reload
