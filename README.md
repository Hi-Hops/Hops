# Hops — 사내 IT 장애·문의 대응 에이전트

사내 IT 문의를 대화로 분석하고, 해결을 안내하거나 진단 근거를 담은 티켓으로 만들어 담당 부서에 연결하는 프로젝트입니다.

4명이 MVP 1주와 후속 2주를 계획합니다. MVP는 Supervisor와 4개 Fake 에이전트 기반 서비스 연결·복수 티켓 생성·부서 배정·관리자 조회이며, 최종 구조는 Supervisor와 4개 전문 에이전트입니다.

## 읽는 순서

1. [AGENTS.md](AGENTS.md): 팀과 AI의 공통 작업 규칙.
2. [PRD](docs/PRD.md): 제품 목적·사용자·기능·범위·성공 기준.
3. [Git 컨벤션](GIT_CONVENTION.md): 브랜치·커밋·PR·리뷰 방법.

Claude Code의 진입점은 [CLAUDE.md](CLAUDE.md)이며 AGENTS.md를 먼저 읽도록 안내합니다.

## 첫 작업

팀이 PRD와 MVP 범위를 함께 검토하고 역할을 나눕니다. ERD는 [MVP 데이터 설계](docs/MVP_DATA.md), 프론트·Spring·Python 연결은 [MVP API](docs/MVP_API.md)를 읽고 최소 계약을 리뷰합니다. 두 문서는 첫 E2E 범위의 설계안입니다. 합의한 API는 OpenAPI와 JSON Schema로 구체화합니다.

기술 구성은 React·Java 21 기반 Spring·Python 3.13 + LangGraph + FastAPI·PostgreSQL입니다. 개발 버전은 Node.js 24.22.0, React 19.3.0, Spring Boot 4.1.1, LangGraph 1.2.16, FastAPI 0.143.5, Uvicorn 0.53.0입니다. 전체 버전과 검증 범위는 [개발 안내](docs/AGENT_DEVELOPMENT.md)의 9~11장을 따릅니다. 직접 실행 LLM과 비용 최소화를 우선하며, 정확한 모델·공용 환경·실행 명령은 구현과 실측을 통해 정합니다. 현재는 초기 문서를 준비한 단계입니다.

에이전트별 개발을 시작할 때는 [공통 입출력 계약](contracts/agents/README.md)을 읽고, [개발·통합 준비 안내](docs/AGENT_DEVELOPMENT.md)에서 본인의 준비물과 통합 완료 조건을 확인합니다. Python 내부 역할 계약과 기존 MVP 서비스 API를 구분하며, 공통 기반과 Fake를 먼저 연결합니다.

## Spring 프로젝트 위치

Spring Boot 프로젝트 루트는 `backend/spring/hops/`입니다. IntelliJ에서는 해당 디렉터리를 Gradle 프로젝트로 엽니다. 빌드·테스트·실행 명령은 이 디렉터리에서 실행합니다.

### 패키지 구성과 DB 설정

`com.hihops.hops` 아래 기능 패키지는 `auth`, `user`, `department`, `inquiry`, `ticket`, `agent`, `investigation`이며 공통 기반은 `global`에 둡니다. 각 패키지의 `package-info.java`에 책임을 기록했습니다. 기능 패키지는 `controller`, `dto`, `service`, `domain`, `repository`로 구분하며 실제 클래스는 기능 구현 시 추가합니다. `agent`는 DB 저장 대신 `client`, `dto`, `service`로 구성합니다. `investigation`은 `controller`, `dto`, `service`, `client`로 구성합니다. 독립 `ownership`은 추가하지 않고 부서 R&R은 `department`, 실제 배정은 `ticket`에서 담당합니다. [Spring 패키지 경계](docs/SPRING_PACKAGE_BOUNDARIES.md)를 참고합니다.

영속성은 Spring Data JPA와 PostgreSQL을 사용할 예정입니다. 현재 기본 `local` 프로필에서는 DataSource 자동 설정을 제외해 DB 없이 서버를 실행합니다. DB가 준비되면 `SPRING_PROFILES_ACTIVE=db`를 설정하고 IntelliJ의 실행 구성 또는 셸에서 아래 표준 Spring 환경변수를 설정합니다. 실제 접속 정보는 저장소에 기록하지 않습니다. Spring Boot는 `.env` 파일을 자동으로 읽지 않습니다.

| 환경변수 | 값 |
|---|---|
| `SPRING_DATASOURCE_URL` | 사용할 PostgreSQL의 JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 |

`ddl-auto=validate`로 Entity와 DB 구조를 검증하고 자동 DDL 변경은 하지 않습니다. SQL 초기화도 비활성화합니다. Entity·테이블·migration은 아직 구현하지 않았으며 migration 도구와 관리 방식은 별도 결정합니다. `open-in-view=false`로 요청 렌더링 단계의 지연 로딩을 사용하지 않습니다.

Java 21을 사용하고 `backend/spring/hops`에서 실행합니다.

```bash
./gradlew test
./gradlew bootJar
./gradlew bootRun
```

기본 테스트는 `local` 프로필의 컨텍스트 기동과 DataSource가 생성되지 않음을 검사합니다. PostgreSQL 연결·JPA 저장 검증을 대신하지 않습니다. `db` 프로필은 local 설정을 로드하지 않아 정상적인 DataSource/JPA 자동 설정을 사용하며 유효한 DB 접속 설정이 필요합니다. `local`과 `db` 프로필을 동시에 활성화하지 않습니다. DB 의존 기능 구현 후에는 해당 기능을 local에서도 사용할 수 있다고 가정하지 않습니다. 아직 Controller가 없어 `/` 요청은 404입니다.

## 개인 자료와 공통 변경

개인 AI 기록·실험·임시 자료는 `.local/`에 두고 공유하지 않습니다. 담당자는 다른 데이터셋을 자유롭게 탐색할 수 있으며 공통 자료와 설정은 팀 검토 후 반영합니다. 문서와 코드 변경은 PR 리뷰를 거칩니다. 초기 문서의 존재가 팀 승인이나 구현 완료를 뜻하지는 않습니다.
