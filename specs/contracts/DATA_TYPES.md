# 공통 데이터 필드

> **PRD 1.44 전환 안내:** 이 문서는 신규 hops-system-1 리뷰 계약안과 기존 부서 기반 전환 전 타입을 구분한다. 신규 계약안의 팀 승인·코드 적용은 별도다. 제품 정책은 [PRD](../../docs/PRD.md), 전송은 [WIRE_CONTRACT](WIRE_CONTRACT.md)를 따른다. 이전 부서 배정·3회 질문·CS 검토·무응답 종료는 신규 목표가 아니다.

상태: 2026-10-28 REST·SSE 타입 정리 / 기존 개발 기준 유지, ResyncRequiredEvent는 develop 확정 계약, 저장 결과는 프론트 연동 프로필 / 팀 리뷰·서비스별 구현 검증 별도. 언어에 독립적인 데이터 계약 문서이며 각 서비스는 필요한 타입을 자기 코드에서 구현한다. 자동 생성 코드나 언어별 DTO를 공통 구현으로 제공하지 않는다. 정책과 구현/검증 구분은 [WIRE_CONTRACT 11절](WIRE_CONTRACT.md#11-담당별-구현과-검증-상태)을 따른다.

프로필별 표현 규칙을 구분한다. 아래 기존 타입의 필수/null 규칙은 전환 전 프로필에 적용한다.


## 신규 계약 hops-system-1

상태: PRD 1.44 팀 리뷰용 구체 계약안. 이 절은 `/api/v2`용이며 아래의 전환 전 타입과 이름이 같아도 상호 교환하지 않는다. 의미·전이는 [TARGET_CONTRACT](TARGET_CONTRACT.md), 전송은 [WIRE_CONTRACT 12절](WIRE_CONTRACT.md#12-신규-계약-hops-system-1)을 따른다. 현재 런타임 타입·Mock은 변경하지 않았다.

### 공통 표현과 버전

- `Uuid`는 소문자 하이픈 UUID, `time`은 UTC RFC 3339 `Z` 시각, `int`는 JSON 정수다. JSON 필드는 camelCase다. 양의 정수 버전·순번은 1~2,147,483,647이며 page·질문 수는 0부터다.
- 아래에 열거한 응답 키는 모두 존재한다. `?`는 키 생략이 아니라 `null` 허용을 뜻한다. 배열의 빈 값은 `[]`다. 권한 거부·조회 오류를 `null`/빈 배열 성공으로 바꾸지 않는다. 비공개 필드가 필요한 객체는 해당 API 자체를 거부한다.
- 표의 `string`은 공백만인 값을 허용하지 않는 비어 있지 않은 문자열이다. 명시한 nullable 문자열의 미확인은 null이다. 사용자 입력은 1~4,000 Unicode 코드 포인트, 단독 surrogate·공백만인 입력은 거부하고 원문을 trim/절단하지 않는다. 공백 집합은 아래 기존 UserContent와 동일하게 유지하는 제안이다.
- 추가 응답 필드는 무시할 수 있지만 필수 키·형식 오류는 INVALID_RESPONSE다. 미등록 상태는 안전한 미확인으로 표시하고 해당 변경 버튼을 비활성화한다. 없는 enum을 기존 성공 값으로 바꾸지 않는다.
- 분석 입력 버전은 inquiryVersion, 메시지 정정은 revision, 업무 변경은 ticketRevision, 해결 응답은 requestRevision, 사례 문서는 caseVersion이다. 적용 시스템 버전은 별도 문자열 appliesToSystemVersion이다.

### enum

| 타입 | 값 |
|---|---|
| SystemCode | ELECTRONIC_APPROVAL, COMMON_LOGIN, FILE_SHARING |
| Role | BIZ_ADMIN. 일반 임직원·담당자는 roles=[]도 가능하며 담당 능력은 primarySystemIds에서 확인 |
| InquiryIntent | HOW_TO, ACCESS_REQUEST, OBSERVED_SYMPTOM, FEATURE_REQUEST, IMPROVEMENT_REQUEST, UNKNOWN, OUT_OF_SCOPE |
| ProcessingType | KNOWLEDGE_QA, SERVICE_REQUEST, INVESTIGATION, INCIDENT, CHANGE_REQUEST, REVIEW_REQUIRED, OUT_OF_SCOPE |
| InquiryStatus | IN_PROGRESS, WAITING_USER, RESOLVED, CLOSED |
| ProblemState | ANALYZING, WAITING_USER, WAITING_SYSTEM, TICKET_PENDING, IN_SERVICE, WAITING_CONFIRMATION, RESOLVED, OUT_OF_SCOPE |
| TicketState | WAITING, IN_PROGRESS, WAITING_CONFIRMATION, RESOLVED |
| CreationOutcome | PENDING, STORED, NOT_CREATED, UNKNOWN |
| OperationState | PENDING, SUCCEEDED, REJECTED, UNKNOWN |
| RunStatus | QUEUED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, SUPERSEDED |
| WorkRecordKind | PROGRESS, VERIFIED_RESOLUTION, IMPLEMENTED_VERIFIED |
| ResolutionSource | GUIDE, WORK_RECORD |
| ResolutionState | PENDING, RESOLVED, UNRESOLVED |
| GenerationState | PENDING, RUNNING, SUCCEEDED, FAILED, NO_REUSABLE_CASE |
| CaseVersionState | DRAFT, APPROVED |
| IndexStore | VECTOR, NEO4J |
| IndexState | PENDING, RUNNING, SUCCEEDED, FAILED, UNKNOWN |
| AccessScope | EMPLOYEE, SYSTEM_STAFF |
| KnowledgeOrigin | SYNTHETIC, DEMO_VERIFIED, OPERATIONAL |
| ConfirmationOrigin | SYNTHETIC_PRESET, HOPS_USER |
| SearchState | FOUND, EMPTY, ERROR |
| ResourceType | INQUIRY, MESSAGE, RUN, TICKET, WORK_ITEM, WORK_RECORD, RESOLUTION_REQUEST, CASE, INDEX_JOB, CASE_GENERATION |

### 인증·시스템

| 타입 | 필드·자료형 | 조건 |
|---|---|---|
| Department | id:Uuid, code:string, name:string | 소속 정보. isRoutable은 신규 응답에서 제외 |
| UserSummary | id:Uuid, name:string, department:Department | 허용 대상에 대한 최소 표시 정보 |
| CurrentUser | id:Uuid, employeeNo:string, name:string, department:Department, roles:Role[], primarySystemIds:Uuid[] | 메뉴용 스냅샷. 서버는 요청마다 현재 관계 재검사 |
| SupportedSystem | id:Uuid, code:SystemCode, name:string, features:SystemFeature[] | 이름은 전자결재·공통 로그인·파일 공유. 직원 목록에 내부 담당 정보 없음 |
| SystemFeature | id:Uuid, name:string | 부모 시스템 안의 기능 |
| PrimaryAssignment | assignmentId:Uuid, systemId:Uuid, primaryUser:UserSummary, validFrom:time, validTo:time? | 담당자/관리자가 정정 대상 검토 시에만 조회 |
| LoginRequest | loginId:string, password:string | 본문·비밀값을 로그에 남기지 않음 |
| LoginResponse | accessToken:string, expiresAt:time, user:CurrentUser | Refresh는 HttpOnly cookie |

### 명령·오류·조회

| 타입 | 필드·자료형 | 조건 |
|---|---|---|
| ResourceRef | type:ResourceType, id:Uuid | 현재 권한에서 GET 가능한 집계 참조. 메시지 저장은 INQUIRY, 처리 기록은 TICKET, 반영 조작은 CASE를 반환 |
| CommandReceipt | requestId:Uuid, operationId:Uuid, state:OperationState, resource:ResourceRef?, error:ApiError? | SUCCEEDED면 resource 필수·error=null. PENDING/UNKNOWN은 resource가 없을 수 있음. REJECTED면 error 필수 |
| ApiError | code:string, message:string, requestId:Uuid, retryable:boolean, fieldErrors:FieldError[] | 코드·HTTP·조작은 wire 정본. 원시 예외 없음 |
| FieldError | field:string, code:string | 사용자 입력 필드만 표시 |
| Page<T> | items:T[], page:int, size:int, totalElements:int | page≥0, size=20 기본·1~100, 정렬별 안정적인 ID 보조 정렬 |
| MessagePage | items:ChatMessage[], nextBeforeSequenceNo:int? | 기본 30·1~100; 최신 페이지를 오름차순 반환 |
| RelatedMessagePage | items:RelatedMessage[], nextCursor:string? | 공개 범위 안의 불투명 커서. 원 문의의 전체 sequenceNo를 노출하지 않음 |

### 문의·메시지·표준 요청

| 타입 | 필드·자료형 | 조건 |
|---|---|---|
| MessageRevision | revisionNo:int, content:string, createdAt:time | 정정 전후 영구 보존 |
| ChatMessage | id:Uuid, inquiryId:Uuid, revision:int, sequenceNo:int, sender:USER 또는 AI, content:string, problemIds:Uuid[], revisions:MessageRevision[], createdAt:time | related problemIds는 서버가 검증한 연결 |
| RelatedMessage | id:Uuid, revision:int, sender:USER 또는 AI, content:string, revisions:MessageRevision[], createdAt:time | 담당자에게 해당 문제의 허용된 원문 구간만 투영. 복합 메시지의 무관한 구간은 제외하고 정정 이력에도 같은 필터 적용. 안전한 분리가 불가능하면 원문 대신 해당 문제의 표준 요청을 제공 |
| Problem | id:Uuid, inquiryId:Uuid, summary:string, intent:InquiryIntent, processingType:ProcessingType, reportedSystemId:Uuid?, reportedFeatureId:Uuid?, state:ProblemState, questionCount:int, guide:GuideAnswer?, creationResult:TicketCreationResult?, ticket:TicketSummary?, activeResolutionRequest:ResolutionRequest? | 문의자 조회용 투영. questionCount=0~5. WAITING_SYSTEM이면 reportedSystemId=null. 담당자 내부 StandardRequest는 반환하지 않음 |
| GuideAnswer | messageId:Uuid, evidence:KnowledgeEvidence[] | 저장된 직원용 안내와 현재 공개 가능한 EMPLOYEE 근거만 반환 |
| StandardRequest | id:Uuid, problemId:Uuid, inquiryVersion:int, summary:string, confirmedFacts:EvidenceStatement[], assumptions:EvidenceStatement[], unknowns:string[], responsibleSystemCandidateId:Uuid?, responsibilityEvidenceIds:Uuid[], details:RequestDetails, knowledge:KnowledgeEvidence[] | 확정 사실·추정·모델 제안은 별도 |
| EvidenceStatement | text:string, sourceMessageIds:Uuid[], knowledgeEvidenceIds:Uuid[] | 확인 사실에는 실제 출처가 하나 이상 필요. 추정은 출처 없음을 허용 |
| RequestDetails | taskOrScreen:string?, requestedAccess:string?, businessPurpose:string?, expectedBehavior:string?, actualBehavior:string?, occurredAt:string?, currentWorkflow:string?, desiredChange:string?, acceptanceCriteria:AcceptanceCriterion[] | 유형별 미확인도 null로 보존. 모르는 값을 접수 차단에 사용하지 않음 |
| AcceptanceCriterion | text:string, confirmation:PROPOSED 또는 CONFIRMED, confirmedBy:Uuid? | PROPOSED이면 confirmedBy=null. 명시적 사람 확인 없이 CONFIRMED로 승격하지 않음 |
| InquirySummary | id:Uuid, title:string, status:InquiryStatus, problemCount:int, ticketCount:int, pendingConfirmationCount:int, createdAt:time, updatedAt:time | 목록에서 담당자 내부 근거 제외 |
| InquiryResult | id:Uuid, inquiryVersion:int, status:InquiryStatus, problems:Problem[], currentRun:RunSummary?, resultRevision:int, createdAt:time, updatedAt:time | resultRevision은 문제별 생성 결과 변경 시 증가. 모달 확인용이며 inquiryVersion과 다름 |
| RunSummary | id:Uuid, inquiryId:Uuid, inquiryVersion:int, status:RunStatus, error:ApiError? | 새 재분석은 새 runId; 기존 성공 업무 유지 |

### 티켓·처리·확인

| 타입 | 필드·자료형 | 조건 |
|---|---|---|
| TicketCreationResult | workItemId:Uuid, problemId:Uuid, inquiryVersion:int, outcome:CreationOutcome, ticketId:Uuid?, error:ApiError?, updatedAt:time | STORED에만 ticketId 필수. UNKNOWN과 NOT_CREATED 구별 |
| TicketSummary | id:Uuid, inquiryId:Uuid, problemId:Uuid, workItemId:Uuid, ticketNumber:string, title:string, processingType:ProcessingType, reportedSystem:SupportedSystem, responsibleSystem:SupportedSystem, assignee:UserSummary, state:TicketState, ticketRevision:int, createdAt:time, updatedAt:time | 저장된 시스템·담당자만 반환 |
| TicketDetail | TicketSummary의 필드 + currentPrimary:UserSummary, standardRequest:StandardRequest, assignmentHistory:AssignmentHistory[], workRecords:WorkRecord[], activeResolutionRequest:ResolutionRequest?, caseGeneration:CaseGeneration? | 담당자 전용. assignee는 저장 배정, currentPrimary는 현재 권한 관계. 관리자 투영은 TicketManagementDetail |
| TicketManagementDetail | TicketSummary의 필드 + currentPrimary:UserSummary, assignmentHistory:AssignmentHistory[] | 원문·표준 요청·내부 지식·처리 내용은 관리 역할만으로 반환하지 않음 |
| AssignmentHistory | id:Uuid, previousSystemId:Uuid?, previousAssigneeId:Uuid?, systemId:Uuid, assignee:UserSummary, reason:string, evidenceMessageIds:Uuid[], evidenceNote:string?, actorId:Uuid?, createdAt:time | 최초 자동 배정 actorId=null, 정정은 실제 행위자 |
| WorkRecord | id:Uuid, ticketId:Uuid, kind:WorkRecordKind, action:string, verification:string?, author:UserSummary, createdAt:time | 완료 종류는 verification 필수. 원본 불변 |
| ResolutionRequest | id:Uuid, inquiryId:Uuid, problemId:Uuid, ticketId:Uuid?, source:ResolutionSource, sourceId:Uuid, sourceVersion:int, requestRevision:int, state:ResolutionState, resultSummary:string, confirmationOrigin:ConfirmationOrigin, createdAt:time, respondedAt:time? | GUIDE는 저장된 안내 메시지·revision, WORK_RECORD는 불변 처리 기록·1. 새 요청 revision=1, 응답 저장 시 2 |
| ResolutionResponse | requestId:Uuid, expectedRequestRevision:int, decision:RESOLVED 또는 UNRESOLVED | 문의 소유자만 응답 |

### 지식·사례·반영

| 타입 | 필드·자료형 | 조건 |
|---|---|---|
| KnowledgeEvidence | id:Uuid, documentId:Uuid, documentVersion:int, kind:GUIDE 또는 POLICY 또는 RUNBOOK 또는 CASE, title:string, excerpt:string, systemId:Uuid, appliesToSystemVersion:string?, applicability:string, accessScope:AccessScope, origin:KnowledgeOrigin | 직원 응답에는 허용된 EMPLOYEE 항목만. CASE는 공개 버전 재검증 |
| SearchResult | state:SearchState, evidence:KnowledgeEvidence[], error:ApiError? | EMPTY는 error=null·evidence=[]; ERROR는 error 필수. 부분 저장소 오류는 전체 ERROR로 보수 처리 |
| CaseGeneration | id:Uuid, workRecordId:Uuid, state:GenerationState, caseId:Uuid?, error:ApiError? | SUCCEEDED에만 caseId 필수; 원 처리 기록당 한 작업 |
| CaseSummary | id:Uuid, title:string, systemId:Uuid, latestCaseVersion:int, latestState:CaseVersionState, publishedVersions:int[], generationState:GenerationState, updatedAt:time | 담당 시스템 범위의 검토 목록 |
| CaseVersion | id:Uuid, caseVersion:int, state:CaseVersionState, ticketId:Uuid, problemId:Uuid, workRecordId:Uuid, resolutionRequestId:Uuid, systemId:Uuid, title:string, action:string, verification:string, applicability:string, limitations:string[], appliesToSystemVersion:string?, accessScope:AccessScope, origin:KnowledgeOrigin, confirmationOrigin:ConfirmationOrigin, sourceMessageIds:Uuid[], createdAt:time | source 원 기록 고정. 초안 편집은 새 버전 |
| CaseApproval | caseId:Uuid, caseVersion:int, approvedBy:UserSummary, approvedAt:time | 해당 시스템 주담당자. 승인 내용 불변 |
| IndexResult | jobId:Uuid, caseId:Uuid, caseVersion:int, store:IndexStore, state:IndexState, attempt:int, error:ApiError?, updatedAt:time | attempt≥1. 저장소별 같은 승인 버전 추적 |
| CaseDetail | version:CaseVersion, approval:CaseApproval?, indexes:IndexResult[], publication:Publication | DRAFT에는 approval=null·indexes=[] |
| Publication | caseId:Uuid, caseVersion:int, state:PRIVATE 또는 SEARCHABLE, canSearch:boolean | state는 승인·두 반영 성공의 전역 사실, canSearch는 현재 사용자 ACL까지 검사한 결과 |

### 변경 요청

모든 변경 요청은 requestId:Uuid를 가지며 HTTP Idempotency-Key와 같아야 한다. 아래 표는 requestId 외 필드다. 첫 문의를 제외한 신규 명령의 모든 대상은 서버가 소유·현재 권한을 검사한다.

| 요청 타입 | 필드 | 조건 |
|---|---|---|
| InquiryCreateCommand | content:string, reportedSystemId:Uuid? | 선택하지 않아도 시작 가능 |
| MessageSendCommand | expectedInquiryVersion:int, content:string, problemIds:Uuid[] | []는 분석으로 대상 식별. 이미 배정된 문제의 대상 불명확 메시지는 보완 대상을 확인 |
| MessageReviseCommand | expectedInquiryVersion:int, expectedMessageRevision:int, content:string | URL의 messageId와 현재 문의 일치 |
| SystemConfirmCommand | expectedInquiryVersion:int, systemId:Uuid | WAITING_SYSTEM 또는 아직 미확정인 분석 전 문제에만 허용 |
| RunRetryCommand | expectedInquiryVersion:int | FAILED/TIMED_OUT이며 현재 활성 run일 때 새 run 생성 |
| CreationRetryCommand | expectedInquiryVersion:int | NOT_CREATED 작업만, 같은 workItemId 유지 |
| WorkRecordCommand | expectedTicketRevision:int, kind:WorkRecordKind, action:string, verification:string? | PROGRESS 외 verification 필수. CHANGE_REQUEST는 IMPLEMENTED_VERIFIED만 완료 가능 |
| AssignmentCorrectionCommand | expectedTicketRevision:int, responsibleSystemId:Uuid, reason:string, evidenceMessageIds:Uuid[], evidenceNote:string? | reason 필수·근거 하나 이상. 관리자는 무권한 메시지 ID 대신 evidenceNote 사용 |
| CaseEditCommand | expectedCaseVersion:int, title:string, action:string, verification:string, applicability:string, limitations:string[], appliesToSystemVersion:string?, accessScope:AccessScope | 출처·원 기록·적용 시스템을 이 API로 바꾸지 않음 |
| CaseApproveCommand | expectedCaseVersion:int | 최신 DRAFT의 확인된 버전에만 승인 |
| IndexRetryCommand | expectedCaseVersion:int, store:IndexStore | FAILED 저장소만 재실행. UNKNOWN은 먼저 확인 |
| IndexCheckCommand | expectedCaseVersion:int, store:IndexStore | UNKNOWN/PENDING/RUNNING의 저장소별 결과 확인. 새 반영 생성 아님 |

각 명령의 응답은 CommandReceipt이며 resource로 관련 문의(메시지 포함)·실행·티켓·작업·확인 요청·사례를 재조회한다. 생성 결과·AI 실행 완료·검색 공개는 receipt의 성공과 별개다. 신규 문의자 DTO에는 전체 표준 요청·내부 근거·다른 문제의 원문을 포함하지 않는다.

## 전환 전 데이터 프로필

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
- `MutationResult`: 아래 프론트 REST 연동 프로필. 백엔드 DTO 최종 확인은 Q-INF-6으로 추적한다.
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

## MutationResult · REST 저장 결과의 프론트 연동 프로필

Q-INF-6: 기존 docs/API.md 2.4절의 저장 필드에 requestId를 포함한 프론트 REST 연동 프로필이다. Mock·REST 소비 타입에 구현했으며 백엔드는 동일 필드·필수/null 조건과 HTTP 상태를 맞춰 저장 API/DTO를 구현해야 한다. 프론트 구현과 팀 리뷰/병합·백엔드 구현·실제 서버 연결 완료는 구분한다. 변경 시 문서·fixture·각 서비스 타입을 함께 검토한다.

프론트 구현 대상은 `POST /api/inquiries`·`POST /api/inquiries/{id}/messages`·`POST /api/inquiries/{id}/messages/{messageId}/revisions`의 공통 HTTP 200 응답이다. 성공 본문은 아래 객체 자체이며 `{data: ...}` wrapper나 `ok` 필드를 요구하지 않는다. 오류는 HTTP 오류 상태와 `ApiErrorBody`를 사용한다. `POST /api/runs/{id}/retries`의 응답은 `RunSummary`이며 이 타입을 사용하지 않는다.

| 필드 | 타입·필수/null | 저장 결과의 의미 |
|---|---|---|
| `requestId` | `Uuid`, 필수·null 불가 | 요청의 `Idempotency-Key`와 동일. 요청·응답·사용자 저장 이벤트를 연결 |
| `inquiryId` | `Uuid`, 필수·null 불가 | 저장된 문의. 기존 문의의 전송·정정이면 요청 경로와 동일 |
| `inquiryVersion` | 정수 1~2147483647, 필수 | 이번 입력 저장 직후의 문의 버전. 최초 생성 1, 사용자 메시지·정정마다 증가 |
| `messageId` | `Uuid`, 필수·null 불가 | 이번에 저장·정정한 사용자 메시지. 정정이면 기존 ID 유지 |
| `revision` | 정수 1~2147483647, 필수 | 해당 메시지의 저장 직후 revision. 새 메시지는 1, 정정 시 증가 |
| `runId` | `Uuid` 또는 null, 필수 키 | `ANALYSIS_STARTED`면 이번 분석 실행 ID, `SUPPLEMENT_SAVED`면 null |
| `effect` | `ANALYSIS_STARTED` 또는 `SUPPLEMENT_SAVED`, 필수 | 분석 접수 또는 정보 보완 저장. 분석·티켓 생성·해결 완료를 뜻하지 않음 |

아래는 가상 UUID로 작성한 분석 접수 성공 예시다.

```json
{
  "requestId": "00000000-0000-4000-8000-000000000701",
  "inquiryId": "00000000-0000-4000-8000-000000000200",
  "inquiryVersion": 2,
  "messageId": "00000000-0000-4000-8000-000000000603",
  "revision": 1,
  "runId": "00000000-0000-4000-8000-000000000301",
  "effect": "ANALYSIS_STARTED"
}
```

정보 보완 저장 예시는 공용 fixture `supplement-result`를 따른다. 동일한 필드가 모두 있고 `runId`는 null, `effect`는 `SUPPLEMENT_SAVED`다. 같은 요청을 다시 확인하면 최초 저장 결과를 반환하며, 현재 버전·새 실행 ID로 결과를 재작성하지 않는다.

프론트 수신 처리는 `shared/api/mutations.ts`에 구현했다. UUID·정수 범위·effect/runId 조합과 요청의 requestId·문의/메시지 ID 일치를 검사한다. 응답이 잘못됐거나 HTTP timeout·네트워크 장애로 결과를 모르면 성공 처리하지 않고 입력·키·본문을 보존한다. 미등록 effect도 성공으로 추정하지 않는다. 화면 내부 `MutationOutcome.ok`는 성공/오류/결과 미확인 분기용이며 HTTP 응답 필드가 아니다. 재전송·버전 충돌·인증 갱신 정책은 WIRE_CONTRACT 4·10절을 따른다.
- 요청 본문의 requestId·inquiryId·messageId는 기존 Command 타입에 유지하며 현재 Idempotency-Key/경로와 같아야 한다. 중복 필드 제거 시점은 백엔드와 확인한다.
- `SubscriptionTarget`은 SSE 연결 대상 표현으로 사용한다. 이벤트 대상의 필드는 `type`과 `id`다.

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
