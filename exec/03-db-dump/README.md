# 3. DB 덤프

이 폴더에 MariaDB 덤프 최신본을 둔다. 파일명 규칙: `itda-YYYYMMDD.sql` (예: `itda-20260928.sql`).

| 항목 | 값 |
| --- | --- |
| DBMS | MariaDB 11.8.9 |
| 데이터베이스 | `itda` |
| 계정 | `DB_USERNAME` / `DB_PASSWORD` (환경 파일 — 저장소에 없음) |
| 문자셋 | utf8mb4 |
| 스키마 원본 | JPA 엔티티(`apps/backend/src/main/java/com/itda/**/domain`). prod는 `ddl-auto: validate`라 덤프의 스키마가 엔티티와 맞아야 기동한다 |
| ERD | `docs/product/erd.md` |

## 덤프 뜨기

```bash
# 로컬 compose의 db 컨테이너에서
docker compose exec -T db sh -c 'mariadb-dump -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" --single-transaction --routines --triggers "$MARIADB_DATABASE"' > exec/03-db-dump/itda-$(date +%Y%m%d).sql

# 외부 MariaDB(운영·팀 공용)에서
mariadb-dump -h <host> -P <port> -u <user> -p --single-transaction --routines --triggers itda > itda-YYYYMMDD.sql
```

## 복원하기

```bash
# 로컬 compose (db가 healthy 상태일 때)
docker compose up -d db
docker compose exec -T db sh -c 'mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE"' < exec/03-db-dump/itda-YYYYMMDD.sql

# 외부 MariaDB
mariadb -h <host> -P <port> -u <user> -p itda < itda-YYYYMMDD.sql
```

복원 후 backend를 기동하면 local 프로필은 `ddl-auto: update`로 부족한 컬럼을 보태고, prod는 `validate`만 한다.

## 시연 계정 준비

가입 인증 메일이 꺼진 환경(`MAIL_DISPATCH_ENABLED=false`)에서는 가입 후 인증 시각을 직접 채워야 로그인된다:

```sql
UPDATE users SET email_verified_at = NOW()
 WHERE username = '<아이디>' AND email_verified_at IS NULL;
```

로그인 조건은 `status = 'ACTIVE'`이고 `email_verified_at`이 비어 있지 않은 것.
