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
