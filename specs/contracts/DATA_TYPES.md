# 공통 데이터 필드

상태: Mock 계약 초안 / 팀 리뷰 전. 언어에 독립적인 데이터 계약 문서이며 각 서비스는 필요한 타입을 자기 코드에서 구현한다. 자동 생성 코드나 언어별 DTO를 공통 구현으로 제공하지 않는다.

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
- `ProblemStatus`: 문자열. 운영 코드 미정 항목은 미등록 값도 받아 안전한 안내로 표시한다.
- `RoutingStatus`: 문자열. 운영 코드 미정 항목은 미등록 값도 받아 안전한 안내로 표시한다.
- `RunStatus`: 문자열. 운영 코드 미정 항목은 미등록 값도 받아 안전한 안내로 표시한다.
- `ErrorCode`: 문자열. 운영 코드 미정 항목은 미등록 값도 받아 안전한 안내로 표시한다.
- `Effect`: 문자열. 운영 코드 미정 항목은 미등록 값도 받아 안전한 안내로 표시한다.
- `Ref`: 기존 저장 항목의 UUID 또는 아직 저장되지 않은 항목의 `new:` 접두 임시 참조 문자열. 원본 제약: `^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|new:[a-zA-Z0-9_-]+)$`.

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
