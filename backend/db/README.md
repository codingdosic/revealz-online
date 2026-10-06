# Spring 배포용 PostgreSQL SQL

Spring 배포가 소유하는 schema와 seed의 단일 운영 원본이다. 복귀용 Node 사본은 `legacy/node-lobby/meta`에 보존한다.

- `schema.sql`: `IF NOT EXISTS`와 호환 `ALTER`를 포함한 현재 전체 schema. 자동 부팅 DDL이 아니라 운영자가 변경 내용을 검토한 뒤 명시적으로 적용한다.
- `migrations/`: 기존 DB에 이미 적용했을 수 있는 과거 증분 SQL. 적용 이력을 확인하지 않고 반복 실행하지 않는다.
- `seed_shop.sql`: 상품과 catalog revision을 의도적으로 갱신할 때만 실행한다. Spring 시작 때 자동 실행하지 않는다.

후보 image에는 `/app/db`로 들어간다. 승인된 전환에서 schema 적용이 필요하다고 확인된 경우에만 PostgreSQL 컨테이너 안에서 다음처럼 실행한다.

```bash
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U revealz_meta -d revealz_meta < backend/db/schema.sql
```

적용 전 backup과 현재 schema 확인이 선행 조건이다. seed와 restore는 위 명령에 포함하지 않는다.
