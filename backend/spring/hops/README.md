# Hops Spring 백엔드

Spring Boot 프로젝트 루트는 `backend/spring/hops/`입니다. IntelliJ에서는 해당 디렉터리를 Gradle 프로젝트로 엽니다. 빌드·테스트·실행 명령은 이 디렉터리에서 실행합니다.

## 패키지 구성

`com.hihops.hops` 아래에 `auth`, `user`, `department`, `inquiry`, `ticket`, `agent`, `investigation`, `global` 패키지를 구성했습니다. 아직 클래스가 없는 패키지에는 `package-info.java`로 역할을 적어 두었고, 클래스가 생기면 그 폴더의 `package-info.java`는 삭제합니다. 현재 구현된 기능은 아래 [인증](#인증)의 로그인·JWT 발급과 `/api/auth/me`입니다.

| 패키지 | 역할 |
|---|---|
| `auth` / `user` | JWT 로그인·토큰 발급과 인증 수명 관리 / 사번·소속 부서 등 사용자 정보 |
| `department` | 부서·소속·활성 여부·R&R |
| `inquiry` | 문의·대화·내용 확인·정정 |
| `ticket` | 티켓 저장·배정·처리 |
| `agent` | Python 실행 요청·진행·결과 통신 |
| `investigation` | 읽기 전용 진단 조회의 검증·실행 경계 |
| `global` | 공통 설정·JWT 검증·접근 제어 기반·예외 처리 |

업무 기능은 `controller`, `dto`, `service`, `domain`, `repository`로 구분합니다. `agent`는 `client`, `dto`, `service`, `investigation`은 `controller`, `dto`, `service`, `client`로 구성합니다. 독립 `ownership`은 현재 추가하지 않습니다. 실제 서비스 간 입출력·인증·오류 계약은 [프로젝트 개발 안내](../../../README.md#첫-작업)에서 연결하는 API 설계와 [Agent 공통 계약](../../../contracts/agents/README.md)에서 관련 담당자와 합의한 뒤 구현합니다.

## JWT 인증과 REST·SSE 연동 설계

아래는 [PRD](../../../docs/PRD.md)와 [API 설계](../../../docs/API.md)에 따른 구현 목표입니다. 로그인·JWT 발급·검증과 `/api/auth/me`는 구현했고(아래 [인증](#인증) 참고), refresh·로그아웃과 문의 변경 REST·브라우저 SSE는 아직 구현하지 않았습니다. JWT는 Spring이 발급하고 REST·SSE 요청에서 직접 검증합니다.

- `auth`는 자체 계정 로그인과 JWT 발급을 담당하고, `user`·`department`·역할 정보에서 사번·소속 부서·권한 문맥을 구성합니다. `global/security`에서 JWT를 검증하고 업무별 접근 권한은 해당 서비스에서 확인합니다. 클레임·토큰 수명·갱신/폐기·보관/전달·키 관리 정책은 API 설계를 기준으로 합의합니다.
- Spring은 문의 생성·메시지 전송·정정을 REST로 받고 `Idempotency-Key`, 인증 주체, 문의 버전과 대상 권한을 검증합니다. `inquiry`·`ticket`이 DB 저장을 확정한 뒤 저장 결과를 반환하며 HTTP 응답 유실에도 같은 업무가 중복 반영되지 않게 합니다.
- React는 `GET /api/realtime/events?targetType={type}&targetId={id}`로 Spring SSE에 직접 연결합니다. Spring은 연결마다 JWT와 현재 소유자·부서·역할을 검사하고 `agent.progress`, `answer.delta`, `message.stored`, `state.changed`를 허용된 대상에만 전달합니다.
- `agent`는 Python HTTP 실행 요청과 내부 SSE 진행·결과 수신을 담당합니다. 공개 가능한 진행·답변 조각은 Spring이 검증해 React SSE로 전달하고 최종 메시지·업무 변경은 DB 저장 결과로 확정합니다. heartbeat·`Last-Event-ID`·`resync.required`·REST 복구의 상세 계약은 API 설계를 따릅니다.

## 처음 설치 (Mac · Windows)

pull 받은 뒤 아래 순서대로 하면 같은 환경에서 실행됩니다.

1. **JDK 17 이상** 설치 (Gradle을 실행하는 용도). 프로젝트가 쓰는 JDK 21은 Gradle이 없으면 자동으로 내려받습니다 (`settings.gradle.kts`의 foojay 플러그인).
2. **PostgreSQL 18** 설치 (`uuidv7()` 사용을 위해 18 이상이 필요합니다).
   - Mac: `brew install postgresql@18` 후 `brew services start postgresql@18`
   - Windows: [PostgreSQL 공식 설치 파일](https://www.postgresql.org/download/windows/)로 18 버전을 설치합니다. 설치 중 정한 `postgres` 비밀번호를 기억해 둡니다.
3. **DB 계정과 DB 생성** (Mac은 `psql postgres`, Windows는 `psql -U postgres`로 접속)
   ```sql
   CREATE ROLE hops WITH LOGIN PASSWORD '내_비밀번호';
   CREATE DATABASE hops OWNER hops;
   CREATE DATABASE hops_test OWNER hops; -- 테스트 전용
   ```
4. **접속 정보 파일 만들기** (`src/main/resources`에서)
   - Mac: `cp application-db.local.properties.example application-db.local.properties`
   - Windows: `Copy-Item application-db.local.properties.example application-db.local.properties`
   - 만든 파일에 3번의 비밀번호를 넣습니다. 이 파일은 git에 올라가지 않습니다.
5. **실행**
   - Mac: `./gradlew bootRun`
   - Windows(PowerShell): `.\gradlew.bat bootRun`
   - 기본 프로필이 `db`라서 프로필을 따로 지정하지 않아도 로컬 PostgreSQL에 연결합니다.
   - 처음 실행하면 테이블과 아래 "가상 데이터"가 자동으로 들어갑니다.

## 이미 설치한 팀원이 pull 받은 뒤 할 일

"처음 설치"를 이미 마쳤다면 따로 설정할 것은 없습니다.

1. `git pull` 후 위 5번처럼 실행합니다. 이전에 `SPRING_PROFILES_ACTIVE=local`로 실행했다면 빼고 실행합니다 (`local` 프로필은 없어졌습니다).
2. 로그에 `Successfully applied ... migration`이 보이면 새 테이블·데이터가 적용된 것입니다.
3. `Port 8080 was already in use`가 나오면 이미 켜진 서버를 끄고 다시 실행합니다.
4. `Validate failed`·`checksum mismatch` 오류가 나면 이미 적용된 `V*.sql` 파일이 바뀐 것입니다. 파일을 되돌리거나 아래 "처음 상태로 되돌리기"로 DB를 새로 만듭니다.

## 가상 데이터 (로컬 개발용)

`src/main/resources/db/seed/R__seed_dev_department_member.sql`에 있습니다. 실제 회사·사람이 아닌 가상 데이터이며 **db 프로필에서만** 들어갑니다. 테스트 DB(`hops_test`)와 운영에는 넣지 않습니다.

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

- 이 계정으로 `POST /api/auth/login`에 로그인할 수 있습니다(아래 [인증](#인증)). DB에서 직접 보려면:
  ```bash
  psql -U hops -d hops -c "SELECT login_id, employee_no, name FROM member;"
  ```
- seed 파일을 수정하면 다음 실행 때 다시 실행되지만, **이미 있는 행(같은 ID)은 바꾸지 않고 건너뜁니다.** 기존 행의 값을 바꾸려면 아래처럼 DB를 새로 만듭니다.

### 처음 상태로 되돌리기

테스트하며 바꾼 데이터를 지우고 처음 상태로 돌아갑니다. 로컬 `hops` DB의 모든 데이터가 지워집니다. 서버를 끈 뒤 실행합니다 (Mac은 `psql postgres`, Windows는 `psql -U postgres`로 접속).

```sql
DROP DATABASE hops;
CREATE DATABASE hops OWNER hops;
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
- 로컬 개발용 가상 데이터는 `db/seed`에 둡니다. `db` 프로필은 `db/migration`과 `db/seed`를 함께 실행하고, 테스트는 `db/migration`만 사용합니다. Flyway와 함께 쓰지 않도록 `data.sql`은 사용하지 않습니다.
- `ddl-auto=validate`로 Entity와 DB 구조가 맞는지만 검사하고 자동 DDL 변경은 하지 않습니다. SQL 초기화도 비활성화합니다.
- `open-in-view=false`로 요청 렌더링 단계의 지연 로딩을 사용하지 않습니다.
- Entity는 `global/entity/BaseEntity`를 상속하면 `id`(UUIDv7), `createdAt`, `updatedAt`, `deletedAt`(소프트 삭제)을 공통으로 갖습니다.

## 공통 기능 사용법

| 기능 | 위치 | 쓰는 방법 |
|---|---|---|
| 공통 오류 응답 | `global/exception` | Controller·Service에서 `throw new BusinessException(ErrorCode.NOT_FOUND)`처럼 던지면 `{code, message, requestId, retryable}` 형식으로 응답합니다 ([API 설계](../../../docs/API.md) §1). try-catch는 쓰지 않습니다. |
| 요청 추적 번호 | `global/filter/RequestIdFilter` | 모든 응답 헤더와 로그 줄에 `X-Request-Id`(requestId)가 붙습니다. 오류 문의 시 이 값으로 로그를 찾습니다. |
| API 호출 로그 | `global/filter/RequestLoggingFilter` | 요청 전 `→ GET /path`, 요청 후 `← GET /path 200 (12ms)`를 남깁니다. 본문·헤더·쿼리는 기록하지 않습니다. |
| 시각 | `application.properties` | API 응답과 DB 모두 UTC입니다. 한국 시간 표시는 화면에서 합니다. |
| 접근 규칙 | `global/security/SecurityConfig` | `POST /api/auth/login`, Swagger, `/error`만 토큰 없이 접근할 수 있고 나머지는 인증이 필요합니다. 새 공개 주소가 필요하면 이 파일에 추가합니다. |
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

- 서버를 띄우는 테스트는 `@ActiveProfiles({"db", "test"})`로 로컬 PostgreSQL의 테스트 전용 `hops_test` DB에 연결합니다 (`src/test/resources/application-test.properties`). 위 "처음 설치" 2~4번을 마쳐야 통과합니다.
- 테스트 DB에는 가상 데이터(seed)를 넣지 않습니다. 필요한 데이터는 각 테스트가 직접 넣고 `@Transactional`로 끝나면 되돌립니다 (예: `MemberRepositoryTest`).
- 로그인 판단 규칙처럼 DB가 필요 없는 테스트는 Repository를 mock으로 바꿔 빠르게 확인합니다 (예: `AuthServiceTest`).

## 인증

로그인하면 Spring이 서명한 액세스 토큰(JWT)을 발급하고, 이후 요청은 `Authorization: Bearer <토큰>`으로 인증합니다. 응답·토큰 모양은 [데이터 타입](../../../specs/contracts/DATA_TYPES.md)의 `LoginResponse`·`CurrentUser`와 [API 설계](../../../docs/API.md) §2.5를 따릅니다. 계정은 위 "가상 데이터"의 표를 참고합니다.

| API | 요청 | 성공 | 실패 |
|---|---|---|---|
| `POST /api/auth/login` | `{loginId, password}` | 200 `{accessToken, expiresAt, user}` | 400 `INVALID_INPUT`(빈 값·형식 오류), 401 `UNAUTHENTICATED`(아이디·비밀번호 불일치, 차단 계정) |
| `GET /api/auth/me` | `Authorization: Bearer <토큰>` | 200 `{id, employeeNo, name, department, roles}` | 401 `UNAUTHENTICATED`(토큰 없음·위조·만료, 회원 없음·차단) |

- **동작 순서:** 로그인 ID로 회원·부서·역할을 DB에서 찾고(삭제된 회원 제외) → BCrypt로 비밀번호 해시 비교 → `AccessTokenIssuer`가 토큰 발급 (`auth/service`).
- **토큰 클레임:** `sub`(회원 UUID), `employeeNo`, `departmentId`·`departmentCode`·`departmentName`, `roles`, `iss`·`aud`·`iat`·`exp`(발급 후 15분). 토큰 내용은 누구나 읽을 수 있으므로 비밀번호 등은 넣지 않습니다.
- **`/me`는 토큰이 아니라 DB를 다시 읽습니다.** 토큰 발급 뒤 역할 회수·부서 변경·계정 차단이 있었을 수 있기 때문입니다.
- 로그인 ID(`loginId`)와 사번(`employeeNo`)은 [ERD 설명](../../../docs/ERD_설명.md)의 `member.login_id`, `member.employee_no`처럼 별도 값입니다. 아이디·비밀번호 중 무엇이 틀렸는지는 응답에서 구분하지 않습니다.
- **로그:** 로그인 성공·실패 이유와 회원 ID를 남기고(`requestId`로 찾기), 비밀번호·찾지 못한 로그인 ID 원문·토큰은 남기지 않습니다.
- **아직 없음:** refresh 토큰(HttpOnly 쿠키), `POST /api/auth/logout`.

### 토큰 직접 확인하기

```bash
# 1) 로그인해서 토큰 저장
TOKEN=$(curl -s -H 'Content-Type: application/json' -d '{"loginId":"bizadmin","password":"hops1234!"}' http://localhost:8080/api/auth/login | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
# 2) 토큰 내용(클레임) 보기 — 서명 검증은 하지 않고 읽기만 한다
echo "$TOKEN" | python3 -c 'import sys,base64,json; p=sys.stdin.read().strip().split(".")[1]; print(json.dumps(json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4))),ensure_ascii=False,indent=2))'
# 3) 서버가 유효하다고 보는지 확인 — 200이면 서명·만료·발급자·대상 통과
curl -s -w ' [%{http_code}]\n' -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/auth/me
```

Swagger에서는 `POST /api/auth/login` 응답의 `accessToken`을 따옴표 없이 복사해 오른쪽 위 **Authorize**에 넣습니다(`Bearer `는 붙이지 않음).
