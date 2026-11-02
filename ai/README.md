# HOPS AI 서버 골격

Frontend·Spring·AI·PostgreSQL을 Docker로 함께 실행하려면 루트 README의 [Docker Compose 실행 안내](../README.md#docker-compose로-실행)를 따른다. 아래 uv 명령은 호스트에서 AI를 직접 실행할 때 사용한다.

[PRD](../docs/PRD.md)에 맞춰 모델 기반 Supervisor와 Inquiry·Knowledge·Ticket·Curator를 개발하기 위한 구조다. 현재 생존·준비 상태 조회와 기술 하네스를 제공하며, 별도 활성화 시 서비스 인증과 저장된 실행 상태 조회를 제공한다. 실제 상담 분석·모델·검색·티켓 제안·사례 생성은 구현 전이다.

실제 모델·검색·도구 연결의 목표 보안 요구사항과 검증은 [보안 개발 명세](../docs/SECURITY.md)를 따른다. 해당 명세는 현재 코드에 통제가 모두 구현됐다는 뜻이 아니다.

신규 역할·State·서버 간 DTO의 구현 기준은 [공통 구현 계약](../specs/contracts/README.md)과 [에이전트 계약](../contracts/agents/README.md)에서 확인한다. 문서의 신규 hops-system-1과 현재 미구현 골격의 상태를 구분한다.

## 현재 실행 범위

| 항목 | 상태 |
|---|---|
| FastAPI | `GET /healthz`는 200, `GET /readyz`는 미구현 상태를 나타내는 503 |
| 역할·Supervisor | 역할 골격만 존재. 호출하면 `ComponentNotReady` 발생 |
| OpenAI·pgvector·Neo4j | 내부 인터페이스와 연결 자리만 존재. 호출하면 미구현 오류 |
| 실행 하네스 | 마감·모델 호출 상한·LLM 일시 오류 재시도·사용량·취소 확인·도구 허용 검사 |
| RunLedger | AI 전용 PostgreSQL의 접수·중복 방지·상태·최종 결과 저장소. 내부 접수·조회와 실행기에 연결, Supervisor 미연결 |
| 서비스 인증·실행 API | 명시적으로 활성화하면 Spring 서비스 토큰 인증·snapshot 조회. 실행 접수는 실행 파이프라인이 주입된 경우에만 열리며 기본 실행에서는 503. 취소·SSE는 503 |
| 업무 실행·SSE | 백그라운드 실행기(접수·상태 전이·안전한 실패 저장) 구현. 실제 Supervisor 파이프라인·SSE·취소 전파는 연결 전 |
| Fake·Mock | 테스트 전용 LLM·도구 대역. 서버에 자동 연결하지 않음 |

단일 baseline, 독립 Diagnosis, Intake 명칭, 규칙 기반 Supervisor, 부서/CS 배정과 옛 `agent-1` 실행 모델은 활성 코드에서 분리했다. 기존 `/internal/mock/v1/runs` 접수·이벤트 경로는 제공하지 않는다. 예전 Fake 모드의 실행 명령이나 프론트 공용 `hops-mock-1` fixture로 이 서버를 호출하지 않는다.

## 구성

```text
src/hops_ai/
├── api/routes/health.py       생존·준비 상태
├── api/routes/runs.py         서비스 인증된 내부 실행 접수·snapshot 조회
├── contracts/                내부 자료형과 Spring↔AI 실행 wire DTO
├── supervisor/graph.py       모델 기반 조율 자리; 상담과 사후 실행 분리 예정
├── persistence/run_ledger.py AI 실행 ID·입력 digest·상태·결과 보존
├── agents/
│   ├── inquiry/              대화·질문·독립 문제·표준 요청
│   ├── knowledge/            Vector·관계 검색과 조건부 분석
│   ├── ticket/               책임 시스템 후보·근거·티켓 내용 제안
│   └── curator/              처리 기록 기반 사례 초안·기존 관계 연결 후보
├── harness/                  공통 예산·재시도·취소·도구 정책; guardrails는 구현 자리
├── services/
│   ├── llm/                  LLM 인터페이스·예산 래퍼·OpenAI 연결 자리
│   ├── runs/                 기한·한도 확인과 RunLedger 접수, 백그라운드 실행기
│   ├── retrieval/            검색 인터페이스·pgvector·Neo4j 연결 자리
│   ├── tools/                읽기 전용 도구 인터페이스·등록·호출 전 검사
│   └── indexing/             청크·임베딩·관계 반영 구현 자리
└── evaluation/               검색·표준화·응답 평가 구현 자리
tests/
├── doubles/                  정해 둔 응답만 반환하는 LLM·도구·실행 기록 대역
├── unit/                     기술 하네스와 미구현 경계 검증
└── integration/              골격 앱의 생존·준비 상태와 옛 API 비노출 검증
```

`contracts/wire.py`는 상담·사례 초안 실행 요청과 결과·이벤트의 전송 형식 및 일부 조건을 검증한다. 내부 실행 API는 이 요청 형식과 저장된 snapshot을 검증하며, 실행 파이프라인이 주입된 경우에만 새 실행을 접수한다. 실행기는 파이프라인 결과를 같은 run·문의·버전의 `AiResult`로 다시 검증한 뒤에만 저장한다. 참조의 실제 존재·현재 권한·질문 한도 누적·실행 간 이벤트 순서 검증은 실행기와 Spring에서 별도로 구현해야 한다. 나머지 내부 자료형과 검색 인터페이스도 Spring의 권한 검증·승인 상태를 대신하지 않는다.

## 실행과 검사

Python 3.13과 uv를 사용한다. 다음 명령은 `ai/`에서 실행한다.

```bash
uv sync --locked
uv run uvicorn --factory hops_ai.main:create_app --host 127.0.0.1 --port 8000
```

```bash
curl http://127.0.0.1:8000/healthz
curl -i http://127.0.0.1:8000/readyz
```

`healthz` 성공은 프로세스 생존만 의미한다. `readyz`는 현재 항상 503이며 분석 요청을 받을 준비가 됐다고 표시하지 않는다. `real` 모드는 시작을 거부한다.

Docker 설정은 [루트 실행 안내](../README.md#docker-compose로-실행)에 따라 한 번 복사한 루트 `.env`의 `HOPS_AI_*` 항목에 입력한다. 같은 파일에 OpenAI·Langfuse 키와 주소를 입력하는 선택 항목도 제공하며 AI 컨테이너에만 전달한다. 실제 모델 호출·추적 연동은 구현 전이다. AI 폴더에서 예시 파일을 별도로 복사하지 않는다. 위 uv 직접 실행은 루트 `.env`를 자동으로 읽지 않으므로 변경할 `HOPS_AI_*` 값을 프로세스 환경변수로 설정한다. 기존 개인 `.env`의 `HOPS_AI_MODE=fake`, `HOPS_AI_AGENT_MODE`, `HOPS_AI_API_PREFIX`는 제거한다. 단일/멀티 선택 설정은 없다. 비밀값은 Git에 올리지 않는다.

실행 예산의 초기 기술 기본값은 전체 60초, 모델 호출당 30초, 최대 8시도, LLM 일시 오류 재시도 2회, 호출당 출력 1,500토큰이다. Spring이 보낸 `limits`는 서버 상한(모델 호출 8회, 입력 60,000토큰, 출력 8×1,500토큰, 도구 호출 10회, 재시도 7회)을 넘으면 접수하지 않고, 실행 중에는 서버 설정과 요청 중 작은 값을 쓴다. 토큰·도구 호출 누적 한도의 실제 적용은 모델·도구 연결 시 추가한다. 한 인스턴스의 동시 실행 수 기본값은 4다. 역할 전환 때 전체 예산을 초기화하지 않는다. 실제 모델·프롬프트·금액 상한은 연결 전에 합의한다. 이 재시도는 색인 반영이나 업무 저장에 적용하지 않는다.

### Spring 전용 내부 조회 API

기본값 `HOPS_AI_INTERNAL_API_ENABLED=false`에서는 내부 경로를 등록하지 않는다. 로컬에서 명시적으로 켜려면 다음 세 환경변수를 서버 프로세스에 주입하고, 아래 RunLedger 안내대로 **AI 전용 DB `hops_ai_runtime`**에 마이그레이션을 먼저 적용한다.

- `HOPS_AI_INTERNAL_API_ENABLED=true`: 인증된 내부 API 기반 활성화.
- `HOPS_AI_RUNTIME_DSN`: `hops_ai_runtime` DB가 명시된 PostgreSQL 연결 문자열. 앱 실행용 최소 권한 계정 사용.
- `AI_SERVICE_TOKEN`: Spring→AI 서비스 인증용 토큰. 최소 32바이트의 암호학적으로 안전한 난수를 base64url로 인코딩해 제공한다. 길이·형식은 검사하지만 난수성을 판별하지는 않는다. AI→Spring 토큰과 공유하지 않는다.

값이 없거나 잘못됐거나 DB 접속·복구에 실패하면 활성 프로필의 시작을 거부한다. 토큰과 DB 비밀번호는 파일 예시·소스·로그에 넣지 않는다. 루트 Docker Compose는 이 세 환경변수를 루트 `.env`에서 AI 컨테이너로 전달하며 기본값은 비활성화다. Compose의 PostgreSQL에는 `hops_ai_runtime` DB와 AI 계정이 자동으로 만들어지지 않으므로 활성화 전에 별도로 준비한다. 내부 경로는 서비스 전용 네트워크에서만 접근하도록 별도 구성해야 한다.

| 경로 | 현재 응답 |
|---|---|
| `GET /internal/v2/runs/{runId}` | Bearer 인증 후 저장된 snapshot 조회. 없는 실행은 404, DB/저장 형식 오류는 안전한 503 |
| `POST /internal/v2/runs` | 파이프라인 미주입 시 503(접수 안 함). 주입 시 신규·재전송 202 `AiAccepted`, 다른 입력 409, 기한 지남 422, 서버 상한 초과 429 `BUDGET_EXCEEDED`, 동시 실행 초과 429 `RATE_LIMITED`+`Retry-After`, 저장소 오류 503 |
| `GET /internal/v2/runs/{runId}/events` | 실행기·SSE 미준비 503 |
| `POST /internal/v2/runs/{runId}/cancel` | 취소 전파 미구현 503 |

`/internal` 아래 모든 경로는 요청 본문을 읽기 전에 서비스 인증을 수행한다. 아직 등록되지 않은 내부 경로도 인증 전에는 401을 반환한다. 인증 실패는 안전한 오류 본문의 401이며 중복 Authorization 헤더도 거부한다. 생존·준비 상태 경로는 인증하지 않는다. `readyz`는 내부 조회 활성화 여부와 관계없이 503이며, 현재 프로필은 운영 배포·실제 모델 실행을 뜻하지 않는다.

```bash
uv run pytest
uv run ruff check .
uv run ruff format --check .
```

기본 테스트는 외부 모델·DB 호출 없이 수행한다. PostgreSQL 통합 검사는 아래 설정을 명시할 때만 실행한다. 대역은 명시적으로 준비한 응답·오류만 반환하며, 문의 분류·원인 판정·담당 배정·해결 성공을 만들어내지 않는다. 하네스·골격 검사를 실제 모델 품질·Spring 연동·서비스 E2E 결과로 보고하지 않는다.

### RunLedger 개발과 PostgreSQL 통합 검사

RunLedger는 Spring 업무 DB가 아닌 별도 `hops_ai_runtime` DB를 사용한다. DB와 최소 권한의 AI 계정은 환경 담당자가 준비한다. 앱 시작 시 DB 생성이나 마이그레이션은 자동 실행하지 않는다. DB 연결 문자열은 호출자가 `RunLedger`에 주입하며 비밀값을 저장소·로그에 남기지 않는다.

대상 DB를 확인한 뒤 [001](migrations/001_run_ledger.sql), [002](migrations/002_run_owner.sql)를 순서대로 적용한다. 이미 001이 적용된 DB는 실행기를 모두 중지한 뒤 002를 한 번 적용한다. 이전 소유자를 확인할 수 없는 미완료 실행은 002에서 `UNKNOWN`으로 보존한다. 운영 DB에 임의로 적용하지 않는다.

`RunAdmissionService`는 `AiRunRequest`의 입력을 다시 검증한 뒤 기한과 주입된 서버 한도를 검사하고 원자적으로 접수한다. 기존 runId와 입력 digest를 보존해 같은 입력은 기존 상태를 반환하고 다른 입력은 충돌로 처리한다. 결과 상세는 최소 24시간 유지하며, `expire_detail`을 명시적으로 호출하면 ID와 digest 표식을 남긴 채 미확인 상태로 바꾼다. 상세 만료 스케줄러는 아직 없다.

`RunLedger.open()`은 인스턴스별 PostgreSQL 세션 잠금을 확보하고, 소유 세션이 사라진 미완료 run을 `UNKNOWN`으로 복구한다. 살아 있는 다른 인스턴스의 잠금은 건드리지 않는다. 접수·실행 변경은 잠금을 보유한 같은 DB 세션을 사용하므로 직접 연결 또는 세션 수명이 유지되는 연결 구성이 필요하다. 트랜잭션 단위 연결 풀은 사용할 수 없다. 네트워크 단절 시 PostgreSQL이 기존 세션 종료를 확인하기 전까지 복구가 지연될 수 있다. 스캔 중 종료된 다른 인스턴스는 다음 `recover_abandoned()` 호출에서 확인할 수 있으며 주기 실행은 아직 연결하지 않았다.

테스트처럼 `create_app(..., ledger=ledger)`로 명시 주입하면 FastAPI 시작 시 ledger를 열고 복구한 뒤 종료 시 세션을 닫는다. 기본 골격 실행은 DB에 연결하지 않고, 실제 실행 모드·인증·업무 API·Supervisor는 여전히 미구현이다.

PostgreSQL 통합 검사는 별도 테스트 전용 DB `hops_ai_runtime_test`가 준비된 경우에만 실행한다. 해당 DB에 위 SQL을 적용하며 테스트용 실행 기록을 추가한다. 기본 테스트에서는 접속 설정이 없으면 이 검사들을 건너뛴다.

```bash
# 테스트 전용 연결 문자열을 환경에 주입한 뒤 실행한다. 실제 비밀번호는 문서에 쓰지 않는다.
HOPS_LEDGER_TEST_DSN='<테스트 전용 PostgreSQL 연결 문자열>' uv run pytest tests/integration/test_run_ledger.py
```

최소 권한 검사는 격리된 테스트 PostgreSQL에 `hops_business_test` DB와 제한 로그인 계정 `hops_ai_test`를 별도로 준비하고 `HOPS_LEDGER_LIMITED_TEST_DSN`도 주입할 때 실행한다. 테스트 관리자 계정으로 두 마이그레이션을 적용한 뒤 제한 계정에 런타임 DB의 `CONNECT`, 스키마 `USAGE`, `ai_run_ledger`의 `SELECT`·`INSERT`·`UPDATE`만 부여한다. 이 검사의 전용 업무 DB는 `PUBLIC`을 포함해 해당 계정의 `CONNECT`가 없어야 한다. 검사는 제한 계정으로 접수·복구가 가능한지와 업무 DB 연결·테이블 `DELETE`·스키마 `CREATE`가 거부되는지 확인한다. 위 설정은 격리 테스트 환경의 예시이며 공유 DB의 권한을 임의로 변경하지 않는다.

```bash
HOPS_LEDGER_TEST_DSN='<테스트 관리자 연결 문자열>' \
HOPS_LEDGER_LIMITED_TEST_DSN='<테스트 제한 계정 연결 문자열>' \
uv run pytest tests/integration/test_run_ledger.py
```

DB 통합 검사를 통과해도 서비스 인증·HTTP/SSE·Supervisor·모델 호출·Spring 업무 저장이 연결됐다는 뜻은 아니다.

## 구현 경계와 다음 작업

- **Inquiry·Ticket:** 의도와 내부 처리 유형을 분리한다. 질문 묶음은 독립 문제당 최대 5회다. 보고 시스템·책임 시스템 후보·근거와 미확인을 보존한다. 구체 질문 전이·서버 저장 검증은 구현 전이다.
- **Knowledge:** 권한이 허용된 가이드·업무 정책·런북·승인 사례를 검색한다. 조건부 분석을 포함하며 그래프 연결만으로 원인을 확정하지 않는다. 검색 0건과 검색 오류를 구분한다.
- **Curator·색인:** 처리 기록으로 초안을 제안한다. 문의자 해결 확인·시스템 담당자 승인과 같은 승인 버전의 양쪽 반영 성공 전에는 사례를 어느 검색 경로에서도 공개하지 않는다. 업무 승인·공개 확정은 Spring의 책임이다.
- **공통:** Python은 업무 DB에 직접 쓰지 않는다. 새 실행/SSE·오류·인증·멱등성·취소·버전·공개 검증 계약을 생산자·소비자와 정한 뒤 연결한다. 읽기 전용 Mock은 기능 명세가 정해진 뒤 구현하며 실제 운영 관측과 구분한다.

먼저 [가상 기업 명세](../docs/knowledge/VIRTUAL_COMPANY.md)와 [데이터 구축 계획](../docs/knowledge/DATASETS.md)으로 소량 데이터를 검토한다. [에이전트 계약](../contracts/agents/README.md)과 [목표 공통 계약](../specs/contracts/TARGET_CONTRACT.md)을 구체화한 뒤 검색·실행·역할을 연결한다. Curator 담당 범위는 관련 담당자와 합의한다.
