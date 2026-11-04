# AI 런타임 DB 계정·권한 준비

AI 서버의 RunLedger는 Spring 업무 DB(`hops`)와 같은 PostgreSQL 인스턴스의 별도 DB `hops_ai_runtime`을 쓴다. 이 폴더는 그 DB와 계정을 최소 권한으로 준비하는 SQL과 절차다. 테이블 정의는 [`ai/migrations`](../migrations)에 있고, 이 폴더의 SQL은 계정·소유자·권한만 다룬다.

이 파일들은 `ai/migrations` 밖에 둔다. 배포 스크립트는 `ai/migrations`의 Git tree가 검증 기준과 다르면 배포를 멈추므로, 계정 설정 변경이 배포 기준을 흔들지 않게 분리했다. 앱 시작이나 배포는 이 SQL을 자동 실행하지 않는다.

## 계정

| 계정 | 용도 | 권한 |
|---|---|---|
| `hops_ai_migrator` | `hops_ai_runtime` DB와 테이블 소유자. 마이그레이션·권한 부여에만 사용 | DB·스키마 소유. 관리자 속성 없음 |
| `hops_ai_app` | AI 서버 실행(`HOPS_AI_RUNTIME_DSN`) | `hops_ai_runtime` CONNECT, `public` USAGE, `ai_run_ledger`의 SELECT·INSERT·UPDATE |

`hops_ai_app`은 다음을 할 수 없다.
- `ai_run_ledger` DELETE·TRUNCATE
- 테이블·스키마 생성, 임시 테이블
- 다른 역할 권한 상속
- 업무 DB 테이블과 `hops_knowledge` 접근

업무 DB에 접속하는 것 자체는 PostgreSQL 기본값(`PUBLIC` CONNECT) 그대로 둔다. 검색 연동(#142) 때 `hops_knowledge`만 허용해야 하므로, 접속을 막는 대신 테이블·스키마 권한이 없는지 확인한다.

## 파일과 실행 순서

| 순서 | 파일 | 실행 계정 | 접속 DB |
|---|---|---|---|
| 1 | [01_roles_and_database.sql](01_roles_and_database.sql) | 관리자(로컬 `hops`, RDS 마스터) | `hops` |
| 2 | 각 계정 비밀번호 설정(`\password`) | 관리자 | `hops` |
| 3 | [02_adopt_existing_tables.sql](02_adopt_existing_tables.sql) | 관리자 | `hops_ai_runtime` | 
| 4 | `ai/migrations/001`, `002` | `hops_ai_migrator` | `hops_ai_runtime` |
| 5 | [03_app_privileges.sql](03_app_privileges.sql) | `hops_ai_migrator` | `hops_ai_runtime` |
| 6 | 점검 명령 | `hops_ai_app` | `hops_ai_runtime` |

- 3번은 예전 안내대로 `hops` 소유의 `hops_ai_runtime`이 이미 있을 때만 실행한다. 행 데이터와 인덱스는 그대로 두고 소유자만 `hops_ai_migrator`로 옮긴다.
- 이미 테이블이 있으면 4번은 건너뛴다. 001만 적용된 DB는 실행기를 모두 멈춘 뒤 002를 한 번 적용한다.
- 01·02·03은 여러 번 실행해도 된다.
- 01은 같은 이름의 계정에 관리자 속성이 있거나, 앱 계정이 다른 역할에 속해 있으면 고치지 않고 멈춘다.
- 새 마이그레이션이 테이블을 추가하면 03에 필요한 최소 권한을 함께 추가하고 다시 실행한다.

비밀번호는 SQL 파일·셸 명령·저장소에 넣지 않는다. `psql`의 `\password <계정>`은 입력값을 암호화해 보내고 기록에 남기지 않는다.

## 로컬 Compose

Compose의 PostgreSQL은 컨테이너 안 소켓 접속을 비밀번호 없이 허용하므로, 관리자·마이그레이션 단계는 `docker compose exec`로 실행한다. AI 컨테이너는 네트워크로 접속하므로 앱 계정 비밀번호만 정하면 된다.

```bash
docker compose exec -T postgres psql -U hops -d hops -v ON_ERROR_STOP=1 < ai/db/01_roles_and_database.sql
docker compose exec postgres psql -U hops -d hops -c '\password hops_ai_app'
```

예전 안내로 `hops_ai_runtime`을 이미 만들었다면 다음 명령으로 기존 테이블을 넘겨받는다. 처음 만드는 경우에는 마이그레이션을 적용한다.

```bash
# 이미 있는 경우
docker compose exec -T postgres psql -U hops -d hops_ai_runtime -v ON_ERROR_STOP=1 < ai/db/02_adopt_existing_tables.sql
```

```bash
# 처음 만드는 경우
docker compose exec -T postgres psql -U hops_ai_migrator -d hops_ai_runtime -v ON_ERROR_STOP=1 < ai/migrations/001_run_ledger.sql
docker compose exec -T postgres psql -U hops_ai_migrator -d hops_ai_runtime -v ON_ERROR_STOP=1 < ai/migrations/002_run_owner.sql
```

```bash
docker compose exec -T postgres psql -U hops_ai_migrator -d hops_ai_runtime -v ON_ERROR_STOP=1 < ai/db/03_app_privileges.sql
```

`ai/.env`의 연결 문자열은 `HOPS_AI_RUNTIME_DSN=postgresql://hops_ai_app:<앱 계정 비밀번호>@postgres:5432/hops_ai_runtime`이다. AI 컨테이너를 다시 만든 뒤 점검한다.

```bash
docker compose up -d --force-recreate ai
docker compose exec ai python -m hops_ai.persistence.check_runtime_db --business-dbname hops
```

## EC2·RDS

EC2의 AI 컨테이너는 RDS에 TLS로 접속한다. 아래는 적용 절차이며, 실행·결과 확인은 환경 담당자가 직접 한다. 적용 전에 RDS 스냅샷을 만들고, 기존 `hops_ai_runtime`이 있는지 먼저 확인한다.

1. EC2에서 임시 psql 컨테이너로 RDS 마스터 계정에 접속한다. 비밀번호는 프롬프트에 입력한다.

   ```bash
   docker run --rm -it -v ~/Hops/ai:/ai:ro -v ~/rds-global-bundle.pem:/ca.pem:ro postgres:18 \
     psql "host=<RDS 엔드포인트> port=5432 dbname=hops user=<마스터 계정> sslmode=verify-full sslrootcert=/ca.pem"
   ```

2. psql 안에서 실행한다.

   ```
   SELECT datname, pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'hops_ai_runtime';
   \i /ai/db/01_roles_and_database.sql
   \password hops_ai_migrator
   \password hops_ai_app
   ```

3. `hops_ai_runtime`이 이미 있었다면 `\c hops_ai_runtime` 후 `\i /ai/db/02_adopt_existing_tables.sql`을 실행한다.
4. 같은 방식으로 `user=hops_ai_migrator dbname=hops_ai_runtime`에 접속한다. 테이블이 없으면 `\i /ai/migrations/001_run_ledger.sql`, `\i /ai/migrations/002_run_owner.sql`을 실행한 뒤 `\i /ai/db/03_app_privileges.sql`을 실행한다.
5. `~/.config/hops/ai.env`의 `HOPS_AI_RUNTIME_DSN`을 앱 계정으로 바꾼다. 비밀번호에 특수문자가 있으면 URL 인코딩한다.

   ```
   HOPS_AI_RUNTIME_DSN=postgresql://hops_ai_app:<비밀번호>@<RDS 엔드포인트>:5432/hops_ai_runtime?sslmode=verify-full&sslrootcert=/etc/hops/rds-global-bundle.pem
   ```

6. AI 컨테이너를 다시 만든다(배포 또는 `deploy.sh`와 같은 `docker run` 옵션). 그다음 점검하고, 처음 한 번은 쓰기 경로까지 확인한다.

   ```bash
   docker exec hops-ai python -m hops_ai.persistence.check_runtime_db --business-dbname hops --write-check
   curl -i http://127.0.0.1:8000/readyz
   ```

7. 세션 잠금과 재시작 복구는 `docker restart hops-ai` 뒤 `/readyz`가 다시 200이 되는지로 확인한다. RunLedger는 소유자 세션 잠금을 쓰므로 RDS Proxy나 트랜잭션 단위 연결 풀이 아니라 RDS 엔드포인트에 직접 접속한다.

RDS 마스터 계정은 슈퍼유저가 아니다. PostgreSQL 16부터는 역할을 만든 계정도 그 역할로 전환할 권한을 자동으로 갖지 않으므로, 01·02는 소유자 지정에 필요한 동안만 `hops_ai_migrator` 권한을 실행 계정에 부여하고 끝에서 해제한다. 실행한 관리자에게는 점검을 위해 `hops_ai_runtime` 접속 권한을 남긴다.

## 점검 명령

`python -m hops_ai.persistence.check_runtime_db`는 `HOPS_AI_RUNTIME_DSN`으로 접속해 항목마다 `OK`/`FAIL`을 출력한다. 하나라도 실패하면 종료 코드 1이다. 연결 문자열·비밀번호·예외 상세는 출력하지 않는다.

| 옵션 | 확인 |
|---|---|
| 없음 | 관리자 속성·역할 상속 없음, `ai_run_ledger` 허용/거부 권한, 실제 DELETE·테이블 생성 거부(트랜잭션 안에서 시도 후 되돌림), DB CREATE·TEMPORARY 거부, 세션 잠금 사용 가능. 데이터를 바꾸지 않는다 |
| `--business-dbname hops` | 같은 계정으로 업무 DB의 테이블·시퀀스 권한과 스키마 생성 권한이 없고 `hops_knowledge` 권한도 없는지. 접속 자체가 거부되면 통과 |
| `--write-check` | 점검용 run 한 건을 접수·시작·완료하고 새 세션에서 조회한다. 이 행은 남으며 24시간 뒤 일반 만료로 상세가 지워진다 |

## 검색 연동(#142) 때

[ERD](../../docs/ERD.md)의 결정대로 AI 계정에는 `hops_knowledge` 스키마의 조각 DML과 FK 검사용 REFERENCES만 준다. 업무 원본 쓰기 권한은 주지 않는다. 이 권한은 업무 DB의 Flyway 소유자가 부여해야 하므로 Spring 마이그레이션 또는 별도 SQL로 리뷰한 뒤 적용한다. 적용 후 점검 명령의 `hops_knowledge 스키마 권한 없음` 기대값을 새 권한 기준으로 바꾼다.

## 검증 기록

로컬 일회용 PostgreSQL 18(pgvector 0.8.7, Compose와 같은 이미지)에서 업무 DB에 Spring 마이그레이션 V1–V22와 시드를 적용한 뒤, 아래 세 경우를 확인했다.

| 경우 | 설명 |
|---|---|
| 처음 만드는 경우 | 새 `hops_ai_runtime` |
| 넘겨받는 경우 | 예전 방식대로 `hops` 소유이고 실행 기록이 있는 DB |
| RDS 관리자와 같은 경우 | 슈퍼유저가 아닌 `CREATEROLE·CREATEDB` 관리자로 진행 |

| 항목 | 결과 |
|---|---|
| 01·03 두 번 실행 | 오류 없이 같은 결과 |
| 점검 명령(`--business-dbname hops --write-check`) | 20/20 통과 |
| 앱 계정으로 업무 테이블 SELECT·UPDATE | 거부 |
| 앱 계정으로 중단된 run 복구 | 재기동 시 `UNKNOWN`으로 정리 |
| 앱 계정으로 AI 앱 기동 | `/readyz` 200 |
| 넘겨받은 기존 실행 기록 | 그대로 보존 |
| 업무 DB 22개 테이블의 행 지문 | 전후 동일 |
| 일부러 DELETE 권한을 준 경우 | 점검 명령이 FAIL과 종료 코드 1로 잡아냄 |

확인하지 않은 것: 실제 EC2·RDS의 네트워크·TLS 인증서 검증·자격 증명 관리, RDS에서의 세션 잠금과 재시작 복구, 공용 개발 DB에 대한 적용.
