# HOPS AI 서버 골격

[PRD](../docs/PRD.md)에 맞춰 모델 기반 Supervisor와 Inquiry·Knowledge·Ticket·Curator를 개발하기 위한 구조다. 현재 실행 가능한 것은 생존·준비 상태 조회와 재사용한 기술 하네스다. 실제 상담 분석·모델·검색·티켓 제안·사례 생성은 구현 전이다.

## 현재 실행 범위

| 항목 | 상태 |
|---|---|
| FastAPI | `GET /healthz`는 200, `GET /readyz`는 미구현 상태를 나타내는 503 |
| 역할·Supervisor | 역할 골격만 존재. 호출하면 `ComponentNotReady` 발생 |
| OpenAI·pgvector·Neo4j | 내부 인터페이스와 연결 자리만 존재. 호출하면 미구현 오류 |
| 실행 하네스 | 마감·모델 호출 상한·LLM 일시 오류 재시도·사용량·취소 확인·도구 허용 검사 |
| 업무 실행·SSE·서비스 인증 | 상세 계약·실행 관리·어댑터 연결 전 |
| Fake·Mock | 테스트 전용 LLM·도구 대역. 서버에 자동 연결하지 않음 |

단일 baseline, 독립 Diagnosis, Intake 명칭, 규칙 기반 Supervisor, 부서/CS 배정과 옛 `agent-1` 실행 모델은 활성 코드에서 분리했다. 기존 `/internal/mock/v1/runs` 접수·이벤트 경로는 제공하지 않는다. 예전 Fake 모드의 실행 명령이나 프론트 공용 `hops-mock-1` fixture로 이 서버를 호출하지 않는다.

## 구성

```text
src/hops_ai/
├── api/routes/health.py       생존·준비 상태; 업무 API는 계약 후 연결
├── contracts/                PRD 의미와 내부 기술 자료형; 외부 전송 DTO 아님
├── supervisor/graph.py       모델 기반 조율 자리; 상담과 사후 실행 분리 예정
├── agents/
│   ├── inquiry/              대화·질문·독립 문제·표준 요청
│   ├── knowledge/            Vector·관계 검색과 조건부 분석
│   ├── ticket/               책임 시스템 후보·근거·티켓 내용 제안
│   └── curator/              처리 기록 기반 사례 초안·기존 관계 연결 후보
├── harness/                  공통 예산·재시도·취소·도구 정책; guardrails는 구현 자리
├── services/
│   ├── llm/                  LLM 인터페이스·예산 래퍼·OpenAI 연결 자리
│   ├── retrieval/            검색 인터페이스·pgvector·Neo4j 연결 자리
│   ├── tools/                읽기 전용 도구 인터페이스·등록·호출 전 검사
│   └── indexing/             청크·임베딩·관계 반영 구현 자리
└── evaluation/               검색·표준화·응답 평가 구현 자리
tests/
├── doubles/                  정해 둔 응답만 반환하는 LLM·도구 대역
├── unit/                     기술 하네스와 미구현 경계 검증
└── integration/              골격 앱의 생존·준비 상태와 옛 API 비노출 검증
```

역할의 상세 요청·응답과 업무 상태는 아직 확정하지 않았다. `contracts/`의 자료형과 검색 인터페이스는 내부 개발용이며 Spring DTO·권한 검증·승인 상태를 대신하지 않는다.

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

환경변수는 [예시](.env.example)를 따른다. 기존 `.env`의 `HOPS_AI_MODE=fake`, `HOPS_AI_AGENT_MODE`, `HOPS_AI_API_PREFIX`는 제거하고 현재 설정에 맞춘다. 단일/멀티 선택 설정은 없다. 비밀값은 Git에 올리지 않는다.

실행 예산의 초기 기술 기본값은 전체 60초, 모델 호출당 30초, 최대 8시도, LLM 일시 오류 재시도 2회, 호출당 출력 1,500토큰이다. 역할 전환 때 전체 예산을 초기화하지 않는다. 실제 모델·프롬프트·금액 상한과 서비스 인증은 연결 전에 합의한다. 이 재시도는 색인 반영이나 업무 저장에 적용하지 않는다.

```bash
uv run pytest
uv run ruff check .
uv run ruff format --check .
```

테스트는 외부 모델·DB 호출 없이 수행한다. 대역은 명시적으로 준비한 응답·오류만 반환하며, 문의 분류·원인 판정·담당 배정·해결 성공을 만들어내지 않는다. 하네스·골격 검사를 실제 모델 품질·Spring 연동·서비스 E2E 결과로 보고하지 않는다.

## 구현 경계와 다음 작업

- **Inquiry·Ticket:** 의도와 내부 처리 유형을 분리한다. 질문 묶음은 독립 문제당 최대 5회다. 보고 시스템·책임 시스템 후보·근거와 미확인을 보존한다. 구체 질문 전이·서버 저장 검증은 구현 전이다.
- **Knowledge:** 권한이 허용된 가이드·업무 정책·런북·승인 사례를 검색한다. 조건부 분석을 포함하며 그래프 연결만으로 원인을 확정하지 않는다. 검색 0건과 검색 오류를 구분한다.
- **Curator·색인:** 처리 기록으로 초안을 제안한다. 문의자 해결 확인·시스템 담당자 승인과 같은 승인 버전의 양쪽 반영 성공 전에는 사례를 어느 검색 경로에서도 공개하지 않는다. 업무 승인·공개 확정은 Spring의 책임이다.
- **공통:** Python은 업무 DB에 직접 쓰지 않는다. 새 실행/SSE·오류·인증·멱등성·취소·버전·공개 검증 계약을 생산자·소비자와 정한 뒤 연결한다. 읽기 전용 Mock은 기능 명세가 정해진 뒤 구현하며 실제 운영 관측과 구분한다.

먼저 [가상 기업 명세](../docs/knowledge/VIRTUAL_COMPANY.md)와 [데이터 구축 계획](../docs/knowledge/DATASETS.md)으로 소량 데이터를 검토한다. [에이전트 계약](../contracts/agents/README.md)과 [목표 공통 계약](../specs/contracts/TARGET_CONTRACT.md)을 구체화한 뒤 검색·실행·역할을 연결한다. Curator 담당 범위는 관련 담당자와 합의한다.
