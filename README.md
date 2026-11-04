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

Docker Desktop의 Linux 컨테이너 또는 Docker Engine·Compose 2.20 이상을 준비합니다. 다음 명령은 **저장소 루트**에서 실행합니다.

#### 1. 서비스별 환경변수 파일 준비

Frontend·Backend·AI의 `.env.example`을 같은 폴더의 `.env`로 복사합니다. Backend 파일에 PostgreSQL 설정도 함께 둡니다. 기존 개인 파일은 덮어쓰지 않습니다.

```powershell
$examples = @('frontend/.env.example', 'backend/spring/hops/.env.example', 'ai/.env.example')
foreach ($example in $examples) {
    $target = $example -replace '\.example$', ''
    if (-not (Test-Path $target)) { Copy-Item $example $target }
}
```

macOS/Linux에서는 다음과 같이 복사합니다.

```sh
for example in frontend/.env.example backend/spring/hops/.env.example ai/.env.example; do
  target="${example%.example}"
  test -f "$target" || cp "$example" "$target"
done
```

#### 2. 필수 설정 입력

- [Frontend 예시](frontend/.env.example): 공개 포트·`VITE_*` 설정과 가상 Mock 값.
- [Backend 예시](backend/spring/hops/.env.example): PostgreSQL·Spring·JWT·CORS·쿠키 설정. `POSTGRES_PASSWORD`와 ASCII 32자 이상의 `HOPS_JWT_SECRET`을 입력합니다. Compose가 같은 DB 값을 PostgreSQL과 Spring datasource에 각각 매핑합니다.
- [AI 예시](ai/.env.example): `HOPS_AI_*`와 기존 선택적인 OpenAI·Langfuse 설정. 주석 처리된 선택 항목은 필요한 경우에만 입력합니다. 외부 연동 자체는 아직 미구현입니다.

#### 3. 전체 서비스 실행

루트 `compose.yaml`이 `compose.services.yaml`을 불러오면서 서비스별 `.env` 세 개를 자동으로 읽습니다. 별도 루트 `.env`나 `--env-file` 옵션은 필요하지 않습니다. 기존 `environment` 매핑으로 각 컨테이너에 필요한 변수만 전달하므로 Backend의 JWT 키가 PostgreSQL·Frontend에 전달되지 않습니다. 서비스 구성은 `compose.services.yaml`에서 수정합니다.

먼저 설정을 확인한 뒤 전체 서비스를 백그라운드로 실행합니다. 처음 실행할 때 로컬 이미지가 없으면 자동으로 빌드합니다. 소스를 변경한 뒤에는 `docker compose up --build -d`로 다시 빌드합니다.

```sh
docker compose config --quiet
docker compose up -d
docker compose ps
```

Frontend·Spring·AI·PostgreSQL·Redis 컨테이너 5개가 실행됩니다. PostgreSQL·Redis는 기존처럼 별도 컨테이너이며 DB 데이터 볼륨과 서비스 의존 관계도 유지합니다. 기본 주소는 Frontend `http://localhost:5173`, Spring Swagger `http://localhost:8080/swagger-ui/index.html`, AI 생존 확인 `http://localhost:8000/healthz`, DB `localhost:5432`입니다. Redis는 PC에 포트를 공개하지 않습니다.

DB는 PostgreSQL 18·pgvector 0.8.7을 사용하고 Spring Flyway가 확장·테이블·개발용 가상 데이터를 적용합니다. AI 전용 `hops_ai_runtime` DB·계정은 기존처럼 별도 준비합니다. AI의 `/healthz`는 프로세스 생존 200이고, `/readyz`는 실행 파이프라인이 연결되고 RunLedger 세션이 응답하는 `real` 모드에서만 200(그 외 503)입니다. 200도 실제 모델·검색·업무 통합 완료를 뜻하지 않습니다.

#### 4. 필요한 서비스만 실행

전체 구성을 읽으므로 서비스 하나만 실행할 때도 위의 `.env` 세 개와 필수 값은 준비합니다.

프론트만 실행하려면 `--no-deps`를 사용합니다. 기본 `live` 모드에서는 Backend가 별도로 실행 중이어야 합니다. 서버 없이 화면을 개발할 때는 [Frontend 안내](frontend/README.md#mock으로-화면-개발)에 따라 Mock 설정을 준비합니다.

```sh
docker compose up --build --no-deps -d frontend
```

Backend를 지정하면 의존 서비스인 PostgreSQL·Redis도 함께 실행됩니다. AI는 별도로 실행합니다.

```sh
docker compose up --build -d backend
```

기본 scaffold 모드의 AI만 실행하려면 다음 명령을 사용합니다. 내부 실행 API를 활성화하는 경우에는 [AI 안내](ai/README.md#spring-전용-내부-조회-api)의 DB·인증 설정도 필요합니다.

```sh
docker compose up --build -d ai
```

#### 5. 상태와 로그 확인

`ps`로 실행 상태를 확인하고 `logs -f`로 Backend·AI 로그를 계속 확인합니다. 로그 보기만 끝낼 때는 `Ctrl+C`를 누릅니다.

```sh
docker compose ps
docker compose logs -f backend ai
```

Redis 응답은 컨테이너 내부에서 확인합니다. 정상 응답은 `PONG`입니다.

```sh
docker compose exec redis redis-cli ping
```

#### 6. 설정 변경 반영과 종료

`.env`만 변경했다면 `up -d`로 컨테이너를 재생성해 반영합니다. 소스를 변경했다면 앞의 `up --build -d 서비스명` 명령으로 해당 서비스를 다시 빌드합니다.

```sh
docker compose up -d
```

프론트만 일시 정지하려면 `stop frontend`, 전체 서비스를 종료하고 컨테이너·네트워크를 정리하려면 `down`을 사용합니다.

```sh
docker compose stop frontend
docker compose down
```

일반 종료 후 DB 볼륨은 유지됩니다. `down --volumes`는 DB 데이터를 삭제하므로 일반 종료에 사용하지 않습니다. `config --quiet`는 설정·필수 값 확인이며 실제 앱 기동이나 API 키 유효성·E2E 통과를 뜻하지 않습니다.

#### 기존 루트 설정에서 전환

루트 `.env`의 `VITE_*`·`FRONTEND_PORT`는 Frontend 파일, `POSTGRES_*`·Spring·JWT·CORS·쿠키·`BACKEND_PORT`는 Backend 파일, `HOPS_AI_*`·`AI_SERVICE_TOKEN`·OpenAI·Langfuse·`AI_PORT`는 AI 파일로 옮깁니다. 루트 `.env`의 `COMPOSE_ENV_FILES` 설정도 더 이상 필요하지 않습니다. 루트 `.env`나 셸에 남아 있는 같은 이름의 변수는 서비스별 파일보다 우선하므로, 이전 값을 정리하고 실제 비밀값을 Git에 올리지 않습니다. 값에 `$`나 `#`가 있으면 작은따옴표로 감싸 리터럴 값으로 입력합니다.

- 기존 DB 볼륨의 이름·계정·비밀번호를 Backend 파일에 그대로 유지합니다. `.env` 변경만으로 기존 DB 계정 비밀번호가 바뀌지 않으며 볼륨을 삭제해 재설정하지 않습니다.
- Frontend의 API URL과 Backend의 CORS를 비우면 기존처럼 공개 포트에 맞춰 기본값을 구성합니다. 별도 주소가 필요할 때만 입력합니다. 컨테이너끼리는 `postgres:5432`, `redis:6379`, `ai:8000`을 사용합니다.
- 셸의 같은 이름 환경변수는 Compose 파일 입력보다 우선합니다. 기존 셸·개인 파일에 폐기된 `HOPS_AI_MODE=fake`가 남아 있지 않은지 확인합니다.
- npm·Gradle·uv 직접 실행은 각 서비스 README를 따릅니다. Spring의 기존 로컬 properties 방식도 유지합니다.

### 구현 준비

팀이 합의한 PRD를 바탕으로 구현 세부 계약과 역할을 정합니다. 전체 데이터 관계는 [데이터 설계](docs/DATA.md), React·Spring·Python 연결은 [API 설계](docs/API.md)를 읽고 기능별 계약을 리뷰합니다. 두 문서는 구현 전 검토할 설계안입니다. 이번 공통 계약은 Markdown의 필드·타입·상태·호출 예시로 공유합니다. 검증용 Schema와 샘플 JSON은 개인 로컬 자료로 관리합니다.

목표 기술 구성은 React·Java 21 기반 Spring·Python 3.13 + LangGraph + FastAPI·PostgreSQL입니다. Node.js는 React 개발·빌드 런타임으로 사용하며 별도의 실시간 중계 서버는 두지 않습니다. Spring은 JWT 발급·인증·권한, 메시지·업무 저장과 브라우저 SSE 스트림을 담당합니다. JWT에는 사번과 소속 부서명을 포함하며 상세 클레임·인증·이벤트 계약은 API 설계에서 정리합니다. 로그인·조회·문의·티켓 업무는 React–Spring REST, 진행 상태·답변 조각·저장 결과 알림은 Spring–React SSE, AI 진행 전달은 Python–Spring SSE를 사용합니다.

프론트 빌드용 Node.js 24.22.0, React 19.3.0, Spring Boot 4.1.1, Gradle Wrapper 9.8.1, LangGraph 1.2.16, FastAPI 0.143.5, Uvicorn 0.53.0을 개발 기준으로 사용합니다. 서비스별 실제 실행·버전과 검증 범위는 각 서비스 개발 안내와 설정 파일을 따릅니다. OpenAI API 기반 LLM과 비용 최소화를 적용하며, 정확한 모델·공용 환경·실행 명령은 구현과 실측을 통해 정합니다. Spring은 PostgreSQL 계정·부서 저장 기반, JWT 로그인·내 정보, Refresh 쿠키 회전·재사용 검출·로그아웃과 부서 SSE를 제공합니다. 프론트는 시스템 업무·처리·정정·해결 확인·사례 승인 화면과 v2 호출을 구현하고 Mock·HTTP/SSE 대역으로 검사합니다. 전환 전 프론트의 부서 SSE 실서버 검사 이력은 현재 시스템 계약의 통합 완료를 뜻하지 않습니다. 문의·티켓 업무 API와 AI 전체 통합은 미완료이며, 담당별 구현·검증 범위는 [공통 계약 11절](specs/contracts/WIRE_CONTRACT.md#11-담당별-구현과-검증-상태)을 따릅니다.

에이전트별 개발을 시작할 때는 [공통 입출력 계약](contracts/agents/README.md)을 읽고, [개발·통합 준비 안내](docs/AGENT_DEVELOPMENT.md)에서 본인의 준비물과 통합 완료 조건을 확인합니다. Python 내부 역할 계약과 기존 서비스 API를 구분합니다. MVP에는 실제 멀티 에이전트를 연결하며 단일 에이전트 기준선은 필수가 아닙니다. Fake는 개발 중 연결 테스트에만 사용합니다.

## 구성별 개발 안내

- [Frontend](frontend/README.md): 구현 화면, v2 연결, 가상 로그인·예시와 검사 방법.
- [Spring 백엔드](backend/spring/hops/README.md): 패키지 구성, DB 설정, 테스트·빌드·실행 방법.
- [AI 서버](ai/README.md): 구성, 환경변수, 실행·동작 확인·테스트 방법, 현재 구현 범위와 제한.

## 개인 자료와 공통 변경

개인 AI 기록·실험·임시 자료는 `.local/`에 두고 공유하지 않습니다. 담당자는 다른 데이터셋을 자유롭게 탐색할 수 있으며 공통 자료와 설정은 팀 검토 후 반영합니다. 문서와 코드 변경은 PR 리뷰를 거칩니다. 문서의 목표 설계와 실제 구현 상태는 구분합니다.
