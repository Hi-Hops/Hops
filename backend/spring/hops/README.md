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

아래는 [PRD](../../../docs/PRD.md)와 [API 설계](../../../docs/API.md)에 따른 구현 목표입니다. JWT 인증과 Socket.IO 연동은 아직 구현하지 않았습니다.

- `auth`는 자체 계정 로그인과 JWT 발급을 담당하고, `user`·`department`·역할 정보에서 사번·소속 부서·권한 문맥을 구성합니다. `global/security`에서 JWT를 검증하고 업무별 접근 권한은 해당 서비스에서 확인합니다. 클레임·토큰 수명·갱신/폐기·보관/전달·키 관리 정책은 API 설계를 기준으로 합의합니다.
- Spring은 Java용 Socket.IO 클라이언트로 Node.js 채팅 서버에 연결해 채팅 명령을 수신하고 진행·저장 결과를 발행합니다. React도 같은 서버의 클라이언트로 연결합니다. Socket.IO 연결 모듈의 패키지 위치와 클라이언트 호환 버전은 구현 전에 정합니다.
- 연결 모듈은 인증된 요청 문맥·요청 식별자·문의 버전을 업무 서비스에 전달합니다. `inquiry`·`ticket`이 권한·중복 요청을 검증하고 DB 저장을 확정한 뒤 결과를 발행합니다. Node.js 수신 확인과 실제 저장 성공을 구분하고, 재연결·ACK 유실 시에도 같은 업무를 중복 반영하지 않습니다.
- `agent`는 Python HTTP 실행 요청과 SSE 진행·결과 수신을 담당합니다. 공개 가능한 진행·답변 조각은 Spring의 검증 후 Node.js를 통해 React에 전달하고 최종 메시지·업무 변경은 저장 결과로 확정합니다. 로그인·목록·상세 조회와 티켓 업무 REST는 Spring이 제공합니다. 구독 권한·토큰 만료·부서 변경·재접속 복구의 상세 계약은 API 설계를 따릅니다.

## DB 설정

영속성은 Spring Data JPA와 PostgreSQL을 사용할 예정입니다. 현재 기본 `local` 프로필에서는 DataSource 자동 설정을 제외해 DB 없이 서버를 실행합니다. DB가 준비되면 `SPRING_PROFILES_ACTIVE=db`를 설정하고 IntelliJ의 실행 구성 또는 셸에서 아래 표준 Spring 환경변수를 설정합니다. 실제 접속 정보는 저장소에 기록하지 않습니다. Spring Boot는 `.env` 파일을 자동으로 읽지 않습니다.

| 환경변수 | 값 |
|---|---|
| `SPRING_DATASOURCE_URL` | 사용할 PostgreSQL의 JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 |

`ddl-auto=validate`로 Entity와 DB 구조를 검증하고 자동 DDL 변경은 하지 않습니다. SQL 초기화도 비활성화합니다. Entity·테이블·migration은 아직 구현하지 않았으며 migration 도구와 관리 방식은 별도 결정합니다. `open-in-view=false`로 요청 렌더링 단계의 지연 로딩을 사용하지 않습니다.

## 테스트·빌드·실행

Java 21, Gradle Kotlin DSL과 Gradle Wrapper 9.8.1을 사용하고 이 README가 있는 `backend/spring/hops`에서 저장소의 Wrapper로 실행합니다.

```bash
./gradlew test
./gradlew bootJar
./gradlew bootRun
```

기본 테스트는 `local` 프로필의 컨텍스트 기동과 DataSource가 생성되지 않음을 검사합니다. PostgreSQL 연결·JPA 저장 검증을 대신하지 않습니다. `db` 프로필은 local 설정을 로드하지 않아 정상적인 DataSource/JPA 자동 설정을 사용하며 유효한 DB 접속 설정이 필요합니다. `local`과 `db` 프로필을 동시에 활성화하지 않습니다. DB 의존 기능 구현 후에는 해당 기능을 local에서도 사용할 수 있다고 가정하지 않습니다. 아직 Controller가 없어 `/` 요청은 404입니다.
