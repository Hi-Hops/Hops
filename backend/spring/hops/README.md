# Hops Spring 백엔드

Frontend·Spring·AI·PostgreSQL을 Docker로 함께 실행하려면 루트 README의 [Docker Compose 실행 안내](../../../README.md#docker-compose로-실행)를 따릅니다. 아래 직접 설치 절차는 PC에서 Spring을 실행할 때 사용합니다.

Docker 설정은 이 폴더의 [.env.example](.env.example)을 `.env`로 복사해 관리합니다. PostgreSQL 설정도 같은 파일에 두고 Compose가 `POSTGRES_*`를 DB 컨테이너와 Spring datasource에 각각 매핑합니다. 필수 값은 `POSTGRES_PASSWORD`와 `HOPS_JWT_SECRET`이며 기존 개인 파일·DB 값은 유지합니다. 루트 Compose가 서비스별 파일 세 개를 자동으로 읽으므로 저장소 루트에서 `docker compose up -d`로 실행합니다.

Gradle·IntelliJ 직접 실행은 `.env`를 자동으로 읽지 않습니다. 아래 기존 `application-db.local.properties`와 프로세스 환경변수 설정 방식을 따릅니다.

> **PRD 1.44 전환:** 아래 부서·시드·SSE·API 내용은 현재 구현 설명이다. 새 목표의 시스템별 주담당자 배정·처리·확인·사례 승인·검색 반영은 이 문서 변경으로 구현되지 않았다. [PRD](../../../docs/PRD.md), [API](../../../docs/API.md), [데이터](../../../docs/DATA.md)를 기준으로 별도 계약·migration·구현이 필요하다.

Spring Boot 프로젝트 루트는 `backend/spring/hops/`입니다. IntelliJ에서는 해당 디렉터리를 Gradle 프로젝트로 엽니다. 빌드·테스트·실행 명령은 이 디렉터리에서 실행합니다.

PRD 1.44 신규 업무 구현 전에는 [공통 구현 계약](../../../specs/contracts/README.md)에서 데이터·전송·상태·AI 경계를 함께 검토합니다. 기존 `/api` 구현과 제안된 `hops-system-1`을 구분하며, 물리 ERD·엔티티 매핑은 이슈 #66과 맞춥니다.

## 패키지 구성

`com.hihops.hops` 아래에 `auth`, `user`, `department`, `itsystem`, `inquiry`, `ticket`, `agent`, `notification`, `knowledge`, `investigation`, `global` 패키지를 구성했습니다. 아직 클래스가 없는 패키지에는 `package-info.java`로 역할을 적어 두었고, 클래스가 생기면 그 폴더의 `package-info.java`는 삭제합니다. 현재 공개 API 구현은 아래 [인증](#인증)의 로그인·JWT 발급·내 정보·Refresh 회전·로그아웃, [조회 API](#조회-api)의 시스템 목록·내 문의·대화·변경 요청 결과 확인, [부서 SSE](#spring-sse-공통-기반)입니다.

| 패키지 | 역할 |
|---|---|
| `auth` / `user` | JWT 로그인·토큰 발급과 인증 수명 관리 / 사번·소속 부서 등 사용자 정보 |
| `department` | 부서·소속·활성 여부·R&R |
| `itsystem` | 문의를 받는 업무시스템·시스템 기능과 시스템별 주담당자 |
| `inquiry` | 문의·대화·내용 확인·정정, 문의 문제와 표준 요청서 |
| `ticket` | 티켓 저장·배정·처리, 변경 이력·배정 이력·처리 기록 |
| `agent` | Python 실행 요청·진행·결과 통신, AI 실행·실행 단계 기록 |
| `notification` | 서비스 내부 알림함 |
| `knowledge` | 지식 문서·문서 버전·검색 조각(pgvector)과 해결 사례 |
| `investigation` | 읽기 전용 진단 조회의 검증·실행 경계 |
| `global` | 공통 설정·JWT 검증·접근 제어 기반·예외 처리, 공통 Entity 부모(`entity`)·중복 요청 방지 기록(`idempotency`) |

업무 기능은 `controller`, `dto`, `service`, `domain`, `repository`로 구분합니다. `agent`는 `client`, `dto`, `service`와 실행 기록용 `domain`, `repository`, `investigation`은 `controller`, `dto`, `service`, `client`로 구성합니다. 독립 `ownership`은 현재 추가하지 않습니다. 실제 서비스 간 입출력·인증·오류 계약은 [프로젝트 개발 안내](../../../README.md#첫-작업)에서 연결하는 API 설계와 [Agent 공통 계약](../../../contracts/agents/README.md)에서 관련 담당자와 합의한 뒤 구현합니다.

## JWT 인증과 REST·SSE 연동 설계

아래는 [PRD](../../../docs/PRD.md)와 [API 설계](../../../docs/API.md)에 따른 구현 목표입니다. 로그인·JWT 발급·검증과 `/api/v2/auth/me`, `DEPARTMENT_TICKETS` 대상의 브라우저 SSE, Refresh 쿠키 발급·회전·재사용 검출·로그아웃과 세션 종료 시 SSE 정리, 시스템 목록·내 문의·대화 조회와 변경 요청 결과 확인(`/operations`)은 구현했습니다. 문의·메시지 저장 등 업무 변경 REST·신규 SSE 대상 연결은 남아 있습니다. JWT는 Spring이 발급하고 REST·SSE 요청에서 직접 검증합니다. 프론트의 현재 시스템 계약 검사와 전환 전 실서버 검사 이력은 [공통 계약 11절](../../../specs/contracts/WIRE_CONTRACT.md#11-담당별-구현과-검증-상태)에서 구분합니다.

- `auth`는 자체 계정 로그인과 JWT 발급을 담당하고, `user`·`department`·역할 정보에서 사번·소속 부서·권한 문맥을 구성합니다. `global/security`에서 JWT를 검증하고 업무별 접근 권한은 해당 서비스에서 확인합니다. 클레임·토큰 수명·갱신/폐기·보관/전달·키 관리 정책은 API 설계를 기준으로 합의합니다.
- Spring은 문의 생성·메시지 전송·정정을 REST로 받고 `Idempotency-Key`, 인증 주체, 문의 버전과 대상 권한을 검증합니다. `inquiry`·`ticket`이 DB 저장을 확정한 뒤 저장 결과를 반환하며 HTTP 응답 유실에도 같은 업무가 중복 반영되지 않게 합니다.
- React는 `GET /api/v2/realtime/events?targetType={type}&targetId={id}`로 Spring SSE에 직접 연결합니다. Spring은 연결마다 JWT와 현재 소유자·부서·역할을 검사하고 `agent.progress`, `answer.delta`, `message.stored`, `state.changed`를 허용된 대상에만 전달합니다.
- `agent`는 Python HTTP 실행 요청과 내부 SSE 진행·결과 수신을 담당합니다. 공개 가능한 진행·답변 조각은 Spring이 검증해 React SSE로 전달하고 최종 메시지·업무 변경은 DB 저장 결과로 확정합니다. heartbeat·`Last-Event-ID`·`resync.required`·REST 복구의 상세 계약은 API 설계를 따릅니다.

## 처음 설치 (Mac · Windows)

pull 받은 뒤 아래 순서대로 하면 같은 환경에서 실행됩니다.

**직접 할 일과 자동으로 되는 일**

git에는 "DB를 만드는 방법(SQL 파일)"만 올라가고, DB 자체는 각자 PC에 만듭니다. 설명서(SQL 파일)는 공유하고, 방(DB)은 각자 준비하는 구조입니다.

| 무엇 | 누가 | 언제 |
|---|---|---|
| PostgreSQL·pgvector·Redis 설치와 실행 | 직접 (아래 2~4번) | 처음 한 번 |
| `hops` 계정과 빈 DB `hops`·`hops_test` | 직접 (아래 5번) | 처음 한 번 |
| 접속 정보 파일 `application-db.local.properties` | 직접 (아래 6번) | 처음 한 번 |
| DB 안의 테이블 (`db/migration/V*.sql`) | Flyway 자동 | 서버·테스트 시작할 때 |
| 가상 데이터 (`db/seed`, `hops` DB만) | Flyway 자동 | 서버 시작할 때 |
| 나중에 추가된 `V2`·`V3`… | Flyway 자동 (아직 안 한 것만) | pull 뒤 서버 시작할 때 |

- Flyway는 **빈 DB 안에 테이블을 채울 뿐, DB 자체는 만들지 않습니다.** 5번을 건너뛰면 `database "hops" does not exist` 오류가 납니다.
- Flyway는 어디까지 실행했는지 DB의 `flyway_schema_history` 표에 적어 두고, 다음 시작 때 그 뒤의 파일만 실행합니다.
- 테이블을 바꿀 때는 이미 실행된 `V*.sql`을 고치지 않고 새 번호 파일을 추가합니다. 고치면 `checksum mismatch` 오류가 납니다.
- Redis는 테이블이 없어 설치·실행만 하면 됩니다.

1. **JDK 17 이상** 설치 (Gradle을 실행하는 용도). 프로젝트가 쓰는 JDK 21은 Gradle이 없으면 자동으로 내려받습니다 (`settings.gradle.kts`의 foojay 플러그인).
2. **PostgreSQL 18** 설치 (`uuidv7()` 사용을 위해 18 이상이 필요합니다).
   - Mac: `brew install postgresql@18` 후 `brew services start postgresql@18`
   - Windows: [PostgreSQL 공식 설치 파일](https://www.postgresql.org/download/windows/)로 18 버전을 설치합니다. 설치 중 정한 `postgres` 비밀번호를 기억해 둡니다.
3. **pgvector 설치** (검색 조각 `knowledge_chunk.embedding`의 `vector` 타입). 없으면 서버가 시작되지 않습니다.
   - Mac: `brew install pgvector`
   - Windows: [pgvector 공식 안내의 Windows 설치](https://github.com/pgvector/pgvector#windows)를 따릅니다.
4. **Redis 설치·실행** (로그인 세션·Refresh 토큰 저장소). 기본 주소 `localhost:6379`를 그대로 쓰므로 설정 파일은 필요 없습니다.
   - Mac: `brew install redis` 후 `brew services start redis`
   - Windows: Redis는 공식 Windows 버전이 없어 WSL(Ubuntu)에서 `sudo apt install redis-server` 후 `sudo service redis-server start`로 실행합니다. Docker Desktop을 쓴다면 `docker run -d --name hops-redis -p 6379:6379 redis:8`도 됩니다.
   - 확인: `redis-cli ping` → `PONG`, `redis-server --version`이 **6.0 이상**이어야 합니다(Refresh 회전 스크립트가 `KEEPTTL` 사용). 개발은 0번 DB, 테스트는 15번 DB를 써서 서로 섞이지 않습니다.
5. **DB 계정과 DB 생성** (Mac은 `psql postgres`, Windows는 `psql -U postgres`로 접속)
   ```sql
   CREATE ROLE hops WITH LOGIN PASSWORD '내_비밀번호';
   CREATE DATABASE hops OWNER hops;
   CREATE DATABASE hops_test OWNER hops; -- 테스트 전용
   -- pgvector는 trusted 확장이 아니라 슈퍼유저만 켤 수 있다. 지금 접속한 슈퍼유저로 두 DB에 한 번씩 켠다
   \c hops
   CREATE EXTENSION IF NOT EXISTS vector;
   \c hops_test
   CREATE EXTENSION IF NOT EXISTS vector;
   ```
6. **접속 정보 파일 만들기** (`src/main/resources`에서)
   - Mac: `cp application-db.local.properties.example application-db.local.properties`
   - Windows: `Copy-Item application-db.local.properties.example application-db.local.properties`
   - 만든 파일에 5번의 비밀번호를 넣습니다. 이 파일은 git에 올라가지 않습니다.
7. **실행**
   - Mac: `./gradlew bootRun`
   - Windows(PowerShell): `.\gradlew.bat bootRun`
   - 기본 프로필이 `db`라서 프로필을 따로 지정하지 않아도 로컬 PostgreSQL에 연결합니다.
   - 처음 실행하면 테이블과 아래 "가상 데이터"가 자동으로 들어갑니다.

## 이미 설치한 팀원이 pull 받은 뒤 할 일

"처음 설치"를 이미 마쳤다면 pgvector와 Redis만 한 번 준비합니다. 위 "처음 설치" 3번(pgvector)·4번(Redis)으로 설치하고, 5번의 `\c hops`부터 `CREATE EXTENSION` 네 줄을 슈퍼유저로 실행합니다. 그리고 아래 1번처럼 접속 정보 파일에 한 줄을 추가합니다.

1. `application-db.local.properties`에 `hops.auth.refresh-cookie.secure=false` 한 줄을 추가합니다(예시 파일 참고). 없으면 Refresh 쿠키에 `Secure`가 붙어 Safari 등에서 http 로그인 쿠키가 저장되지 않습니다.
2. `git pull` 후 위 7번처럼 실행합니다. 이전에 `SPRING_PROFILES_ACTIVE=local`로 실행했다면 빼고 실행합니다 (`local` 프로필은 없어졌습니다).
3. 로그에 `Successfully applied ... migration`이 보이면 새 테이블·데이터가 적용된 것입니다.
4. `Port 8080 was already in use`가 나오면 이미 켜진 서버를 끄고 다시 실행합니다.
5. `extension "vector" is not available` 또는 `permission denied to create extension "vector"`가 나오면 위 "처음 설치" 3번(pgvector 설치)과 5번의 `CREATE EXTENSION` 두 줄을 한 번 실행합니다. 검색 조각 테이블(V19)부터 필요합니다.
6. `Validate failed`·`checksum mismatch` 오류가 나면 이미 적용된 `V*.sql` 파일이 바뀐 것입니다. 파일을 되돌리거나 아래 "처음 상태로 되돌리기"로 DB를 새로 만듭니다.

## 가상 데이터 (로컬 개발용)

문의 목록·상세·대화 조회를 위한 수동 시연 데이터는 [시연 seed 안내](src/main/resources/db/demo/README.md)를 참고합니다. 기본 개발용 회원·시스템을 먼저 준비해야 하며 Flyway가 자동으로 실행하지 않습니다.

### 관리자 회원 등록 API

`POST /api/v2/auth/register`는 현재 DB에서 `BIZ_ADMIN` 권한이 있는 사용자가 일반 직원을 등록하는 API다.
먼저 `POST /api/v2/auth/login`으로 로그인하고 Swagger의 **Authorize**에 응답의 `accessToken`을 넣는다.
최초 관리자 계정과 부서는 사전에 별도 절차로 준비해야 하며 이 API는 관리자 권한을 부여하지 않는다.
요청의 `departmentId`는 DB에 존재하고 삭제되지 않은 부서 UUID여야 한다. 부서 조회 API는 아직 없다.

```json
{
  "loginId": "employee.test",
  "employeeNo": "TEST-001",
  "password": "example-only-password",
  "name": "테스트 직원",
  "departmentId": "00000000-0000-0000-0000-000000000001"
}
```

위 값은 형식 예시이며 실제 사용 시 준비된 부서 ID를 입력한다. 성공 시 `201`과 `memberId`를 반환한다.
빈 필드·없는 부서·UTF-8 기준 72바이트를 초과하는 비밀번호는 `400`, 중복 ID·사번은 `409`,
미인증은 `401`, 현재 관리자 권한이 없거나 토큰 발급 후 소속·권한이 변경되면 `403`이다. 비밀번호는 BCrypt로 저장한다.
직원에게 계정을 안전하게 전달하는 방법과 초기 비밀번호 변경 기능은 별도 준비가 필요하다.


`src/main/resources/db/seed/`의 `R__seed_dev_department_member.sql`(부서·회원·역할)과 `R__seed_dev_it_system.sql`(시스템·기능)에 있습니다. 실제 회사·사람이 아닌 가상 데이터이며 **db 프로필에서만** 들어갑니다. 테스트 DB(`hops_test`)와 운영에는 넣지 않습니다.

- 부서 11개: IT 처리 부서 8개(`is_routable=true`) + 요청자 소속 부서 3개(영업팀·인사팀·경영지원팀)
- 회원 20명, **모든 계정 비밀번호 `hops1234!`**
- ID는 고정값이라 모든 PC에서 같습니다 (예: `requester` 회원 ID `a0000000-0000-7000-8000-000000000001`). 전체 목록은 SQL 파일에 있습니다.

| 로그인 ID | 사번 | 소속 부서 | 역할 | 용도 |
|---|---|---|---|---|
| `requester` | 20260001 | 영업팀 | 없음 | 일반 직원(문의자) |
| `network` | 20260002 | 네트워크 | 없음 | IT 담당자 |
| `bizadmin` | 20260003 | 경영지원팀 | `BIZ_ADMIN` | 업무 관리자 |
| `opsadmin` | 20260004 | 경영지원팀 | `AGENT_OPS_ADMIN` | 에이전트 운영 관리자 |
| `20260020` | 20260020 | 경영지원팀 | `BIZ_ADMIN`, `AGENT_OPS_ADMIN` | 역할 두 개 확인용 |
| `20260005`~`20260019` (15명) | 로그인 ID와 같음 | 처리 부서별 담당자·일반 직원 | 없음 | 부서별 테스트 |

시스템 3개와 기능 10개의 ID는 [데이터 저장 계약 2절](../../../docs/DATA.md#2-고정-카탈로그) 값과 같습니다(프론트 Mock·AI 테스트와 같은 ID). 기능 코드는 DATA.md에 없어 seed 파일에서 정했습니다.

| 시스템 | 코드 | 기능 | 주담당자 |
|---|---|---|---|
| 전자결재 | `ELECTRONIC_APPROVAL` | 문서 작성·문서 조회·결재 상태·첨부 열람 | `network` |
| 공통 로그인 | `COMMON_LOGIN` | 로그인·SSO·계정 접근·권한 신청 | `network` |
| 파일 공유 | `FILE_SHARING` | 파일 열람·파일 공유·접근 권한 | `20260010` |

- `network`로 로그인하면 `primarySystemIds`에 시스템 2개가 담깁니다(담당자 화면 확인용).

- 이 계정으로 `POST /api/v2/auth/login`에 로그인할 수 있습니다(아래 [인증](#인증)). DB에서 직접 보려면:
  ```bash
  psql -U hops -d hops -c "SELECT login_id, employee_no, name FROM member;"
  ```
- seed 파일을 수정하면 다음 실행 때 다시 실행되지만, **이미 있는 행(같은 ID)은 바꾸지 않고 건너뜁니다.** 기존 행의 값을 바꾸려면 아래처럼 DB를 새로 만듭니다.

### 처음 상태로 되돌리기

테스트하며 바꾼 데이터를 지우고 처음 상태로 돌아갑니다. 로컬 `hops` DB의 모든 데이터가 지워집니다. 서버를 끈 뒤 실행합니다 (Mac은 `psql postgres`, Windows는 `psql -U postgres`로 접속).

```sql
DROP DATABASE hops;
CREATE DATABASE hops OWNER hops;
-- DB를 지우면 확장도 함께 지워지므로 다시 켠다
\c hops
CREATE EXTENSION IF NOT EXISTS vector;
```

다시 실행하면 테이블과 가상 데이터가 새로 들어갑니다.

## DB 설정

영속성은 Spring Data JPA와 PostgreSQL을 사용합니다. 로그인 등 기능이 DB를 쓰므로 DB 없이 서버를 실행하는 모드는 두지 않고, 기본 프로필을 `db`로 둡니다. `db` 프로필은 `application-db.local.properties`(git 제외)에서 접속 정보를 읽습니다. 실제 접속 정보는 저장소에 기록하지 않습니다. 아래 표준 Spring 환경변수를 설정하면 파일 값보다 우선합니다.

| 환경변수 | 값 |
|---|---|
| `SPRING_DATASOURCE_URL` | 사용할 PostgreSQL의 JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 |

- 테이블 생성·변경은 **Flyway**로 관리합니다. `src/main/resources/db/migration`에 `V1__설명.sql`처럼 버전 붙은 SQL을 추가하면 서버 시작 시 아직 적용하지 않은 것만 실행합니다. 이미 적용된 파일은 고치지 않고 새 버전 파일을 추가합니다.
- EC2 개발 서버는 Flyway를 실행하지 않습니다(`SPRING_FLYWAY_ENABLED=false`). 새 `V*.sql`은 배포 담당자가 따로 적용해야 배포가 진행되므로 PR 본문에 알리고, 절차는 [EC2 배포 문서의 마이그레이션 경계](../../../deploy/ec2/README.md#마이그레이션-경계)를 따릅니다.
- 로컬 개발용 가상 데이터는 `db/seed`에 둡니다. `db` 프로필은 `db/migration`과 `db/seed`를 함께 실행하고, 테스트는 `db/migration`만 사용합니다. Flyway와 함께 쓰지 않도록 `data.sql`은 사용하지 않습니다.
- `ddl-auto=validate`로 Entity와 DB 구조가 맞는지만 검사하고 자동 DDL 변경은 하지 않습니다. SQL 초기화도 비활성화합니다.
- `open-in-view=false`로 요청 렌더링 단계의 지연 로딩을 사용하지 않습니다.
- Entity는 `global/entity/BaseEntity`를 상속하면 `id`(UUIDv7), `createdAt`, `updatedAt`, `deletedAt`(소프트 삭제)을 공통으로 갖습니다.

## 공통 기능 사용법

| 기능 | 위치 | 쓰는 방법 |
|---|---|---|
| API 주소 접두사 | `global/config/ApiPathConfig` | 우리 `@RestController`의 주소 앞에 `/api/v2`가 자동으로 붙습니다. 컨트롤러에는 `@RequestMapping("/tickets")`처럼 접두사 뒤 주소만 적습니다. Swagger·`/error`에는 붙지 않습니다. `SecurityConfig`처럼 전체 주소가 필요하면 `ApiPathConfig.PREFIX + "/auth/login"`으로 씁니다. |
| 공통 오류 응답 | `global/exception` | Controller·Service에서 `throw new BusinessException(ErrorCode.NOT_FOUND)`처럼 던지면 `{code, message, requestId, retryable, fieldErrors}` 형식(DATA_TYPES 신규 절 ApiError)으로 응답합니다. `fieldErrors`는 입력 검증 실패(400)에만 `[{field, code}]`로 채우고 그 밖에는 빈 배열입니다. try-catch는 쓰지 않습니다. |
| 요청 추적 번호 | `global/filter/RequestIdFilter` | 모든 응답 헤더와 로그 줄에 `X-Request-Id`(requestId)가 붙습니다. 오류 문의 시 이 값으로 로그를 찾습니다. |
| API 호출 로그 | `global/filter/RequestLoggingFilter` | 요청 전 `→ GET /path`, 요청 후 `← GET /path 200 (12ms)`를 남깁니다. 본문·헤더·쿼리는 기록하지 않습니다. |
| 시각 | `application.properties` | API 응답과 DB 모두 UTC입니다. 한국 시간 표시는 화면에서 합니다. |
| 접근 규칙 | `global/security/SecurityConfig` | `POST /api/v2/auth/login`, Swagger, `/error`만 토큰 없이 접근할 수 있고 나머지는 인증이 필요합니다. 새 공개 주소가 필요하면 이 파일에 추가합니다. |
| JWT 검증 | `global/security/JwtConfig` | `Authorization: Bearer 토큰`의 서명(HS256)·만료·발급자(`hops.jwt.issuer`)·대상(`hops.jwt.audience`)을 검사합니다. Controller에서 `@AuthenticationPrincipal Jwt jwt`로 클레임을 꺼내고, `roles`는 `hasRole('BIZ_ADMIN')`처럼 씁니다. `hops.jwt.secret`은 개발용 값이며 공용·운영 서버에서는 `HOPS_JWT_SECRET` 환경변수로 바꿉니다. 발급은 `auth/service/AccessTokenIssuer`가 같은 키로 합니다. |
| CORS | `hops.cors.allowed-origins` | 기본값은 Vite 개발 서버 `http://localhost:5173`, `http://127.0.0.1:5173`입니다 (브라우저는 둘을 다른 주소로 봅니다). 로그인 쿠키를 위해 `allowCredentials`를 켰습니다. |
| Swagger UI | `global/config/OpenApiConfig` | 서버 실행 후 `http://localhost:8080/swagger-ui/index.html`. 오른쪽 위 Authorize에 JWT를 넣어 호출합니다. 계약 기준은 Swagger가 아니라 저장소의 OpenAPI YAML입니다. |
| Python AI 서버 호출 | `agent/client` | `@Qualifier(AgentClientConfig.AGENT_REST_CLIENT) RestClient`를 주입해 씁니다. 주소는 `hops.agent.base-url`(기본 `http://localhost:8000`), 응답 대기 90초입니다. 경로·요청 형식은 AI 담당자와 합의 후 추가합니다. |

## 테스트·빌드·실행

Java 21, Gradle Kotlin DSL과 Gradle Wrapper 9.8.1을 사용하고 이 README가 있는 `backend/spring/hops`에서 저장소의 Wrapper로 실행합니다. Windows는 `./gradlew` 대신 `.\gradlew.bat`을 씁니다.

```bash
./gradlew test
./gradlew bootJar
./gradlew bootRun
```

- 서버를 띄우는 테스트는 `@ActiveProfiles({"db", "test"})`로 로컬 PostgreSQL의 테스트 전용 `hops_test` DB에 연결합니다 (`src/test/resources/application-test.properties`). 위 "처음 설치" 2~6번(PostgreSQL·pgvector·Redis·DB·접속 정보)을 마쳐야 통과합니다.
- 테스트 DB에는 가상 데이터(seed)를 넣지 않습니다. 필요한 데이터는 각 테스트가 직접 넣고 `@Transactional`로 끝나면 되돌립니다 (예: `MemberRepositoryTest`).
- 로그인 판단 규칙처럼 DB가 필요 없는 테스트는 Repository를 mock으로 바꿔 빠르게 확인합니다 (예: `AuthServiceTest`).

## 인증

로그인하면 Spring이 서명한 액세스 토큰(JWT)을 발급하고, 이후 요청은 `Authorization: Bearer <토큰>`으로 인증합니다. 현재 응답은 아래 표와 코드의 `LoginResponse`·`CurrentUserResponse`([데이터 타입](../../../specs/contracts/DATA_TYPES.md#신규-계약-hops-system-1)의 신규 `CurrentUser`)를 따릅니다. 계정은 위 "가상 데이터"의 표를 참고합니다.

| API | 요청 | 성공 | 실패 |
|---|---|---|---|
| `POST /api/v2/auth/login` | `{loginId, password}` | 200 `{accessToken, expiresAt, user}` + `Set-Cookie: hops_refresh` | 400 `INVALID_INPUT`(빈 값·형식 오류, `fieldErrors`에 칸 이름), 401 `UNAUTHENTICATED`(아이디·비밀번호 불일치, 차단 계정) |
| `POST /api/v2/auth/refresh` | 본문 없음, `hops_refresh` 쿠키 | 200 `{accessToken, expiresAt, user}` + 새 `Set-Cookie: hops_refresh` | 401 `REFRESH_TOKEN_REVOKED`(쿠키 없음·이미 쓰인 값·만료), 403 `ORIGIN_DENIED` |
| `POST /api/v2/auth/logout` | 본문 없음, `hops_refresh` 쿠키 | 204 + 쿠키 삭제(`Max-Age=0`). 쿠키가 없거나 이미 끝난 세션이어도 같음 | 403 `ORIGIN_DENIED`, 503 `SERVICE_UNAVAILABLE`(세션 저장소 장애. 쿠키 삭제는 그대로 보냄) |
| `GET /api/v2/auth/me` | `Authorization: Bearer <토큰>` | 200 `{id, employeeNo, name, department, roles, primarySystemIds}` | 401 `ACCESS_TOKEN_EXPIRED`(서명은 맞고 만료됨 → 프론트가 refresh), 401 `UNAUTHENTICATED`(토큰 없음·위조, 회원 없음·차단) |

- **동작 순서:** 로그인 ID로 회원·부서·역할을 DB에서 찾고(삭제된 회원 제외) → BCrypt로 비밀번호 해시 비교 → `AccessTokenIssuer`가 토큰 발급 (`auth/service`).
- **토큰 클레임:** `sub`(회원 UUID), `employeeNo`, `departmentId`·`departmentCode`·`departmentName`, `roles`, `iss`·`aud`·`iat`·`exp`(발급 후 15분). 토큰 내용은 누구나 읽을 수 있으므로 비밀번호 등은 넣지 않습니다.
- **`user` 모양 (DATA_TYPES 신규 절 CurrentUser):** `department`는 `{id, code, name}`, `roles`는 계약에 있는 `BIZ_ADMIN`만(DB의 `AGENT_OPS_ADMIN`은 응답에서 뺌), `primarySystemIds`는 이 회원이 주담당자인 시스템 ID(삭제된 시스템 제외, 없으면 `[]`)입니다. 로그인·refresh·`/me`가 같은 모양입니다.
- **`/me`는 토큰이 아니라 DB를 다시 읽습니다.** 토큰 발급 뒤 역할 회수·부서 변경·계정 차단이 있었을 수 있기 때문입니다.
- 로그인 ID(`loginId`)와 사번(`employeeNo`)은 [ERD](../../../docs/ERD.md)의 `member.login_id`, `member.employee_no`처럼 별도 값입니다. 아이디·비밀번호 중 무엇이 틀렸는지는 응답에서 구분하지 않습니다.
- **로그:** 로그인 성공·실패 이유와 회원 ID를 남기고(`requestId`로 찾기), 비밀번호·찾지 못한 로그인 ID 원문·토큰은 남기지 않습니다.
- **Refresh 쿠키:** `HttpOnly`, `SameSite=Lax`, `Path=/api/v2/auth`. `Secure`는 요청이 아니라 설정 `hops.auth.refresh-cookie.secure`로 정합니다(로컬 `false`, 설정이 없으면 `true`). 설정 파일 기본값에는 넣지 않으므로 공용·운영(HTTPS) 서버는 따로 설정하지 않으면 `Secure`입니다. 로컬은 `application-db.local.properties`(아래 "pull 받은 뒤 할 일"), Docker는 이 폴더의 `.env`에서 `HOPS_AUTH_REFRESHCOOKIE_SECURE=false`로 끕니다. 원문은 JSON에 넣지 않고 Redis에는 SHA-256 해시만 키로 둡니다. 갱신할 때마다 새 값으로 바꾸고(이전 값은 즉시 폐기), 만료는 최초 로그인부터 7일로 늘어나지 않습니다.
- **재사용 검출:** 회전으로 이미 쓴 Refresh가 10초 안에 다시 오면 탭 두 개의 동시 갱신 경합으로 보고 그 요청만 401입니다. 10초가 지나서 오면 탈취로 보고 그 로그인 세션 전체를 폐기합니다(정상 사용자도 재로그인).
- **갱신 순서:** 회원을 DB에서 먼저 확인하고 Refresh 회전은 마지막에 합니다. DB가 일시 실패해도 같은 쿠키로 다시 갱신할 수 있습니다. 삭제·차단된 회원은 그 세션을 끝내고 401 `REFRESH_TOKEN_REVOKED`입니다.
- **로그아웃:** 그 쿠키의 로그인 세션만 끝냅니다(다른 기기 로그인은 유지). login·refresh·logout은 `Authorization` 헤더를 읽지 않아서, 만료·손상된 Bearer가 붙어 와도 쿠키로 처리합니다. 세션 저장소 장애 중에도 쿠키 삭제는 내려보냅니다. 이미 발급한 Access는 최대 15분 남지만 프론트가 메모리에서 버립니다.
- **재로그인:** 같은 브라우저에서 다시 로그인하면 예전 쿠키의 세션을 끝냅니다.
- **세션이 끝나면** (로그아웃·재로그인·재사용 감지·차단 회원) `LoginSessionRevokedEvent`를 받은 `RealtimeSessionListener`가 그 회원의 열린 SSE를 닫습니다. Access에 세션 ID가 없어 기기 구분 없이 회원 단위로 닫습니다.
- **브라우저 주소:** `SameSite=Lax` 쿠키라 프론트 화면과 API 주소의 호스트가 같아야 합니다. `localhost`와 `127.0.0.1`은 다른 사이트로 취급되어, `http://127.0.0.1:5173` 화면에서 `http://localhost:8080` API를 부르면 Refresh 쿠키가 저장·전송되지 않습니다(15분 뒤 재로그인). live 모드는 둘 다 `localhost`로 맞춥니다.
- **Origin 검사:** 쿠키를 쓰는 refresh·logout은 `Origin` 헤더가 `hops.cors.allowed-origins`에 있을 때만 받습니다(없거나 다르면 403 `ORIGIN_DENIED`). 프론트와 API를 같은 주소로 배포해도 그 주소를 이 목록에 넣어야 합니다. Swagger 화면(`localhost:8080`)에서는 이 둘을 호출할 수 없으니 curl에 `-H "Origin: http://localhost:5173"`을 붙여 확인합니다.

### 토큰 직접 확인하기

```bash
# 1) 로그인해서 토큰 저장
TOKEN=$(curl -s -H 'Content-Type: application/json' -d '{"loginId":"bizadmin","password":"hops1234!"}' http://localhost:8080/api/v2/auth/login | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
# 2) 토큰 내용(클레임) 보기 — 서명 검증은 하지 않고 읽기만 한다
echo "$TOKEN" | python3 -c 'import sys,base64,json; p=sys.stdin.read().strip().split(".")[1]; print(json.dumps(json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4))),ensure_ascii=False,indent=2))'
# 3) 서버가 유효하다고 보는지 확인 — 200이면 서명·만료·발급자·대상 통과
curl -s -w ' [%{http_code}]\n' -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v2/auth/me
```

Swagger에서는 `POST /api/v2/auth/login` 응답의 `accessToken`을 따옴표 없이 복사해 오른쪽 위 **Authorize**에 넣습니다(`Bearer `는 붙이지 않음).

## 티켓 조회 API

현재 PRIMARY의 티켓 목록·상세·허용된 관련 대화, BIZ_ADMIN의 제한된 관리 상세와 시스템 주담당자를 조회합니다. 티켓 상세의 처리 기록·해결 확인·사례 생성 상태는 기존 DB 기록만 읽으며 GET에서 업무 상태나 AI 실행을 만들지 않습니다.

- `GET /api/v2/systems/{systemId}/primary`
- `GET /api/v2/tickets`
- `GET /api/v2/tickets/{ticketId}`
- `GET /api/v2/tickets/{ticketId}/related-messages`
- `GET /api/v2/management/tickets/{ticketId}`
- `GET /api/v2/case-generations/{generationId}`

시스템 목록은 기존 `GET /api/v2/systems`를 사용합니다. 현재 DB 권한과 Access 발급 당시의 담당 문맥을 비교하므로 기존 Access는 재로그인이 필요합니다. 원문·배정 근거는 현재 문제와 조회자의 공개 범위로 제한하고, 관리자 역할만으로 담당자 내부 내용을 공개하지 않습니다.

조회 쿼리는 `TicketQueryRepository`, `ItSystemQueryRepository`, `TicketProgressQueryRepository`로 분리하며 GET은 업무 상태를 변경하거나 AI를 실행하지 않습니다. 사례 생성 결과는 저장된 초안 또는 재사용 없음 결과를 확인해 반환합니다. 처리·정정·해결 확인 저장은 아래 명령 Service가 담당합니다.

`SYSTEM_TICKETS`·`TICKET` SSE는 연결·권한 재검사·heartbeat와 재연결 시 REST 재동기화를 지원합니다. 업무 저장·Curator 적용의 commit 뒤 `state.changed`를 보내며, 전송 직전 현재 권한을 재검사합니다. PostgreSQL/MockMvc 검사는 실제 AI나 브라우저 E2E 검증을 대신하지 않습니다.

## 티켓 처리·정정·해결 확인 API

- `POST /api/v2/tickets/{ticketId}/work-records`: 진행·실패·검증 완료 기록을 저장합니다. 검증 완료는 확인 요청과 CASE_DRAFT 접수를 같은 거래로 보존합니다. 확인 대기 중에는 모든 새 기록을 거절하고 문의자의 미해결 응답 후 다시 허용합니다.
- `POST /api/v2/tickets/{ticketId}/processing-type-corrections`: 현재 PRIMARY가 미완료 조사/장애 유형을 근거와 함께 정정합니다.
- `POST /api/v2/tickets/{ticketId}/assignment-corrections`: 현재 PRIMARY 또는 관리자가 책임 시스템을 정정합니다. 서버가 새 PRIMARY를 결정하며 배정 이력·진행 중 확인 요청을 보존합니다.
- `GET /api/v2/resolution-requests/{resolutionRequestId}`: 문의 소유자 또는 원 티켓의 현재 PRIMARY가 저장된 확인 요청을 조회합니다. GUIDE는 문의 소유자만 조회합니다.
- `POST /api/v2/resolution-requests/{resolutionRequestId}/responses`: 문의 소유자가 한 번 응답합니다. GUIDE 미해결은 같은 문제·입력 버전·질문 횟수·확인 문맥과 기존 분류를 유지한 재분석으로 접수합니다.

명령은 본문 requestId와 같은 Idempotency-Key를 보내며 CommandReceipt를 반환합니다. Controller는 입력·응답, Service는 현재 인가·멱등 업무, Repository는 문의→티켓 순서의 잠금을 담당합니다. 확인 요청 조회·원 기록 검증은 `ResolutionRequestReader`, 응답 저장은 `ResolutionRequestService`로 나눴습니다. 문의 입력 버전과 도메인 revision을 구분하고 다른 분석이 진행 중이면 티켓 확인만으로 문의를 완료하지 않습니다. 분석 성공 결과를 적용할 때 현재 문제 상태를 다시 집계하며, 미적용 새 문제·질문·티켓 제안이 있으면 진행 상태를 유지합니다.

AI 전달은 기존 `AgentRunClient`·`AgentRunDispatcher`를 재사용합니다. `CurationInputFactory`와 `CurationResultHandler`가 CASE_DRAFT 입력·결과를 담당하며 원 조치·검증·원인·적용 버전과 제한 조건을 보존합니다. 해당 문제의 공개 가능한 USER 원문 구간만 불변 입력에 고정하고, 재사용 초안의 출처·APPLIES_TO 근거를 그 입력과 대조해 보존합니다. AI 메시지·보완 대상 대기·다른 문제의 구간은 원문으로 보내지 않으며 SYSTEM_STAFF 공개 범위를 넓히지 않습니다. 외부 실패는 저장한 업무를 취소하지 않습니다. Curator 결과 적용 전 원 확인 요청을 잠금 안에서 다시 읽고 이미 미해결이면 새 초안을 저장하지 않으며 NO_REUSABLE_CASE로 처리합니다. 이미 저장한 초안은 업무 이력으로 보존하고 승인 시 별도 검증합니다. 문의 Problem/GUIDE 결과 투영과 Python Curator는 develop 구현을 유지합니다. 검증된 GUIDE 결과는 GuideResultWriter가 대상 문제·적용 표준 요청·안내 메시지의 PENDING 해결 확인 요청을 같은 거래로 저장합니다. 일반 문제 분리·질문·티켓 제안 저장과 사례 조회·승인·색인은 남아 있습니다. Spring AI 연동 회귀 테스트는 가상 응답을 사용하며 실제 모델 통합 결과와 구분합니다.

## 문의 생성·조회 API

모두 로그인이 필요합니다(없으면 401 `UNAUTHENTICATED`). 형식은 [전송 계약 12.2](../../../specs/contracts/WIRE_CONTRACT.md#122-rest-연결표)와 [데이터 타입](../../../specs/contracts/DATA_TYPES.md#신규-계약-hops-system-1)을 따릅니다. null 값도 키를 생략하지 않고, 빈 목록은 `[]`입니다.

| API | 요청 | 성공 | 실패 |
|---|---|---|---|
| `POST /api/v2/inquiries` | 헤더 `Idempotency-Key`(= 본문 `requestId`), 본문 `{requestId, content, reportedSystemId}` | 200 `CommandReceipt` (SUCCEEDED, `resource={type: INQUIRY, id}`) | 400 `INVALID_INPUT`(헤더·본문 키 불일치, `content` 규칙 위반은 `fieldErrors[{field: content, code: UserContent}]`, 없는 시스템), 409 `IDEMPOTENCY_KEY_REUSED` |
| `POST /api/v2/inquiries/{inquiryId}/messages` | 헤더 `Idempotency-Key`(= 본문 `requestId`), 본문 `{requestId, expectedInquiryVersion, content, problemIds}` | 200 `CommandReceipt` (SUCCEEDED, `resource={type: INQUIRY, id}`) | 400 `INVALID_INPUT`(`content` 규칙 위반, 이 문의의 문제가 아닌 `problemIds`), 404 `NOT_FOUND`, 409 `INQUIRY_VERSION_CONFLICT`, 409 `INVALID_STATE`(끝난 문의) |
| `POST /api/v2/inquiries/{inquiryId}/messages/{messageId}/revisions` | 헤더 `Idempotency-Key`(= 본문 `requestId`), 본문 `{requestId, expectedInquiryVersion, expectedMessageRevision, content}` | 200 `CommandReceipt` (SUCCEEDED, `resource={type: INQUIRY, id}`) | 400 `INVALID_INPUT`, 404 `NOT_FOUND`(남의 문의·이 문의의 USER 메시지가 아님), 409 `INQUIRY_VERSION_CONFLICT`·`MESSAGE_REVISION_CONFLICT`·`INVALID_STATE`(끝난 문의) |
| `POST /api/v2/inquiries/{inquiryId}/messages/{messageId}/problem-links` | 헤더 `Idempotency-Key`(= 본문 `requestId`), 본문 `{requestId, expectedInquiryVersion, problemIds}` | 200 `CommandReceipt` (SUCCEEDED, `resource={type: INQUIRY, id}`) | 400 `INVALID_INPUT`(빈 `problemIds`·이 문의의 문제가 아님), 404 `NOT_FOUND`, 409 `INQUIRY_VERSION_CONFLICT`·`INVALID_STATE`(대기 메시지가 아님·끝난 문의) |
| `GET /api/v2/systems` | 없음 | 200 `SupportedSystem[]` (코드 순, 기능 포함. 주담당자는 넣지 않음) | |
| `GET /api/v2/inquiries` | `page`(기본 0), `size`(기본 20, 1~100) | 200 `{items: InquirySummary[], page, size, totalElements}` (본인 문의, 최근 수정 순) | 400 `INVALID_INPUT`(범위 밖 값은 고치지 않음) |
| `GET /api/v2/inquiries/{inquiryId}` | 없음 | 200 `InquiryResult` | 404 `NOT_FOUND`(남의 문의·삭제·없음을 구분하지 않음) |
| `GET /api/v2/inquiries/{inquiryId}/messages` | `size`(기본 30, 1~100), `beforeSequenceNo`(선택, 1 이상) | 200 `{items: ChatMessage[], nextBeforeSequenceNo}` (저장이 끝난 메시지만, 오래된 순) | 400 `INVALID_INPUT`, 404 `NOT_FOUND` |
| `GET /api/v2/runs/{runId}` | 없음 | 200 `RunSummary` (실패한 실행도 200, 실패면 `error` 포함) | 404 `NOT_FOUND`(남의 실행·Curator 실행·없음) |
| `POST /api/v2/runs/{runId}/retries` | 헤더 `Idempotency-Key`(= 본문 `requestId`), 본문 `{requestId, expectedInquiryVersion}` | 200 `CommandReceipt` (SUCCEEDED, `resource={type: RUN, id: 새 실행}`) | 404 `NOT_FOUND`, 409 `INQUIRY_VERSION_CONFLICT`(문의 버전 다름), 409 `RUN_NOT_RETRYABLE`(현재 실행이 아님·재분석 불가 실패·끝난 문의) |
| `GET /api/v2/operations/{operationId}`<br>`GET /api/v2/operations/by-request/{requestId}` | 없음 | 200 `CommandReceipt` (본인이 보낸 변경 요청의 결과) | 404 `NOT_FOUND`, 410 `OPERATION_RESULT_EXPIRED`(보존 24시간 경과) |

- **대화 페이지:** 처음에는 `beforeSequenceNo` 없이 최신 페이지를 받고, 응답의 `nextBeforeSequenceNo`를 다음 요청의 `beforeSequenceNo`로 보내 이전 페이지를 받습니다. `null`이면 더 없습니다.
- **문의 생성:** 문의(버전 1)·첫 USER 메시지·상담 실행(QUEUED)과 AI 입력(`agent_run.input_snapshot`, AiRunRequest)·`currentRun`을 한 거래로 저장합니다. 응답은 입력과 실행 접수의 저장이며 AI 답변 완료가 아닙니다. 같은 키로 다시 보내면 처음 영수증을 돌려주고 새 실행을 만들지 않습니다. `content`는 1~4,000 코드 포인트이며 trim하지 않고 저장합니다. 제목은 첫 입력의 공백을 한 칸으로 줄여 200자까지 씁니다.
- **AI 전달:** 이 API는 요청 스레드에서 AI 서버를 부르지 않습니다. commit 뒤 `AgentRunQueued` 이벤트를 `AgentRunDispatcher`가 받아 전용 스레드에서 처리합니다. 순서는 작업 종류별 `AgentRunResultHandler.verifyAccess`로 접수 때의 권한 문맥 재확인 → `POST /internal/v2/runs`(`AgentRunClient`, 서비스 토큰) → `QUEUED→RUNNING` → `GET /internal/v2/runs/{id}` 폴링(기본 0.5초) → 종료 처리입니다. AI 거절·연결 불가·계약 위반·기한(`expiresAt`+5초) 초과는 실행을 FAILED/TIMED_OUT으로 끝내고 AI 오류 코드를 `agent_run.error_code`에 남깁니다. 설정은 `hops.agent.dispatch-enabled`(기본 true, 테스트 프로필은 false)·`dispatch-poll-interval`·`dispatch-deadline-grace`, 서비스 토큰은 환경변수 `AI_SERVICE_TOKEN`(ai/.env와 같은 값)입니다. 서버 재시작으로 끊긴 QUEUED·RUNNING 실행의 복구는 아직 없습니다.
- **AI 결과 반영:** 상담 결과는 `InquiryAnswerHandler`가 반영합니다. 문의 행을 잠그고 이 실행이 현재 실행이며 문의 버전이 접수 때와 같은지 확인한 뒤(아니면 `SUPERSEDED`로 반영하지 않음), AI 답변(`answer`)을 접수 때 예약한 `answerMessageId`로 저장하고 `resultRevision`을 올립니다. 문제 분리·질문 묶음·가이드·티켓 제안은 아직 저장하지 않고 `agent_run.validation_result.unapplied`에 받았다는 사실만 남깁니다(결과 원문은 `agent_run.result`). 작업 종류가 늘어나면(예: 사례 초안) `AgentRunResultHandler` 구현만 추가합니다.
- **실행 한도·버전:** `hops.agent.run.*`(전체 60초, 호출 8회, 재시도 2회, 출력 토큰은 실행 합계 12,000 = 호출 8회 × 호출당 1,500). `model-version`·`prompt-version`은 AI 서버와 정하기 전까지 `pending`입니다.
- **보고 시스템:** `reportedSystemId`는 첫 메시지 payload에만 저장하고 AI 입력에는 넣지 않습니다. AiRunRequest에 받을 필드가 없어 전달 방식을 정해야 합니다.
- **실행 상태:** DB `CANCELLED`는 `SUPERSEDED`로 보냅니다. 실패 오류는 공개 코드(`AI_INVALID_OUTPUT`·`MODEL_UNAVAILABLE`·`SEARCH_UNAVAILABLE`·`SERVICE_UNAVAILABLE`·`AUTH_CONTEXT_CHANGED`·`AI_RUN_STATE_UNKNOWN`, 시간 초과는 `DEADLINE_EXCEEDED`)로 바꾸고 `requestId`에 실행을 접수한 명령 ID를 넣습니다.
- **메시지 전송:** 문의 행을 잠그고 `expectedInquiryVersion`을 확인한 뒤 버전을 1 올려 USER 메시지를 저장합니다. 진행 중인 문의면 이전 활성 실행(QUEUED·RUNNING)을 대체(`SUPERSEDED`)하고 새 버전의 실행을 접수합니다. AI 입력에는 저장된 대화 전체, 기존 문제(`inquiry_problem` 상태·질문 수 + 문제별 최신 적용 실행의 `standard_request`), 티켓 생성 작업 결과(`existingWorkItems`)를 넣습니다. 질문 중(CLARIFYING)인 문제는 이번 메시지가 고른 문제만 `ANALYZING`, 나머지는 `WAITING_USER`입니다. 고른 `problemIds`는 메시지 전체 구간으로 연결합니다. 끝난 문의(RESOLVED·CLOSED)는 재오픈하지 않고 409 `INVALID_STATE`로 새 문의를 안내합니다. 이전 실행의 AI 서버 취소 전파는 아직 없습니다.
- **보완 대상:** 티켓이 배정된 문제가 있는데 `problemIds=[]`로 보내면, 문제가 둘 이상이면 어느 티켓에도 붙이지 않고 `pendingTarget`으로 저장해 `pendingTargetMessageIds`에 올리고(배정된 문제가 하나뿐이면 그 문제의 보완), `problem-links`로 원문을 바꾸지 않고 대상을 확정합니다(입력 버전 +1, 이미 정한 메시지는 409). 대기 메시지는 대상이 정해질 때까지 AI 입력의 대화에서 빠지고 그 메시지로는 분석하지 않지만, 다른 메시지의 분석은 막지 않습니다. 대상이 모두 배정된 문제인 입력도 보완 정보라 자기 분석이 없습니다. 이런 입력이 진행 중이던 실행을 대체하면 그 실행의 대상을 새 실행으로 잇습니다. 해결됐거나(RESOLVED) 지원 범위 밖(OUT_OF_SCOPE)인 문제는 대상이 될 수 없습니다(400). 메시지 명령이 접수한 실행은 시작 메시지 `payload.runRequestIds`에 접수 명령을 남겨 실행 오류의 `requestId`로 씁니다.
- **정정:** 바꾸기 전 원문을 `message_revision`에 보존하고 같은 메시지의 `content`를 바꿔 `revision`을 1 올립니다. ID·순번·처음 `requestId`는 그대로입니다. 그 뒤는 전송과 같이 문의 버전을 올리고 이전 실행을 대체해 새 실행을 접수하며, 대상 문제는 그 메시지에 연결된 문제입니다. 원문 전체에 걸린 문제 연결은 새 원문 전체로 잇고(일부 구간 연결은 잇지 않음), payload `revisionRequestIds`에 차수별 정정 요청 ID를 남겨 정정으로 시작한 실행의 오류 `requestId`로 씁니다.
- **거절 기록:** 업무 규칙으로 거절한 변경 요청(409 버전 충돌·재분석 불가, 업무 검사 400)은 `REJECTED`로 기록합니다. 같은 키로 다시 보내면 다시 검사하지 않고 같은 오류를 돌려주고, `GET /operations/...`는 `state=REJECTED`와 `error`를 반환합니다. 입력 형식 400·401·403·429·5xx는 기록하지 않습니다.
- **만료 기록 정리:** 보존 기간(24시간)이 지난 중복 요청 방지 기록은 `IdempotencyTombstoneScheduler`가 1시간마다 응답(`response_status`·`response_body`)을 비우고 `tombstoned_at`을 남깁니다. 행과 키·해시·작업·업무 대상은 지우지 않아 같은 키로 다시 와도 410이고 업무는 다시 실행되지 않습니다. 접수 중(PENDING)인 기록은 건드리지 않습니다. 설정은 `hops.idempotency.tombstone.enabled`(기본 true, 테스트 프로필은 false)·`initial-delay`·`interval`입니다.
- **재분석:** 문의의 현재 실행이 `retryable=true`인 FAILED/TIMED_OUT일 때만 같은 문의 버전으로 새 runId·새 `answerMessageId`의 QUEUED 실행을 접수합니다. 실패한 실행은 그대로 두고 대화 입력은 재사용하며, 권한 문맥·시스템 목록·기한은 새로 만듭니다. 재분석 실행의 실패 오류 `requestId`는 그 재분석 요청 ID입니다.
- **문의 상세의 `problems`:** 문제 번호 순으로 `Problem`을 돌려줍니다. 요약·의도·처리 유형·보고 시스템은 문제별 최신 적용(SUCCEEDED) 실행의 `standard_request`, 상태·질문 수는 `inquiry_problem`(DATA.md 11.2 변환), 안내는 최신 AI 메시지의 `payload.guide`와 그 실행 결과 중 지금 공개 가능한 직원용 근거, 티켓 생성 결과는 `creation_outcome`, 티켓은 티켓 화면과 같은 방식으로 읽습니다. 해결 확인 요청은 티켓이 있으면 티켓 이벤트, 없으면 안내 메시지의 `payload.resolutionRequest`(GUIDE)입니다. 질문 중인 문제는 질문을 했고 분석 중인 실행이 그 문제를 대상으로 삼지 않았으면 `WAITING_USER`(대상은 실행 입력의 `problemIds`), 시스템 확인 대기는 `reportedSystemId=null`입니다. 담당자 내부 표준 요청서는 담지 않습니다. 적용된 요청서가 없는 문제처럼 저장 규칙이 깨진 경우는 짐작하지 않고 500입니다.

## Spring SSE 공통 기반

[전송 계약](../../../specs/contracts/WIRE_CONTRACT.md#5-sse-연결이벤트복구)에 따라
`GET /api/v2/realtime/events?targetType=DEPARTMENT_TICKETS&targetId=<부서 UUID>`로 연결합니다.
`Authorization: Bearer <accessToken>`과 `Accept: text/event-stream`을 전달합니다.
프론트는 Bearer 헤더를 지정할 수 있는 fetch 기반 SSE 소비자가 필요합니다.

| 구현 항목 | 현재 동작 |
|---|---|
| 대상 권한 | 현재 DB 회원·부서·역할과 토큰 문맥을 비교합니다. 현재 처리 부서 구성원 또는 BIZ_ADMIN만 허용합니다. 없는 부서는 404입니다. |
| 문의·시스템·개별 티켓 대상 | `SYSTEM_TICKETS`·`TICKET`은 현재 시스템 담당 관계와 관리자 공개 범위를 검사해 구독합니다. `INQUIRY`는 토큰·소속·역할 검사 뒤 문의자 본인의 삭제되지 않은 문의만 구독합니다(남의 문의·없는 문의는 404). 티켓 업무 commit 후 변경 신호를 전송합니다. |
| 메시지 저장 알림 | 문의 생성·메시지 전송·정정·AI 답변 저장이 commit되면 그 문의 구독에 `message.stored` `{eventId, inquiryId, inquiryVersion, messageId, requestId}`를 보냅니다(AI 답변은 `requestId=null`). 본문은 싣지 않으며 프론트는 문의·메시지를 다시 조회합니다. 보내기 직전에 구독 권한을 다시 확인합니다. |
| 연결 유지 | 최초·유휴 시 comment를 전송합니다. `hops.realtime.heartbeat=15s`, `hops.realtime.timeout=5m`은 조정 가능한 개발 기본값이며 운영 부하·프록시 검증 수치가 아닙니다. |
| 종료·권한 회수 | timeout·오류·완료·서버 종료 때 등록과 예약 작업을 정리합니다. 토큰 만료 시 종료하고, heartbeat 및 업무 이벤트 전 현재 DB 권한을 재검사합니다. |
| 확정 이벤트 | 트랜잭션 안에서 `StateChangedEvent`를 발행하면 커밋 후 해당 부서 연결에 `state.changed`를 전달합니다. 롤백·트랜잭션 없는 발행은 전달하지 않습니다. |
| 재접속 | 이벤트 보존소가 없습니다. `Last-Event-ID`가 있으면 `resync.required` 제어 이벤트를 보낸 뒤 연결을 종료합니다. 소비자는 REST 복구 후 커서를 비워 새 연결을 열어야 합니다. SSE 재생을 제공하지 않습니다. |

업무 서비스는 `ApplicationEventPublisher.publishEvent(...)`로 `realtime/dto/StateChangedEvent`를
자신의 저장 트랜잭션 안에서 발행합니다. 이벤트에는 사용자 JWT·원문 대화·내부 예외를 넣지 않습니다.
메시지 저장은 `realtime/dto/MessageStoredEvent`를 같은 방식으로 발행합니다. 티켓 업무 저장과 변경 이벤트는 위 명령 API에 연결했습니다. 외부 이벤트 발행용 HTTP API는 제공하지 않습니다. AI 접수 뒤 별도 수신 풀에서 내부 progress/result SSE를 구독하며 snapshot 폴링도 즉시 병행합니다. 현재 RUNNING 상담 실행·문의 입력 버전이 맞는 진행만 `agent.progress`로 중계하고 전송 직전 구독 권한을 다시 검사합니다. CURATOR 진행은 문의자에게 보내지 않습니다. 내부 seq를 공개 stepNo로 사용하고 공개 eventId는 새로 발급합니다. SSE 최종 결과와 snapshot은 dispatcher 한 경로에서 한 번만 적용합니다. 스트림 종료·오류·수신 풀 포화 시 snapshot 폴링으로 완료를 확인하며 업무 명령을 다시 만들지 않습니다. INQUIRY의 `state.changed`(실행 종료 등)와 `answer.delta`는 아직 연결하지 않았습니다.

현재는 단일 Spring 프로세스의 연결만 관리합니다. 로그인 세션 종료 시 회원 단위 SSE 정리는 연결했습니다.
부서·역할 변경을 즉시 모든 연결에 통지하는 `auth.invalidated`는 아직 없습니다.
유휴 연결은 다음 heartbeat에서 회수를 확인하며, 업무 이벤트는 전송 전 재검사합니다.
권한 변경 트랜잭션과 전송 간 경합을 완전히 직렬화하지는 않습니다.
다중 인스턴스 전달·이벤트 보존/replay·프록시 buffering·최대 연결 수·느린 소비자에 대한
전송 격리와 부하 테스트는 후속 작업입니다. 현재 공유 scheduler에서 SSE 전송이 수행되므로
느린 연결의 영향도 운영 전 검증해야 합니다.

DB 없이 SSE 기반 테스트만 실행할 수 있습니다.

```bash
./gradlew test --tests '*RealtimeAuthorizationTest' --tests '*RealtimeServiceTest' --tests '*RealtimeSecurityTest'
```

위 DB 없는 SSE 테스트는 연결·대상별 전달·권한 회수·만료·재생 불가 종료·커밋/롤백·Security/CORS와
예약 작업 취소·스케줄러 거부 시 정리를 검증합니다.
Mock 권한·MVC 테스트는 실제 JWT/DB·브라우저·운영 프록시 E2E 검증을 대신하지 않습니다.

### 다른 작업과의 연결 경계

| 작업 | 책임 | SSE 기반과 연결할 부분 |
|---|---|---|
| #26 채팅 저장 | 문의 소유권·메시지 저장·버전·멱등성 | 도메인의 권한 검사를 연결하고 커밋 후 저장 이벤트를 제공 |
| #28 티켓 저장·조회 | 티켓 권한·배정·실제 상태 변경 | 트랜잭션 안에서 부서별 StateChangedEvent 발행. 공통 권한 서비스가 생기면 SSE도 재사용 |
| #27 Spring–Python | AI 이벤트 수신·실행/문의 버전 검증 | 공개 가능한 진행·답변 이벤트를 SSE 전송 계층에 연결 |
| #56 프론트 SSE | 파싱·연결 공유·재접속·REST 동기화 | 초기 서버의 커서 요청 종료를 처리하고 REST 복구 후 커서 없이 재연결 |

이번 #57에서는 위 업무 서비스·프론트 구현을 변경하지 않습니다. 공개 제어 이벤트 형식과 복구 절차는 공통 계약 문서에 반영합니다.
현재 RealtimeAuthorization은 SSE 대상 검사이며, 전체 REST API 권한 회수를 대신하지 않습니다.
초기 replay 미지원 시 DATA_TYPES의 `ResyncRequiredEvent`를 사용합니다. SSE id와 JSON eventId는 같고,
소비자는 해당 대상의 REST 복구 후 기존 커서와 제어 이벤트 ID를 모두 비워 새로 연결해야 합니다.
comment는 heartbeat 용도이며 복구 판단에 사용하지 않습니다.

### 실제 HTTP·DB 통합 검증

테스트 DB 설정 후 다음 명령으로 실제 Tomcat·JWT 필터·PostgreSQL을 사용하는 SSE 테스트를 실행합니다.

```bash
./gradlew test --tests '*RealtimeHttpIntegrationTest'
```

고유한 테스트 회원·부서를 `hops_test`에 만들고 종료 시 해당 fixture만 삭제합니다.
실제 HTTP 스트림에서 미인증·타 부서 접근 거부, 연결·heartbeat, 커서 요청 종료 후 재연결,
토큰 만료·테스트 회원 차단 후 종료를 확인합니다. 짧은 heartbeat·수명의 JWT는 테스트 전용이며
실제 Access Token 15분 정책을 변경하지 않습니다. `Accept: text/event-stream` 요청의 연결 전
오류는 HTTP 상태와 JSON 오류 본문으로 반환합니다.

이 검사는 프론트 fetch parser·REST 복구·실제 업무 이벤트 생산자·운영 프록시 E2E까지 포함하지 않습니다.

### 공개 이벤트와 연결 전 오류

- 부서 `state.changed`의 `target`은 `{type: "DEPARTMENT_TICKETS", id: 부서 UUID}`입니다.
  공통 계약에 따라 필드명은 `id`이고 값은 부서 ID이며, `inquiryVersion`은 반드시 `null`입니다.
- 유효한 JWT의 소속 부서·역할이 현재 DB와 다르면 403 `AUTH_CONTEXT_CHANGED`로 응답합니다(WIRE_CONTRACT 12.3). 역할은 계약에 있는 것(`BIZ_ADMIN`)끼리만 비교합니다. Access가 만료됐으면 401 `ACCESS_TOKEN_EXPIRED`라 프론트가 refresh 후 다시 연결합니다.
  프론트는 일반 인증 갱신과 구분해 재로그인을 안내합니다. 누락된 필수 인증 문맥은 기존 401입니다.
- 연결 전 DB 연결 실패·일시적 조회 장애·트랜잭션 시작 실패는 503 `SERVICE_UNAVAILABLE`입니다.
  다른 종류의 예상하지 못한 DB 오류를 일괄 503으로 바꾸지는 않습니다.
- HTTP 통합 테스트는 부서 변경·역할 변경의 응답 검증을 포함해 6개입니다.
