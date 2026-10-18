# MVP API — 첫 E2E 통신 계약

[PRD](PRD.md)의 로그인 → 문의 분석 → 복수 티켓 생성·부서 배정 → 관리자 조회만 다룬다. [데이터 설계](MVP_DATA.md)의 M1~M6에 대응한다. 기존 상세 계약에서 필요한 부분을 추린 **팀 리뷰용 최소 설계안**이다. 아래 API가 구현되어 있다는 뜻은 아니다.

React는 Spring에만 요청한다. Spring이 인증·저장·배정을 담당하고 Python은 분석 결과를 반환한다. AI가 티켓을 직접 저장하거나 브라우저가 부서를 지정해 확정하는 API는 없다.

## 1. 공통 요청·응답

- REST 본문은 UTF-8 JSON이다. UUID는 문자열, 시간은 UTC RFC 3339 문자열, 버전·횟수는 정수다. DB의 snake_case를 API에서는 camelCase로 표현한다.
- 성공 응답은 객체를 바로 반환한다. 목록은 `{ "items": [], "page": 0, "size": 20, "totalElements": 0 }` 형태다. 페이지는 0부터 시작하고 size 기본 20, 최대 100을 제안한다.
- 자체 로그인 세션 쿠키는 HttpOnly로 사용한다. 운영 HTTPS에서는 Secure를 적용한다. 변경 요청은 CSRF 토큰을 검사한다. 비밀번호·세션 토큰을 로그에 남기지 않는다.
- 업무 변경 요청에는 `Idempotency-Key`를 보낸다. 범위는 사용자·HTTP 메소드·경로·키다. 같은 본문은 기존 응답, 다른 본문은 409다. 로그인·로그아웃은 업무 멱등키 대상이 아니다.
- 메시지 `content`는 Unicode 코드 포인트 기준 1~4,000자이며 공백만 있는 값은 거부한다. 줄바꿈을 임의 변경하거나 초과 내용을 자르지 않는다.
- `202`는 접수 성공이다. 분석·티켓 저장 완료는 REST 상세와 실행 상태로 확인한다.

오류 형식:

```json
{
  "code": "INQUIRY_VERSION_CONFLICT",
  "message": "문의 내용이 변경되었습니다. 최신 내용을 다시 불러와 주세요.",
  "requestId": "00000000-0000-4000-8000-000000000001",
  "retryable": false
}
```

400 입력 오류, 401 로그인 필요, 403 권한 부족, 404 조회 대상 없음, 409 버전·멱등 충돌, 429 요청 제한, 503 일시 장애를 사용한다. 내부 예외·프롬프트·비밀값은 응답하지 않는다. 202 이후의 실행 실패는 최초 HTTP 응답을 바꾸지 않고 run에 기록한다.

## 2. 화면 → Spring

### 로그인과 초기 정보 (M1)

- `GET /api/auth/csrf` → 200 `{token, headerName}`. 로그인 전에도 발급하며 반환된 헤더로 변경 요청을 보낸다.
- `POST /api/auth/login` — `{loginId, password}` → 200 `{userId, displayName, departmentId, roles}`와 세션 쿠키.
- `GET /api/auth/session` → 200 위 사용자 정보. 유효한 세션이 없으면 401.
- `POST /api/auth/logout` → 204. 세션을 무효화한다.
- `GET /api/departments` → 200 `{items:[{id, code, name, active}]}`. 인증된 사용자에게 부서 카탈로그를 제공한다. 이 작은 기준 목록은 페이지네이션하지 않는다.

### 문의와 대화 (M2·M3)

- `POST /api/inquiries` — `{content}` → 202 `{inquiryId, inquiryVersion, messageId, runId}`. 문의자·소속은 세션에서 얻는다.
- `GET /api/inquiries?page=0&size=20` → 본인 문의만 최신 갱신순 목록. 각 항목은 `id, title, status, updatedAt, ticketCount`다.
- `GET /api/inquiries/{inquiryId}` → `id, title, version, status, summary, problems, tickets, creationFailures, currentRun`. summary와 currentRun은 없으면 null이다. tickets는 실제 저장된 티켓만 담는다.
- `GET /api/inquiries/{inquiryId}/messages?page=0&size=20` → 대화순 메시지 목록. 항목은 `messageId, revision, sender, content, createdAt, revisions`다. revisions에는 이전 버전의 `revision, content, createdAt`을 담는다.
- `POST /api/inquiries/{inquiryId}/messages` — `{content, expectedInquiryVersion}` → 202 `{inquiryId, inquiryVersion, messageId, runId}`.
- `POST /api/inquiries/{inquiryId}/messages/{messageId}/revisions` — `{content, expectedInquiryVersion}` → 위 응답과 `revision`. 문의자가 본인의 사용자 메시지만 정정할 수 있다.

추가·정정은 문의 버전을 올린다. 이전 실행의 미반영 결과를 무효화하고 최신 버전으로 실행한다. 기존 티켓은 삭제하지 않는다. 같은 버전을 전제로 한 동시 요청은 하나만 반영하고 나머지는 409로 응답한다.

상세의 `problems`는 `{id, summary, category, status, questionCount}` 배열이다. `creationFailures`는 `{workItemId, problemId, status, errorCode}` 배열이며 사용자에게는 안전한 오류 코드만 제공한다. 문의 상태만으로 숨기지 않고 추가 질문·저장 실패 현황도 표시한다.

### 실행과 재시도 (M3·M6)

- `GET /api/runs/{runId}` → `{id, inquiryId, inquiryVersion, status, startedAt, finishedAt, errorCode, creationSummary}`. 미시작·미완료 시각과 오류 없음은 null이다.
- `creationSummary`는 `{succeeded, pending, needsReview}` 정수 건수다. AI 실행 성공과 티켓 저장 성공을 구분한다.
- `POST /api/runs/{runId}/retries` — `{expectedInquiryVersion}` → 202 `{runId, inquiryId, inquiryVersion}`. 본인 문의의 실패·시간 초과 실행에 한해 최신 내용을 재실행한다. 성공 티켓의 작업 ID는 유지한다.
- `GET /api/creation-failures?page=0&size=20` → 업무/운영 관리자의 복구 대상 목록. `NEEDS_REVIEW` 작업의 `workItemId, inquiryId, problemId, status, errorCode, retryCount, updatedAt`을 반환한다.
- `POST /api/creation-failures/{failureId}/retries` — `{expectedInquiryVersion}` → 202 `{workItemId, status}`. failureId는 workItemId다. 관리자가 기존 작업을 재시도하며 새 티켓을 임의 복제하지 않는다. 오래된 버전의 제안이면 409로 최신 분석을 요구한다.

### 티켓 조회 (M4·M5)

- `GET /api/tickets?page=0&size=20&departmentId=...` → 최신 생성순 티켓 목록. 업무 관리자는 전체/부서 필터, 일반 사용자는 본인 문의 또는 현재 배정 부서에 속하는 티켓 범위만 조회한다. 필터가 권한 범위를 넓혀서는 안 된다.
- `GET /api/tickets/{ticketId}` → 티켓 상세. 필수 응답 필드는 아래와 같다.

```json
{
  "id": "00000000-0000-4000-8000-000000000011",
  "inquiryId": "00000000-0000-4000-8000-000000000012",
  "problemId": "00000000-0000-4000-8000-000000000013",
  "workItemId": "00000000-0000-4000-8000-000000000014",
  "title": "VPN 연결 실패",
  "description": "VPN 연결 시 오류가 발생합니다.",
  "category": "NETWORK_ACCESS",
  "purpose": "INCIDENT",
  "department": {"id": "00000000-0000-4000-8000-000000000015", "code": "NETWORK", "name": "네트워크"},
  "processingStatus": "WAITING",
  "routingStatus": "ASSIGNED",
  "diagnosisSummary": "사용자가 보고한 접속 실패를 네트워크 문의로 분류했습니다.",
  "handoffReason": "담당 부서의 추가 조사가 필요합니다.",
  "uncertainty": "현재 시스템 상태는 조회하지 않았습니다.",
  "evidence": [],
  "createdAt": "2026-10-18T04:00:00Z"
}
```

목록과 문의 상세의 tickets는 위 필드 중 `id, inquiryId, title, department, processingStatus, routingStatus, createdAt`을 반환한다. 카드에는 생성 결과와 배정 부서를 표시하며 승인 버튼은 없다. 완료·재이관·개인 배정 변경 API는 후속 단계에서 추가한다.

## 3. Spring → Python 실행 요청

`POST /internal/agent-runs`는 서비스 간 인증으로만 호출한다. 브라우저 세션으로 직접 접근할 수 없다. 필수 입력은 다음과 같다.

- `schemaVersion`: 문자열 `mvp-1`. 이번 최소 계약의 버전이며 기존 상세 계약과 별개다.
- `runId`, `inquiryId`: UUID. `inquiryVersion`: 정수. `deadlineAt`: 종료 제한 시각.
- `actorContext`: `{userId, departmentId, roles}`. Spring에서 검증한 최소 정보만 전달한다.
- `departments`: 활성 부서의 `{id, code, name}` 배열.
- `messages`: 최신 메시지의 `{messageId, revision, sender, content}` 배열. 정정 전 정보가 필요하면 별도 `revisions` 배열에 넣는다.
- `problems`: `{id, summary, category, status, questionCount}` 배열.
- `workItems`: `{id, problemId, workScope, status, ticketId}` 배열. ticketId는 미생성이면 null이다. 기존 문제와 작업 식별자를 재사용하기 위한 입력이다.
- `budget`: `{executionSeconds:90, modelCallSeconds:30, modelErrorRetries:1, formatRepairRetries:1, questionsPerProblem:3}`.

202 `{runId, status:"QUEUED"}`로 접수한다. 동일 runId·동일 본문 재전송은 중복 실행하지 않으며 다른 본문은 409다. 실행 접수와 결과는 영속적으로 추적하고 통신 단절 시 같은 ID로 재전송한다.

## 4. LLM 구조화 결과와 Python → Spring

Python은 아래 형식으로 모델에 JSON 출력을 요구한다. `problems`와 `ticketProposals`는 항상 배열이며, 추가 질문과 티켓 제안을 같은 응답에 담을 수 있다.

```json
{
  "answer": "VPN 문제는 담당 부서에 연결하고, 포털 오류는 추가 정보를 확인하겠습니다.",
  "problems": [
    {"ref": "new:p1", "summary": "VPN 연결 실패", "category": "NETWORK_ACCESS", "action": "PROPOSE_TICKETS"},
    {"ref": "new:p2", "summary": "포털 데이터 조회 실패", "category": "BUSINESS_SERVICE_ERROR", "action": "ASK_USER", "question": "어떤 메뉴에서 어떤 오류가 표시되나요?"}
  ],
  "ticketProposals": [
    {
      "ref": "new:w1",
      "problemRef": "new:p1",
      "workScope": "VPN 접속 장애 조사",
      "title": "VPN 연결 실패",
      "description": "VPN 연결 오류로 업무 접속이 어렵습니다.",
      "category": "NETWORK_ACCESS",
      "departmentCode": "NETWORK",
      "purpose": "INCIDENT",
      "diagnosisSummary": "문의 내용에 따른 초기 분류입니다.",
      "handoffReason": "접속 상태 확인과 담당 부서 조사가 필요합니다.",
      "uncertainty": "실제 장애 원인은 확인하지 못했습니다.",
      "evidence": [
        {"messageId": "00000000-0000-4000-8000-000000000016", "messageRevision": 1, "summary": "사용자가 VPN 연결 오류를 보고했습니다."}
      ]
    }
  ]
}
```

문제 `action`은 `ASK_USER`, `PROPOSE_TICKETS`, `OUT_OF_SCOPE`다. ASK_USER에만 question이 필수다. 분류 전 category는 null을 허용한다. 이 MVP 계약은 진단·이관에 집중하며 Knowledge 빠른 경로와 사용자 해결 해석 계약은 후속 단계에서 확장한다.

새 문제·작업의 ref는 실행 내에서 고유한 `new:` 접두어 문자열이다. 기존 항목은 입력으로 받은 UUID를 ref로 사용한다. Spring은 새 ref를 UUID로 매핑해 저장한다. 이미 존재하는 문제·작업을 새 ref로 반복 제안한 경우 자동 삽입하지 않고 정합성 검토 대상으로 남긴다. 제목 문자열만으로 동일 작업이라고 판단하지 않는다.

Python은 결과를 `POST /internal/agent-events`로 보낸다. 필수 외피는 `schemaVersion, eventId, runId, inquiryId, inquiryVersion, occurredAt, type, payload`다. ID는 UUID, type은 `result` 또는 `failed`다. result의 payload는 위 결과, failed는 `{code, retryable}`다.

Spring은 200 `{eventId, disposition}`으로 수신을 확인한다. disposition은 `APPLIED`, `DUPLICATE`, `IGNORED_STALE`다. result 수신 처리는 유효 결과·작업 접수를 의미하며 모든 티켓 저장 완료를 의미하지 않는다. 같은 eventId의 다른 본문은 409다. 결과 처리와 수신 기록은 중복 반영이 없도록 원자적으로 처리한다.

검증해야 할 사항:

1. 요청한 실행·문의·현재 버전에 해당하는 결과인지 확인한다.
2. 기존 ID의 소유 관계, ref의 고유성, problemRef 참조, 필수 필드·enum·근거 메시지 버전을 확인한다.
3. 제안한 부서가 활성 카탈로그에 있고 유형·목적과 모순되지 않는지 확인한다. CS_REVIEW는 CS로 배정한다.
4. 추가 질문은 문제당 최대 3회다. 초과하면 질문을 반복하지 않고 현재 근거로 CS 검토 제안을 요구한다.
5. 검증된 각 작업을 별도로 저장·재시도한다. 한 작업 실패로 성공한 티켓을 취소하지 않는다.

모델 호출 오류 재시도·형식 복구도 전체 실행 90초 안에서 진행한다. 사용자 답변을 기다리는 시간은 실행 시간에 포함하지 않는다. 모델 제한과 티켓 저장의 5초·15초 재시도는 별개다.

## 5. 실시간 표시와 복구

MVP의 영속 상태 원본은 REST다. WebSocket은 진행 단계·답변 조각·상태 변경을 빠르게 보여주는 보조 통로다. 접속 경로는 `/ws`를 제안하며 세션과 Origin을 검증한다.

- 구독 요청: `{type:"subscribe", inquiryId}`. 서버가 문의 조회 권한을 검사한다.
- 이벤트 외피: `{type, inquiryId, inquiryVersion, runId, payload}`.
- `progress`: payload `{stage, message}`. 사용자용 짧은 진행 설명만 보낸다.
- `answer_delta`: payload `{messageId, sequence, text}`. 같은 메시지의 순번은 1부터 증가한다. 조각이 유실되면 REST의 최종 메시지를 사용한다.
- `state_changed`: payload `{}`. 문의와 실행 상세를 REST로 다시 조회한다.
- `error`: payload `{code, message}`. 저장 성공을 추정하지 않는다.

최종 답변은 먼저 메시지로 저장한 뒤 state_changed를 보낸다. 이전 문의 버전의 스트림은 표시하지 않는다. 재연결 시 문의·메시지·실행을 REST로 조회하며 이벤트 재생 서버는 만들지 않는다. 세션 만료 후 재로그인해 같은 문의를 이어간다.

## 6. 첫 E2E 완료 조건

- M1: 로그인한 문의자의 소속·역할이 서버에서 적용되고 타인 문의 접근이 거부된다.
- M2: 문의를 이어 쓰고 정정할 수 있으며 이전 내용도 남는다.
- M3: 추가 질문 후 분석을 이어가며 문제별 3회 제한을 지킨다. 이전 실행 결과는 최신 내용에 덮어쓰지 않는다.
- M4: 복합 문의에서 필요한 복수 티켓이 생성되고 각각 부서에 배정된다. 사용자 승인을 요구하지 않는다.
- M5: 정보 카드와 관리자 목록에서 같은 티켓·부서를 확인한다.
- M6: 요청·결과를 중복 전달해도 티켓이 늘지 않는다. 일부 저장 실패 시 성공 티켓은 유지되고 실패 작업만 재시도·관리자 확인 대상으로 남는다.

이 문서는 ERD와 첫 API 구현을 함께 검토하기 위한 자연어 계약이다. 팀 리뷰 뒤 같은 내용을 OpenAPI YAML과 JSON Schema로 옮겨 필수 필드·타입·예외를 자동 검증한다. YAML을 추가할 때는 이 문서와 다르게 별도 정책을 만들지 않는다.
