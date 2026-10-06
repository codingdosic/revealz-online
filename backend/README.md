# Revealz Spring backend

`backend/` is the active lobby, game API, authentication, ops, and Dedicated coordination runtime. The repository-root `docker-compose.yml` is the default single-VM stack. The former Node implementation is rollback-only under `legacy/node-lobby/`.

## Local compile and tests

Java 21 is required.

```powershell
cd backend
.\mvnw.cmd test
```

Database integration tests use Testcontainers and therefore also require Docker.

## Docker stack

Copy `.env.example` to the ignored root `.env`, fill secrets, and create the external PostgreSQL volume once for a new environment:

```powershell
docker volume create revealz_pgdata
docker compose up -d --build postgres redis lobby poller
```

Production must build a fixed tag before switching traffic. Do not use `latest` as the rollback reference. The active runtime data paths are root `.env`, `ops-data/`, `export/`, Docker volumes, and certificate volumes; none belongs under the legacy source directory.

## Persistence boundary

- JPA: accounts/tombstones, Google identities, refresh tokens, and patch-note CRUD/simple reads.
- JDBC: aggregate meta snapshots, JSONB deck/catalog/mailbox payloads, purchase and mailbox claim transactions, match-log set writes, ops search, and the PostgreSQL advisory identity lock.
- JPA and JDBC share the one configured `HikariDataSource` and Spring transaction. A service calls `flush()` before a JDBC snapshot must observe a JPA mutation.

Schema and reviewed migrations live in `backend/db/`; application startup does not apply them automatically.
