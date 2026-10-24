# Hops Spring 백엔드

Spring Boot 프로젝트 루트는 `backend/spring/hops/`입니다. IntelliJ에서는 해당 디렉터리를 Gradle 프로젝트로 엽니다. 빌드·테스트·실행 명령은 이 디렉터리에서 실행합니다.

## 패키지 구성

`com.hihops.hops` 아래에 `auth`, `user`, `department`, `inquiry`, `ticket`, `agent`, `investigation`, `global` 패키지를 구성했습니다. 각 패키지의 `package-info.java`에 역할을 기록했으며 실제 기능 클래스는 아직 구현하지 않았습니다.

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

## JWT 인증과 Socket.IO 연동 설계

아래는 [PRD](../../../docs/PRD.md)와 [API 설계](../../../docs/API.md)에 따른 구현 목표입니다. JWT 검증만 구현했고, 발급(로그인)과 Socket.IO 연동은 아직 구현하지 않았습니다. JWT는 Spring만 발급·검증하기로 팀에서 정했습니다(문서 수정 예정).

- `auth`는 자체 계정 로그인과 JWT 발급을 담당하고, `user`·`department`·역할 정보에서 사번·소속 부서·권한 문맥을 구성합니다. `global/security`에서 JWT를 검증하고 업무별 접근 권한은 해당 서비스에서 확인합니다. 클레임·토큰 수명·갱신/폐기·보관/전달·키 관리 정책은 API 설계를 기준으로 합의합니다.
- Spring은 Java용 Socket.IO 클라이언트로 Node.js 채팅 서버에 연결해 채팅 명령을 수신하고 진행·저장 결과를 발행합니다. React도 같은 서버의 클라이언트로 연결합니다. Socket.IO 연결 모듈의 패키지 위치와 클라이언트 호환 버전은 구현 전에 정합니다.
- 연결 모듈은 인증된 요청 문맥·요청 식별자·문의 버전을 업무 서비스에 전달합니다. `inquiry`·`ticket`이 권한·중복 요청을 검증하고 DB 저장을 확정한 뒤 결과를 발행합니다. Node.js 수신 확인과 실제 저장 성공을 구분하고, 재연결·ACK 유실 시에도 같은 업무를 중복 반영하지 않습니다.
- `agent`는 Python HTTP 실행 요청과 SSE 진행·결과 수신을 담당합니다. 공개 가능한 진행·답변 조각은 Spring의 검증 후 Node.js를 통해 React에 전달하고 최종 메시지·업무 변경은 저장 결과로 확정합니다. 로그인·목록·상세 조회와 티켓 업무 REST는 Spring이 제공합니다. 구독 권한·토큰 만료·부서 변경·재접속 복구의 상세 계약은 API 설계를 따릅니다.

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
   - Mac: `SPRING_PROFILES_ACTIVE=db ./gradlew bootRun`
   - Windows(PowerShell): `$env:SPRING_PROFILES_ACTIVE="db"; .\gradlew.bat bootRun`

## DB 설정

영속성은 Spring Data JPA와 PostgreSQL을 사용합니다. 기본 `local` 프로필은 DataSource 자동 설정을 제외해 DB 없이 서버를 실행합니다. DB를 쓰려면 `SPRING_PROFILES_ACTIVE=db`로 실행합니다. `db` 프로필은 `application-db.local.properties`(git 제외)에서 접속 정보를 읽습니다. 실제 접속 정보는 저장소에 기록하지 않습니다. 아래 표준 Spring 환경변수를 설정하면 파일 값보다 우선합니다.

| 환경변수 | 값 |
|---|---|
| `SPRING_DATASOURCE_URL` | 사용할 PostgreSQL의 JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 |

- 테이블 생성·변경은 **Flyway**로 관리합니다. `src/main/resources/db/migration`에 `V1__설명.sql`처럼 버전 붙은 SQL을 추가하면 서버 시작 시 아직 적용하지 않은 것만 실행합니다. 이미 적용된 파일은 고치지 않고 새 버전 파일을 추가합니다.
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
| JWT 검증 | `global/security/JwtConfig` | `Authorization: Bearer 토큰`의 서명(HS256)·만료·발급자(`hops.jwt.issuer`)·대상(`hops.jwt.audience`)을 검사합니다. Controller에서 `@AuthenticationPrincipal Jwt jwt`로 클레임을 꺼내고, `roles`는 `hasRole('AGENT')`처럼 씁니다. `hops.jwt.secret`은 개발용 값이며 공용·운영 서버에서는 `HOPS_JWT_SECRET` 환경변수로 바꿉니다. 발급(로그인)은 아직 구현하지 않았습니다. |
| CORS | `hops.cors.allowed-origins` | 기본값은 Vite 개발 서버 `http://localhost:5173`입니다. |
| Swagger UI | `global/config/OpenApiConfig` | 서버 실행 후 `http://localhost:8080/swagger-ui/index.html`. 오른쪽 위 Authorize에 JWT를 넣어 호출합니다. 계약 기준은 Swagger가 아니라 저장소의 OpenAPI YAML입니다. |
| Python AI 서버 호출 | `agent/client` | `@Qualifier(AgentClientConfig.AGENT_REST_CLIENT) RestClient`를 주입해 씁니다. 주소는 `hops.agent.base-url`(기본 `http://localhost:8000`), 응답 대기 90초입니다. 경로·요청 형식은 AI 담당자와 합의 후 추가합니다. |

## 테스트·빌드·실행

Java 21, Gradle Kotlin DSL과 Gradle Wrapper 9.8.1을 사용하고 이 README가 있는 `backend/spring/hops`에서 저장소의 Wrapper로 실행합니다. Windows는 `./gradlew` 대신 `.\gradlew.bat`을 씁니다.

```bash
./gradlew test
./gradlew bootJar
./gradlew bootRun
```

- 대부분의 테스트는 DB 없이 `local` 프로필로 동작합니다.
- `BaseEntityTest`는 로컬 PostgreSQL의 `hops_test` DB에 직접 연결합니다. 위 "처음 설치" 2~4번을 마쳐야 통과합니다.
- `local`과 `db` 프로필을 동시에 활성화하지 않습니다. DB 의존 기능 구현 후에는 해당 기능을 local에서도 사용할 수 있다고 가정하지 않습니다.
