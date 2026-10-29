# 에이전트 공통 입출력 계약

계약 버전: `agent-1` · Python 내부 모듈 계약 · 팀 리뷰용 설계안

[PRD](../../docs/PRD.md)의 Supervisor와 4개 전문 에이전트를 서로 독립적으로 개발하기 위한 계약이다. 담당자별 준비와 통합 순서는 [개발 안내](../../docs/AGENT_DEVELOPMENT.md)를 따른다. 아직 구현·팀 승인 완료를 뜻하지 않는다.

로그인·채팅·부서 티켓의 서비스 연결용 Mock은 [공통 wire 계약](../../specs/contracts/README.md)을 따른다. [공통 데이터 타입 문서](../../specs/contracts/DATA_TYPES.md#서비스-내부-표현)를 기준으로 각 서비스의 어댑터를 통해 이 내부 agent-1과 변환하며 내부 계약을 교체하지 않는다.

## 1. 적용 경계

Python 3.13 + LangGraph + FastAPI 애플리케이션 하나 안에서 Supervisor가 에이전트 모듈을 호출한다. LangGraph는 내부 실행 흐름을, FastAPI는 서비스 간 HTTP 어댑터를 담당한다. 이 내부 역할 계약은 후속 멀티 에이전트 구조에 적용한다. MVP는 실제 단일 에이전트를 서비스 어댑터에 연결하고 기준선 평가 결과를 보존한다. Fake는 개발 중 역할별 연결 테스트 용도이며 MVP 평가를 대신하지 않는다. 에이전트마다 HTTP 서버·모델 연결·업무 DB 저장 코드를 따로 만들지 않는다.

```text
Spring 실행 요청 → Python API 어댑터 → Supervisor
                                      ├─ Intake
                                      ├─ Knowledge
                                      ├─ Diagnosis
                                      └─ Ticket
Spring 검증·저장 ← Python 결과 어댑터 ← 실행 결과 조합
```

- 서비스 간 요청은 [API 설계](../../docs/API.md)의 MVP 최소 결과를 참고한다. `mvp-1` 요청·응답 필드와 Spring–Python SSE의 최종 결과 경로·재연결·인증은 통합 전에 확정한다. `agent-1`은 내부 버전이며 외부 API를 조용히 대체하지 않는다.
- Supervisor는 경로·순서·예산과 결과 조합을 담당한다. 각 에이전트는 자기 역할의 결과만 반환하며 서로를 직접 호출하지 않는다.
- 모델 출력은 에이전트 코드에서 검증한 뒤 반환한다. Spring이 권한·업무 쓰기·배정·해결 상태를 최종 확정한다.
- 전체 실행과 역할 호출을 구분한다. 하나의 `runId` 안에서 서로 다른 `invocationId`로 여러 역할을 호출할 수 있다.

## 2. 공통 타입과 식별자

JSON 키는 camelCase, 문자열은 UTF-8, 시각은 UTC RFC 3339, 영속 ID는 UUID 문자열이다. 아래 필드는 별도 optional 표기가 없으면 필수다. nullable은 키를 생략하는 것이 아니라 null을 전달한다. 배열은 항목이 없어도 `[]`로 전달한다. 정의하지 않은 필드는 검증 단계에서 거부한다.

- `AgentRole`: `INTAKE`, `KNOWLEDGE`, `DIAGNOSIS`, `TICKET`.
- `Category`: `ACCOUNT_AUTH`, `PERMISSION`, `NETWORK_ACCESS`, `SOFTWARE`, `BUSINESS_SERVICE_ERROR`, `OTHER`.
- `Ref`: 기존 문제·작업은 Spring에서 받은 UUID. 새 항목은 `new:`로 시작하는 임시 문자열이다.
- 새로운 문제 ref는 Intake가, 작업 ref는 Ticket이 제안한다. Supervisor가 실행 전체에서 충돌을 검사한다. 다른 역할은 입력 ref를 바꾸지 않는다.
- 실제 UUID 발급·기존 항목과의 최종 매핑은 Spring이 담당한다. 실행을 다시 시작하면 기존 저장 ID를 입력으로 전달한다. 이미 생성된 작업을 새 ref로 복제하지 않는다.
- `questionCount`는 서버의 현재 값이다. 모델이 임의로 초기화하거나 증가시키지 않는다. 검증·전달된 추가 질문을 서버가 한 번만 집계한다.

### 공통 데이터 객체

- `Message`: `messageId: UUID`, `revision: int>=1`, `sender: USER|ASSISTANT`, `content: string`. 정정 후 최신 버전을 기본 제공하며 필요 시 이전 버전을 명시적으로 추가한다.
- `Problem`: `ref: Ref`, `summary: string`, `category: Category|null`, `questionCount: int(0..3)`.
- `WorkItem`: `ref: Ref`, `problemRef: Ref`, `workScope: string`, `status: string`, `ticketId: UUID|null`. status는 외부 계약의 작업 상태를 그대로 받는다.
- `Department`: `id: UUID`, `code: string`, `name: string`, `responsibilities: string[]`. code는 서버가 제공한 활성 부서 카탈로그에서만 선택한다. 초기 8개 부서와 실제 기업의 조직 확장을 구분한다.
- `Evidence`: `id: string`, `kind: MESSAGE|DOCUMENT|TOOL`, `sourceId: string`, `sourceVersion: string`, `summary: string`, `observedAt: timestamp|null`.
- MESSAGE 근거의 sourceId는 messageId, sourceVersion은 메시지 revision의 문자열이다. DOCUMENT는 문서 ID·버전, TOOL은 조회 결과 ID·조회 스냅샷 버전이다. TOOL은 observedAt이 필수 값이다. 모델이 근거 ID를 만들어내면 거부한다.
- 문서·도구 근거는 권한 검사된 공통 서비스가 제공한다. Evidence에는 원문 전체나 접근 토큰을 넣지 않는다. 서비스 계층은 원본의 접근 범위 메타데이터를 유지하고 반환·재사용 때 검사한다.

## 3. 공통 실행 인터페이스

```python
async def execute(request: AgentRequest, services: SharedServices) -> AgentResult:
    ...
```

함수 형태의 역할 계약이며 LangGraph 노드 어댑터가 이 계약으로 모듈을 호출한다. LangGraph 내부 상태나 FastAPI 요청 객체를 역할별 공통 계약에 노출하지 않는다. Python 타입 구현은 JSON 계약의 의미를 그대로 따라야 한다.

### AgentRequest

- `contractVersion: "agent-1"`.
- `runId: UUID`, `invocationId: UUID`, `inquiryId: UUID`, `inquiryVersion: int>=1`.
- `role: AgentRole`.
- `context.actor: {userId: UUID, departmentId: UUID, roles: string[]}`: Spring이 검증한 정보.
- `context.accessScopeRef: string`: 서버가 검증 가능한 접근 범위 참조. 임의 역할 문자열을 권한 증명으로 사용하지 않는다.
- `context.deadlineAt: timestamp`: 전체 실행 종료 시각. 에이전트 호출마다 60초를 새로 부여하지 않는다.
- `context.allowedTools: string[]`: 해당 역할이 요청할 수 있는 도구 이름. 최종 실행 시 서버 권한 검사를 다시 거친다.
- `payload`: 4절의 role별 입력 객체.

context는 Runtime이 만들고 모델에게 수정 권한을 주지 않는다. 역할에 불필요한 개인정보·대화·도구는 전달하지 않는다. 취소 신호와 남은 시간은 SharedServices의 Runtime 접근 기능으로 확인한다.

### AgentResult

- `contractVersion: "agent-1"`, `runId`, `invocationId`, `inquiryId`, `inquiryVersion`, `role`: 요청과 동일.
- `status: OK|ERROR|TIMED_OUT|CANCELLED`.
- `data`: OK이면 역할별 결과 객체, 다른 상태면 null.
- `error`: OK이면 null, 다른 상태면 `{code: string, message: string, retryable: boolean}`.
- `evidence: Evidence[]`: 결과에서 참조하는 검증된 근거 목록.
- `meta: {durationMs: int>=0, modelId: string|null, promptVersion: string|null}`: 측정값이며 모델이 생성하지 않는다. 모델 미사용 시 null.

OK는 역할 실행 성공을 뜻한다. 업무 해결·티켓 저장·배정 성공이 아니다. 검색 결과 없음은 OK+INSUFFICIENT이며 검색 시스템 장애는 ERROR다. 예상한 기술 오류는 안전한 코드로 반환하고 예상치 못한 예외는 Runtime이 ERROR로 변환한다. 비밀값·내부 사고 과정·원시 예외를 반환하지 않는다.

## 4. 역할별 입력과 결과

### Intake — 문의 분석

입력 payload:

- `messages: Message[]`, `existingProblems: Problem[]`.
- `resolutionTargets: Ref[]`: 서버가 제공한 해결 확인 대상 티켓/문제 식별자. 대상이 없으면 빈 배열.

결과 data:

- `replyDraft: string`.
- `problems: IntakeProblem[]`. IntakeProblem은 Problem의 필드에 `nextAction: ASK_USER|SEARCH|DIAGNOSE|PROPOSE_TICKETS|OUT_OF_SCOPE`, `question: string|null`, `evidenceIds: string[]`을 추가한다.
- `resolutionSignals: {targetRef: Ref, answer: SOLVED|NOT_SOLVED|UNCLEAR, evidenceIds: string[]}[]`.

ASK_USER이면 question이 비어 있으면 안 된다. 질문 한도가 소진되면 추가 질문을 반복하지 않고 현재 근거로 티켓 검토 경로를 제안한다. nextAction은 Supervisor에 대한 제안이다. 해결 응답은 사용자 발화의 해석이며 실제 완료 변경이 아니다. 확인 대상이 불명확하면 특정 티켓에 SOLVED를 적용하지 않는다.

### Knowledge — 지식 검색과 답변

입력 payload:

- `problem: Problem`, `query: string`, `messages: Message[]`, `knownEvidence: Evidence[]`.
- `mode: ANSWER|SUPPORT_DIAGNOSIS`.

결과 data:

- `problemRef: Ref`, `answerDraft: string|null`.
- `sufficiency: SUFFICIENT|INSUFFICIENT`, `evidenceIds: string[]`.
- `missingInformation: string[]`, `suggestedNext: GUIDE_USER|ASK_USER|DIAGNOSE|HANDOFF`.

검색 전략은 담당자가 선택하지만 결과 형식은 유지한다. 근거 없는 정답을 생성하지 않는다. 검색 전략·문서 버전은 실행 이력에 기록한다. 권한 필터는 검색 전 적용하며 근거 충분 판정만으로 문의를 해결 완료하지 않는다.

### Diagnosis — 장애 진단

입력 payload:

- `problem: Problem`, `messages: Message[]`, `knownEvidence: Evidence[]`.
- `targetServiceIds: string[]`: 서버가 제공한 서비스 카탈로그의 대상.

결과 data:

- `problemRef: Ref`.
- `findings: {summary: string, evidenceIds: string[]}[]`.
- `causeCandidates: {summary: string, evidenceIds: string[]}[]`.
- `uncertainties: string[]`.
- `disposition: GUIDE_USER|HANDOFF|NEED_INFO|UNDETERMINED`.
- `guidanceDraft: string|null`, `question: string|null`.

조회 계획·근거 검토는 에이전트 내부 단계다. 도구는 읽기 전용이며 실제 변경은 하지 않는다. 조회 일부가 실패해도 유효한 근거가 남으면 불확실성을 포함한 OK 결과를 낼 수 있다. 도구 시스템 장애로 분석 자체가 불가능하면 ERROR를 반환한다. 조회 실패를 정상 상태나 확정 원인으로 변환하지 않는다.

### Ticket — 티켓 초안과 부서 판단

입력 payload:

- `problems: Problem[]`, `evidence: Evidence[]`, `diagnoses: DiagnosisData[]`.
- `departments: Department[]`, `existingWorkItems: WorkItem[]`.
- `csReviewProblemRefs: Ref[]`: 추가 질문 한도 후에도 분류할 수 없는 IT 문제.

결과 data:

- `ticketProposals: TicketProposal[]`.
- `unresolvedProblemRefs: Ref[]`: 유효한 제안을 만들지 못한 문제. 빈 제안으로 성공한 것처럼 숨기지 않는다.

TicketProposal 필드:

- `ref: Ref`, `problemRef: Ref`, `workScope: string`.
- `title: string`, `description: string`, `category: Category`, `departmentCode: string`.
- `purpose: INCIDENT|CS_REVIEW`, `diagnosisSummary: string`, `handoffReason: string`, `uncertainty: string|null`.
- `evidenceIds: string[]`.

문제·작업별로 필요한 복수 제안을 반환하며 고정 개수 제한을 두지 않는다. CS_REVIEW는 CS로 제안한다. 부서는 서버 카탈로그 안에서만 선택하고 기존 작업은 같은 ref를 사용한다. 사용자 승인이나 담당 부서 수락을 요구하지 않는다. 재이관·그룹핑은 후속 계약으로 별도 확장하며 이번 최초 생성 payload에 임의 필드를 추가하지 않는다.

## 5. 성공 결과 예시

아래는 검색을 정상 수행했지만 근거가 부족한 경우다. 기술 실패와 구분한다.

```json
{
  "contractVersion": "agent-1",
  "runId": "00000000-0000-4000-8000-000000000001",
  "invocationId": "00000000-0000-4000-8000-000000000002",
  "inquiryId": "00000000-0000-4000-8000-000000000003",
  "inquiryVersion": 1,
  "role": "KNOWLEDGE",
  "status": "OK",
  "data": {
    "problemRef": "new:p1",
    "answerDraft": null,
    "sufficiency": "INSUFFICIENT",
    "evidenceIds": [],
    "missingInformation": ["오류가 발생한 메뉴와 오류 메시지"],
    "suggestedNext": "ASK_USER"
  },
  "error": null,
  "evidence": [],
  "meta": {"durationMs": 120, "modelId": null, "promptVersion": null}
}
```

## 6. 공통 서비스와 실패 처리

SharedServices는 LLM 호출, 지식 검색, 읽기 도구, 실행 추적, 남은 시간·취소 확인을 제공한다. 구현체를 Fake로 교체할 수 있어야 한다. 역할별 허용 기능을 제한하며 전체 서비스 객체가 모든 도구의 실행 권한을 뜻하지 않는다.

- LLM: 구조화 출력 검증·형식 복구·호출 제한을 공통 처리한다. 담당자가 내부 재시도 루프를 추가하지 않는다.
- 검색: 접근 범위가 적용된 문서·근거를 반환한다. 전략은 Knowledge가 선택한다.
- 도구: 요청한 도구·대상·입력을 검사하고 Spring 권한 검사 후 실행한다. `OK|DENIED|TIMEOUT|ERROR`와 조회 시각·근거 ID를 반환한다. DENIED를 다른 도구로 우회하지 않는다.
- 추적: runId·invocationId·역할·단계·소요 시간·근거 ID·오류를 기록한다. 원시 내부 사고 과정은 기록하지 않는다.

공통 오류 코드는 `INVALID_INPUT`, `INVALID_OUTPUT`, `MODEL_UNAVAILABLE`, `MODEL_TIMEOUT`, `RETRIEVAL_UNAVAILABLE`, `TOOL_FAILURE`, `ACCESS_DENIED`, `DEADLINE_EXCEEDED`, `BUDGET_EXCEEDED`, `SUPERSEDED`, `INTERNAL_ERROR`를 제안한다. 모델 연결 장애·검색 연결 장애 등 일시 오류만 retryable=true로 표시한다. 권한 거부·기한 소진·이전 버전·취소는 같은 요청을 자동 반복하지 않는다. 재시도 여부는 Runtime이 남은 예산과 원인을 함께 보고 결정한다.

전체 60초, 모델 호출 30초, 호출당 모델 오류 재시도 2회, 한 실행의 모델 호출 상한 8회(재시도 포함), 형식 복구 1회(구현 예정)는 단일·멀티 공통 설정을 입력으로 받는다. 역할 전환 시 실행 시간을 초기화하지 않는다. 병렬 역할은 읽기 스냅샷을 받아 독립 실행하고 Supervisor만 결과를 병합한다. 새 문의 버전 또는 취소 이후의 늦은 결과는 사용하지 않는다.

## 7. 기존 MVP API와 연결하는 규칙

- Supervisor는 역할 결과를 모아 외부 `answer`, `problems`, `ticketProposals` 형태로 변환한다. 내부 nextAction을 외부 action으로 그대로 보내지 않는다.
- 추가 질문은 `ASK_USER`, 검증된 생성 제안은 `PROPOSE_TICKETS`, 범위 밖은 `OUT_OF_SCOPE`로 매핑한다. SEARCH·DIAGNOSE는 실행 도중의 내부 결정이다.
- 현재 mvp-1의 근거는 메시지 ID·버전·요약이다. 내부 MESSAGE 근거를 그 형식으로 변환한다. 문서·도구 근거를 가짜 메시지로 바꾸거나 버리지 않는다.
- Knowledge 무티켓 안내·해결 확인 결과, DOCUMENT/TOOL 근거는 현재 mvp-1에서 충분히 표현되지 않는다. 해당 에이전트는 내부 계약과 Fake로 개발할 수 있지만 실제 Spring 연결 전 외부 OpenAPI·스키마·저장 구조를 함께 확장해야 한다.
- 권한 범위 참조, 부서 R&R, 서비스 카탈로그도 기존 최소 요청에 없는 보강 정보다. 서버가 검증하는 공통 어댑터로 제공하고 모델이 생성한 값을 신뢰하지 않는다.
- 기술 오류를 OUT_OF_SCOPE나 CS_REVIEW로 치환하지 않는다. 성공한 문제 결과를 보존하되 모든 업무 저장·실패 판단은 Spring 응답으로 확인한다.

## 8. 계약 리뷰와 변경

Intake·Supervisor 담당이 내부 공통 타입과 어댑터의 책임자이고 다른 세 담당자가 생산자·소비자 관점에서 검토한다. Ticket 담당은 Spring 계약 영향을 함께 검토한다. 키·enum·필수성·의미 변경은 소비자 코드·예시·테스트와 같이 PR로 제출한다.

이 문서는 자연어 계약이다. 첫 공통 기반 PR에서 이를 Python 타입과 JSON Schema로 옮기고 역할별 정상·오류 fixture를 검증한다. 그 검증을 통과하기 전에는 기계 판독 계약이나 통합이 완료됐다고 표시하지 않는다.
