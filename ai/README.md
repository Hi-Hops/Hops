# Hops AI 서버

Spring이 요청한 문의 분석을 에이전트로 실행하고, 진행과 결과를 SSE로 돌려주는 Python 서버입니다. Python 3.13 + FastAPI + LangGraph로 만들며 업무 DB에는 쓰지 않습니다. 권한·저장·티켓 배정·상태 확정은 Spring이 하고, 이 서버의 결과는 제안입니다([PRD](../docs/PRD.md), [개발 안내](../docs/AGENT_DEVELOPMENT.md)).

## 현재 상태

| 구현됨 (Fake 모드) | 구현 전 |
|---|---|
| 실행 접수·SSE 엔드포인트(Mock 계약 경로), 중복 요청·버전 대체·이어 받기 | 실제 LLM(OpenAI) 연결과 실제 프롬프트. `real` 모드는 시작을 거부함 |
| 단일(도구형)·멀티(Supervisor) 구성과 같은 예산·같은 결과 검증 | 실제 검색(RAG)·읽기 도구. 지금은 가상 문서 3개와 가짜 도구 |
| 하네스: 마감·호출 상한·재시도·취소·결과 검증·실행 기록 | 서비스 간 인증, 취소 엔드포인트, 답변 조각(`answer.delta`) |
| 가짜 에이전트·검색·도구, 팀 공용 Mock 예시로 계약 검증 | Langfuse 연결, 실제 모델 평가 |

Fake 모드의 검사는 **연결과 통제 규칙**만 확인합니다. 분석 품질이나 MVP 완료를 뜻하지 않습니다.

## 준비물과 실행

- Python 3.13과 [uv](https://docs.astral.sh/uv/)가 필요합니다. 아래 명령은 모두 `ai/`에서 실행합니다.

```bash
cd ai
uv sync
```

```bash
uv run uvicorn --factory hops_ai.main:create_app --port 8000
```

서버가 뜨면 생존을 확인합니다.

```bash
curl http://127.0.0.1:8000/healthz
```

설정은 환경변수(`HOPS_AI_*`)나 `ai/.env`로 바꿉니다. 이름과 기본값은 [`.env.example`](.env.example)을 복사해서 참고하며 `.env`는 Git에 올리지 않습니다.

| 변수 | 기본값 | 의미 |
|---|---|---|
| `HOPS_AI_MODE` | `fake` | `fake`(가짜 구현) · `real`(아직 미지원) |
| `HOPS_AI_AGENT_MODE` | `single` | `single`(도구를 쓰는 단일 에이전트) · `multi`(Supervisor + 4개 역할) |
| `HOPS_AI_API_PREFIX` | `/internal/mock/v1` | Spring이 호출하는 경로의 접두사. 운영 경로가 합의되면 값만 바꿈 |
| `HOPS_AI_RUN_DEADLINE_SECONDS` | `60` | 한 실행 전체 제한 시간. Spring이 준 마감과 비교해 더 이른 쪽을 씀 |
| `HOPS_AI_LLM_CALL_TIMEOUT_SECONDS` | `30` | 모델 호출 1회 제한 시간 |
| `HOPS_AI_MAX_LLM_CALLS` | `8` | 한 실행의 모델 호출 상한. 재시도 포함, 단일·멀티 공통 |
| `HOPS_AI_MAX_AUTO_RETRIES` | `2` | 일시 오류에 대한 호출당 자동 재시도 |
| `HOPS_AI_MAX_OUTPUT_TOKENS` | `1500` | 모델 호출 1회의 최대 출력 토큰 |

예산 값은 초기 기준이며 실제 모델 측정 후 조정합니다. 값이 잘못되면 서버가 시작되지 않습니다.

## 동작 확인

서버를 띄운 뒤 팀 공용 Mock 예시([`specs/mocks`](../specs/mocks/README.md))로 한 번 실행해 봅니다. 예시의 마감 시각(`deadlineAt`)은 과거라서 미래로 바꿔 보냅니다.

```bash
uv run python - <<'EOF'
import datetime as dt, json, pathlib, httpx

cases = json.loads(pathlib.Path("../specs/mocks/fixtures.json").read_text(encoding="utf-8"))["cases"]
request = next(case["value"] for case in cases if case["id"] == "ai-request")
request["deadlineAt"] = (dt.datetime.now(dt.UTC) + dt.timedelta(seconds=60)).strftime("%Y-%m-%dT%H:%M:%SZ")

base = "http://127.0.0.1:8000/internal/mock/v1"
print(httpx.post(f"{base}/runs", json=request).status_code)  # 202
with httpx.stream("GET", f"{base}/runs/{request['runId']}/events") as response:
    print(response.read().decode())  # 이벤트(id·event·data)가 차례로 나오고 result로 끝남
EOF
```

`HOPS_AI_AGENT_MODE=multi`로 띄우면 역할별 `progress` 이벤트가 `result` 앞에 추가됩니다.

## Spring이 호출하는 경로

계약 원본은 [WIRE_CONTRACT 7절](../specs/contracts/WIRE_CONTRACT.md)과 [DATA_TYPES](../specs/contracts/DATA_TYPES.md)입니다. 아래는 구현 요약이며 운영 경로·서비스 인증은 아직 합의 전입니다.

| 경로 | 동작 |
|---|---|
| `GET /healthz` | 프로세스 생존 확인(접두사 없음) |
| `POST {prefix}/runs` | `AiRequest` → `202 AiAccepted`. 같은 `runId`를 다시 보내면 새로 실행하지 않고 같은 응답, 같은 `runId`에 다른 문의·버전이면 `409` |
| `GET {prefix}/runs/{runId}/events` | SSE `progress` · `result`(한 번, 마지막). 이벤트 `id`는 번호이며 `Last-Event-ID`로 이어 받음. 알 수 없는 실행은 `404` |

- 같은 문의에 더 높은 버전 요청이 오면 끝나지 않은 낮은 버전 실행은 `CANCELLED`로 끝납니다. 최종 판단은 Spring이 문의 버전으로 합니다.
- 오류 응답은 `ApiErrorBody` 형태이고 잘못된 요청에도 보낸 내용을 담지 않습니다.
- `result`의 `OK`는 분석이 끝났다는 뜻이며 티켓 저장·배정 성공이 아닙니다.

## 구성

```text
src/hops_ai/
├── api/          Spring과의 HTTP 어댑터: 경로, 요청·응답 타입(schemas), 변환(mappers), SSE, 오류 응답
├── contracts/    agent-1(에이전트 내부 계약)과 분석 입출력 타입
├── harness/      모든 실행에 공통인 통제(LLM 밖에서 코드로 강제)
│                 예산·재시도·정책·취소·결과 검증·이벤트·실행 관리(runner)·기록(trace)
├── supervisor/   멀티 구성의 LangGraph: 경로 선택(Router)과 결과 조합
├── agents/       baseline(도구를 쓰는 단일 에이전트), 역할 규약·레지스트리, intake·knowledge·diagnosis·ticket 자리
├── services/     에이전트가 공유하는 LLM·검색·도구 인터페이스와 예산 안에서 호출하는 래퍼
└── fakes/        외부 연결 없는 가짜 구현. Fake 모드 서버 실행과 테스트에서 함께 씀
tests/            unit · contract · integration · fixtures
```

- **단일(baseline)**: LLM은 `decide` 노드 하나만 쓰고, 검색·도구 조회·결과 조립은 LLM 없이 실행합니다. 검색 범위(접근 필터)는 서버가 정하며 모델이 고르지 않습니다.
- **멀티(Supervisor)**: Intake → (필요할 때만) Ticket → 결과 조합. 경로는 `RuleRouter`가 역할이 낸 제안을 전이표로 따라 정합니다. Knowledge·Diagnosis 경로는 해당 역할을 연결할 때 추가합니다.
- 두 구성은 같은 입력·예산·서비스·결과 검증을 쓰므로 같은 조건으로 비교할 수 있습니다.
- 모델이 만든 값(근거 ID·부서 코드·문제 참조·질문 횟수)은 서버 사실과 대조해 어긋나면 거부합니다.

## 테스트와 검사

```bash
uv run pytest
```

```bash
uv run ruff check .
```

```bash
uv run ruff format --check .
```

테스트는 외부 연결 없이 Fake로 돕니다. 계약 테스트는 팀 공용 Mock 예시를 그대로 읽습니다. 에디터(VS Code 등)에서 `hops_ai`·`fastapi` import 오류가 보이면 인터프리터로 `ai/.venv/bin/python`을 선택합니다.

## 알려진 제한

- **상태는 메모리에만 있습니다.** 서버를 다시 시작하면 진행 중인 실행과 보관한 결과(기본 10분)가 사라집니다. 알 수 없는 `runId`(404)를 받았을 때 Spring이 어떻게 복구할지(예: 새 `runId`로 다시 요청)는 Spring과 합의해야 합니다. 여러 인스턴스로 나눠 띄울 수도 없습니다.
- 서비스 간 인증이 없으므로 내부망에서만 노출합니다.
- 서비스 간 계약의 `AiSuccess`에는 근거 목록이 없어 문서·도구 근거 ID를 Spring이 해석할 수 없습니다. 실제 검색·진단을 연결하기 전에 계약을 확장해야 합니다.
- agent-1 오류 코드를 서비스 간 `ErrorCode`로 바꾸는 표는 계약 리뷰 전 제안입니다(`api/mappers.py`).

## 함께 볼 문서

[공통 입출력 계약(agent-1)](../contracts/agents/README.md) · [개발·통합 준비 안내](../docs/AGENT_DEVELOPMENT.md) · [Knowledge 설계](../docs/knowledge/README.md) · [공용 Mock](../specs/mocks/README.md)
