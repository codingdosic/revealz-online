#!/bin/sh
set -eu

until psql -h postgres -U revealz_meta -d revealz_meta -tAc \
  "SELECT to_regclass('public.shop_products') IS NOT NULL AND to_regclass('public.app_config') IS NOT NULL AND to_regclass('public.accounts') IS NOT NULL AND to_regclass('public.deleted_accounts') IS NOT NULL" \
  | grep -q t; do
  sleep 1
done

psql -h postgres -U revealz_meta -d revealz_meta -v ON_ERROR_STOP=1 <<'SQL'
GRANT USAGE ON SCHEMA public TO revealz_catalog;
GRANT SELECT ON shop_products, app_config TO revealz_catalog;
GRANT SELECT ON accounts, deleted_accounts TO revealz_catalog;
GRANT UPDATE (display_name, meta_revision) ON accounts TO revealz_catalog;
SQL
