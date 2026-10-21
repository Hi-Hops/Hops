# Hops Spring 백엔드

Spring Boot 프로젝트 루트는 `backend/spring/hops/`입니다. IntelliJ에서는 해당 디렉터리를 Gradle 프로젝트로 엽니다. 빌드·테스트·실행 명령은 이 디렉터리에서 실행합니다.

## 패키지 구성

`com.hihops.hops` 아래에 `auth`, `user`, `department`, `inquiry`, `ticket`, `agent`, `investigation`, `global` 패키지를 구성했습니다. 각 패키지의 `package-info.java`에 역할을 기록했으며 실제 기능 클래스는 아직 구현하지 않았습니다.

| 패키지 | 역할 |
|---|---|
| `auth` / `user` | 로그인·인증 / 사용자 정보 |
| `department` | 부서·소속·활성 여부·R&R |
| `inquiry` | 문의·대화·내용 확인·정정 |
| `ticket` | 티켓 저장·배정·처리 |
| `agent` | Python 실행 요청·진행·결과 통신 |
| `investigation` | 읽기 전용 진단 조회의 검증·실행 경계 |
| `global` | 공통 설정·보안 기반·예외 처리 |

업무 기능은 `controller`, `dto`, `service`, `domain`, `repository`로 구분합니다. `agent`는 `client`, `dto`, `service`, `investigation`은 `controller`, `dto`, `service`, `client`로 구성합니다. 독립 `ownership`은 현재 추가하지 않습니다. 실제 서비스 간 입출력·인증·오류 계약은 [프로젝트 개발 안내](../../../README.md#첫-작업)에서 연결하는 API 설계와 [Agent 공통 계약](../../../contracts/agents/README.md)에서 관련 담당자와 합의한 뒤 구현합니다.

## DB 설정

영속성은 Spring Data JPA와 PostgreSQL을 사용할 예정입니다. 현재 기본 `local` 프로필에서는 DataSource 자동 설정을 제외해 DB 없이 서버를 실행합니다. DB가 준비되면 `SPRING_PROFILES_ACTIVE=db`를 설정하고 IntelliJ의 실행 구성 또는 셸에서 아래 표준 Spring 환경변수를 설정합니다. 실제 접속 정보는 저장소에 기록하지 않습니다. Spring Boot는 `.env` 파일을 자동으로 읽지 않습니다.

| 환경변수 | 값 |
|---|---|
| `SPRING_DATASOURCE_URL` | 사용할 PostgreSQL의 JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 |

`ddl-auto=validate`로 Entity와 DB 구조를 검증하고 자동 DDL 변경은 하지 않습니다. SQL 초기화도 비활성화합니다. Entity·테이블·migration은 아직 구현하지 않았으며 migration 도구와 관리 방식은 별도 결정합니다. `open-in-view=false`로 요청 렌더링 단계의 지연 로딩을 사용하지 않습니다.

## 테스트·빌드·실행

Java 21을 사용하고 이 README가 있는 `backend/spring/hops`에서 실행합니다.

```bash
./gradlew test
./gradlew bootJar
./gradlew bootRun
```

기본 테스트는 `local` 프로필의 컨텍스트 기동과 DataSource가 생성되지 않음을 검사합니다. PostgreSQL 연결·JPA 저장 검증을 대신하지 않습니다. `db` 프로필은 local 설정을 로드하지 않아 정상적인 DataSource/JPA 자동 설정을 사용하며 유효한 DB 접속 설정이 필요합니다. `local`과 `db` 프로필을 동시에 활성화하지 않습니다. DB 의존 기능 구현 후에는 해당 기능을 local에서도 사용할 수 있다고 가정하지 않습니다. 아직 Controller가 없어 `/` 요청은 404입니다.
