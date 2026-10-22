# 공통 데이터 필드

생성 원본: `contract.schema.json` · 상태: Mock 계약 초안.

모든 객체 필드는 필수다. nullable은 키를 생략하지 않고 null을 보낸다. 배열은 []를 보낸다.

## Department

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `code` | `string` | {"minLength": 1} |
| `name` | `string` | {"minLength": 1} |
| `isRoutable` | `boolean` | 필수 |

## CurrentUser

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `employeeNo` | `string` | {"minLength": 1} |
| `name` | `string` | {"minLength": 1} |
| `department` | `Department` | 필수 |
| `roles` | `Array<Role>` | 필수 |

## LoginRequest

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `loginId` | `string` | {"minLength": 1} |
| `password` | `string` | {"minLength": 1} |

## LoginResponse

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `accessToken` | `string` | {"minLength": 1} |
| `expiresAt` | `IsoDateTime` | 필수 |
| `user` | `CurrentUser` | 필수 |

## ApiErrorBody

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `code` | `ErrorCode` | 필수 |
| `message` | `string` | 필수 |
| `requestId` | `Uuid` | 필수 |
| `retryable` | `boolean` | 필수 |

## MessageRevision

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `revisionNo` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `content` | `UserContent` | 필수 |
| `createdAt` | `IsoDateTime` | 필수 |

## ChatMessage

| 필드 | JSON / TypeScript | 조건 |
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

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `summary` | `string` | 필수 |
| `category` | `"ACCOUNT_AUTH" \| "PERMISSION" \| "NETWORK_ACCESS" \| "SOFTWARE" \| "BUSINESS_SERVICE_ERROR" \| "OTHER" \| null` | {"enum": ["ACCOUNT_AUTH", "PERMISSION", "NETWORK_ACCESS", "SOFTWARE", "BUSINESS_SERVICE_ERROR", "OTHER", null]} |
| `status` | `ProblemStatus` | 필수 |
| `questionCount` | `number` | {"minimum": 0, "maximum": 3} |

## RunSummary

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `status` | `RunStatus` | 필수 |
| `error` | `ApiErrorBody \| null` | {"anyOf": [{"$ref": "#/$defs/ApiErrorBody"}, {"type": "null"}]} |

## TicketCard

| 필드 | JSON / TypeScript | 조건 |
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

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `department` | `Department` | 필수 |
| `assignedAt` | `IsoDateTime` | 필수 |
| `endedAt` | `string \| null` | {"format": "date-time", "pattern": "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z$"} |

## TicketDetail

| 필드 | JSON / TypeScript | 조건 |
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

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `workItemId` | `Uuid` | 필수 |
| `problemId` | `Uuid` | 필수 |
| `status` | `string` | {"minLength": 1} |
| `errorCode` | `ErrorCode` | 필수 |

## InquiryResult

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `version` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `status` | `InquiryStatus` | 필수 |
| `problems` | `Array<Problem>` | 필수 |
| `tickets` | `Array<TicketCard>` | 필수 |
| `creationFailures` | `Array<CreationFailure>` | 필수 |
| `currentRun` | `RunSummary \| null` | {"anyOf": [{"$ref": "#/$defs/RunSummary"}, {"type": "null"}]} |

## InquirySummary

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `title` | `string` | 필수 |
| `status` | `InquiryStatus` | 필수 |
| `createdAt` | `IsoDateTime` | 필수 |

## TicketPage

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `items` | `Array<TicketCard>` | 필수 |
| `page` | `number` | {"minimum": 0, "maximum": 2147483647} |
| `size` | `number` | {"minimum": 1, "maximum": 100} |
| `totalElements` | `number` | {"minimum": 0, "maximum": 2147483647} |

## InquiryPage

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `items` | `Array<InquirySummary>` | 필수 |
| `page` | `number` | {"minimum": 0, "maximum": 2147483647} |
| `size` | `number` | {"minimum": 1, "maximum": 100} |
| `totalElements` | `number` | {"minimum": 0, "maximum": 2147483647} |

## MessagePage

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `items` | `Array<ChatMessage>` | 필수 |
| `nextAfterSequenceNo` | `number \| null` | {"minimum": 1, "maximum": 2147483647} |

## TicketListQuery

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `page` | `number` | {"minimum": 0, "maximum": 2147483647} |
| `size` | `number` | {"minimum": 1, "maximum": 100} |
| `processingStatus` | `"WAITING" \| "IN_PROGRESS" \| "WAITING_CONFIRM" \| "RESOLVED" \| "CLOSED_UNCONFIRMED" \| null` | {"enum": ["WAITING", "IN_PROGRESS", "WAITING_CONFIRM", "RESOLVED", "CLOSED_UNCONFIRMED", null]} |

## InquiryListQuery

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `page` | `number` | {"minimum": 0, "maximum": 2147483647} |
| `size` | `number` | {"minimum": 1, "maximum": 100} |

## MessageListQuery

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `afterSequenceNo` | `number` | {"minimum": 0, "maximum": 2147483647} |
| `size` | `number` | {"minimum": 1, "maximum": 100} |

## InquiryCreateCommand

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `requestId` | `Uuid` | 필수 |
| `content` | `UserContent` | 필수 |

## MessageSendCommand

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `requestId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `expectedInquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `content` | `UserContent` | 필수 |

## MessageReviseCommand

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `requestId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `expectedInquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `content` | `UserContent` | 필수 |
| `messageId` | `Uuid` | 필수 |

## InquiryTarget

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `type` | `"INQUIRY"` | {"const": "INQUIRY"} |
| `id` | `Uuid` | 필수 |

## DepartmentTicketsTarget

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `type` | `"DEPARTMENT_TICKETS"` | {"const": "DEPARTMENT_TICKETS"} |
| `id` | `Uuid` | 필수 |

## TicketTarget

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `type` | `"TICKET"` | {"const": "TICKET"} |
| `id` | `Uuid` | 필수 |

## SubscriptionCommand

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `requestId` | `Uuid` | 필수 |
| `target` | `SubscriptionTarget` | 필수 |

## StoredAck

| 필드 | JSON / TypeScript | 조건 |
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

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `ok` | `false` | {"const": false} |
| `requestId` | `Uuid` | 필수 |
| `error` | `ApiErrorBody` | 필수 |

## SubscriptionAccepted

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `ok` | `true` | {"const": true} |
| `requestId` | `Uuid` | 필수 |
| `target` | `SubscriptionTarget` | 필수 |

## AgentProgressPayload

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `stepNo` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `stepType` | `string` | 필수 |
| `displaySummary` | `string` | 필수 |
| `status` | `string` | 필수 |

## AnswerDeltaPayload

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `messageId` | `Uuid` | 필수 |
| `seq` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `text` | `string` | 필수 |

## AgentProgressEvent

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `eventId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `runId` | `Uuid` | 필수 |
| `payload` | `AgentProgressPayload` | 필수 |

## AnswerDeltaEvent

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `eventId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `runId` | `Uuid` | 필수 |
| `payload` | `AnswerDeltaPayload` | 필수 |

## MessageStoredEvent

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `eventId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `messageId` | `Uuid` | 필수 |
| `requestId` | `string \| null` | {"format": "uuid", "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"} |

## StateChangedEvent

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `eventId` | `Uuid` | 필수 |
| `target` | `SubscriptionTarget` | 필수 |
| `inquiryVersion` | `number \| null` | {"minimum": 1, "maximum": 2147483647} |

## ActorContext

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `userId` | `Uuid` | 필수 |
| `departmentId` | `Uuid` | 필수 |
| `roles` | `Array<Role>` | 필수 |
| `accessScopeRef` | `string` | {"minLength": 1} |

## AiDepartment

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `id` | `Uuid` | 필수 |
| `code` | `string` | {"minLength": 1} |
| `name` | `string` | {"minLength": 1} |
| `isRoutable` | `boolean` | 필수 |
| `responsibilities` | `Array<string>` | 필수 |

## Evidence

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `id` | `string` | {"minLength": 1} |
| `kind` | `"MESSAGE" \| "DOCUMENT" \| "TOOL"` | {"enum": ["MESSAGE", "DOCUMENT", "TOOL"]} |
| `sourceId` | `string` | {"minLength": 1} |
| `sourceVersion` | `string` | {"minLength": 1} |
| `summary` | `string` | 필수 |
| `observedAt` | `string \| null` | {"format": "date-time", "pattern": "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z$"} |

## WorkItem

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `ref` | `Ref` | 필수 |
| `problemRef` | `Ref` | 필수 |
| `workScope` | `string` | 필수 |
| `status` | `string` | 필수 |
| `ticketId` | `string \| null` | {"format": "uuid", "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"} |

## AiRequest

| 필드 | JSON / TypeScript | 조건 |
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

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `ref` | `Ref` | 필수 |
| `summary` | `string` | 필수 |
| `category` | `"ACCOUNT_AUTH" \| "PERMISSION" \| "NETWORK_ACCESS" \| "SOFTWARE" \| "BUSINESS_SERVICE_ERROR" \| "OTHER" \| null` | {"enum": ["ACCOUNT_AUTH", "PERMISSION", "NETWORK_ACCESS", "SOFTWARE", "BUSINESS_SERVICE_ERROR", "OTHER", null]} |
| `questionCount` | `number` | {"minimum": 0, "maximum": 3} |
| `action` | `"ASK_USER" \| "PROPOSE_TICKETS" \| "OUT_OF_SCOPE"` | {"enum": ["ASK_USER", "PROPOSE_TICKETS", "OUT_OF_SCOPE"]} |
| `question` | `string \| null` | 필수 |
| `evidenceIds` | `Array<string>` | 필수 |

## TicketProposal

| 필드 | JSON / TypeScript | 조건 |
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

| 필드 | JSON / TypeScript | 조건 |
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

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `schemaVersion` | `"hops-mock-1"` | {"const": "hops-mock-1"} |
| `runId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `status` | `"ERROR" \| "TIMED_OUT" \| "CANCELLED"` | {"enum": ["ERROR", "TIMED_OUT", "CANCELLED"]} |
| `error` | `ApiErrorBody` | 필수 |

## AiAccepted

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `schemaVersion` | `"hops-mock-1"` | {"const": "hops-mock-1"} |
| `runId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `status` | `"ACCEPTED"` | {"const": "ACCEPTED"} |

## AiProgress

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `schemaVersion` | `"hops-mock-1"` | {"const": "hops-mock-1"} |
| `runId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `payload` | `AgentProgressPayload` | 필수 |

## AiDelta

| 필드 | JSON / TypeScript | 조건 |
|---|---|---|
| `schemaVersion` | `"hops-mock-1"` | {"const": "hops-mock-1"} |
| `runId` | `Uuid` | 필수 |
| `inquiryId` | `Uuid` | 필수 |
| `inquiryVersion` | `number` | {"minimum": 1, "maximum": 2147483647} |
| `payload` | `AnswerDeltaPayload` | 필수 |

