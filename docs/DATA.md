# 데이터 저장 계약 — PRD 1.44

상태: PR #68 제출 결정안 · 외부 ID/필드와 저장 매핑·거래 경계 기입 완료. 팀 PR 승인·Entity/DDL 작성·DB 적용·실행 검증은 별도다. [PRD](PRD.md)·[공통 계약](../specs/contracts/README.md)·[타입](../specs/contracts/DATA_TYPES.md#신규-계약-hops-system-1)을 따른다. 적용된 V1과 기존 계정/부서 데이터는 변경하지 않는다.

## 1. 데이터 정본

Spring의 PostgreSQL 업무 DB가 사용자·시스템 PRIMARY·문의·티켓·처리·확인·사례 승인·공개의 정본이다. Vector/Neo4j는 검색 파생 저장소이며 담당자/권한/해결 확정의 정본이 아니다. AI는 업무 원본에 직접 쓰지 않으며 같은 DB의 hops_knowledge 검색 schema와 별도 hops_ai_runtime RunLedger DB에 서비스 전용 권한으로 접근한다.

## 2. 고정 카탈로그

세 시스템·기능10개·가상 사용자·현재 PRIMARY3개는 [공용 예시](../specs/mocks/hops-system-1.examples.json)의 catalogVersion=hops-demo-v1을 사용한다. 부서는 소속이다. 시스템 UUID는 `uuid5(NAMESPACE_URL,"hops-system-1.example/"+SystemCode)`, 기능은 `uuid5(NAMESPACE_URL,"hops-system-1.feature/"+SystemCode+"/"+기능코드)`다. 데이터는 모두 가상이며 로그인 비밀값을 포함하지 않는다.

## 3. 버전과 원문

| 값 | 증가·불변 규칙 |
|---|---|
| inquiryVersion | 최초1, 사용자 메시지·정정·시스템 선택·보완 대상 확정 저장 시 증가. AI 메시지/처리/해결 응답은 증가시키지 않음 |
| message.revision | 같은 messageId·표시 순서를 유지하며 정정마다 증가, 원문 이력 보존 |
| ticketRevision | ticket.version+1, 최초1(DB version=0). 처리/정정 등 해당 업무 변경의 낙관 버전. 요청의 expected 값 불일치409 |
| requestRevision | 기존 message.payload 또는 ticket_event의 확인 메타데이터에서 투영. 최초1·응답 시2이며 원 처리 기록·가이드 출처를 유지 |
| caseVersion | 현재 DRAFT 편집 시 version_no 증가. 승인 뒤 읽기 전용이며 복수 승인 버전 병행은 후순위 |
| resultRevision | 문제별 티켓 생성 결과가 바뀔 때 증가. 접수 안내 모달 표시용 |

문제와 원 메시지는 원 문의에 속한다. 관련 원문 구간은 revision과 Unicode [start,end)를 보존하고 담당자 투영에서 다른 문제의 구간을 제외한다. 안전한 분리가 불가능하면 해당 표준 요청만 제공한다. 전체 원문은 문의에 남긴다.

## 4. 문제·표준 요청

한 문의의 독립 완료 조건을 문제로 나누고 같은 문제 ID를 재분석에도 유지한다. 한 증상의 원인 후보만으로 문제를 추가하지 않는다. 의도/처리 유형·사실/추정/미확인·보고/책임 시스템 후보와 출처를 별도로 둔다. 표준 요청 스냅샷은 원문·입력 버전·실행에 연결해 보존한다.

질문은 저장된 (problemId,bundleRef) 하나당 한 번만 세며 최대5다. 5회·시스템 미확인은 WAITING_SYSTEM을 보존하고 닫기/재접속으로 초기화하지 않는다. 명시적 보완 대상 선택은 원 메시지의 문제 연결 조작이며 추가 자유 질문 횟수를 늘리지 않는다.

## 5. 담당 관계와 티켓

현재 유효 PRIMARY는 `it_system.primary_owner_member_id`로 관리한다. 당시 확정 배정은 ticket의 assignee와 `ticket_assignment`에 남기며 PRIMARY 변경이 기존 티켓 assignee를 자동 수정하지 않는다. 현재 처리/조회 권한은 서버의 현재 PRIMARY다. 담당 변경 시 권한 세대 증가·구독 무효화와 저장을 연결한다.

## 6. 처리·확인

티켓의 PROGRESS는 실제 시도 기록이다. VERIFIED_RESOLUTION 또는 기능 요청의 IMPLEMENTED_VERIFIED에 실제 조치·검증을 기록해야 확인 요청을 생성한다. 기존 ticket/ticket_event와 inquiry_problem/message.payload에서 현재 확인 요청과 응답을 투영하고 별도 확인 테이블은 만들지 않는다. 문제별 현재 확인 요청은 하나로 관리하며 미해결이면 같은 티켓에서 처리를 계속하고 무응답은 대기를 유지한다.

## 7. 사례·공개

검증 기록 commit 뒤 Curator를 호출한다. 실행 실패·재사용할 내용 없음이 업무 기록을 취소하지 않는다. 현재 resolved_case는 원 처리 기록과 확인 응답·공개 범위·적용 버전에 연결한다. 승인 시 현재 PRIMARY·원 기록의 해결 확인·기능 구현/검증을 재검사한다. MVP는 현재 초안만 편집하고 승인 후에는 읽기 전용으로 두며, 복수 승인 버전 병행은 후순위다.

승인과 두 저장소 반영 성공은 별개다. 현재 resolved_case.version_no와 vector_status/graph_status를 기준으로 양쪽 성공을 확인한 뒤 공개한다. 서비스 요청의 caseVersion/attempt/digest는 결과 대조에 사용하고 기존 journal에는 현재 작업 결과를 남긴다. 별도 반영 이력 테이블은 만들지 않으며 미확인·실패·다른 버전 결과는 공개 성공으로 처리하지 않는다. 승인 CASE의 검색용 스냅샷은 기존 knowledge_document/knowledge_document_version에 저장한다.

## 8. 보존·전환

업무 이력은 이번 MVP에서 삭제하지 않는다. journal/AI ledger 상세 재확인은24시간, 사용한 키/digest 표식과 업무 유일성은 이후에도 유지한다. 업무 DB와 AI runtime DB에 별도 계정을 사용한다. 새 프로필은 별도 가상 데이터로 검증 후 선택하며 기존 v0/Mock에 필드명 일괄 치환이나 자동 이관을 수행하지 않는다.

<a id="62-문제생성-작업티켓"></a>

## 9. hops-system-1 저장 제약안

물리 기준은 [병합된 ERD PR #69](https://github.com/Hi-Hops/Hops/pull/69)의 22개 테이블이다. MVP는 테이블을 추가하지 않고 아래 매핑과 [ERD6절](ERD.md#6-hops-system-1-공통-계약-보완)의 기존 컬럼·DTO 대응을 적용한다. 별도 엔티티 사전을 생성하지 않는다.

| 외부 개념 | 채택한 저장·거래 방식 |
|---|---|
| SupportedSystem / PRIMARY | it_system·system_feature. PRIMARY FK1명·ownership_version 증가, 영향 회원 policy_revision 갱신과 기존 구독 차단. 별도 담당 관계 Entity 없음 |
| WorkItem / workItemId | 별도 작업 테이블을 두지 않음. workItemId=problemId=inquiry_problem.id, 생성 outcome/기준 입력 버전/오류를 문제 행에 보존 |
| Ticket | ticket.inquiry_problem_id UNIQUE. 논리 workItemId는 같은 문제 FK에서 투영. ticket·초기 ticket_assignment·문제 STORED를 한 거래로 저장하고 commit 뒤 상태 통지 |
| 메시지·질문 | message+revision 원문 보존, 질문 번들은 AI 메시지의 문제별 metadata와 agent_run 적용 키로 중복 방지. 문제 행 잠금 안에서 count≤5 |
| 표준 요청 | standard_request 불변 스냅샷, UNIQUE(inquiry_problem_id,inquiry_version,agent_run_id)로 출처 보존. 현재 포인터는 최신 유효 결과에만 연결 |
| 처리·확인 | ticket/ticket_event·ticket_work_log와 inquiry_problem/message.payload로 현재 확인 상태·원 기록·가이드 revision 투영. 별도 확인 테이블 없음 |
| Curator / CASE | 원 검증 기록의 agent_run.task=CURATION과 resolved_case.source_work_log_id로 연결. 현재 초안의 version_no를 관리하고 승인 뒤 읽기 전용 |
| 반영·공개 | resolved_case.vector_status/graph_status에 현재 승인 버전의 결과 저장. 별도 시도 이력 테이블 없이 요청/응답 버전·digest를 검증 |
| journal | idempotency_key에서 (actor_member_id,contract_version,idempotency_key) 유일·메서드/경로/본문 digest 고정, 결과 재확인 먼저 후 업무 버전 비교 |
| 후속 호출 | 업무 commit 뒤 Spring이 AI·색인·SSE 호출. 영속 이벤트 큐·재시작 자동 복구는 후순위이며 외부 호출 실패로 업무 저장을 취소하지 않음 |

PostgreSQL 격리는 READ COMMITTED, 잠금 순서는 Inquiry → problemId 정렬 → Ticket → CASE 버전이다. 같은 요청/작업 재전달은 먼저 기존 결과를 찾는다. 저장 후 HTTP 유실은 기존 결과를 반환하고, 조회 실패나404만으로 NOT_CREATED를 만들지 않는다. 현재 소유권 변경·버전 충돌은 재조회 후409이며 자동 덮어쓰지 않는다.

질문/AI 결과 적용·티켓·검증 기록/확인 상태·사례 승인·공개 상태 갱신은 각각 업무 거래로 저장한다. 외부 모델/Vector/Neo4j 호출은 commit 뒤 수행하고 현재 승인·버전·반영 결과를 서버가 검증한다. 업무 저장과 외부 호출 사이의 영속 전달 보장은 MVP 완료 조건에 넣지 않는다.

## 10. 물리 ERD 적용 기준

기준은 develop의 [PR #69](https://github.com/Hi-Hops/Hops/pull/69), merge commit `d896faeb2a0b132da2c3212cba408bf880d797cf`다. 원본 22개 테이블을 사용하고 추가 테이블 없이 기존 컬럼 보완과 DTO 투영을 [ERD6절](ERD.md#6-hops-system-1-공통-계약-보완)에 정의한다. 원본 ERD 대조와 팀 승인·DDL/Entity/실제 DB 적용을 구분한다.

<a id="11-완성-erd-대조와-문서-pr-병합-기준"></a>

## 11. 완성 ERD 대조 결과와 병합 기준

### 11.1 대조 기준과 결과

기준 문서: [docs/ERD.md 원본](https://github.com/Hi-Hops/Hops/blob/d896faeb2a0b132da2c3212cba408bf880d797cf/docs/ERD.md), PR #69로 develop에 병합. E01~E10의 대조를 수행했고 값 변환과 저장 보완 방식을 아래처럼 결정했다. 이 결과는 실제 FK·트랜잭션·E2E를 실행한 결과가 아니다.

| 항목 | 대조 결과·채택 방식 | 반영 명세 |
|---|---|---|
| E01 시스템/PRIMARY | it_system.primary_owner_member_id·system_feature.it_system_id를 정본으로 사용. 별도 assignment Entity 없음. PrimaryAssignment는 systemId/primaryUser만 반환. 당시 배정은 ticket_assignment | F001/F007/F009 |
| E02 문제/작업/티켓 | inquiry_problem.id=problemId=workItemId, ticket.inquiry_problem_id UNIQUE. ticket_error_code만으로 미저장 확정 불가: creation_outcome/creation_input_version 추가 | F003/F005/F008/F010 |
| E03 원문/보완 | message.revision 초기1, message_revision은 과거 원문만 보존. message.payload의 문제/구간/번들 메타데이터로 연결하며 원문 수정과 보완 대상 변경을 구분 | F003/F004/F009 |
| E04 표준 요청 | confirmed_facts/assumptions/missing_items/type_detail/related_knowledge에 공통 타입 저장. 같은 입력 버전의 다른 run도 불변 스냅샷이므로 유일 키에 agent_run_id 포함 | F003/F008/F009 |
| E05 상태/버전 | 아래 변환표 사용. ticketRevision=ticket.version+1, expectedTicketRevision-1을 DB 비교에 사용. 문의 버전은 inquiry.version 그대로 | F002/F004/F005/F007/F009 |
| E06 처리/확인 | 기존 ticket/ticket_event·inquiry_problem/message.payload에 현재 확인 상태·출처·응답을 저장하고 ResolutionRequest DTO로 투영 | F006/F009/F011 |
| E07 CASE 버전 | resolved_case의 현재 초안과 version_no를 사용. 승인 뒤 내용은 읽기 전용이며 과거 승인 버전과 새 초안의 병행 관리는 후순위 | F009/F011 |
| E08 반영/공개 | 현재 resolved_case의 vector_status/graph_status와 승인 버전을 검증. 반영 시도 전체 이력 테이블은 후순위 | F011 |
| E09 AI 실행 | runId=agent_run.id, CASE_DRAFT↔task CURATION, INQUIRY↔agent_type INQUERY 변환. input_snapshot/validation_result로 입력/정책/최종 적용을 추적 | F002/F003/F004/F010/F011 |
| E10 journal/후속 호출 | 기존 idempotency_key의 현재 결과와 업무 유일성을 사용. commit 뒤 AI·색인·SSE 호출하며 영속 전달 큐는 추가하지 않음 | F002/F005/F010/F011 |

### 11.2 DB 값과 외부 값의 변환

| DB 값/원천 | hops-system-1 외부 값·규칙 |
|---|---|
| ticket.ticket_status OPEN / IN_PROGRESS / WAITING_CONFIRM / RESOLVED | TicketState WAITING / IN_PROGRESS / WAITING_CONFIRMATION / RESOLVED. DB 코드를 브라우저 필터로 직접 보내지 않음 |
| ticket.version 0부터 | ticketRevision=version+1. DB 허용 범위0~2,147,483,646, 외부1~2,147,483,647. expectedTicketRevision-1과 DB 버전을 비교해409 처리 |
| inquiry.inquiry_status | IN_PROGRESS / WAITING_USER / RESOLVED / CLOSED. CLOSED는 모든 문제가 OUT_OF_SCOPE이고 범위 안내가 저장된 문의에만 사용. 원본3종 CHECK는 ERD6.1절에서4종으로 보완하며 무응답 자동 종료는 없음 |
| problem CLARIFYING | 질문 응답 대기이면 WAITING_USER, 그 외 ANALYZING. 활성 실행 여부는 currentRun으로 별도 표시 |
| problem SYSTEM_PENDING / GUIDED / RESOLVED_WITHOUT_TICKET | WAITING_SYSTEM / WAITING_CONFIRMATION / RESOLVED. GUIDED는 원 가이드 확인 요청의 현재 상태와 함께 조회 |
| problem TICKET_CREATED | 연결 ticket의 WAITING/IN_PROGRESS이면 IN_SERVICE, 확인 대기면 WAITING_CONFIRMATION, 최종 완료면 RESOLVED |
| problem TICKET_FAILED 또는 생성 중 | creation_outcome이 PENDING/NOT_CREATED/UNKNOWN이면 TICKET_PENDING. 실패 코드·404·timeout만으로 NOT_CREATED를 만들지 않음. OUT_OF_SCOPE는 그대로 |
| work log PROGRESS / FAILED_ATTEMPT / RESOLVED / IMPLEMENTED | WorkRecordKind PROGRESS / FAILED_ATTEMPT / VERIFIED_RESOLUTION / IMPLEMENTED_VERIFIED. 완료 두 종류는 verification_result 필수 |
| access_level EMPLOYEE / STAFF | AccessScope EMPLOYEE / SYSTEM_STAFF |
| document_type EMPLOYEE_GUIDE / POLICY / STAFF_RUNBOOK / CASE | KnowledgeEvidence.kind GUIDE / POLICY / RUNBOOK / CASE. CASE는 현재 승인 resolved_case의 검색용 문서 스냅샷. 외부 documentId/documentVersion은 caseId/caseVersion이며 내부 문서 PK와 구분해 source_metadata로 연결·검증 |
| origin_type PRODUCTION / SYNTHETIC / DEMO_VERIFIED | KnowledgeOrigin OPERATIONAL / SYNTHETIC / DEMO_VERIFIED. is_synthetic=false만으로 OPERATIONAL을 추정하지 않음 |
| agent_run.task CURATION / agent_step.agent_type INQUERY | 외부 CASE_DRAFT / INQUIRY. 다른 실행/역할은 기존 외부 명칭 그대로. route GUIDE/REQUEST/SYMPTOM은 조회 내부 값 |
| agent_run CANCELLED | 외부 SUPERSEDED. QUEUED는 DB CHECK에 추가하고 started_at은 실제 시작 전 null |

변환표 밖 값은 안전한 INVALID_RESPONSE/프로토콜 오류이며 성공 상태로 치환하지 않는다. 업무 enum 변경이 필요한 경우 ERD와 DATA_TYPES/TARGET/WIRE·영향 spec을 함께 갱신한다.

### 11.3 JSONB와 조회 투영

| 저장 위치 | 결정한 내용·조회 규칙 |
|---|---|
| message.payload | 서버 관리 contractVersion, problemLinks[{problemId,revision,spans:[{start,end}]}], questionBundles[{problemId,bundleRef}], pendingTarget:boolean. 질문 번들 유일성/횟수 갱신은 문제 행 잠금 안에서 적용. 범위는 Unicode 코드 포인트 [start,end) |
| message_revision | revision_no는 보존한 과거 원문의 번호. 현재 content/revision은 message에서 읽고 둘을 합쳐 정정 이력 구성. 미수정 메시지는 revision1·이력[] |
| standard_request.system_candidates | [{systemId,evidenceIds}]와 현재 responsible_system_id 후보. confirmed_facts/assumptions는 EvidenceStatement[], missing_items는 string[], type_detail은 RequestDetails, related_knowledge는 KnowledgeEvidence[] |
| ticket_event.before_data/after_data | WORK_TYPE_CHANGED이면 {processingType,evidence:{messageIds:Uuid[],note:string?}}. 현재 담당자의 근거/사유와 실제 값 변경을 ticket.version 증가와 같은 거래로 기록 |
| ticket_assignment.evidence | 추가 JSONB {messageIds:Uuid[],note:string?}. 이전 시스템/담당자는 assigned_at/id 순서의 직전 이력에서 투영. AUTO의 actorId=null |
| agent_run.input_snapshot | 검증한 AiRunRequest 원본. model/사용자 비밀값과 내부 사고 과정 제외. inquiry.current_run_id는 상담만 가리키며 CURATION은 trigger_work_log_id로 분리 |
| agent_run.validation_result | {policyRevision,inputDigest,resultDigest?,applied:boolean,appliedAt:time?,resources:ResourceRef[],caseGenerationState:GenerationState?}. resultDigest는 최종 AiResult의 canonical JSON SHA-256. 같은 run/digest는 재적용하지 않음 |
| resolved_case의 현재 행·기존 JSONB | CaseVersion은 현재 사례 컬럼과 confirmed_facts/relation_candidates 등 기존 JSONB의 출처·적용 조건에서 투영. 승인 필드는 기존 approved_by_member_id/approved_at 사용. 승인 후 내용은 읽기 전용 |
| knowledge_document.source_data | {origin:KnowledgeOrigin,sourceRef,reviewPr,contentDigest,appliesToSystemVersion:string?,applicability:string}. 승인 manifest는 이 메타데이터의 버전 고정 반입 자료이며 문서와 버전의 review_status/현재 ACL/data_split=RAG를 매 검색 검사. origin/출처/조건은 knowledge_document_version.source_metadata에 버전별 고정 |

knowledge_document_version.id는 내부 FK이고, 외부 documentId는 knowledge_document.id·documentVersion은 version_no다. CASE의 documentId는 resolved_case.id다. 공통 POLICY만 systemId=null을 허용하고 특정 시스템을 임의 배정하지 않는다. 시스템 없는 STAFF 공통 정책은 현재 PRIMARY가 있는 담당자 문맥에서만 허용한다. DEV/EVAL 자료는 검색에서 제외한다.

### 11.4 문서 병합과 실제 구현

원본 ERD 대조는 완료했다. 추가 테이블 없이 기존 컬럼·DTO 변환을 백엔드와 함께 리뷰하고 작성자 외 팀원 최소1명 승인, 리뷰 이슈 해결, 문서/예시/spec 검사와 충돌 없음 확인 후 문서 PR을 병합한다. [ERD6절](ERD.md#6-hops-system-1-공통-계약-보완)의 컬럼 보완과 MVP 범위는 실제 DB 적용과 구분한다.

Entity/Flyway·DB 경합·서비스 권한·실제 모델/검색·브라우저 E2E는 구현 PR에서 검증한다. Spring이 중계하는 임시 답변 조각은 message의 저장 완료 행과 별개이며 ERD의 message_status만으로 실시간 연결이 구현됐다고 볼 수 없다. 현재 프론트/Mock은 legacy 프로필을 유지하고 전환 작업은 [프론트 기준](frontend/README.md#erd-대조-후-프론트-구현-범위)을 따른다.

GUIDE의 UNRESOLVED 응답은 기존 메시지의 확인 메타데이터와 원 안내를 보존한 채 문제를 CLARIFYING으로 돌리고 commit 뒤 새 상담 run을 호출한다. input.problems[].resolutionContext에 원 요청/안내 revision·UNRESOLVED를 넣고 activeRunId를 갱신한다. 질문 횟수는 유지한다. WORK_RECORD의 UNRESOLVED는 기존 ticket_event에 남기고 같은 티켓 IN_PROGRESS로 돌아가며 새 사람 처리 기록 전 CASE_DRAFT를 다시 실행하지 않는다.
