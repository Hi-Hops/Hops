# 공통 데이터 필드

상태: 2026-10-25 개발 기준 확정 / 팀 리뷰·서비스별 구현 검증 별도. 언어에 독립적인 데이터 계약 문서이며 각 서비스는 필요한 타입을 자기 코드에서 구현한다. 자동 생성 코드나 언어별 DTO를 공통 구현으로 제공하지 않는다. 정책과 구현/검증 구분은 [WIRE_CONTRACT 11절](WIRE_CONTRACT.md#11-담당별-구현과-검증-상태)을 따른다.

모든 객체 필드는 필수다. nullable은 키를 생략하지 않고 null을 보낸다. 배열은 []를 보낸다.


## 기본 타입과 값

- `Uuid`: 소문자 하이픈 UUID 문자열.
- `IsoDateTime`: UTC 날짜·시각 문자열. `YYYY-MM-DDTHH:mm:ss[.소수초]Z` 형식, 소수초는 1~6자리다.
- `UserContent`: 공백 포함 1~4,000 Unicode 코드 포인트의 문자열. 단독 surrogate와 공백만 있는 입력은 거부하며 원문을 자르거나 trim하여 저장하지 않는다.
- 공백 집합: U+0009~000D, U+0020, U+0085, U+00A0, U+1680, U+2000~200A, U+2028/2029, U+202F, U+205F, U+3000, U+FEFF.
- 정수는 필드별 최소값부터 2,147,483,647 이하. `number`로 기재된 버전·차수·순번·페이지·횟수는 소수를 허용하지 않는다.
- `T[]` 또는 `Array<T>`는 JSON 배열, `A 또는 B`는 둘 중 하나의 형태다. `null` 허용 여부는 각 표에 명시한다.

- `Category`: `ACCOUNT_AUTH`, `PERMISSION`, `NETWORK_ACCESS`, `SOFTWARE`, `BUSINESS_SERVICE_ERROR`, `OTHER`.
- `Role`: `BIZ_ADMIN`, `AGENT_OPS_ADMIN`.
- `InquiryStatus`: `IN_PROGRESS`, `WAITING_USER`, `RESOLVED`, `CLOSED_UNCONFIRMED`, `CLOSED`.
- `ProcessingStatus`: `WAITING`, `IN_PROGRESS`, `WAITING_CONFIRM`, `RESOLVED`, `CLOSED_UNCONFIRMED`.
- `ProblemStatus`: `ANALYZING`(분석 중), `WAITING_USER`(추가 질문 중), `TICKET_CREATED`(티켓 생성·배정됨), `CREATION_FAILED`(생성 실패), `OUT_OF_SCOPE`(지원 범위 밖). 부분 성공은 tickets/creationFailures와 함께 표시한다. 이 코드만으로 해결을 확정하지 않는다.
- `RoutingStatus`: `ASSIGNED`(부서 배정됨), `CS_REVIEW`(CS 검토), `REASSIGNING`(재배정 중), `EXCEPTION`(배정 예외), `RECOVERY_PENDING`(복구 대기). 개인 담당자 유무는 이 코드에 섞지 않고 담당자 식별자로 관리한다. 현재 TicketCard/Detail은 담당자 필드를 제공하지 않으며 담당자 기능은 후속이다.
- `RunStatus`: `QUEUED`(대기), `RUNNING`(분석 중), `SUCCEEDED`(분석 완료), `FAILED`(실패), `TIMED_OUT`(시간 초과), `SUPERSEDED`(새 입력 버전/실행으로 대체됨). 분석 성공과 티켓 저장 성공은 별개다.
- `RecoveryStatus`(후속): `PENDING`(복구 대기), `RETRYING`(재시도 중), `RECOVERED`(복구 완료), `FAILED`(복구 실패). 현재 화면/응답 타입에 구현되었다고 가정하지 않는다.
- `ConsentStatus`(후속): `PENDING`(응답 대기), `ACCEPTED`(동의), `DECLINED`(거절), `EXPIRED`(만료), `INVALIDATED`(대상 내용 변경으로 무효). 만료 시간·후속 payload는 해당 기능 구현 전에 정한다.
- `ErrorCode`: `UNAUTHENTICATED`, `ACCESS_TOKEN_EXPIRED`, `REFRESH_TOKEN_EXPIRED`, `REFRESH_TOKEN_REVOKED`, `AUTH_CONTEXT_CHANGED`, `ACCESS_DENIED`, `INVALID_INPUT`, `NOT_FOUND`, `INQUIRY_VERSION_CONFLICT`, `IDEMPOTENCY_KEY_REUSED`, `RUN_NOT_RETRYABLE`, `RATE_LIMITED`, `SERVICE_UNAVAILABLE`, `DEADLINE_EXCEEDED`, `MODEL_UNAVAILABLE`. 동작은 WIRE_CONTRACT 10절을 따른다. INVALID_RESPONSE/NETWORK_ERROR/CONNECTION_NOT_CONFIGURED/MOCK_AUTH_NOT_CONFIGURED는 프론트·Mock 로컬 오류이며 서버 업무 코드와 구분한다.
- `Effect`: `ANALYSIS_STARTED`, `SUPPLEMENT_SAVED`.

위 값은 생산자의 개발 계약이다. 소비자는 버전 차이에 따른 미등록 문자열을 받아도 성공으로 추정하지 않고 안전한 안내로 표시한다. 현재 프론트의 문자열 타입·Mock 예제·실제 서버가 모든 값을 구현했다는 뜻은 아니다.

- `Ref`: 기존 저장 항목의 UUID 또는 아직 저장되지 않은 항목의 `new:` 접두 임시 참조 문자열. 원본 제약: `^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|new:[a-zA-Z0-9_-]+)$`.

### 상태 전이 기준

- 실행: QUEUED → RUNNING → SUCCEEDED/FAILED/TIMED_OUT. QUEUED/RUNNING에서 입력 버전 또는 활성 실행이 교체되면 SUPERSEDED다. 재시도는 실패 실행의 상태를 되돌리지 않고 새 runId를 만든다. 이미 저장된 성공 업무는 유지한다.
- 문제: ANALYZING → WAITING_USER/TICKET_CREATED/CREATION_FAILED/OUT_OF_SCOPE, 추가 답변은 WAITING_USER → ANALYZING이다. 생성 실패 작업의 기술 복구는 기존 workItemId로 성공 티켓을 연결한다. 이관이 끝난 문제의 정정은 정보 보완이며 자동 재배정하지 않는다. 같은 문제의 부분 성공/질문 진행은 문제 상태 하나로 tickets/creationFailures를 숨기지 않는다.
- 배정: ASSIGNED/CS_REVIEW → REASSIGNING → ASSIGNED/CS_REVIEW/EXCEPTION/RECOVERY_PENDING. EXCEPTION은 업무 관리자 판단, RECOVERY_PENDING은 운영 기술 복구로 구분한다. 실제 재이관 저장 성공 전에는 처리 부서를 바꾸지 않는다.
- 후속 복구: PENDING/FAILED → RETRYING → RECOVERED/FAILED. 재접수와 완료는 별개다. 후속 동의: PENDING → ACCEPTED/DECLINED/EXPIRED/INVALIDATED. 같은 동의 재요청으로 새 문의를 중복 생성하지 않는다.
- 정의되지 않은 전이를 성공으로 추정하지 않는다. 후속 기능의 상세 전이·권한·원자 저장은 해당 기능 계약에서 추가 검증한다.

## 합성 타입

- `SubscriptionTarget`: `InquiryTarget` 또는 `DepartmentTicketsTarget` 또는 `TicketTarget`. 각 형태의 필드를 서로 섞지 않는다.
- `CommandAck`: `StoredAck` 또는 `ErrorAck`. 각 형태의 필드를 서로 섞지 않는다.
- `SubscriptionAck`: `SubscriptionAccepted` 또는 `ErrorAck`. 각 형태의 필드를 서로 섞지 않는다.
- `AiResult`: `AiSuccess` 또는 `AiFailure`. 각 형태의 필드를 서로 섞지 않는다.

## Department

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `code` | `string` | {"minLength": 1} |
| `name` | `string` | {"minLength": 1} |
| `isRoutable` | `boolean` | 필수 |

## CurrentUser

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `employeeNo` | `string` | {"minLength": 1} |
| `name` | `string` | {"minLength": 1} |
| `department` | `Department` | 필수 |
| `roles` | `Array<Role>` | 필수 |

## LoginRequest

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `loginId` | `string` | {"minLength": 1} |
| `password` | `string` | {"minLength": 1} |

## LoginResponse

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `accessToken` | `string` | {"minLength": 1} |
| `expiresAt` | `IsoDateTime` | 필수 |
| `user` | `CurrentUser` | 필수 |

## ApiErrorBody

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `code` | `ErrorCode` | 필수 |
| `message` | `string` | 필수 |
| `requestId` | `Uuid` | 필수 |
| `retryable` | `boolean` | 필수 |

## MessageRevision

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `revisionNo` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `content` | `UserContent` | 필수 |
| `createdAt` | `IsoDateTime` | 필수 |

## ChatMessage

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `revision` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `sequenceNo` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `sender` | `"USER" \| "AI"` | {"enum": ["USER", "AI"]} |
| `content` | `string` | 필수 |
| `createdAt` | `IsoDateTime` | 필수 |
| `revisions` | `Array<MessageRevision>` | 필수 |

## Problem

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `summary` | `string` | 필수 |
| `category` | `"ACCOUNT_AUTH" \| "PERMISSION" \| "NETWORK_ACCESS" \| "SOFTWARE" \| "BUSINESS_SERVICE_ERROR" \| "OTHER" \| null` | {"enum": ["ACCOUNT_AUTH", "PERMISSION", "NETWORK_ACCESS", "SOFTWARE", "BUSINESS_SERVICE_ERROR", "OTHER", null]} |
| `status` | `ProblemStatus` | 필수 |
| `questionCount` | `number` | {"minimum": 0, "maximum": 3} |

## RunSummary

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `status` | `RunStatus` | 필수 |
| `error` | `ApiErrorBody \| null` | {"anyOf": [{"$ref": "#/$defs/ApiErrorBody"}, {"type": "null"}]} |

## TicketCard

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `problemId` | `Uuid` | 필수 |
| `workItemId` | `Uuid` | 필수 |
| `ticketNumber` | `string` | {"minLength": 1} |
| `title` | `string` | 필수 |
| `department` | `Department` | 필수 |
| `processingStatus` | `ProcessingStatus` | 필수 |
| `routingStatus` | `RoutingStatus` | 필수 |
| `createdAt` | `IsoDateTime` | 필수 |

## AssignmentHistory

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `department` | `Department` | 필수 |
| `assignedAt` | `IsoDateTime` | 필수 |
| `endedAt` | `string \| null` | {"format": "date-time", "pattern": "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z$"} |

## TicketDetail

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `problemId` | `Uuid` | 필수 |
| `workItemId` | `Uuid` | 필수 |
| `ticketNumber` | `string` | {"minLength": 1} |
| `title` | `string` | 필수 |
| `department` | `Department` | 필수 |
| `processingStatus` | `ProcessingStatus` | 필수 |
| `routingStatus` | `RoutingStatus` | 필수 |
| `createdAt` | `IsoDateTime` | 필수 |
| `description` | `string` | 필수 |
| `diagnosisSummary` | `string \| null` | 필수 |
| `assignmentHistory` | `Array<AssignmentHistory>` | 필수 |

## CreationFailure

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `workItemId` | `Uuid` | 필수 |
| `problemId` | `Uuid` | 필수 |
| `status` | `string` | {"minLength": 1} |
| `errorCode` | `ErrorCode` | 필수 |

## InquiryResult

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `version` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `status` | `InquiryStatus` | 필수 |
| `problems` | `Array<Problem>` | 필수 |
| `tickets` | `Array<TicketCard>` | 필수 |
| `creationFailures` | `Array<CreationFailure>` | 필수 |
| `currentRun` | `RunSummary \| null` | {"anyOf": [{"$ref": "#/$defs/RunSummary"}, {"type": "null"}]} |

## InquirySummary

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `title` | `string` | 필수 |
| `status` | `InquiryStatus` | 필수 |
| `createdAt` | `IsoDateTime` | 필수 |

## TicketPage

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `items` | `Array<TicketCard>` | 필수 |
| `page` | `number` | {"minimum": 0, "maximum": 2147483647} |
| `size` | `number` | {"minimum": 1, "maximum": 100} |
| `totalElements` | `number` | {"minimum": 0, "maximum": 2147483647} |

## InquiryPage

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `items` | `Array<InquirySummary>` | 필수 |
| `page` | `number` | {"minimum": 0, "maximum": 2147483647} |
| `size` | `number` | {"minimum": 1, "maximum": 100} |
| `totalElements` | `number` | {"minimum": 0, "maximum": 2147483647} |

## MessagePage

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `items` | `Array<ChatMessage>` | 필수 |
| `nextAfterSequenceNo` | `number \| null` | {"minimum": 1, "maximum": 2147483647} |

## MessageHistoryPage · MessageHistoryQuery · RunRetryRequest 추가 프로필

- `MessageHistoryPage`: `items: Array<ChatMessage>`와 `nextBeforeSequenceNo: number | null`이 필수다. items는 오래된 순서이며 다음 커서는 첫 항목의 sequenceNo, 더 이전이 없으면 null이다.
- `MessageHistoryQuery`: `size: number`(기본 30, 1~100), `beforeSequenceNo: number | null`(1~2147483647). null은 URL에서 생략하며 최신 페이지를 뜻한다. 이전 페이지는 해당 순번 미만이다. 기존 afterSequenceNo와 동시에 보내지 않는다.
- `RunRetryRequest`: `requestId: Uuid`, `expectedInquiryVersion: number`(1~2147483647). 경로의 원본 runId와 함께 멱등 범위를 구성한다. 응답은 RunSummary다.
- Refresh 응답은 기존 `LoginResponse`다. Refresh Token 원문은 JSON에 추가하지 않으며 실제 서버의 HttpOnly 쿠키로 전달한다. Mock 메모리 세션은 실제 쿠키 검증을 대신하지 않는다.
- 위 추가 타입의 처리 규칙·상태·오류는 WIRE_CONTRACT 10절을 따른다. 기존 MessagePage/MessageListQuery는 전방 조회 호환 예제이며 새 프론트 채팅은 MessageHistoryPage/MessageHistoryQuery를 사용한다.

## TicketListQuery

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `page` | `number` | {"minimum": 0, "maximum": 2147483647} |
| `size` | `number` | {"minimum": 1, "maximum": 100} |
| `processingStatus` | `"WAITING" \| "IN_PROGRESS" \| "WAITING_CONFIRM" \| "RESOLVED" \| "CLOSED_UNCONFIRMED" \| null` | {"enum": ["WAITING", "IN_PROGRESS", "WAITING_CONFIRM", "RESOLVED", "CLOSED_UNCONFIRMED", null]} |

## InquiryListQuery

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `page` | `number` | {"minimum": 0, "maximum": 2147483647} |
| `size` | `number` | {"minimum": 1, "maximum": 100} |

## MessageListQuery

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `afterSequenceNo` | `number` | {"minimum": 0, "maximum": 2147483647} |
| `size` | `number` | {"minimum": 1, "maximum": 100} |

## InquiryCreateCommand

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `requestId` | `Uuid` | 필수 |
| `content` | `UserContent` | 필수 |

## MessageSendCommand

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `requestId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `expectedInquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `content` | `UserContent` | 필수 |

## MessageReviseCommand

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `requestId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `expectedInquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `content` | `UserContent` | 필수 |
| `messageId` | `Uuid` | 필수 |

## InquiryTarget

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `type` | `"INQUIRY"` | {"const": "INQUIRY"} |
| `id` | `Uuid` | 필수 |

## DepartmentTicketsTarget

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `type` | `"DEPARTMENT_TICKETS"` | {"const": "DEPARTMENT_TICKETS"} |
| `id` | `Uuid` | 필수 |

## TicketTarget

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `type` | `"TICKET"` | {"const": "TICKET"} |
| `id` | `Uuid` | 필수 |

## SubscriptionCommand

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `requestId` | `Uuid` | 필수 |
| `target` | `SubscriptionTarget` | 필수 |

## StoredAck

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `ok` | `true` | {"const": true} |
| `requestId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `messageId` | `Uuid` | 필수 |
| `revision` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `runId` | `string \| null` | {"format": "uuid", "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"} |
| `effect` | `Effect` | 필수 |

## ErrorAck

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `ok` | `false` | {"const": false} |
| `requestId` | `Uuid` | 필수 |
| `error` | `ApiErrorBody` | 필수 |

## SubscriptionAccepted

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `ok` | `true` | {"const": true} |
| `requestId` | `Uuid` | 필수 |
| `target` | `SubscriptionTarget` | 필수 |

## AuthInvalidatedEvent

`auth.invalidated`는 인증 대상 사용자에게만 보내는 제어 이벤트다. 문의 이벤트 외피를 사용하지 않는다. 서버는 통지 전에 해당 인증·구독 권한을 회수하며 통지 유실이 접근 허용으로 이어지지 않게 한다.

| 필드 | 타입 | 필수 | 의미 |
|---|---|---|---|
| eventId | Uuid | 예 | 중복 통지 제거 |
| code | string | 예 | ACCESS_TOKEN_EXPIRED / AUTH_CONTEXT_CHANGED / REFRESH_TOKEN_REVOKED 중 하나 |
| message | string | 예 | 공개 가능한 안내. 토큰·비밀값 제외 |

## ResyncRequiredEvent

`resync.required`는 해당 SSE 연결의 이벤트를 재생할 수 없을 때 보내는 제어 이벤트다.
SSE `id`와 JSON `eventId`는 같은 UUID다. 문의 이벤트 외피를 사용하지 않으며 대상은 현재 연결의 target이다.
서버는 이 이벤트를 보낸 뒤 스트림을 종료한다. 클라이언트는 해당 대상의 영속 상태를 REST로 다시 조회하고,
기존 커서와 이 제어 이벤트의 ID를 비운 뒤 `Last-Event-ID` 없이 새 연결을 연다.
이 알림은 저장 실패나 인증 실패를 뜻하지 않는다. 알림 유실에 대비해 일반 재접속 시 REST 복구도 유지한다.

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `eventId` | `Uuid` | 필수. 제어 이벤트 중복 구분용이며 복구 후 재연결 커서로 재사용하지 않음 |

## RelayStatusEvent

`relay.status`는 Node↔Spring 중계 상태가 바뀔 때 연결된 클라이언트에 보내는 제어 이벤트다. 업무 저장 성공·인증 만료를 뜻하지 않는다.

| 필드 | 타입 | 필수 | 의미 |
|---|---|---|---|
| eventId | Uuid | 예 | 중복 통지 제거 |
| status | string | 예 | AVAILABLE / UNAVAILABLE |
| occurredAt | IsoDateTime | 예 | 상태 확인 시각 |

## AgentProgressPayload

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `stepNo` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `stepType` | `string` | 필수 |
| `displaySummary` | `string` | 필수 |
| `status` | `string` | 필수 |

## AnswerDeltaPayload

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `messageId` | `Uuid` | 필수 |
| `seq` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `text` | `string` | 필수 |

## AgentProgressEvent

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `eventId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `runId` | `Uuid` | 필수 |
| `payload` | `AgentProgressPayload` | 필수 |

## AnswerDeltaEvent

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `eventId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `runId` | `Uuid` | 필수 |
| `payload` | `AnswerDeltaPayload` | 필수 |

## MessageStoredEvent

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `eventId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `messageId` | `Uuid` | 필수 |
| `requestId` | `string \| null` | {"format": "uuid", "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"} |

## StateChangedEvent

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `eventId` | `Uuid` | 필수 |
| `target` | `SubscriptionTarget` | 필수 |
| `inquiryVersion` | `number \| null` | {"minimum": 1, "maximum": 2147483647} |

## ActorContext

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `userId` | `Uuid` | 필수 |
| `departmentId` | `Uuid` | 필수 |
| `roles` | `Array<Role>` | 필수 |
| `accessScopeRef` | `string` | {"minLength": 1} |

## AiDepartment

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `code` | `string` | {"minLength": 1} |
| `name` | `string` | {"minLength": 1} |
| `isRoutable` | `boolean` | 필수 |
| `responsibilities` | `Array<string>` | 필수 |

## Evidence

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `id` | `string` | {"minLength": 1} |
| `kind` | `"MESSAGE" \| "DOCUMENT" \| "TOOL"` | {"enum": ["MESSAGE", "DOCUMENT", "TOOL"]} |
| `sourceId` | `string` | {"minLength": 1} |
| `sourceVersion` | `string` | {"minLength": 1} |
| `summary` | `string` | 필수 |
| `observedAt` | `string \| null` | {"format": "date-time", "pattern": "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z$"} |

## WorkItem

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `ref` | `Ref` | 필수 |
| `problemRef` | `Ref` | 필수 |
| `workScope` | `string` | 필수 |
| `status` | `string` | 필수 |
| `ticketId` | `string \| null` | {"format": "uuid", "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"} |

## AiRequest

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `schemaVersion` | `"hops-mock-1"` | {"const": "hops-mock-1"} |
| `runId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `actor` | `ActorContext` | 필수 |
| `deadlineAt` | `IsoDateTime` | 필수 |
| `messages` | `Array<ChatMessage>` | 필수 |
| `existingProblems` | `Array<Problem>` | 필수 |
| `existingWorkItems` | `Array<WorkItem>` | 필수 |
| `departments` | `Array<AiDepartment>` | 필수 |
| `evidence` | `Array<Evidence>` | 필수 |

## AiProblem

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `ref` | `Ref` | 필수 |
| `summary` | `string` | 필수 |
| `category` | `"ACCOUNT_AUTH" \| "PERMISSION" \| "NETWORK_ACCESS" \| "SOFTWARE" \| "BUSINESS_SERVICE_ERROR" \| "OTHER" \| null` | {"enum": ["ACCOUNT_AUTH", "PERMISSION", "NETWORK_ACCESS", "SOFTWARE", "BUSINESS_SERVICE_ERROR", "OTHER", null]} |
| `questionCount` | `number` | {"minimum": 0, "maximum": 3} |
| `action` | `"ASK_USER" \| "PROPOSE_TICKETS" \| "OUT_OF_SCOPE"` | {"enum": ["ASK_USER", "PROPOSE_TICKETS", "OUT_OF_SCOPE"]} |
| `question` | `string \| null` | 필수 |
| `evidenceIds` | `Array<string>` | 필수 |

## TicketProposal

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `ref` | `Ref` | 필수 |
| `problemRef` | `Ref` | 필수 |
| `workScope` | `string` | 필수 |
| `title` | `string` | 필수 |
| `description` | `string` | 필수 |
| `category` | `Category` | 필수 |
| `departmentCode` | `string` | {"minLength": 1} |
| `purpose` | `"INCIDENT" \| "CS_REVIEW"` | {"enum": ["INCIDENT", "CS_REVIEW"]} |
| `diagnosisSummary` | `string` | 필수 |
| `handoffReason` | `string` | 필수 |
| `uncertainty` | `string \| null` | 필수 |
| `evidenceIds` | `Array<string>` | 필수 |

## AiSuccess

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `schemaVersion` | `"hops-mock-1"` | {"const": "hops-mock-1"} |
| `runId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `status` | `"OK"` | {"const": "OK"} |
| `answer` | `string` | 필수 |
| `problems` | `Array<AiProblem>` | 필수 |
| `ticketProposals` | `Array<TicketProposal>` | 필수 |
| `error` | `null` | 필수 |

## AiFailure

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `schemaVersion` | `"hops-mock-1"` | {"const": "hops-mock-1"} |
| `runId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `status` | `"ERROR" \| "TIMED_OUT" \| "CANCELLED"` | {"enum": ["ERROR", "TIMED_OUT", "CANCELLED"]} |
| `error` | `ApiErrorBody` | 필수 |

## AiAccepted

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `schemaVersion` | `"hops-mock-1"` | {"const": "hops-mock-1"} |
| `runId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `status` | `"ACCEPTED"` | {"const": "ACCEPTED"} |

## AiProgress

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `schemaVersion` | `"hops-mock-1"` | {"const": "hops-mock-1"} |
| `runId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `payload` | `AgentProgressPayload` | 필수 |

## AiDelta

| 필드 | JSON 표현 | 조건 |
|---|---|---|
| `schemaVersion` | `"hops-mock-1"` | {"const": "hops-mock-1"} |
| `runId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `payload` | `AnswerDeltaPayload` | 필수 |


## 서비스 내부 표현

JSON 필드명과 의미는 이 문서를 따르되 DB 컬럼·내부 클래스·언어 자료형은 각 담당자가 선택한다. 메시지 revision과 문의 version은 서로 다른 값이다. 서비스 간 `AiRequest/AiResult`와 기존 내부 agent-1의 `AgentRequest/AgentResult`는 별도 계약이다. 내부 어댑터에서는 저장 메시지의 id를 messageId로, AI 발신자를 ASSISTANT로 대응하며 현재 revision/content만 전달한다. AI의 임시 ref·problemRef는 서버가 저장 UUID로 연결하고, AI 제안만으로 저장·배정·성공을 확정하지 않는다.
