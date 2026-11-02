# Hops — 사내 IT 장애·문의 대응 에이전트

사내 IT 문의를 대화로 구체화하고 책임 시스템의 주담당자에게 연결하며, 검증된 해결 사례를 축적해 재사용하는 프로젝트입니다.

4명·약 10일을 출발 조건으로 계획합니다. MVP는 모델 기반 Supervisor와 Inquiry·Knowledge·Ticket·Curator, 시스템별 담당자 배정, 해결 확인과 승인된 사례의 Vector·Neo4j 재검색을 포함합니다. 실제 구현과 통합 검증은 별도입니다.

## 읽는 순서

1. [AGENTS.md](AGENTS.md): 팀과 AI의 공통 작업 규칙.
2. [PRD](docs/PRD.md): 제품 목적·사용자·기능·범위·성공 기준.
3. [Git 컨벤션](GIT_CONVENTION.md): 브랜치·커밋·PR·리뷰 방법.
4. [보안 개발 명세](docs/SECURITY.md): AI와 서비스 개발의 신뢰 경계·OWASP 대응·검증 기준.
5. [공통 구현 계약](specs/contracts/README.md): Frontend·Spring·AI 서버가 함께 검토하는 업무 상태·데이터·전송·역할의 정본과 읽는 순서. PRD 1.44 신규 계약안과 현재 구현 프로필을 구분합니다.

Claude Code의 진입점은 [CLAUDE.md](CLAUDE.md)이며 AGENTS.md를 먼저 읽도록 안내합니다.

## 첫 작업

### Docker Compose로 실행

Docker Desktop의 Linux 컨테이너 또는 Docker Engine·Compose 2.20 이상을 실행한 뒤, 아래 명령을 **저장소 루트**에서 실행합니다.

처음 한 번만 루트 [.env.example](.env.example)을 `.env`로 복사합니다. 기존 `.env`가 있으면 유지합니다.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

macOS/Linux에서는 `test -f .env || cp .env.example .env`를 사용합니다. 복사한 `.env`에서 `POSTGRES_PASSWORD`와 ASCII 32자 이상의 `HOPS_JWT_SECRET`을 입력합니다. 프론트·백엔드·AI·DB 설정은 모두 같은 파일에서 관리하며, 기존 루트 `.env`에는 필요한 새 항목만 추가합니다.

```sh
docker compose up --build -d
docker compose ps
```

Frontend·Spring·AI·PostgreSQL 컨테이너 4개가 함께 실행됩니다. 기본 접속 주소는 프론트 `http://localhost:5173`, Spring Swagger `http://localhost:8080/swagger-ui/index.html`, AI 생존 확인 `http://localhost:8000/healthz`이며 DB는 `localhost:5432`입니다. 모든 포트는 로컬 PC에만 공개합니다.

DB는 [pgvector 프로젝트 이미지](https://github.com/pgvector/pgvector#docker)의 PostgreSQL 18·pgvector 0.8.7을 사용합니다. Spring Flyway가 확장 활성화, 테이블과 개발용 가상 데이터를 적용하므로 pgvector를 따로 설치할 필요가 없습니다. 테스트 DB는 [Spring 설치 안내](backend/spring/hops/README.md#처음-설치-mac--windows)에 따라 별도로 준비합니다.

AI 모드·예산은 `HOPS_AI_*`로 설정합니다. [OpenAI](https://developers.openai.com/api/docs/quickstart)의 `OPENAI_API_KEY`와 [Langfuse](https://langfuse.com/docs/observability/sdk/troubleshooting-and-faq)의 `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, `LANGFUSE_BASE_URL`는 같은 `.env`에 선택 입력하며 AI 컨테이너에만 전달합니다. Langfuse 주소는 사용하는 리전 또는 자체 호스팅 주소로 설정합니다. 현재 모델 호출·추적 연동은 구현 전입니다.

프론트는 기존 `/api`에 연결하며 신규 `/api/v2` 전환은 별도 작업입니다. AI는 업무 파이프라인 미구현 상태의 골격으로 `/healthz`는 200, `/readyz`는 503입니다. 프론트 단독 Mock 실행은 [Frontend 안내](frontend/README.md)를 따릅니다.

#### 기존 서비스별 설정에서 전환

기존 `frontend/.env`, `ai/.env`, Spring의 `application-db.local.properties`는 보존할 수 있습니다. Docker는 이 파일들을 읽거나 이미지에 넣지 않으므로 필요한 값을 루트 `.env`로 옮깁니다. `npm`, `uv`, Gradle로 PC에서 직접 실행할 때는 각 서비스 README의 설정을 따릅니다.

- **실행 위치:** 통합 실행 명령은 저장소 루트에서 사용합니다. `frontend/` 안에서 `docker compose up`을 실행하면 프론트 단독 Compose와 그 폴더의 기존 `.env`가 선택됩니다. 프론트만 실행할 때도 [Frontend 안내](frontend/README.md)의 루트 기준 명령을 사용합니다.
- **포트 중복:** 이전 컨테이너나 직접 실행한 서버가 같은 포트를 사용하면 해당 실행을 종료하거나 루트 `.env`의 `FRONTEND_PORT`, `BACKEND_PORT`, `AI_PORT`, `POSTGRES_PORT`를 바꿉니다. `VITE_API_BASE_URL`, `HOPS_CORS_ALLOWEDORIGINS`를 비우면 포트에 맞춰 API·CORS 주소를 구성하며, 별도 주소가 필요하면 직접 입력합니다. 기본 프론트 단독 프로젝트는 루트에서 `docker compose --env-file .env -f frontend/compose.yaml down`으로 종료할 수 있습니다.
- **기존 DB:** 통합 Compose의 DB 볼륨을 이미 사용했다면 DB 이름·계정·비밀번호를 루트 `.env`에 유지합니다. [PostgreSQL 초기 설정 변수](https://github.com/docker-library/docs/blob/master/postgres/README.md#environment-variables)는 기존 DB 값을 바꾸지 않으므로 다른 비밀번호를 입력하면 DB 접속이 실패합니다. PC에 직접 설치한 DB의 데이터도 Docker 볼륨으로 자동 이관되지 않습니다.
- **셸 환경변수:** 터미널·IDE에서 설정한 같은 이름의 환경변수는 [루트 `.env`보다 우선](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/#ways-to-set-variables-with-interpolation)합니다. 루트 설정이 적용되지 않으면 해당 셸 변수를 확인·해제합니다. 폐기된 `HOPS_AI_MODE=fake`도 남아 있지 않은지 확인합니다.

실행 전 `docker compose config --quiet`로 Compose 문법·필수 값 누락을 확인할 수 있습니다. AI 설정 범위와 JWT 키 길이는 각 서비스가 시작할 때 검증하고 DB 접속도 기동 시 확인합니다. 이 명령이나 설정 회귀 테스트는 실제 API 키의 유효성을 확인하지 않습니다.

#### 재빌드·로그·종료

소스를 바꾼 서비스만 다시 빌드할 수 있습니다. 아래 `backend`를 `frontend` 또는 `ai`로 바꿔 사용합니다. 루트 `.env`만 바꾸면 `docker compose up -d`로 반영합니다.

```sh
docker compose up --build -d backend
docker compose logs -f backend ai
docker compose down
```

일반 종료·재빌드 후에도 DB 볼륨은 유지됩니다. `docker compose down --volumes`는 DB 데이터를 삭제합니다.

### 구현 준비

팀이 합의한 PRD를 바탕으로 구현 세부 계약과 역할을 정합니다. 전체 데이터 관계는 [데이터 설계](docs/DATA.md), React·Spring·Python 연결은 [API 설계](docs/API.md)를 읽고 기능별 계약을 리뷰합니다. 두 문서는 구현 전 검토할 설계안입니다. 이번 공통 계약은 Markdown의 필드·타입·상태·호출 예시로 공유합니다. 검증용 Schema와 샘플 JSON은 개인 로컬 자료로 관리합니다.

목표 기술 구성은 React·Java 21 기반 Spring·Python 3.13 + LangGraph + FastAPI·PostgreSQL입니다. Node.js는 React 개발·빌드 런타임으로 사용하며 별도의 실시간 중계 서버는 두지 않습니다. Spring은 JWT 발급·인증·권한, 메시지·업무 저장과 브라우저 SSE 스트림을 담당합니다. JWT에는 사번과 소속 부서명을 포함하며 상세 클레임·인증·이벤트 계약은 API 설계에서 정리합니다. 로그인·조회·문의·티켓 업무는 React–Spring REST, 진행 상태·답변 조각·저장 결과 알림은 Spring–React SSE, AI 진행 전달은 Python–Spring SSE를 사용합니다.

프론트 빌드용 Node.js 24.22.0, React 19.3.0, Spring Boot 4.1.1, Gradle Wrapper 9.8.1, LangGraph 1.2.16, FastAPI 0.143.5, Uvicorn 0.53.0을 개발 기준으로 사용합니다. 서비스별 실제 실행·버전과 검증 범위는 각 서비스 개발 안내와 설정 파일을 따릅니다. OpenAI API 기반 LLM과 비용 최소화를 적용하며, 정확한 모델·공용 환경·실행 명령은 구현과 실측을 통해 정합니다. Spring의 PostgreSQL 계정·부서 저장 기반, JWT 로그인·내 정보 조회와 부서 SSE를 구현했습니다. 프론트에서 실제 Spring·PostgreSQL의 로그인·내 정보·부서 SSE 연결 및 재동기화 알림 수신을 검증했습니다. 문의·티켓 업무 API와 AI 전체 통합은 미완료이며, 담당별 구현·검증 범위는 [공통 계약 11절](specs/contracts/WIRE_CONTRACT.md#11-담당별-구현과-검증-상태)을 따릅니다.

에이전트별 개발을 시작할 때는 [공통 입출력 계약](contracts/agents/README.md)을 읽고, [개발·통합 준비 안내](docs/AGENT_DEVELOPMENT.md)에서 본인의 준비물과 통합 완료 조건을 확인합니다. Python 내부 역할 계약과 기존 서비스 API를 구분합니다. MVP에는 실제 멀티 에이전트를 연결하며 단일 에이전트 기준선은 필수가 아닙니다. Fake는 개발 중 연결 테스트에만 사용합니다.

## 구성별 개발 안내

- [Spring 백엔드](backend/spring/hops/README.md): 패키지 구성, DB 설정, 테스트·빌드·실행 방법.
- [AI 서버](ai/README.md): 구성, 환경변수, 실행·동작 확인·테스트 방법, 현재 구현 범위와 제한.

## 개인 자료와 공통 변경

개인 AI 기록·실험·임시 자료는 `.local/`에 두고 공유하지 않습니다. 담당자는 다른 데이터셋을 자유롭게 탐색할 수 있으며 공통 자료와 설정은 팀 검토 후 반영합니다. 문서와 코드 변경은 PR 리뷰를 거칩니다. 문서의 목표 설계와 실제 구현 상태는 구분합니다.
