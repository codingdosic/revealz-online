#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ENV_FILE=${REVEALZ_ENV_FILE:-$ROOT_DIR/.env}

if [ ! -f "$ENV_FILE" ]; then
  echo "missing env file: $ENV_FILE" >&2
  exit 1
fi

set -a
. "$ENV_FILE"
set +a

: "${API_DOMAIN:?set API_DOMAIN in .env}"
if [ "$API_DOMAIN" = "api.example.com" ]; then
  echo "replace the example domain in .env" >&2
  exit 1
fi

if [ -n "${LETSENCRYPT_EMAIL:-}" ]; then
  set -- --email "$LETSENCRYPT_EMAIL"
else
  set -- --register-unsafely-without-email
fi

cd "$ROOT_DIR"
REVEALZ_ENV_FILE="$ENV_FILE" docker compose --profile https run --rm certbot \
  certonly --standalone --non-interactive --agree-tos --no-eff-email \
  "$@" --cert-name "$API_DOMAIN" --domain "$API_DOMAIN"
REVEALZ_ENV_FILE="$ENV_FILE" docker compose --profile https up -d nginx
REVEALZ_ENV_FILE="$ENV_FILE" docker compose exec nginx nginx -t
