# 데이터 저장 계약 — PRD 1.44

상태: PR #68 제출 결정안 · 외부 ID/필드와 저장 매핑·거래 경계 기입 완료. 팀 PR 승인·Entity/DDL 작성·DB 적용·실행 검증은 별도다. [PRD](PRD.md)·[공통 계약](../specs/contracts/README.md)·[타입](../specs/contracts/DATA_TYPES.md#신규-계약-hops-system-1)을 따른다. 적용된 V1과 기존 계정/부서 데이터는 변경하지 않는다.

## 1. 데이터 정본

Spring의 PostgreSQL 업무 DB가 사용자·시스템 PRIMARY·문의·티켓·처리·확인·사례 승인·공개의 정본이다. Vector/Neo4j는 검색 파생 저장소이며 담당자/권한/해결 확정의 정본이 아니다. AI는 업무 DB에 직접 쓰지 않고, 별도 지식 DB와 RunLedger DB만 서비스 전용 권한으로 접근한다.

## 2. 고정 카탈로그

세 시스템·기능10개·가상 사용자·현재 PRIMARY3개는 [공용 예시](../specs/mocks/hops-system-1.examples.json)의 catalogVersion=hops-demo-v1을 사용한다. 부서는 소속이다. 시스템 UUID는 `uuid5(NAMESPACE_URL,"hops-system-1.example/"+SystemCode)`, 기능은 `uuid5(NAMESPACE_URL,"hops-system-1.feature/"+SystemCode+"/"+기능코드)`다. 데이터는 모두 가상이며 로그인 비밀값을 포함하지 않는다.

## 3. 버전과 원문

| 값 | 증가·불변 규칙 |
|---|---|
| inquiryVersion | 최초1, 사용자 메시지·정정·시스템 선택·보완 대상 확정 저장 시 증가. AI 메시지/처리/해결 응답은 증가시키지 않음 |
| message.revision | 같은 messageId·표시 순서를 유지하며 정정마다 증가, 원문 이력 보존 |
| ticketRevision | 처리/정정 등 해당 업무 변경의 낙관 버전. 요청의 expected 값 불일치409 |
| requestRevision | 확인 요청 최초1, 원 처리 기록·가이드 출처 고정, 응답 시2. 다른 응답으로 덮어쓰지 않음 |
| caseVersion | 내용 편집마다 새 불변 버전. 승인 후 편집도 새 초안·재승인 |
| resultRevision | 문제별 티켓 생성 결과가 바뀔 때 증가. 접수 안내 모달 표시용 |

문제와 원 메시지는 원 문의에 속한다. 관련 원문 구간은 revision과 Unicode [start,end)를 보존하고 담당자 투영에서 다른 문제의 구간을 제외한다. 안전한 분리가 불가능하면 해당 표준 요청만 제공한다. 전체 원문은 문의에 남긴다.

## 4. 문제·표준 요청

한 문의의 독립 완료 조건을 문제로 나누고 같은 문제 ID를 재분석에도 유지한다. 한 증상의 원인 후보만으로 문제를 추가하지 않는다. 의도/처리 유형·사실/추정/미확인·보고/책임 시스템 후보와 출처를 별도로 둔다. 표준 요청 스냅샷은 원문·입력 버전·실행에 연결해 보존한다.

질문은 저장된 (problemId,bundleRef) 하나당 한 번만 세며 최대5다. 5회·시스템 미확인은 WAITING_SYSTEM을 보존하고 닫기/재접속으로 초기화하지 않는다. 명시적 보완 대상 선택은 원 메시지의 문제 연결 조작이며 추가 자유 질문 횟수를 늘리지 않는다.

## 5. 담당 관계와 티켓

현재 유효 PRIMARY는 `it_system.primary_owner_member_id`로 관리한다. 당시 확정 배정은 ticket의 assignee와 `ticket_assignment`에 남기며 PRIMARY 변경이 기존 티켓 assignee를 자동 수정하지 않는다. 현재 처리/조회 권한은 서버의 현재 PRIMARY다. 담당 변경 시 권한 세대 증가·구독 무효화와 저장을 연결한다.

## 6. 처리·확인

티켓의 PROGRESS는 실제 시도 기록이다. VERIFIED_RESOLUTION 또는 기능 요청의 IMPLEMENTED_VERIFIED에 실제 조치·검증을 기록해야 확인 요청을 생성한다. 원 처리 기록 하나에 확인 요청을 연결하고 문제별 PENDING은 최대 하나다. 미해결이면 같은 티켓에서 다음 처리 주기, 무응답은 대기, 해결이면 해당 문제만 최종 완료다.

## 7. 사례·공개

검증 기록 저장과 함께 Curator 작업을 예약한다. 실행 실패·재사용할 내용 없음이 업무 기록을 취소하지 않는다. CASE 버전은 원 ticket/workRecord/resolutionRequest와 공개 범위·적용 버전에 고정한다. 승인 시 현재 PRIMARY·같은 원 기록의 해결 확인·기능 구현/검증을 재검사한다.

승인과 두 저장소 반영 성공은 별개다. `(caseId,caseVersion,store)`별 하나의 논리 작업, 수동 재실행마다 attempt 증가·동일 내용 digest를 사용한다. 승인 버전·최신 attempt·digest가 일치하는 두 성공이 DB에서 확인될 때만 공개 gate를 연다. 미확인/실패/다른 버전은 해당 버전 양쪽 비공개다. 이전 공개 버전은 새 초안 때문에 자동 폐기하지 않는다.

## 8. 보존·전환

업무 이력은 이번 MVP에서 삭제하지 않는다. journal/AI ledger 상세 재확인은24시간, 사용한 키/digest 표식과 업무 유일성은 이후에도 유지한다. 업무 DB와 AI runtime DB에 별도 계정을 사용한다. 새 프로필은 별도 가상 데이터로 검증 후 선택하며 기존 v0/Mock에 필드명 일괄 치환이나 자동 이관을 수행하지 않는다.

<a id="62-문제생성-작업티켓"></a>

## 9. hops-system-1 저장 제약안

이 절의 논리 제약은 이번 PR의 채택값이다. 물리 구현은 [이슈 #66](https://github.com/Hi-Hops/Hops/issues/66)의 명칭에 아래 매핑으로 작성한다. 별도 엔티티 사전을 생성하지 않는다.

| 외부 개념 | 채택한 저장·거래 방식 |
|---|---|
| SupportedSystem / PRIMARY | it_system·system_feature. 시스템별 PRIMARY FK1명. 변경 시 시스템 행 잠금·ownership_version 증가·변경 이력/outbox 저장 |
| WorkItem / workItemId | 별도 작업 테이블을 두지 않음. workItemId=problemId=inquiry_problem.id, 생성 outcome/현재 제안/오류를 문제 행에 보존 |
| Ticket | ticket.inquiry_problem_id UNIQUE. 논리 workItemId는 같은 문제 FK에서 투영. ticket·초기 ticket_assignment·문제 STORED·outbox를 한 거래로 저장 |
| 메시지·질문 | message+revision 원문 보존, 질문 번들은 AI 메시지의 문제별 metadata와 agent_run 적용 키로 중복 방지. 문제 행 잠금 안에서 count≤5 |
| 표준 요청 | standard_request 불변 스냅샷, (문제/입력버전/runId) 출처 보존. 현재 포인터는 최신 유효 결과에만 연결 |
| 처리·확인 | ticket_work_log와 문제별 확인 요청/응답 원 기록을 연결. 원 기록당 한 처리 주기·PENDING 최대 하나를 DB 제약/행 잠금으로 보장 |
| Curator / CASE | 원 검증 기록당 논리 작업1개, agent_run.kind=CASE_DRAFT. resolved_case는 (caseId,caseVersion)으로 불변 내용·출처·승인 보존 |
| 반영·공개 | 승인 CASE 버전의 VECTOR/NEO4J 상태/attempt/digest를 각각 저장. 다른 버전/attempt의 성공은 새 승인 공개에 재사용하지 않음 |
| journal | idempotency_key에서 (actorId,profile,requestId) 유일·메서드/경로/본문 digest 고정, 결과 재확인 먼저 후 업무 버전 비교 |
| outbox | 업무 commit 안에 (aggregate,eventType,dedupeKey) 유일 이벤트 저장. 같은 전달 재확인은 새 모델/색인 업무 attempt를 만들지 않음 |

PostgreSQL 격리는 READ COMMITTED, 잠금 순서는 Inquiry → problemId 정렬 → Ticket → CASE 버전이다. 같은 요청/작업 재전달은 먼저 기존 결과를 찾는다. 저장 후 HTTP 유실은 기존 결과를 반환하고, 조회 실패나404만으로 NOT_CREATED를 만들지 않는다. 현재 소유권 변경·버전 충돌은 재조회 후409이며 자동 덮어쓰지 않는다.

최초 질문/AI 결과 적용·티켓·검증 기록/확인요청·사례 승인/두 반영 작업 생성·공개 gate 갱신은 각각 명시한 업무 거래로 원자화한다. 외부 모델/Vector/Neo4j 호출을 DB 트랜잭션 안에서 기다리지 않고 outbox와 같은 논리 키로 연결한다. 승인·버전·외부 attempt/digest 검증은 최종 gate 갱신 시 다시 수행한다.

## 10. 물리 ERD 적용 기준

#66의 시스템 PRIMARY 컬럼·문제 행 생성 상태 방식을 채택했다. wire DTO·State가 1:1 테이블을 요구하지 않는다. #66에서 작성할 DDL과 Entity는 위의 키·유일성·원 기록·권한 세대·반영 버전 조건을 구현한다. 기술 outbox/ledger·확인 이력을 기존 표에 합치더라도 동일 제약을 보장해야 한다. 이 결정과 다른 물리안을 적용하려면 공통 계약부터 변경 PR로 갱신한다.

현재 V1·논리 ERD 문서·실제 Entity를 이 문서 변경으로 수정하지 않는다. migration 생성·FK/UK/CHECK·실제 경합/권한/공개 gate 검사는 백엔드 구현 완료 조건이며 계약 결정을 남겨 둔 항목이 아니다.

## 11. 완성 ERD 대조와 문서 PR 병합 기준

대조 상태: **완성 물리 ERD의 파일·기준 revision·검토 PR이 아직 연결되지 않아 대조 전**이다. 9/10절은 공통 계약의 채택 저장 방식이며 완성 ERD를 이미 확인했다는 뜻이 아니다. 기존 논리 ERD 설명과 #66의 작업 범위만으로 실제 FK·컬럼·제약의 일치를 완료 표시하지 않는다. 대조 대상은 검토할 ERD 파일과 고정 revision으로 식별하고, GitHub에 게시되면 해당 PR/commit을 기준으로 연결한다.

### 11.1 대조 항목과 구체화할 spec

각 행은 이미 채택한 업무 규칙을 실제 저장 위치에 대응시키는 작업이다. 완성 ERD에서 보이는 테이블/컬럼·PK/FK·관계·null·유일성·저장 enum을 기록한다. 그림에 없는 제약은 ERD 설명 또는 스키마 정의에 명시해 검토하며 존재한다고 추정하지 않는다.

| ID·범위 | ERD에서 확인·기록할 내용 | 더 구체화할 계약·spec |
|---|---|---|
| E01 시스템·현재 담당·배정 이력 | 시스템/기능 키, 현재 PRIMARY FK·필수성, 담당 변경 이력/권한 세대 저장 위치. ticket의 당시 배정과 현재 PRIMARY의 구분 | DATA9절 매핑, DATA_TYPES의 SupportedSystem/PrimaryAssignment/TicketDetail. F001/F007/F009의 현재 권한·수동 정정 |
| E02 문의·문제·생성 작업·티켓 | 문의→문제 FK, problemId=workItemId 대응, 티켓의 문제 FK 유일성, 생성 outcome·오류 저장 위치, 문제별 질문 수≤5의 저장/제약 | TicketCreationResult·Problem·InquiryResult, F003/F005/F008/F010의 일부 성공·중복 생성 방지·동일 작업 복구 |
| E03 원문·정정·문제 연결 | 메시지/revision 복합 참조, 원문 이력·현재 포인터, 같은 메시지와 여러 문제의 관계, 관련 원문 구간·보완 대상 대기 저장 | ChatMessage/RelatedMessage·MessageProblemLinkCommand, F003/F004/F009의 원문 보존·버전 충돌·허용 구간 조회 |
| E04 표준 요청 | 불변 스냅샷의 문제·문의 입력 버전·runId FK/참조, 현재 스냅샷 선택 방식, 사실/추정/미확인·출처의 JSON 또는 관계 저장 위치 | StandardRequest·AiRunRequest/TicketProposal의 저장·투영 매핑, F003/F008/F009 |
| E05 상태·버전 | 문의/문제/티켓 저장 enum과 wire enum의 대응, inquiryVersion/ticketRevision/resultRevision 저장 위치·초기값·증가 조건 | DATA_TYPES·TARGET의 상태/버전, WIRE의 409·최신 조회, F002/F004/F005/F007/F009 |
| E06 처리·문의자 확인 | 불변 WorkRecord 키/종류, 확인 요청의 원 기록 또는 가이드 revision 참조, 문제별 PENDING 한 개·응답 한 번의 제약, 미해결 후 다음 처리 주기 이력 | WorkRecord/ResolutionRequest/Response, F006/F009와 F011 승인 전제 |
| E07 CASE 초안·버전·승인 | 원 ticket/problem/workRecord/resolutionRequest 연결, caseId와 버전 행 PK의 구분, 같은 논리 CASE의 버전 유일성, 승인자·승인 버전·공개 범위·출처 | CaseVersion/CaseApproval·CASE_DRAFT 원 기록·ApprovedCaseBody의 투영, F009/F011의 불변 버전·재승인 |
| E08 저장소별 반영·공개 | (caseId,caseVersion,store)의 논리 작업, jobId/attempt/digest·각 결과 이력·최신 시도, 동일 승인 버전 두 성공과 publication의 연결 | IndexResult/Publication·IndexApplyRequest/Snapshot, F011의 부분 실패·확인·수동 재실행·공개 gate |
| E09 Spring 실행·AI 입력/결과 | agent_run의 runId/kind·입력/정책 버전·현재 실행·최종 digest·적용 여부, role step 참조. Spring 업무 실행 기록과 별도 AI RunLedger의 구분 | RunSummary·AiRunRequest/Result/Snapshot·State 저장 경계, F002/F003/F004/F010/F011의 늦은 결과·재시작 처리 |
| E10 명령 journal·outbox | requestId/actor/profile·본문 digest·operationId와 업무 참조, 키/이벤트 중복 제약, 상세24시간 이후 사용 키 보존 위치 | CommandReceipt·WIRE의 재확인/410·내부 outbox 전달, F002/F005/F010/F011의 응답 유실·중복 적용 방지 |

물리 컬럼명이 wire 필드명과 다르면 Spring의 DTO 투영/변환으로 대응한다. DB의 UUID 생성 방식이나 CASE 버전 행 PK가 달라도 외부 논리 ID의 안정성과 관계를 유지한다. 관계 테이블/JSON 저장 방식의 차이만으로 API 경로·화면·AI 내부 State를 변경하지 않는다.

DB enum/길이/null 제약이 외부 의미에 영향을 주면 해당 필드의 DB 값↔wire 값, 생성 전/후 null 조건, 버전 증가와 충돌 응답을 명시한다. 원문 1~4,000 코드 포인트를 조용히 절단하거나 새 DB 상태를 기존 성공으로 치환하지 않는다. 공통 계약의 의미를 바꿔야 하는 경우 DATA_TYPES·TARGET·WIRE·영향받는 spec을 같은 PR에서 수정하고, AI wire까지 영향이 있으면 Schema·공용 예시·에이전트 문서도 함께 갱신한다. PRD 정책 변경은 별도 기획 리뷰가 필요하다.

### 11.2 반영 순서와 병합 완료 조건

1. ERD 기준 파일/revision·검토 PR을 이 절에 연결하고 E01~E10 각각의 실제 저장 위치와 제약 근거를 기록한다. 자료가 별도 ERD PR에 있다면 변경 가능한 브랜치 링크만 쓰지 않고 확인한 commit을 함께 남긴다. 문서 PR은 그 ERD가 develop에 먼저 병합되거나 두 문서의 기준 revision을 일치시킨 뒤 병합한다.
2. DATA9/10절의 매핑을 대조 결과로 갱신하고 차이가 있는 외부 필드/상태만 DATA_TYPES·TARGET·WIRE에 반영한다. 백엔드가 작성 중인 ERD/Entity 산출물의 정본은 해당 작업에서 유지한다.
3. 영향받는 기능 spec에 저장 제약에 대응하는 관찰 조건을 보완한다. 물리 제약명만 추가하는 경우 기존 CONTRACT 시나리오의 근거를 연결하며 같은 동작의 시나리오를 중복 생성하지 않는다. AI 입력/결과 필드가 바뀌면 Schema·공용 예시도 함께 검사한다.
4. 문서 링크/앵커·타입/상태·예시·spec 연결·기존 프로필 보존·diff 검사를 통과하고, 백엔드 담당자가 저장 매핑과 보장 방식을 리뷰한다. 마지막 변경 기준 작성자 외 팀원 최소1명 승인, 리뷰 이슈 해결, 필수 CI 또는 해당 문서의 로컬 검증, 충돌 없음까지 확인한다.

위 문서 병합 조건에 실제 Entity/Flyway·API 구현·DB 경합·OpenAI/검색·브라우저 E2E의 완료를 포함하지 않는다. 해당 구현은 별도 PR에서 검증한다. ERD는 관계/저장 제약의 설계 근거이며 트랜잭션·실시간 중계·권한 회수·중복 방지가 실행됐다는 증거가 아니다. API 경로·모델/예산·프론트 공통 UI는 이 대조만으로 다시 선택하는 항목이 아니다.
