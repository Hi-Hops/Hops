# HOPS AI 서버 골격

Frontend·Spring·AI·PostgreSQL을 Docker로 함께 실행하려면 루트 README의 [Docker Compose 실행 안내](../README.md#docker-compose로-실행)를 따른다. 아래 uv 명령은 호스트에서 AI를 직접 실행할 때 사용한다.

[PRD](../docs/PRD.md)의 모델 기반 Supervisor와 Inquiry·Knowledge·Ticket·Curator를 위한 서버다. 서비스 인증·실행 조회·접수·백그라운드 실행과 Supervisor 그래프를 제공한다. real 모드에는 OpenAI 어댑터를 사용하는 Supervisor·Inquiry와 Spring 생성/조회 도구를 실행하는 Ticket이 연결돼 있다. Curator 모델·progress/result SSE·Python 실행 취소도 연결돼 있다. Knowledge는 명시적인 fixture 설정에서 개발용 고정 자료를 사용하며 운영 실검색·Spring 취소 전파는 별도다. 실제 모델 품질·전체 서비스 E2E 완료를 뜻하지 않는다.

실제 모델·검색·도구 연결의 목표 보안 요구사항과 검증은 [보안 개발 명세](../docs/SECURITY.md)를 따른다. 해당 명세는 현재 코드에 통제가 모두 구현됐다는 뜻이 아니다.

신규 역할·State·서버 간 DTO의 구현 기준은 [공통 구현 계약](../specs/contracts/README.md)과 [에이전트 계약](../contracts/agents/README.md)에서 확인한다. wire 검증·기술 기반의 구현과 역할 실행·서비스 전체 통합 상태를 구분한다.

## 현재 실행 범위

| 항목 | 상태 |
|---|---|
| FastAPI | `GET /healthz`는 프로세스 생존 200. `GET /readyz`는 실행 접수가 열려 있고 RunLedger 소유 세션이 응답할 때만 200, 그 외 503 |
| 역할·Supervisor | 판단 검증·역할 호출·State 병합·최종화의 LangGraph 흐름 구현. `real` 모드(`build_registry`)는 Inquiry(원문 추출 + 양식 규칙 질문), Ticket(모델 제안 + Spring 생성·조회 도구 실행), Curator(모델이 사례 문구·재사용 여부를 제안하고 조치·검증·버전은 원 기록 그대로, 출처는 원문 대조)를 실행한다. Curator의 엔티티 연결은 원 기록 시스템의 `APPLIES_TO`만 만든다. Knowledge는 `HOPS_AI_KNOWLEDGE_SOURCE=fixture`(개발용 고정 자료) 또는 `spring`(Spring 내부 API로 DB의 승인 문서)일 때만 실행하고 기본(`none`)은 연결 전 자리. 역할 조합을 만드는 함수는 `build_registry` 하나이며 Knowledge 연결 여부는 `knowledge_source` 인자로, Ticket의 Spring 생성 실행 여부는 `execute_tickets` 인자로 정한다. 역할을 주입하지 않은 `Supervisor()`는 모든 역할이 연결 전 자리(`pending_agents()`)다 |
| OpenAI·pgvector·Neo4j | OpenAI Responses 구조화 출력 어댑터 연결. pgvector·Neo4j 검색은 아직 구현하지 않았고 연결 자리 코드도 두지 않는다. 2차에 `KnowledgeSource` 뒤에서 교체한다 |
| 실행 하네스 | Supervisor와 역할이 누적 호출·보고 토큰·도구·재시도 한도를 공유. 전체 기한·응답 대기 중 취소와 안전한 오류 변환 |
| RunLedger | AI 전용 PostgreSQL의 접수·중복 방지·상태·최종 결과 저장소. 내부 접수·조회와 실행기에 연결. real 모드 Supervisor 파이프라인 사용 |
| 서비스 인증·실행 API | 명시적으로 활성화하면 Spring 서비스 토큰 인증·snapshot·progress/result SSE 조회. 실행 접수와 취소는 실행 파이프라인이 주입된 경우에만 열리며 기본 실행에서는 503. 주입 시 취소는 항상 202 빈 본문(최선 노력) |
| 업무 실행·SSE | 백그라운드 실행기와 `real` 모드 Supervisor 파이프라인 연결. 역할 실행 전 고정된 공개 진행 문구 발행, 최종 결과 저장 뒤 result 전달. 취소 요청 시 진행 중인 실행을 멈추고 `SUPERSEDED`로 저장(Spring의 취소 호출은 연결 전). answer.delta는 연결 전 |
| Fake·Mock | 테스트 전용 LLM·도구 대역. 서버에 자동 연결하지 않음 |

단일 baseline, 독립 Diagnosis, Intake 명칭, 규칙 기반 Supervisor, 부서/CS 배정과 옛 `agent-1` 실행 모델은 활성 코드에서 분리했다. 기존 `/internal/mock/v1/runs` 접수·이벤트 경로는 제공하지 않는다. 예전 Fake 모드의 실행 명령이나 프론트 공용 `hops-mock-1` fixture로 이 서버를 호출하지 않는다.

## 구성

```text
src/hops_ai/
├── api/routes/health.py       생존·준비 상태
├── api/routes/runs.py         서비스 인증된 내부 실행 접수·snapshot 조회
├── contracts/                내부 자료형(State·역할 호출 입출력 포함)과 Spring↔AI 실행 wire DTO
├── supervisor/               역할 정책·State 전이·판단 검증·역할 실행·병합·실행 제한·LangGraph 최종화
├── rules/                    에이전트와 Supervisor가 함께 쓰는 문제 진행 상태 계산·제안 검증(supervisor·agents를 불러오지 않음)
├── persistence/run_ledger.py AI 실행 ID·입력 digest·상태·결과 보존
├── agents/
│   ├── inquiry/              대화·질문·독립 문제·표준 요청
│   ├── knowledge/            승인된 지식 검색과 직원 안내 제안
│   ├── ticket/               책임 시스템 후보·근거·티켓 내용 제안
│   └── curator/              처리 기록 기반 사례 초안·기존 관계 연결 후보
├── harness/                  누적 예산·유일한 재시도 루프·실행 대기/취소·도구 정책
├── services/
│   ├── llm/                  LLM 인터페이스·예산 래퍼·OpenAI 연결 자리
│   ├── runs/                 기한·한도 확인과 RunLedger 접수, 백그라운드 실행기, 주기 복구·상세 만료
│   ├── retrieval/            Knowledge 검색 자료 경계(KnowledgeSource)와 개발용 고정 자료
│   └── tools/                읽기 도구 인터페이스·등록/인자 검사·공통 예산 wrapper
tests/
├── doubles/                  정해 둔 응답만 반환하는 LLM·도구·실행 기록 대역
├── unit/                     기술 하네스와 미구현 경계 검증
└── integration/              골격 앱의 생존·준비 상태와 옛 API 비노출 검증
```

`src/` 밖의 `evaluation/`은 정리 전후의 동작을 같은 기준으로 비교하는 평가 하네스다(안전 성질 P1~P11 검사기, 시나리오 재생기, 규칙 사용자 모사). 운영 코드와 실행 이미지에 들어가지 않는다. 사용법과 한계는 [evaluation/README.md](evaluation/README.md)를 본다. Fake 모델 결과는 실제 모델 품질이 아니다.

`contracts/wire.py`는 상담·사례 초안 실행 요청과 결과·이벤트의 전송 형식 및 일부 조건을 검증한다. 내부 실행 API는 이 요청 형식과 저장된 snapshot을 검증하며, 실행 파이프라인이 주입된 경우에만 새 실행을 접수한다. 실행기는 파이프라인 결과를 같은 run·문의·버전의 `AiResult`로 다시 검증한 뒤에만 저장한다. 참조의 실제 존재·현재 권한·질문 한도 누적·실행 간 이벤트 순서 검증은 실행기와 Spring에서 별도로 구현해야 한다. 나머지 내부 자료형과 검색 인터페이스도 Spring의 권한 검증·승인 상태를 대신하지 않는다.

Supervisor는 공통 `contracts/wire.py`의 요청·제안·결과 타입을 재사용한다. `contracts/roles.py`는 역할별 내부 결과와 검색 권한 확인 참조를 정의한다. 기본 함수는 `policy.py` → `state.py` → `merge.py` → `finalize.py` 순서로 읽는다. 예약된 역할 호출의 결과를 검증한 뒤 새 State로 반환하며 동일 호출 재전달·질문 한도·근거 충돌·기존 작업·종료 후 변경을 검사한다.

Supervisor가 사용자 메시지의 처리 방향을 먼저 판단한다. IT 문의나 보완이 있으면 인사가 섞여 있어도 Inquiry에 분석을 맡기고, 문의 없이 대화로 답할 때는 `REPLY`로 고정 문구를 답하며, 응답 종류는 코드가 정한다(`supervisor/validation.py`). 아직 분석하지 않은 사용자 메시지가 확인된 인사 표현뿐이면 `GREETING`, 감사 표현이 있으면 `THANKS`, 그 밖에는 되묻기(`CLARIFY`)다. 잘못 분류된 문의를 인사로 조용히 끝내지 않기 위한 보수적인 처리이며, 목록 밖의 단순 인사도 되묻기가 될 수 있다. Inquiry는 문의 분석만 하며, 문제를 찾지 못하면 빈 결과를 돌려주고 Supervisor가 다시 묻는다. 같은 실행에서 Inquiry를 다시 호출하지 않는다. 아직 분석하지 않은 사용자 메시지가 확인된 인사·감사 표현뿐이면 처리 중인 기존 문제가 있어도 선택지에서 Inquiry를 빼서(`supervisor/choices.py`) 이전 질문을 반복하지 않고 인사로 답한다. 아직 분석하지 않은 메시지는 기존 문제가 없으면 전체 사용자 메시지, 있으면 마지막 AI 답변 이후의 사용자 메시지다.

되묻기(`CLARIFY`)처럼 사용자 입력을 기다리는 대화 응답만 `AiResult.requiresFollowUp=true`를 보낸다. 인사·감사 응답은 처리 중인 문제의 완료를 막지 않는다. Spring은 이를 검증해 문의 완료 집계를 보류하고, 그 답변 뒤에 사용자 메시지가 저장되면 대기를 푼다. 다른 결과의 `false`가 질문이 없음을 의미하지는 않으며 기존 질문 처리는 별도다. 이전 생산자의 필드 생략과 서비스 교체 순서는 [AiResult 전송 계약](../contracts/agents/README.md#s2-외부-wire-형식)을 따른다.

Inquiry가 기존 확인 사실을 일부 빠뜨리기만 하면 기존 사실을 보존한다. 새 사실을 추가하려면 기존 사실도 함께 전달해야 하며, 기존 사실을 빼면서 다른 사실을 넣는 모호한 교체는 거부한다. 상세 정보의 `null`은 삭제 명령으로 해석하지 않고 기존 값을 유지하며 명시된 값은 갱신한다. 서버가 확인한 수용 조건은 누락해도 보존하고 임의 강등은 거부한다. 사실 철회·삭제는 별도 정정 계약이나 서버가 정정한 다음 입력이 필요하다. 티켓의 처리 유형도 해당 문제의 유형과 일치해야 병합한다.

서버가 기존 문제의 보고 시스템을 확인한 경우 Inquiry의 다른 시스템 제안은 거부하고 `null` 제안은 기존 값으로 보완한다. Ticket과 최종화에서도 같은 서버 값을 대조한다. 보고 시스템의 정정은 서버가 갱신한 다음 입력으로 받고, 다른 책임 시스템은 대상 문제에서 허용된 근거가 있을 때만 제안할 수 있다. 보고 시스템을 아직 확인하지 못한 문제의 최초 식별은 허용한다.

확인 사실·추정·티켓 책임 근거의 메시지 ID는 `get_problem_message_ids`로 대상 문제의 `InputMessage.problemIds`와 대조한다. 입력에서 생략된 과거 출처는 해당 문제의 기존 표준 요청만 참조하며, 현재 메시지가 있으면 현재 연결을 우선한다. 신규 `new:` 문제는 복합 문의 분리를 위해 미연결 원문과 이번 실행 대상 문제의 원문을 사용할 수 있지만 참고용 비대상 문제나 다른 문제의 과거 출처는 복사하지 않는다. 원문의 내용상 관련성 판단은 Inquiry, 실제 문제·메시지 연결과 저장 확정은 Spring이 담당한다.

의존 방향은 `contracts` ← `rules` ← `agents` ← `supervisor`다. `agents`·`rules`·`contracts`는 `supervisor`를 불러오지 않으며 `tests/unit/test_import_layers.py`가 이를 검사한다.

실행 흐름은 `decision.py` → `execution.py` → `runtime.py` → `graph.py` 순서로 확인한다.

- `choices.py`의 `compute_choices`가 State에서 이번 판단의 선택지(호출할 수 있는 역할과 종료 행동)를 계산한다. 연결 전 자리(`PendingAgent`)와, 문제가 아직 없는데 검색하는 것처럼 지금 State에서 호출이 성립하지 않는 역할은 선택지에 없다. 역할별 호출 조건은 이 파일의 규칙 표 한 곳에 있고 역할 구현은 호출 조건을 알지 못한다. 그래프의 `decide` 노드가 선택지를 계산해 모델 호출과 조건부 엣지에 넘기며, 모델이 선택지 밖의 역할을 내면 거부한다. `Supervisor(agents, restrict_roles=True)`일 때만 제한하고(`SupervisorPipeline`은 항상 제한) 기본은 등록된 역할을 모두 선택지로 둔다.
- `SupervisorDecision`은 다음 행동·전문 역할·공개 요약을 담는다. `decide_next_action`은 최소 실행 문맥으로 LLM을 호출하고 `validate_supervisor_decision`이 실행 종류별 역할과 준비된 제안을 검사한다. `problemProgress`에는 대상별 결과 준비 여부·남은 질문 횟수·미확인 개수·값이 있는 상세 필드명만 투영하며 담당자 지식과 상세 분석 원문은 넣지 않는다. 모델의 `publicSummary`는 최종 답변 본문으로 사용하지 않는다.
- `execute_role`은 호출 ID를 발급하고 독립적인 `RoleRequest` 스냅샷을 전달한다. 역할은 그 ID를 결과에 유지하며 모델이 호출 ID를 생성하지 않는다. 반환 결과의 종류·ID를 검사한 뒤 기존 역할별 merge 함수로 병합한다. Knowledge의 `RoleResponse.authorized_refs`는 허용 문서 참조, `verified_evidence`는 같은 어댑터가 검증한 원본 `KnowledgeEvidence` 객체의 tuple이다. 둘 다 모델 JSON에서 만들지 않으며 FOUND 결과는 검증 객체와 모든 필드가 일치해야 한다. 문서 참조만 전달하면 거부한다. 검색 본문을 모델에 전달하기 전 현재 Spring 권한과 원문·청크 digest 확인은 검색 어댑터가 수행한다. 문서 전체 digest를 발췌문 해시와 직접 비교하지 않는다. Curator의 기존 엔티티 목록도 신뢰된 어댑터가 제공한다.
- `SupervisorRuntime`은 요청의 ID·누적 한도를 공통 실행기에 연결하고 사용량을 State에 복사하는 어댑터다. `RunBudget`이 호출·보고 토큰·도구·전체 재시도 사용량을 소유하고, `call_with_retry`만 모델 재시도 루프를 구현한다. `RunExecution`은 전체 그래프 대기·취소·종료 정리를 관리하며 `BudgetedLLMClient`와 `BudgetedToolGateway`가 실제 호출에 제한을 적용한다. 이미 감싼 LLM이나 재사용한 `RunContext`는 거부한다. 도구는 기본 거부 정책과 `GuardedToolGateway`의 등록·인자 검사를 통과해야 한다. 정확한 입력 토큰 사전 계산과 실패한 원격 호출의 미보고 과금 확인은 공급자 어댑터의 후속 작업이며 이 수치는 금액 예산 보장이 아니다.
- `Supervisor.analyze`는 판단(`decide`) → 선택한 역할 노드(`inquiry`·`knowledge`·`ticket`·`curator`)의 호출·병합 → 재판단 → 최종화(`finish`)의 LangGraph를 실행한다. 그래프 State가 한 run의 유일한 정본이며 노드 경계에서 실행기가 예산·취소를 State에 반영하고 고정 진행 문구를 발행한다. 실패 결과는 그래프가 마지막으로 확정한 State로 만든다. 역할을 고정 순서로 모두 호출하지 않는다. `ASK_USER`는 현재 run을 끝내며 사용자 답변은 Spring이 새 run으로 전달한다. 실패 결과는 새 제안을 비우고 안전한 오류만 반환한다.
- `get_problem_progress`(`rules/progress.py`)는 별도 State 필드를 저장하지 않고 준비 상태를 계산한다. `validate_completion`은 모든 대상과 신규 문제에 질문·가이드·티켓 제안 또는 안내할 기존 결과·범위 안내·시스템 선택 대기가 있는지 검사한다. `allowedActions`, 종료 행동 검증, 최종화가 이 조건을 함께 사용한다. 검색 0건이나 자유 문구만으로 종료할 수 없고, 원인 미확인 항목이 남았다는 이유만으로 조사 티켓을 차단하지 않는다.
- `compose_public_answer`는 검증된 질문·가이드 항목을 한 번씩 조립하고, 티켓 제안 준비와 서버가 제공한 기존 저장 결과를 구분해 답변을 만든다. 서로 다른 문제의 질문 본문이 완전히 같으면 문제 번호를 묶어 한 번 표시하고 문제별 질문 메타데이터는 유지한다. 질문과 가이드처럼 의미가 다른 결과는 같은 문구라고 임의로 합치지 않는다. 전송 DTO는 필수 본문 누락을 검사하며 다른 질문·가이드 안의 같은 부분 문자열을 중복 항목으로 세지 않는다. 이 검사는 사용자 답변의 적절성 판단이나 질문 횟수 집계와 구분한다. 새 티켓 저장·담당자 배정·해결을 추정하지 않는다. 질문 5회 소진 후 시스템이 미확인이면 추가 질문 대신 시스템 선택 안내를 구성한다. 최종 본문의 길이·참조 검증이 실패하면 자동 절단하지 않고 실패 처리한다. 질문·가이드 자체의 의미 정확성은 역할별 평가가 필요하다.

이 내부 흐름은 명시적인 테스트 대역으로 검증한다. 서비스 실행은 아래 `real` 모드·RunLedger·HTTP 접수·진행 SSE 경로에서 연결하며 실제 모델·검색·Spring 권한·저장 전체 통합 검증은 별도다. 취소는 아래 `cancel` 경로로 전파한다. 서버 준비 상태는 아래 `readyz` 기준을 따른다. 메모리 문맥의 중복 실행 차단은 영속 RunLedger 접수를 대신하지 않는다.

## Supervisor 단독 실행과 의존성 주입

이 작업의 확인 대상은 주입받은 모델·역할 인터페이스를 사용해 한 run을 판단 → 역할 호출 → 병합 → 최종 결과까지 조율하는 것이다. 실제 OpenAI·전문 역할·검색 구현과 Spring 서비스 통합은 각 담당 작업에서 제공할 수 있다. 아래 조립 함수는 클라이언트 생성이나 외부 접속을 수행하지 않는다.

```python
from hops_ai.supervisor import Supervisor, create_run_services

services = create_run_services(
    request,
    settings=settings,
    access_scope_ref=server_scope_ref,
    llm=llm_client,
    tools=guarded_tools,
    tool_policy=tool_policy,
)
result = await Supervisor(agents=agents).analyze(request, services)
```

위 변수는 호출 측이 준비한다. `create_run_services`는 요청을 재검증하고 ID·버전·요청 기한을 반영한 새 `RunContext`를 만든다. `access_scope_ref`는 서버가 정한 접근 문맥 참조이며 권한 증명이나 사용자 JWT가 아니다. 지식 본문을 모델에 전달하기 전 현재 Spring 권한 확인과 digest 검증은 검색 어댑터가 수행해야 한다.

LLM은 `LLMClient.generate(LLMRequest)`에 맞는 원시 구현체를 주입한다. 반환값은 요청한 모델로 파싱한 `LLMResponse`와 사용량이어야 한다. SDK 자체 재시도는 끄며 `SupervisorRuntime`이 공통 예산 wrapper를 연결한다. 전문 역할은 `role: AgentRole`과 `async run(RoleRequest, SharedServices)`를 제공하고, 전달받은 호출 ID를 유지한 역할별 Result 또는 `RoleResponse`를 반환한다. 검색 권한·기존 엔티티 메타데이터는 신뢰된 어댑터가 채운다. 클라이언트 생성과 종료는 호출 측이 관리한다.

`RunContext`는 한 실행에만 사용한다. 같은 runId로 새 문맥을 만드는 것까지 이 함수가 영속 차단하지는 않으므로 실제 서비스 접수에서는 별도로 RunLedger를 연결한다. HTTP 실행 접수·취소·SSE와 `real` 모드는 이 단독 실행 경로에 연결하지 않는다.

`develop`의 백그라운드 실행 관리자에 연결할 때는 호출 측이 `RunPipeline.run(request, context)` 어댑터를 제공한다. 이 어댑터는 실행 관리자가 전달한 `context`를 그대로 `SharedServices.runtime`에 넣고 준비한 원시 클라이언트와 역할 목록으로 `Supervisor.analyze(request, services)`를 호출해 `AiResult`를 반환한다. 이 경로에서는 새 문맥을 만드는 `create_run_services`를 다시 호출하지 않는다. 접수·저장·동시 실행 제한은 `RunExecutor`, 한 run 내부의 역할 조율은 Supervisor가 담당한다. 기본 앱에는 이 어댑터를 자동 등록하지 않는다.

명시적인 테스트 대역으로 주입·그래프 실행 흐름을 확인하려면 `ai/`에서 다음 검사를 실행한다. 모델·역할 대역과 합성 입력은 회귀 테스트에서만 사용하며 실제 서버에 자동 연결되지 않는다. 실제 모델 품질이나 Spring·외부 DB를 포함한 서비스 E2E 검사가 아니다.

```bash
uv run pytest tests/integration/test_supervisor_standalone.py tests/integration/test_supervisor_routing.py tests/unit/test_supervisor_graph.py
```

검사는 질문·티켓·가이드·사례 제안, 잘못된 역할 선택의 안전한 실패, 예산·취소, 역할 구현 교체와 동시 요청의 입력·결과 분리를 확인한다. 형식은 contracts, 공통 실행 제한은 harness·서비스 wrapper, 업무 참조·질문 한도·기존 작업·병합·최종 응답은 Supervisor가 검증한다. 역할 호출 전후와 최종화 시점의 활성 검사는 늦은 응답 차단을 위해 유지한다.

`test_supervisor_routing.py`의 합성 회귀 시나리오 v1은 미처리 문제의 조기 종료, 복합 문의 누락, 정상 역할 실행 후 허위 완료 요약의 본문 유입, 조사 티켓의 미확인 항목 보존, 검색 권한 범위 확대 거부를 검사한다. 정해진 결정을 반환하는 대역 검사이므로 실제 모델의 역할 선택 정확도를 측정하지 않는다. 실제 모델 평가는 별도로 고정한 시나리오에서 역할 선택·종료 시점·반복 호출 수·답변 근거를 확인하고 모델·프롬프트·데이터 버전과 실행 제한을 함께 기록한다. 개인 실험 원문과 로그는 공유 코드에 포함하지 않는다.

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

`healthz` 성공은 프로세스 생존만 의미한다. `readyz`는 실행 파이프라인이 연결돼 실행 접수가 열려 있고 RunLedger 소유 세션이 2초 안에 응답할 때만 200 `{"status":"ready"}`다. scaffold·파이프라인 미주입·종료 중이면 503 `RUN_PIPELINE_DISABLED`, 소유 세션이 끊기거나 응답하지 않으면 503 `RUN_LEDGER_UNAVAILABLE`이다. 모델·검색 등 외부 서비스는 호출하지 않으므로 200이 OpenAI 연결이나 답변 품질을 뜻하지는 않는다. `real` 모드는 내부 API 활성화, `OPENAI_API_KEY`, `HOPS_AI_SUPERVISOR_MODEL`, 그리고 Ticket 도구(기본 켜짐)용 `HOPS_AI_SPRING_BASE_URL`·`SPRING_SERVICE_TOKEN`이 모두 있을 때만 시작하고 하나라도 없으면 거부한다. Fake로 대체하지 않는다. 오류 문구는 설정해야 할 변수 이름을 알려준다.

이 폴더의 [.env.example](.env.example)을 `ai/.env`로 복사해 `HOPS_AI_*`와 선택적인 OpenAI·Langfuse 설정을 관리합니다. 기존 개인 파일은 덮어쓰지 않습니다. 루트 Compose는 서비스별 파일 세 개를 자동으로 읽고 AI에 필요한 변수만 컨테이너에 전달합니다. uv 직접 실행은 `ai/`를 작업 디렉터리로 할 때 `.env`를 읽으며 프로세스 환경변수가 우선합니다. 두 Langfuse 키(`LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, 필요하면 `LANGFUSE_BASE_URL`)를 설정하면 real 모드의 실행을 추적합니다. Langfuse Tracing에서 `hops.ai.run`을 열면 `SUPERVISOR`, `INQUIRY`, `TICKET` 등 실제 호출된 단계의 Input과 Output을 확인할 수 있습니다. 기본값은 건수·분류·다음 행동 같은 요약만 기록합니다. 가상 데이터 전용 환경에서 `HOPS_AI_LANGFUSE_CAPTURE_CONTENT=true`로 실행하면 `model.generate`의 Input에 모델 요청 메시지, Output에 파싱된 구조화 응답이 저장됩니다. 같은 옵션에서 `INQUIRY` Output의 `questions`에 이번 단계가 추가한 질문 문구, `hops.ai.run` Output의 `answer`에 최종 답변이 표시됩니다. 병합이 끝난 문제별 확인 사실은 `INQUIRY` Output의 `factsByProblem`에서 `preservedFacts`와 `addedFacts`로 나뉘며 메시지와 지식 근거의 출처 ID를 함께 표시합니다. 이 모드는 프롬프트와 문의 내용 전체를 Langfuse 프로젝트로 전송합니다. 폐기된 `HOPS_AI_MODE=fake`, `HOPS_AI_AGENT_MODE`, `HOPS_AI_API_PREFIX`는 제거합니다. 비밀값은 Git에 올리지 않습니다.

실행 예산의 초기 기술 기본값은 전체 60초(`HOPS_AI_RUN_DEADLINE_SECONDS`), 모델 호출당 30초(`HOPS_AI_LLM_CALL_TIMEOUT_SECONDS`), 최대 8시도(`HOPS_AI_MAX_LLM_CALLS`), LLM 일시 오류 재시도 2회(`HOPS_AI_MAX_AUTO_RETRIES`), 호출당 출력 1,500토큰(`HOPS_AI_MAX_OUTPUT_TOKENS`)이다. Spring이 보낸 `limits`는 서버 상한(모델 호출 8회, 입력 60,000토큰(`HOPS_AI_MAX_RUN_INPUT_TOKENS`), 출력 8×1,500토큰, 도구 호출 10회(`HOPS_AI_MAX_TOOL_CALLS`), 재시도 7회)을 넘으면 접수하지 않고, 실행 중에는 서버 설정과 요청 중 작은 값을 쓴다. 토큰·도구 호출 누적 한도의 실제 적용은 모델·도구 연결 시 추가한다. 한 인스턴스의 동시 실행 수 기본값은 4(`HOPS_AI_MAX_CONCURRENT_RUNS`)이며, 기한 뒤 취소에 응답하지 않아 분리한 작업도 끝날 때까지 이 수에 포함한다. AI 서버는 한 인스턴스로 운영하는 것을 전제로 하므로 이 값이 서비스 전체 상한이다. 여러 인스턴스로 늘리면 인스턴스마다 상한이 따로 적용되므로 공유 저장소 기반의 서비스 전체 상한을 먼저 구현해야 한다. 역할 전환 때 전체 예산을 초기화하지 않는다. 실제 모델·프롬프트·금액 상한은 연결 전에 합의한다. 이 재시도는 색인 반영이나 업무 저장에 적용하지 않는다.

### 상담 실행(`real` 모드) 로컬 실행

`ai/.env`에 `HOPS_AI_MODE=real`, `HOPS_AI_INTERNAL_API_ENABLED=true`, `HOPS_AI_RUNTIME_DSN`, `AI_SERVICE_TOKEN`, `OPENAI_API_KEY`를 설정하고 Spring 연결용 `SPRING_SERVICE_TOKEN`·`HOPS_AI_SPRING_BASE_URL`(아래 Ticket 설명)도 갖추면 Spring이 접수한 상담 실행(`POST /internal/v2/runs`)을 공통 Supervisor가 실제 모델로 처리한다. 모델은 `HOPS_AI_SUPERVISOR_MODEL`(기본 `gpt-6-luna`)이다. 토큰은 받는 쪽 서비스의 `.env`에 한 번만 둔다. Spring→AI인 `AI_SERVICE_TOKEN`은 `ai/.env`, AI→Spring인 `SPRING_SERVICE_TOKEN`은 `backend/spring/hops/.env`이며 Compose가 세 파일을 모두 읽어 상대 컨테이너에도 같은 값을 전달한다.

처리 흐름은 Supervisor 판단 → Inquiry(원문 추출·양식 규칙 질문) → 필요 시 Ticket(제안·Spring 생성 요청·저장 결과 확인) → 최종 답변 조립이다. 정보가 부족하면 질문으로 끝난다. 사용법 문의처럼 근거 있는 가이드가 필요한 경로는 Knowledge 기본 설정(none)에서는 안전한 실패로 끝나며, 개발용 고정 자료 연결은 아래 Knowledge 절을 따른다. Ticket은 등록된 업무 도구로 Spring REST를 호출하고 저장·배정 확정은 Spring이 한다. 최종 AI 결과 수신 경로가 같은 티켓을 다시 생성하지 않는다.

real 모드의 기본 Ticket은 `ExecutingTicketAgent`다. 내부 `ModelTicketAgent`가 공용 LLM·동일 실행 예산으로 한 번의 구조화 출력을 요청하고(`ticket.proposal.v2`, 출력 상한 1,500토큰), 이후 생성·조회·제한 재시도는 코드로 수행해 추가 모델 호출을 하지 않는다. 모델/서비스 실패 시 규칙 구현으로 자동 대체하지 않는다. `build_registry()`의 기본 Ticket은 순수 제안 구현(`ModelTicketAgent`)이고, `execute_tickets=True`(제품 `SupervisorPipeline(ticket_client=...)`)이면 실행 구현(`ExecutingTicketAgent`)을 연결한다. `agents`를 직접 주면서 `ticket_client`를 함께 줄 수는 없다.

`HOPS_AI_TICKET_TOOLS_ENABLED=true`가 기본이다. real 모드에는 `HOPS_AI_SPRING_BASE_URL`과 Spring 내부 API의 `SPRING_SERVICE_TOKEN`도 필요하며 없으면 시작을 거부한다. 이 토큰은 Spring→AI의 `AI_SERVICE_TOKEN`과 다르다. Compose는 주소 기본값 http://backend:8080을 쓰므로 주소는 설정하지 않고 `backend/spring/hops/.env`의 `SPRING_SERVICE_TOKEN`만 채우면 된다. 컨테이너 안의 `localhost`는 AI 자신이라 Compose에서는 `http://localhost:8080`을 넣지 않는다. 호스트 직접 실행은 `ai/.env` 또는 프로세스 환경변수에 `HOPS_AI_SPRING_BASE_URL=http://localhost:8080`과 같은 토큰 값을 설정하고 Spring에도 같은 값을 주입한다. 토큰이 없거나 비어 있으면 미설정으로 보며 형식이 틀린 값(32바이트 미만 등)은 시작을 거부한다. 값 만들기는 `.env.example`을 따른다. 명시적으로 false를 설정한 경우만 제안 전용 모드로 실행하고 저장 성공을 표시하지 않는다.

기본 한도는 문제당 최초 포함 생성 3회, 조회 4회, 조회 간격 0.25초, HTTP 요청 5초다. `.env.example`의 `HOPS_AI_TICKET_*`로 더 작은 값을 선택할 수 있다. Spring의 생성 상한과 남은 실행 기한을 넘지 않으며 실제 HTTP 호출은 공통 toolCalls, 업무 재시도는 LLM과 같은 retries 예산을 소비한다. timeout/5xx/404는 미생성 증명이 아니므로 새 생성 없이 같은 작업만 조회한다. 재시도 응답 유실 후에는 이전 NOT_CREATED를 현재 사실로 재사용하지 않는다. 상세는 [Ticket 도구 계약](../contracts/agents/TICKET_TOOLS.md)을 따른다.

모델은 대상 문제별 제목·책임 시스템 후보·원문 인용·관련 지식 ID만 제안한다. 코드는 Inquiry의 요약·확인 사실·추정·미확인 항목·상세를 그대로 복사하고, 지식의 원본·버전·적용 조건을 보존한다. 보고 시스템은 변경하지 않는다. 근거 없는 다른 책임 후보는 보고 시스템으로 되돌리며, 허위 인용·지원하지 않는 시스템·중복/누락 문제·허용되지 않은 근거는 `OutputRejected`로 거부한다. 원문 인용 일치는 의미적 타당성이나 실제 장애 원인의 증명이 아니므로 담당자 검토가 필요하다.

기존 `STORED`·`PENDING`·`UNKNOWN`, 질문/안내 대기, 시스템 미확인·범위 밖 문제는 모델에 티켓 생성 대상으로 보내지 않는다. 서버가 `NOT_CREATED`로 확인한 문제만 재제안할 수 있다. 일부 문제가 이미 저장됐으면 해당 결과를 유지하고 남은 대상만 제안한다. 저장·배정·최신 버전·중복 생성의 최종 검증은 Spring이 담당한다. Knowledge 검색 자체는 별도 구현이며 Ticket은 State에 이미 검증되어 들어온 근거만 사용한다.

Ticket의 합성 시나리오 v1은 `tests/unit/test_ticket_model.py`와 `test_ticket_agent.py`에서 확인한다. 아래 검사는 Fake LLM의 계약·실패·Supervisor 연결 검사이며 실제 모델 품질 평가, 실제 검색, Spring 업무 저장 또는 브라우저 E2E를 뜻하지 않는다.

`test_ticket_tools.py`는 명시적인 HTTP 대역으로 전송·인증·응답 검증·한도를 검사한다. `tests/integration/test_ticket_spring_http.py`는 LLM만 대역으로 두고 제품 실행 코드와 실제 Spring TCP/HTTP·PostgreSQL을 연결한다. 전용 `hops_ticket_tools_test` DB와 `HOPS_TICKET_HTTP_TEST_ORIGIN/DSN/TOKEN/JWT_SECRET` 환경변수를 준비한 뒤 `uv run --no-sync pytest tests/integration/test_ticket_spring_http.py -q`로 실행한다. 미설정 시 이 외부 연동 검사는 skip하며 실제 모델 품질·로그인·브라우저 E2E는 포함하지 않는다.

2026-11-04에는 격리된 로컬 Spring·AI·PostgreSQL·Redis에서 실제 `gpt-6-luna`를 사용한 API E2E도 수행했다. 가상 문의자의 로그인·문의 접수 → Spring 자동 전달 → Supervisor·Inquiry·Ticket의 OpenAI 호출 → Spring 티켓 생성·조회 → 최종 답변 저장 → 담당자 로그인·티켓 상세 조회를 확인했다. 모델 생성 5회(입력 8,977·출력 491토큰), 약 17.3초에 티켓 1건과 주담당자 배정이 확인됐고 동일 문의 재전송에도 중복되지 않았다. 이 단일 시나리오는 모델 품질 평가나 브라우저 E2E를 대신하지 않는다. 이 검증 당시에는 문제 저장 후 `GET /api/v2/inquiries/{id}`가 Problem 투영 미구현으로 500을 반환했다. 이후 아래 develop 갱신 검증에서는 200과 Problem 표시를 확인했다. 문의 대화 조회와 담당자 티켓 상세 조회는 정상 응답했다.

```bash
uv run --no-sync pytest tests/unit/test_ticket_model.py tests/unit/test_ticket_agent.py -q
uv run --no-sync ruff check .
uv run --no-sync ruff format --check .
```

AI 런타임 DB와 계정은 Compose가 만들어 주지 않는다. 이미 만들어진 볼륨에는 초기화 스크립트가 다시 실행되지 않으므로 [AI 런타임 DB 계정·권한 준비](db/README.md)의 로컬 Compose 절차대로 `hops_ai_runtime` DB, 마이그레이션 계정 `hops_ai_migrator`, 앱 계정 `hops_ai_app`을 만든다. 예전 안내대로 `hops` 소유로 만든 DB도 같은 문서의 절차로 데이터를 보존한 채 넘겨받는다.

`HOPS_AI_RUNTIME_DSN`은 Compose 안에서 `postgresql://hops_ai_app:<앱 계정 비밀번호>@postgres:5432/hops_ai_runtime` 형식이다. AI 앱에 관리자 계정(`hops`)을 쓰지 않는다. 설정 후 `docker compose exec ai python -m hops_ai.persistence.check_runtime_db --business-dbname hops`로 권한을 점검한다. 토큰은 32바이트 난수를 base64url로 만든다.

```bash
python3 -c "import base64, secrets; print(base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b'=').decode())"
```

### Knowledge 개발용 고정 자료

`ai/.env`에 `HOPS_AI_KNOWLEDGE_SOURCE=fixture`를 추가하면 `real` 모드에서 Knowledge 역할이 켜진다(Compose도 같은 이름으로 AI 컨테이너에 전달한다). `spring`이면 Spring 내부 API(`/internal/v2/runs/{runId}/knowledge-searches`)로 DB의 승인 문서를 읽는다. `HOPS_AI_SPRING_BASE_URL`과 `SPRING_SERVICE_TOKEN`이 없으면 시작을 거부하고, 접근 범위는 요청이 아니라 Spring이 실행에서 확정한다. 검색 0건은 빈 결과, 검색 실패·접근 범위 초과(`DENIED`)는 검색 오류(`RetrievalUnavailable`)이며 빈 결과로 바꾸지 않는다. 호출마다 실행 전체 도구 호출 한도(`toolCalls`)를 소비하고 제한 시간은 `HOPS_AI_KNOWLEDGE_HTTP_TIMEOUT_SECONDS`(기본 5초)와 남은 기한 중 짧은 쪽이다. 관계 조회용 `SpringRelationSource`(증상→확인 항목→질문 문구)는 클라이언트까지만 있고 상담 흐름에는 연결하지 않았다. `fixture`는 테스트·오프라인용이다. 기본값 `none`에서는 Knowledge가 연결 전 자리로 남고 Inquiry도 기존 양식 규칙을 그대로 쓴다. 설정을 바꾸면 AI를 다시 시작해야 한다.

- 자료는 코드에 들어 있는 합성 가이드·점검 문서 몇 건이며 `origin=SYNTHETIC`으로 표시된다. 사용법 안내 흐름을 확인하는 용도이고, 검색 품질이나 운영 지식을 뜻하지 않는다. 실제 pgvector·Neo4j 검색과 Spring 권한 서버 연동은 이 설정에 포함되지 않는다.
- 검색은 서버가 허용한 문서 범위와 문제의 보고 시스템으로 먼저 좁힌 뒤 키워드 일치 수로 순위를 매긴다. 질의는 그 문제 자신의 정보로 만든다. 문제가 하나뿐일 때는 요약, 확인한 상세 값, 최근 사용자 원문을 함께 쓰고, 문제가 여럿이면 다른 문제의 단어가 섞이지 않게 원문과 모델이 쓴 요약을 쓰지 않고 사용자 인용으로 검증된 상세 값만 쓴다. 직원 대상 가이드(`EMPLOYEE`)만 사용자 안내가 되며 담당자 전용 문서는 안내로 쓰지 않는다.
- 사용법 문의는 시스템과 하려는 작업·화면만 확인되면 곧바로 가이드를 찾는다. 현재 진행 방식 같은 추가 정보를 먼저 묻지 않는다.
- 검색 결과가 없거나(`EMPTY`), 결과는 있지만 직원 공개 가이드가 없어 안내를 만들지 못하면(담당자용 런북만 걸린 경우 등) 그 문제는 "안내 불가"다. 오류가 아니라 "찾지 못함"이며 **검색한 문제(`KnowledgeResult.problemRef`)에만** 적용된다. 이때 Supervisor는 하려는 결과(`expectedBehavior`) 등을 더 묻거나, 담당자 검토 티켓(`REVIEW_REQUIRED`) 제안으로 넘길 수 있다. 검색하지 않은 문제는 담당자 전달 대상이 아니다. 같은 문제를 같은 질의로 같은 실행에서 다시 검색하지 않고, 확인한 사실이 더해져 질의가 바뀌었을 때만 다시 검색할 수 있다. 검색 자체가 실패하면(`ERROR`) 티켓을 만들지 않고 `SEARCH_UNAVAILABLE`로 실행이 실패한다.
- 한계: 공통 계약상 한 번의 실행에서 안내(`guide`)는 하나다. 사용법 문제가 둘 이상이면 첫 문제만 안내하고 나머지는 결과 없이 남아 실행이 실패할 수 있다(계약 확장 합의 후 후속). Spring이 AI의 문제·질문을 아직 저장하지 않아 사용자가 답할 때마다 질문 횟수(문제당 5회)가 이어서 누적되지 않는다. 안내 불가 뒤 질문과 티켓 중 무엇을 고르는지는 소수 표본의 실제 모델 시험으로 확인했을 뿐 안정성을 보증하지 않는다.

### Spring 전용 내부 조회 API

기본값 `HOPS_AI_INTERNAL_API_ENABLED=false`에서는 내부 경로를 등록하지 않는다. 로컬에서 명시적으로 켜려면 다음 세 환경변수를 서버 프로세스에 주입하고, 아래 RunLedger 안내대로 **AI 전용 DB `hops_ai_runtime`**에 마이그레이션을 먼저 적용한다.

- `HOPS_AI_INTERNAL_API_ENABLED=true`: 인증된 내부 API 기반 활성화.
- `HOPS_AI_RUNTIME_DSN`: `hops_ai_runtime` DB가 명시된 PostgreSQL 연결 문자열. 앱 실행용 최소 권한 계정 사용.
- `AI_SERVICE_TOKEN`: Spring→AI 서비스 인증용 토큰. 최소 32바이트의 암호학적으로 안전한 난수를 base64url로 인코딩해 제공한다. 길이·형식은 검사하지만 난수성을 판별하지는 않는다. AI→Spring 토큰과 공유하지 않는다.

값이 없거나 잘못됐거나 DB 접속·복구에 실패하면 활성 프로필의 시작을 거부한다. 토큰과 DB 비밀번호는 파일 예시·소스·로그에 넣지 않는다. 세 값은 `ai/.env`에서 관리하고 기본값은 비활성화다. 런타임 DSN은 Docker에서 `postgres:5432`, 호스트 직접 실행에서 `localhost:POSTGRES_PORT`를 사용하도록 실행 환경에 맞춘다. Compose의 PostgreSQL에는 `hops_ai_runtime` DB와 AI 계정이 자동으로 만들어지지 않으므로 활성화 전에 별도로 준비한다. 내부 경로는 서비스 전용 네트워크에서만 접근하도록 별도 구성해야 한다.

| 경로 | 현재 응답 |
|---|---|
| `GET /internal/v2/runs/{runId}` | Bearer 인증 후 저장된 snapshot 조회. 없는 실행은 404, DB/저장 형식 오류는 안전한 503 |
| `POST /internal/v2/runs` | 파이프라인 미주입 시 503(접수 안 함). 주입 시 신규·재전송 202 `AiAccepted`, 다른 입력 409, 기한 지남 422, 서버 상한 초과 429 `BUDGET_EXCEEDED`, 동시 실행 초과 429 `RATE_LIMITED`+`Retry-After`, 저장소 오류 503 |
| `GET /internal/v2/runs/{runId}/events` | 인증 후 progress/result SSE. 없는 실행 404, 연결 전 저장소 오류 503. 종료 실행은 저장된 result 한 건 후 종료 |
| `POST /internal/v2/runs/{runId}/cancel` | 파이프라인 미주입 시 503. 주입 시 본문 없는 202 빈 응답. 진행 중·시작 전 run은 취소하고, 이미 끝난 run·없는 runId·반복 요청도 202(최선 노력). 결과는 snapshot으로 확인 |

`/internal` 아래 모든 경로는 요청 본문을 읽기 전에 서비스 인증을 수행한다. 아직 등록되지 않은 내부 경로도 인증 전에는 401을 반환한다. 인증 실패는 안전한 오류 본문의 401이며 중복 Authorization 헤더도 거부한다. 생존·준비 상태 경로는 인증하지 않는다. 내부 조회만 활성화하고 실행 파이프라인이 없으면 `readyz`는 503이다.

```bash
uv run pytest
uv run ruff check .
uv run ruff format --check .
```

기본 테스트는 외부 모델·DB 호출 없이 수행한다. PostgreSQL 통합 검사는 아래 설정을 명시할 때만 실행한다. 대역은 명시적으로 준비한 응답·오류만 반환하며, 문의 분류·원인 판정·담당 배정·해결 성공을 만들어내지 않는다. 하네스·골격 검사를 실제 모델 품질·Spring 연동·서비스 E2E 결과로 보고하지 않는다.

### RunLedger 개발과 PostgreSQL 통합 검사

RunLedger는 Spring 업무 DB가 아닌 별도 `hops_ai_runtime` DB를 사용한다. DB와 최소 권한의 AI 계정은 환경 담당자가 [db/README.md](db/README.md)의 SQL과 절차로 준비한다. 앱 시작 시 DB 생성이나 마이그레이션은 자동 실행하지 않는다. DB 연결 문자열은 호출자가 `RunLedger`에 주입하며 비밀값을 저장소·로그에 남기지 않는다.

대상 DB를 확인한 뒤 [001](migrations/001_run_ledger.sql), [002](migrations/002_run_owner.sql)를 순서대로 적용한다. 이미 001이 적용된 DB는 실행기를 모두 중지한 뒤 002를 한 번 적용한다. 이전 소유자를 확인할 수 없는 미완료 실행은 002에서 `UNKNOWN`으로 보존한다. 운영 DB에 임의로 적용하지 않는다.

`RunAdmissionService`는 `AiRunRequest`의 입력을 다시 검증한 뒤 기한과 주입된 서버 한도를 검사하고 원자적으로 접수한다. 기존 runId와 입력 digest를 보존해 같은 입력은 기존 상태를 반환하고 다른 입력은 충돌로 처리한다. 결과 상세는 최소 24시간 유지하며, 앱이 떠 있는 동안 `expire_details`가 보존 기간이 지난 결과를 오래된 순으로 일괄 만료하며(기본 1시간마다, `HOPS_AI_LEDGER_EXPIRY_INTERVAL_SECONDS`), ID와 digest 표식을 남긴 채 미확인 상태로 바꾼다. 한 주기에 최대 1,000건을 처리하고 남은 건은 다음 주기에 처리한다. 단건 `expire_detail`도 그대로 쓸 수 있다.

`RunLedger.open()`은 인스턴스별 PostgreSQL 세션 잠금을 확보하고, 소유 세션이 사라진 미완료 run을 `UNKNOWN`으로 복구한다. 살아 있는 다른 인스턴스의 잠금은 건드리지 않는다. 접수·실행 변경은 잠금을 보유한 같은 DB 세션을 사용하므로 직접 연결 또는 세션 수명이 유지되는 연결 구성이 필요하다. 트랜잭션 단위 연결 풀은 사용할 수 없다. 네트워크 단절 시 PostgreSQL이 기존 세션 종료를 확인하기 전까지 복구가 지연될 수 있다. 앱이 떠 있는 동안 `recover_abandoned()`를 주기적으로 실행하므로(기본 60초, `HOPS_AI_LEDGER_RECOVERY_INTERVAL_SECONDS`) 다른 인스턴스가 중단되면 재시작을 기다리지 않고 그 run을 `UNKNOWN`으로 정리한다. 주기 작업이 실패하면 예외 종류만 경고 로그로 남기고 다음 주기에 다시 시도하며 앱과 준비 상태에는 영향을 주지 않는다. 앱 종료 시에는 준비 상태를 내리고 주기 작업과 실행을 멈춘 뒤 소유 세션을 닫는다.

테스트처럼 `create_app(..., ledger=ledger, pipeline=pipeline)`로 명시 주입하면 FastAPI 시작 시 ledger를 열고 복구한 뒤 백그라운드 실행기를 연결하며 종료 시 실행기·세션을 정리한다. pipeline 없이 내부 API만 활성화하면 저장된 실행 조회와 종료 결과 SSE를 제공하고 새 접수는 503이다. 기본 골격 실행은 DB에 연결하지 않는다. 실제 모델·Spring·DB 전체 통합 검증은 별도다.

### 실행 진행 SSE

내부 SSE의 `data`는 `AiEvent`이며 `event`는 progress/result, `id`는 실행 전체 `seq`다. Supervisor 판단·역할 실행 전에 서버가 정한 role/displaySummary를 발행한다. 모델의 요약·사고 과정·도구 원문을 진행 문구로 사용하지 않는다. 최종 result와 마지막 seq는 RunLedger 저장이 성공한 뒤에만 전달하며 종료 후 진행은 차단한다.

진행은 프로세스 내 유실 허용 전달이며 replay하지 않는다. 실행당 구독 8개·전체 256개, 구독당 대기 이벤트 64개를 상한으로 두며 느린 구독은 종료한다. 15초 comment heartbeat 시 저장 상태도 확인하므로 다른 AI 프로세스에서 끝난 실행은 최종 snapshot으로 복구할 수 있다(프로세스 간 실시간 진행 전달은 제공하지 않는다). 연결 해제는 실행을 취소하지 않는다. Spring은 SSE 연결 직후부터 snapshot 폴링을 병행하고 연결 종료·누락 시 같은 실행을 조회한다. 새 실행이나 모델 호출을 자동 생성하지 않는다. answer.delta는 이번 구현 범위에 포함하지 않는다.

### 실행 취소

`POST /internal/v2/runs/{runId}/cancel`은 이 프로세스에서 진행 중인 run에 취소를 전달하는 최선 노력 요청이며 항상 202 빈 본문으로 응답한다. 실행기가 run별 `RunContext`를 보관하다가 `cancel()`을 호출하면 하네스가 모델·역할 대기를 즉시 끊고 `RunCancelled`가 되며, run은 `SUPERSEDED`(오류 코드 `SUPERSEDED`, 재시도 불가)로 저장된 뒤 result 이벤트가 나간다. 시작 전에 취소된 run은 파이프라인을 실행하지 않는다. 취소가 반영되는 시점은 최종 결과 저장을 시작하기 전까지이며, 그 전에 받은 취소는 파이프라인이 성공 결과를 돌려줘도 반드시 `SUPERSEDED`로 저장하고 저장을 시작한 뒤 받은 취소는 결과를 바꾸지 않는다(snapshot과 result 이벤트가 항상 일치). 또 취소에 응답하지 않는 파이프라인은 유예 시간 뒤 run에서 떼어 내되 끝날 때까지 실행 슬롯을 차지한다. 이미 끝난 run, 없는 runId, 다른 프로세스에서 실행 중인 run에는 아무 일도 하지 않으므로 Spring은 snapshot으로 실제 상태를 확인한다. Spring이 새 입력으로 run을 대체할 때 이 경로를 호출하는 연결은 아직 없다.

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

### develop 갱신 후 실제 모델 추가 질문 검증

`c9e432a` 기준 develop에 Ticket 변경을 복원한 뒤 가상 문의로 실제 `gpt-6-luna`와 Spring·AI·PostgreSQL·Redis를 연결했다. 첫 정보 부족 입력에서는 기대 동작·발생 시점을 질문했고 티켓은 없었다. 화면·동작만 답한 두 번째 실행은 Supervisor가 Inquiry를 4회 반복해 모델 호출 상한 8회에서 `BUDGET_EXCEEDED`로 종료됐다. 발생 시점까지 입력한 세 번째 새 실행에서는 `STORED` 티켓 1건·배정을 확인했고 문의 상세도 HTTP 200으로 Problem을 표시했다. 이 실패를 포함한 결과이므로 정상 멀티턴 통과로 보고하지 않는다. 별도의 최초 시도에서 Supervisor 응답 `INVALID_OUTPUT`도 1회 관측했으며 응답 원문을 수집하지 않아 세부 원인은 확정하지 않았다.

질문 후 DB 문제 목록과 다음 실행의 `input.problems`가 비어 있어 질문 횟수·문제 식별자의 서버 누적은 확인되지 않았다. 대화 원문은 다음 입력에 포함되어 마지막 실행이 필요한 사실을 다시 추출했다. 보완 대상은 반복 역할 호출 제어와 문제·질문 상태 저장/재주입이며, 현재 테스트는 사용자 정정·복수 문제의 실제 모델 품질이나 브라우저 E2E를 포함하지 않는다. 갱신 후 Linux AI 회귀 검사는 822 passed, 외부 DB/HTTP 설정 미지정 23 skipped이며 Spring 이미지 빌드와 Ruff도 통과했다.

### Ticket 멀티턴 재검증 (develop `7b8bf2e` 통합 기준)

실제 OpenAI `gpt-6-luna`와 격리된 Spring·AI·PostgreSQL·Redis를 연결해 정보 부족 문의 → 기대/실제 동작 보충 → 발생 시점 보충의 세 입력을 검사했다. 최초 재현에서는 두 번째 입력 뒤 추가 질문이 남았는데 Supervisor가 Ticket을 선택하여 `INVALID_OUTPUT`으로 종료됐다. Ticket의 `available_for`를 실제 대상 문제 검사와 공유하여 제안할 문제가 없으면 역할 출력 스키마의 선택지에서 제외하도록 수정했다. 다른 독립 문제가 처리 가능하면 Ticket 선택은 유지한다.

수정 후 세 실행 모두 성공했다. 첫 두 실행은 추가 질문과 티켓 0건, 마지막 실행은 질문 종료와 실제 `STORED` 티켓 1건·`AUTO` 배정을 확인했다. 문의 상세 및 실제 담당자 로그인 후 티켓 상세도 HTTP 200이며, 총 22개 검증 조건을 통과했다. 이는 실제 모델을 사용하는 API E2E이며 브라우저 UI 검증은 아니다.

같은 기준의 회귀 검사는 Linux AI 995 passed, 23 skipped, 1 xfailed와 Spring 474개 통과다. 외부 환경 미지정으로 제외한 AI 검사 중 Ticket 실제 HTTP 8개는 별도 전용 DB·Spring에서 모두 통과했다. 예상 실패 1개는 기존 복수 가이드 계약 미정 시나리오다. Ruff check/format과 Spring 이미지 빌드도 통과했다.

질문 단계의 `inquiry_problem`과 다음 입력의 기존 문제 목록은 여전히 비어 있었다. 대화 원문을 다시 추출해 이번 세 입력은 완료됐지만, 문제 ID와 질문 횟수를 서버에 누적하는 기능 및 재시작을 포함한 문제당 5회 상한 검증은 별도 미완료다. 위 과거 실패 기록을 현재 재검증 결과와 구분한다.

### 브라우저 생성·표시 검증

실제 OpenAI와 같은 로컬 서버를 연결한 live 프론트에서 문의자 로그인 → 문의 → 추가 질문 답변 → 접수 결과 모달·티켓 카드 → 새로고침 후 유지 → 담당자 로그인·업무 보드·티켓 상세를 직접 확인했다. 최초 검증에서는 티켓 저장 뒤에도 Inquiry를 반복 호출해 8회 모델 호출 상한에 걸리고 오류 안내가 함께 표시됐다. 분석을 마친 티켓 대상에 양식상 더 물을 내용이 없거나 질문 한도에 도달하면 Inquiry를 선택지에서 제외하도록 보완했다. 미확인 시스템·다른 미처리 문제·새 실행의 최초 분석은 차단하지 않는다.

수정 후 새 가상 문의의 두 실행은 모두 `SUCCEEDED`, 티켓은 정확히 1건·`AUTO` 배정이며 최종 생성 안내와 담당자 상세가 오류 없이 표시됐다. Linux AI 회귀는 998 passed, 23 skipped, 1 xfailed다. 화면 검증은 이 정상 생성 경로에 한정하며 모든 화면·실패 경로·모델 품질 평가 완료를 의미하지 않는다. 질문 상태의 영속화는 위 미완료 범위 그대로다.

## 구현 경계와 다음 작업

- **Inquiry·Ticket:** 의도와 내부 처리 유형을 분리한다. 질문 묶음은 독립 문제당 최대 5회다. 보고 시스템·책임 시스템 후보·근거와 미확인을 보존한다. 구체 질문 전이·서버 저장 검증은 구현 전이다.
- **Knowledge:** 권한이 허용된 가이드·업무 정책·런북·승인 사례를 검색한다. 조건부 분석을 포함하며 그래프 연결만으로 원인을 확정하지 않는다. 검색 0건과 검색 오류를 구분한다.
- **Curator·색인:** 처리 기록으로 초안을 제안한다(`agents/curator/`, 프롬프트 `curator-draft-v1`). 입력(`WorkRecordContext`)은 같은 티켓의 이전 처리 기록·해결 확인 이력·상담에 쓴 근거와 AI 질문·답변을 가산 필드로 받지만(#198 ①) 아직 모델 입력에는 쓰지 않고, 사례 출처는 사용자 원문만 허용한다. 문의자가 미해결로 답한 기록은 모델 호출 없이 사례 없음이며, 모델·형식 오류와 USER 원문에서 확인되는 출처가 없는 재사용 초안은 사례 없음으로 바꾸지 않고 실패한다. 증상·점검 엔티티 연결(`ADDRESSES`·`USES_CHECK`)은 그래프 검색 연결 후 추가한다. 문의자 해결 확인·시스템 담당자 승인과 같은 승인 버전의 양쪽 반영 성공 전에는 사례를 어느 검색 경로에서도 공개하지 않는다. 업무 승인·공개 확정은 Spring의 책임이다.
- **공통:** Python은 업무 DB에 직접 쓰지 않는다. 실행/SSE·오류·인증·멱등성·취소·버전·공개 검증은 공통·에이전트 계약의 채택값을 적용하고 생산자·소비자의 실제 연결을 검증한다. 읽기 전용 도구 Mock은 해당 기능 명세에 따라 구현하며 실제 운영 관측과 구분한다.

먼저 [가상 기업 명세](../docs/knowledge/VIRTUAL_COMPANY.md)와 [데이터 구축 계획](../docs/knowledge/DATASETS.md)으로 소량 데이터를 검토한다. [에이전트 계약](../contracts/agents/README.md)과 [목표 공통 계약](../specs/contracts/TARGET_CONTRACT.md)을 구체화한 뒤 검색·실행·역할을 연결한다. Curator 담당 범위는 관련 담당자와 합의한다.
