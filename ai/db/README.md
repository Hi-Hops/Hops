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

- 3번은 예전 안내대로 `hops` 소유의 `hops_ai_runtime`이 이미 있을 때만 실행한다. 행 데이터와 인덱스는 그대로 두고 소유자만 `hops_ai_migrator`로 옮긴다. PostgreSQL 15 이전에 만든 DB처럼 `public` 스키마 소유자가 관리자로 남아 있으면 스키마 소유권도 옮겨, 03이 기존 `public` CREATE 권한을 거둘 수 있게 한다.
- 이미 테이블이 있으면 4번은 건너뛴다. 001만 적용된 DB는 실행기를 모두 멈춘 뒤 002를 한 번 적용한다.
- 승인 CASE 색인을 활성화할 때는 별도로 `ai/migrations/003_index_jobs.sql`과
  [04_index_privileges.sql](04_index_privileges.sql)을 migrator로 실행한다.
  런타임 계정에는 `ai_index_job`의 SELECT·INSERT·UPDATE만 추가하며 업무 DB 권한을 늘리지 않는다.
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

EC2의 AI 컨테이너는 RDS에 TLS로 접속한다(`sslmode=verify-full`, `/etc/hops/rds-global-bundle.pem`). 아래는 개발 RDS에 실제로 적용한 순서다. 실행과 결과 확인은 환경 담당자가 직접 한다. EC2에서 `docker`는 `sudo`로 실행한다.

1. **적용할 파일 꺼내기.** `~/Hops` 체크아웃은 바꾸지 않고 필요한 파일만 임시 폴더로 꺼낸다. `<커밋 또는 브랜치>`는 적용할 버전이다.

   ```bash
   git -C ~/Hops fetch origin <커밋 또는 브랜치>
   mkdir -p ~/hops85 && git -C ~/Hops archive FETCH_HEAD ai/db ai/migrations ai/src/hops_ai/persistence/check_runtime_db.py | tar -x -C ~/hops85
   ```

2. **현재 상태 확인(읽기 전용).** 앱 계정 점검을 먼저 실행하고, 마스터 계정으로 계정·소유자·권한을 조회한다. 같은 이름의 계정이 이미 있으면 01이 속성과 역할 상속을 검사해 넓은 권한이면 멈춘다.

   ```bash
   sudo docker exec -i hops-ai python - --business-dbname hops < ~/hops85/ai/src/hops_ai/persistence/check_runtime_db.py
   ```

   배포된 이미지에 점검 모듈이 들어간 뒤에는 `sudo docker exec hops-ai python -m hops_ai.persistence.check_runtime_db --business-dbname hops`로 실행해도 된다.

3. **RDS 스냅샷**을 만든다. 02의 테이블 소유자 변경은 잠깐 테이블 잠금을 잡으므로 진행 중인 AI 실행이 없을 때 한다.

4. **마스터 계정으로 01·02.** `postgres` DB에 접속해 비밀번호는 프롬프트로 입력한다.

   ```bash
   sudo docker run --rm -it -v ~/hops85/ai:/ai:ro -v ~/rds-global-bundle.pem:/ca.pem:ro postgres:18 \
     psql "host=<RDS 엔드포인트> port=5432 dbname=postgres user=<마스터 계정> sslmode=verify-full sslrootcert=/ca.pem"
   ```

   ```
   \set ON_ERROR_STOP on
   \i /ai/db/01_roles_and_database.sql
   \password hops_ai_migrator
   \c hops_ai_runtime
   \i /ai/db/02_adopt_existing_tables.sql
   ```

   앱 계정을 새로 만드는 경우에는 `\password hops_ai_app`도 실행한다. `hops_ai_runtime`이 처음 만들어진 경우에는 02 대신 다음 단계에서 마이그레이션을 적용한다. `hops_ai_migrator` 비밀번호는 비밀번호 관리자에 보관하며 앱 설정에는 넣지 않는다.

5. **마이그레이션 계정으로 03.** 테이블이 없으면 먼저 `\i /ai/migrations/001_run_ledger.sql`, `\i /ai/migrations/002_run_owner.sql`을 실행한다.

   ```bash
   sudo docker run --rm -it -v ~/hops85/ai:/ai:ro -v ~/rds-global-bundle.pem:/ca.pem:ro postgres:18 \
     psql "host=<RDS 엔드포인트> port=5432 dbname=hops_ai_runtime user=hops_ai_migrator sslmode=verify-full sslrootcert=/ca.pem"
   ```

   ```
   \i /ai/db/03_app_privileges.sql
   ```

6. **앱 연결 문자열.** `~/.config/hops/ai.env`의 `HOPS_AI_RUNTIME_DSN`이 앱 계정을 쓰는지 확인한다. 바꿨다면 AI 컨테이너를 다시 만든다(배포 또는 `deploy.sh`와 같은 `docker run` 옵션). 비밀번호의 특수문자는 URL 인코딩한다.

   ```
   HOPS_AI_RUNTIME_DSN=postgresql://hops_ai_app:<비밀번호>@<RDS 엔드포인트>:5432/hops_ai_runtime?sslmode=verify-full&sslrootcert=/etc/hops/rds-global-bundle.pem
   ```

7. **점검과 재시작 복구.** 처음 한 번은 쓰기 경로까지 확인하고, 재시작 후에도 세션 잠금을 다시 잡아 준비 상태가 되는지 본다.

   ```bash
   sudo docker exec -i hops-ai python - --business-dbname hops --write-check < ~/hops85/ai/src/hops_ai/persistence/check_runtime_db.py
   curl -s -o /dev/null -w 'readyz %{http_code}\n' http://127.0.0.1:8000/readyz
   sudo docker restart hops-ai && sleep 10 && curl -s -o /dev/null -w 'readyz %{http_code}\n' http://127.0.0.1:8000/readyz
   ```

8. 확인이 끝나면 `~/hops85`를 지운다.

RunLedger는 소유자 세션 잠금을 쓰므로 RDS Proxy나 트랜잭션 단위 연결 풀이 아니라 RDS 엔드포인트에 직접 접속한다.

RDS 마스터 계정은 슈퍼유저가 아니다. PostgreSQL 16부터는 역할을 만든 계정도 그 역할로 전환할 권한을 자동으로 갖지 않으므로, 01·02는 소유자 지정에 필요한 동안만 `hops_ai_migrator` 권한을 실행 계정에 부여하고 끝에서 해제한다. 실행한 관리자에게는 점검을 위해 `hops_ai_runtime` 접속 권한을 남긴다.

## 점검 명령

`python -m hops_ai.persistence.check_runtime_db`는 `HOPS_AI_RUNTIME_DSN`으로 접속해 항목마다 `OK`/`FAIL`을 출력한다. 하나라도 실패하면 종료 코드 1이다. 연결 문자열·비밀번호·예외 상세는 출력하지 않는다.

| 옵션 | 확인 |
|---|---|
| 없음 | 관리자 속성·역할 상속 없음, `ai_run_ledger` 허용/거부 권한(열 단위 포함), 런타임 DB의 원장 외 테이블·열·시퀀스 무권한, 모든 스키마 생성 거부, `public` 외 스키마 사용(USAGE) 거부, 실제 DELETE·테이블 생성 거부(트랜잭션 안에서 시도 후 되돌림), DB CREATE·TEMPORARY 거부, 세션 잠금 사용 가능. 데이터를 바꾸지 않는다 |
| `--business-dbname hops` | 같은 계정으로 업무 DB의 테이블·열·시퀀스 권한과 스키마 생성 권한이 없고 `hops_knowledge` 권한도 없는지. 접속 자체가 거부되면 통과 |
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

RDS 현재 상태를 재현한 경우도 로컬에서 확인했다. 슈퍼유저가 아닌 마스터가 DB·테이블을 소유하고, 앱 계정과 실행 기록 6건이 있으며, 업무 DB에는 `PUBLIC` 접속 권한이 없는 상태다. 현재 점검 명령 기준으로 적용 전 18/19(TEMPORARY만 실패), 01·02·03 적용과 재실행 뒤 `--write-check` 포함 20/20 통과였다. 실행 기록 보존과 마스터의 임시 역할 권한 해제도 확인했다.

앱 계정에 넘치는 권한을 미리 준 경우도 확인했다. 런타임 DB CREATE·TEMPORARY, `public`·다른 스키마 CREATE, 원장 외 테이블·열·시퀀스 권한, 원장 DELETE·TRUNCATE·열 REFERENCES 등이다. 01·03만 다시 실행해도 CONNECT와 원장 SELECT·INSERT·UPDATE만 남고 열 단위 권한은 0건이 되어 점검 22/22 통과였다. 점검 명령이 업무 DB·런타임 DB의 테이블·열·시퀀스·스키마 권한 21가지를 하나씩 부여했을 때 모두 실패로 보고하는 것도 확인했다.

### 개발 RDS 적용 (2026-11-04)

| 항목 | 결과 |
|---|---|
| 대상 | 개발 RDS PostgreSQL 18.3, EC2의 AI 컨테이너(real 모드), `sslmode=verify-full` TLS 1.3 |
| 적용 전 | `hops_ai_app`은 이미 최소 권한이었음. DB·`ai_run_ledger` 소유자는 마스터 `postgres`, `PUBLIC`에 런타임 DB TEMPORARY가 남아 점검 16/17(적용 당시 점검 명령 `abe81cc` 기준). 업무 DB `hops`는 Spring 계정(`hops_spring_migrate`·`hops_spring_app`)만 접속 가능 |
| 적용 | 위 4–5단계(01 → `\password hops_ai_migrator` → 02 → 03). 오류 없음 |
| 적용 후 | DB·테이블 소유자 `hops_ai_migrator`, `PUBLIC` 권한 제거, 앱 권한 SELECT·INSERT·UPDATE 그대로. 앱 연결 문자열은 바꾸지 않음 |
| 점검(`--business-dbname hops --write-check`) | 18/18 통과(적용 당시 점검 명령 `abe81cc` 기준, 업무 DB는 접속 거부). 이후 리뷰 반영으로 MAINTAIN·원장 외 권한·전체 스키마 검사가 추가돼 현재 명령은 같은 옵션에서 20항목이다 |
| 현재 점검 명령(`33ef347`)으로 재점검 | `--business-dbname hops`(쓰기 검사 제외) 19/19 통과. 추가된 MAINTAIN·원장 외 테이블·열·시퀀스·전체 스키마 검사 포함, 업무 DB는 접속 거부 |
| 준비 상태 | 적용 직후 `/readyz` 200, `docker restart hops-ai` 10초 뒤 `/readyz` 200(세션 잠금 재확보·재기동 복구) |

확인하지 않은 것: `hops_knowledge` 검색 권한(#142에서 부여·점검), 자격 증명 교체 절차, RDS 장애 조치·네트워크 단절 시 잠금 해제 지연.
