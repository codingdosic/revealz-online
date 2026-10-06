# BE05-A local catalog and account JPA slice

This stack is isolated from the repository root Compose project. It uses a
separate network and `backend_local_pg` volume and binds every diagnostic port
to localhost.

## Start and verify

Prerequisites: Docker with Compose. The Spring image supplies Java 21 and uses
the Maven Wrapper pinned to Maven 3.9.16.

```powershell
docker compose -f infra/backend-local/compose.yaml up -d --build --wait
python infra/backend-local/verify_contract.py
```

- Nginx entry: `http://127.0.0.1:18080`
- Node diagnostic: `http://127.0.0.1:18081`
- Spring A and Actuator diagnostic: `http://127.0.0.1:18082`
- Spring B and Actuator diagnostic: `http://127.0.0.1:18083`
- PostgreSQL diagnostic: `127.0.0.1:15432`
- Redis: internal Compose network only; use `docker compose exec redis redis-cli`

`/v1/shop/catalog` and the one-segment
`/v1/local/accounts/{accountKey}` path go to the `spring_catalog` upstream,
which contains the same Spring image running as `spring-a` and `spring-b`.
Nginx uses its default round-robin selection. Every other path, including the
existing `/v1/meta/accounts/...` game API and `/v1/health`, stays on Node.
Actuator is never proxied by Nginx.

Node owns schema/seed startup and uses its normal `pg` pool default (10).
Each Spring Hikari pool is capped at 5, so the two Spring pools total 10 and
the local stack can open at most 20 application connections. This is recorded,
not tuned or benchmarked in BE04-A. The shared Spring role can `SELECT` from
`shop_products`, `app_config`, `accounts`, and `deleted_accounts`. Its only
write permission is column-level `UPDATE` on `accounts.display_name` and
`accounts.meta_revision`; it cannot create schema or write unrelated fields.
`WARM_POOL_SIZE=0`, no Dedicated export is mounted, and no match process is
requested. Node health therefore reports the real worker-absent state rather
than a fabricated one.

Both Spring instances share `revealz:catalog:v1` in Redis. The local Compose
setting enables cache-aside with a 30-second TTL; the application default is
disabled. A cache miss runs the existing two catalog SELECTs, stores the whole
response JSON, and returns it. Redis or cached-JSON failure falls back to the
database. Redis is not part of Spring readiness. Cache data has no volume or
backup because it can be rebuilt from PostgreSQL.

## Local account summary and display-name update

Compose explicitly enables the `local-account` Spring profile on A and B. The
application default does not expose this route. It is an unauthenticated local
learning API, not proof that an account key belongs to the caller.

Prepare one disposable fixture through the local PostgreSQL service:

```powershell
docker compose -f infra/backend-local/compose.yaml exec -T postgres `
  psql -U revealz_meta -d revealz_meta -c `
  "INSERT INTO accounts (account_key, display_name, meta_revision) VALUES ('be05_manual', 'Before', 0) ON CONFLICT (account_key) DO NOTHING"
```

Read from A, update through B, and read from A again:

```powershell
Invoke-RestMethod http://127.0.0.1:18082/v1/local/accounts/be05_manual
Invoke-RestMethod -Method Patch `
  -Uri http://127.0.0.1:18083/v1/local/accounts/be05_manual `
  -ContentType application/json `
  -Body '{"displayName":"After","baseRevision":0}'
Invoke-RestMethod http://127.0.0.1:18082/v1/local/accounts/be05_manual
```

The Nginx form is `http://127.0.0.1:18080/v1/local/accounts/be05_manual`.
Delete only the disposable key after checking it:

```powershell
docker compose -f infra/backend-local/compose.yaml exec -T postgres `
  psql -U revealz_meta -d revealz_meta -c `
  "DELETE FROM accounts WHERE account_key = 'be05_manual'"
```

## Cache modes and comparison

Switch both Spring instances off and recreate Nginx because the container
addresses change:

```powershell
$env:CATALOG_CACHE_ENABLED = "false"
docker compose -f infra/backend-local/compose.yaml up -d --force-recreate --wait spring spring-b nginx
```

Restore the local cache-on default:

```powershell
$env:CATALOG_CACHE_ENABLED = "true"
docker compose -f infra/backend-local/compose.yaml up -d --force-recreate --wait spring spring-b nginx
Remove-Item Env:CATALOG_CACHE_ENABLED
```

Inspect or remove only this cache entry:

```powershell
docker compose -f infra/backend-local/compose.yaml exec -T redis redis-cli TTL revealz:catalog:v1
docker compose -f infra/backend-local/compose.yaml exec -T redis redis-cli DEL revealz:catalog:v1
```

The small comparison script performs 20 direct warm-up requests per instance,
then records one sequential 100-request series. With `--label on`, it deletes
the cache key and records one cold miss separately. It appends to the selected
CSV, so use a fresh output path for a fresh experiment.

```powershell
python infra/backend-local/compare_catalog_cache.py --round 1 --label off --output docs/evidence/backend/catalog_cache_comparison.csv
python infra/backend-local/compare_catalog_cache.py --round 1 --label on --output docs/evidence/backend/catalog_cache_comparison.csv
```

Repeat the off/on pair with rounds 2 and 3 after switching the setting as
shown above. The recorded 2026-09-28 result and full reproduction steps are in
`docs/evidence/backend/catalog_cache_comparison.md`.

Run the BE04-A routing and rollback check without fault injection:

```powershell
./infra/backend-local/verify_local.ps1 -SkipFailureChecks
```

It waits for both Spring readiness checks, compares direct A/B responses,
sends ten catalog requests through Nginx, checks Node health, switches the
catalog to Node, then restores the two-instance upstream.

The preserved full BE02 failure check temporarily stops local services and
restores both Spring instances in `finally`:

```powershell
./infra/backend-local/verify_local.ps1
```

## Explicit rollback to Node

There is no automatic fallback. Recreate only Nginx with the catalog upstream
set to Node:

```powershell
$env:CATALOG_UPSTREAM = "node:8080"
docker compose -f infra/backend-local/compose.yaml up -d --force-recreate nginx
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18080/v1/shop/catalog
```

Restore the two-instance route explicitly:

```powershell
$env:CATALOG_UPSTREAM = "spring_catalog"
docker compose -f infra/backend-local/compose.yaml up -d --force-recreate nginx
Remove-Item Env:CATALOG_UPSTREAM
```

Stop the isolated stack without deleting its local database:

```powershell
docker compose -f infra/backend-local/compose.yaml down
```

Add `-v` only when intentionally discarding the backend local-only database.
Never restore an operating database dump into this stack.
