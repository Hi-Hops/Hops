# 에이전트 공통 입출력 계약 — PRD 1.44

상태: 팀 합의 제품 방향에 따른 논리 계약 초안 · 상세 필드·모델·서비스 인증 및 구현은 미확정. [PRD](../../docs/PRD.md), [API 설계](../../docs/API.md), [목표 공통 계약](../../specs/contracts/TARGET_CONTRACT.md)을 따른다. 기존 코드·Fake의 `agent-1` 계약은 [현재 Python 타입](../../ai/src/hops_ai/contracts/agent_1.py)과 [AI 서버 안내](../../ai/README.md)에서 확인한다.

## 역할과 최소 입출력

| 역할 | 입력 의미 | 결과 제안 | 경계 |
|---|---|---|---|
| Supervisor | 허용된 대화·실행 상태, 전문 결과와 서버 결과 | 모델 판단 기반 다음 행동 선택·응답 통합 | 실행기는 허용 경로·시간·호출·토큰·재시도를 제한 |
| Inquiry | 최신 대화·기존 표준 요청·지원 시스템 | 의도·보고 시스템 후보·확인/미확인 사실·독립 문제·추가 질문 | 문제별 최대 5회 질문 묶음; 미확인 값 추정 금지 |
| Knowledge | 표준 요청·접근 범위·검색 목적 | Vector·Neo4j 근거·원인 후보·조건·다음 확인 항목 | 조건부 분석 포함; 별도 Diagnosis 없음; 권한·공개 범위 준수 |
| Ticket | 문제별 표준 요청·허용 근거·시스템 목록 | 제목·내용·처리 유형·책임 시스템 후보와 근거 | Spring이 현재 버전·지원 시스템·주담당자·저장 결과 검증 |
| Curator | 담당자 해결 기록·원문·기존 지식·관계 스키마 | 사례 초안·중복 및 기존 엔티티 연결 후보 | 기록 저장 직후 사후 실행. 임의의 신규 개념·관계 유형 게시 금지 |

역할 수와 모델 호출 수는 다르며 모든 문의에 모든 역할을 호출하지 않는다. 문의자 상담과 Curator 실행은 분리하되 별도 서버는 필수가 아니다. Python은 업무 DB에 직접 쓰지 않으며 생성·배정·완료·승인·공개를 확정하지 않는다.

## 실행과 실패

- Inquiry와 Knowledge는 최신 문의·문제 버전과 사용자에게 허용된 사실·지식만 사용한다. 시스템 의존 관계나 과거 사례만으로 현재 원인을 확정하지 않는다.
- Ticket의 제안에 대해 Spring은 이미 저장된 티켓·현재 문의 버전·시스템·주담당자를 확인한다. 저장 여부 미확인이면 새 티켓을 만들지 않는다.
- 처리 기록이 저장된 후 Curator 초안 생성 실패는 기록이나 업무 완료를 취소하지 않는다.
- 사례는 문의자 확인과 해당 시스템 담당자 승인 후 동일 승인 버전의 Vector·Neo4j가 모두 성공해야 검색에 공개된다. 실패한 반영은 원인을 수정한 뒤 재실행한다.
- 진행·답변 조각, 모델 출력과 Spring 업무 확정을 구분한다. 지식 검색 없음·검색 오류·도구 오류를 구분하고 직원에게 내부 자료를 노출하지 않는다.

## 구현 전 계약

실행·문의·문제·작업·사례의 식별자와 버전, 역할별 필수/선택 필드·형식·오류, 서비스 간 인증, 시간·호출·토큰·비용 한도, SSE 이벤트와 재연결, 중복 방지, 공개 상태 검증은 기능별로 합의한다. 이 문서는 새 역할이 구현되거나 실제 통합이 검증됐다는 뜻이 아니다.

## 공통 실행 문맥과 결과 — 설계 제안

여기서 말하는 항목은 역할 간 전달해야 할 **의미**다. 실제 Python 내부 클래스는 별도이며 외부 wire·필수성의 구체 제안은 [실행 계약안](#hops-system-1-실행-계약안), 업무 데이터는 [신규 데이터 계약](../../specs/contracts/DATA_TYPES.md#신규-계약-hops-system-1)을 따른다. 팀 승인·구현은 별도다.

| 공통 문맥 | 내용 | 확인 주체 |
|---|---|---|
| 실행 참조 | 실행·호출·문의·문제 참조와 입력 버전 | 런타임과 Spring이 기존 실행·최신 문의를 대조 |
| 사용자/접근 범위 | Spring이 검증한 최소 사용자·자료·시스템 접근 문맥 | 서버가 만들며 모델의 사용자 ID나 역할 주장으로 확장하지 않음 |
| 시간·예산·취소 | 전체 기한, 남은 호출·토큰 예산, 취소 여부 | 실행기가 강제. 역할 전환마다 전체 기한을 새로 시작하지 않음 |
| 기존 업무 결과 | 이미 식별된 문제·작업·저장된 티켓과 서버 결과 | 재실행 시 유지하고 AI가 저장 성공을 만들어내지 않음 |
| 근거 | 원 메시지·문서·읽기 Mock의 참조, 버전·허용 범위·시각 | 검색/도구 결과에 실제 존재하고 접근 가능한 근거만 사용 |
| 실행 결과 | 역할별 제안, 공개 가능한 요약, 근거 참조 또는 안전한 실패 | 역할 실행 성공과 업무 저장/배정/완료를 구분 |

모델 응답의 구조와 근거 참조가 유효하지 않으면 검증 실패로 처리한다. 문의자에게 내부 사고 과정·원시 예외·비밀값을 노출하지 않는다. 검색이 정상 수행됐으나 관련 결과가 없는 경우와 검색 서비스가 실패한 경우를 구분한다. 늦은 이전 입력 버전 결과는 저장된 이력을 지우지 않고 적용 대상에서 제외한다.

## 역할별 상세 책임

### Supervisor

- **입력:** 현재 문의·문제의 상태, 허용된 다음 행동, 각 전문 역할 결과와 Spring의 저장·권한 검증 결과.
- **판단:** 필요한 경우 Inquiry·Knowledge·Ticket을 선택하고, 답변·질문·담당자 연결·종료 등의 허용된 다음 행동을 모델 판단으로 제안한다. 역할 간 결과는 실행기가 검증 후 조합한다.
- **제한:** 모든 문의에 모든 역할을 호출하지 않는다. 실행기는 허용 경로·전체 시간·호출·토큰·재시도를 강제한다. 무제한 상호 호출과 허용되지 않은 도구 접근을 막는다.
- **검증:** 실행 성공이나 모델의 문장만으로 저장·배정·해결·공개를 확정하지 않는다. 최신 버전, 실제 Spring 결과와 접근 범위를 반영한다.

### Inquiry

- **입력:** 현재 대화·정정, 기존 문제·질문 횟수, 지원 시스템·기능, 기존 표준 요청과 이미 확인된 사실.
- **결과 제안:** 문제별 의도·처리 유형 후보, 보고 시스템/기능 후보, 확인 사실·추정·미확인, 추가 질문 묶음, 독립 문제 분리와 사용자 해결 응답 해석.
- **분기:** 같은 문제에 최대 5회 질문 묶음을 보낸다. 사용자가 알 수 없는 값을 채우지 않고 이미 확인한 것을 반복하지 않는다. 보고 시스템을 끝내 확인하지 못하면 시스템 선택 대기를 제안한다.
- **검증:** 독립 완료 조건만 별도 문제로 나눈다. 한 현상의 여러 원인 후보를 별도 문제로 단정하지 않는다. 사용자 해결 응답의 대상이 불명확하면 임의로 다른 티켓을 완료하지 않는다.

### Knowledge

- **입력:** 현재 문제·표준 요청·이미 확인한 사실, 검색 목적과 서버가 정한 접근 범위.
- **결과 제안:** 허용된 가이드·정책·런북·승인 사례의 근거·버전, Vector 유사 결과, 필요한 Neo4j 관계, 가능 원인·반대/미확인 정보·다음 확인 질문·안내 초안.
- **검색:** 직원에게는 공개된 직원 지식만 제공하고 담당자 내부 자료는 허용된 담당자에게만 제공한다. 두 저장소 반영이 완료되지 않은 사례 버전은 어떤 경로에서도 검색 공개하지 않는다.
- **검증:** 검색 0건과 검색 오류를 분리한다. 과거 사례·그래프 의존 관계를 현재 원인으로 확정하지 않는다. Mock 결과를 실제 운영 관측으로 표현하지 않는다. 조회한 문서의 지시가 권한이나 서비스 정책을 변경할 수 없다.

### Ticket

- **입력:** 문제별 표준 요청, 확인 사실·미확인 항목·허용 근거, 지원 시스템 목록과 서버가 제공한 기존 작업/티켓 결과.
- **결과 제안:** 문제별 제목·내용·처리 유형·책임 시스템 후보·판단 근거. 문의 원문과 보고 시스템은 유지한다. 유효한 제안을 만들 수 없으면 부족한 정보와 미확정 사실을 반환한다.
- **책임 시스템:** 대화·근거가 공통 로그인을 처리 대상으로 뒷받침하면 공통 로그인 후보를 제안한다. 증거가 부족하면 확인된 보고 시스템을 최초 조사 대상으로 제안한다.
- **검증:** Python은 실제 ticketId·담당자 배정 성공을 만들어내지 않는다. Spring이 문제·버전·지원 시스템·현재 주담당자·기존 저장을 검증한다. 저장 미확인이면 신규 생성하지 않는다.

### Curator

- **입력:** 서버에 저장된 담당자 처리·검증 기록, 연결 문의·원문, 기존 지식·관계 스키마와 접근 범위.
- **결과 제안:** 사례 초안, 원문·처리 기록과의 출처, 확인된 원인/미확인, 실제 조치·검증 결과·적용 조건, 중복 후보와 기존 시스템·기능·증상 연결 후보.
- **검증:** 새로운 개념·관계 유형을 임의 게시하지 않는다. 사례 초안 작성은 문의자 확인·담당자 승인·두 저장소 반영과 별개다. 실행 실패는 저장된 처리 기록이나 업무 완료를 취소하지 않는다.
- **공개:** 문의자 확인과 해당 시스템 담당자 승인, 같은 승인 버전의 Vector·Neo4j 반영 성공 전 검색 공개하지 않는다. 담당자에게 적용 시스템·버전·조건을 전달하고 미확인은 추정하지 않는다.

## 실행·검색·도구 오류

| 상황 | 역할 또는 실행기의 처리 | 업무 결과 |
|---|---|---|
| 정보 부족 | 확인된 사실·미확인과 필요한 질문을 구분한다. | 질문 한도와 시스템 확인 상태를 서버가 검증한다. |
| 권한 없는 자료/도구 | 접근을 거부하고 다른 경로로 우회하지 않는다. | 내부 자료를 문의자에게 반환하지 않는다. |
| 검색 0건 | 정상 검색의 근거 부족을 반환한다. | 원인을 만들지 않고 안내·담당자 연결을 검토한다. |
| 검색·Mock 조회 오류 | 오류와 조회 불가를 표시한다. | 오류를 정상 상태·검색 0건·확정 원인으로 바꾸지 않는다. |
| 모델 형식 오류·시간/예산 초과 | 실행기는 안전한 실패와 잔여 예산에 따른 처리 여부를 반환한다. | 성공한 기존 업무·기록은 유지한다. |
| 이전 문의 버전의 늦은 결과 | 현재 문의 버전과 비교해 미반영 결과를 차단한다. | 이미 저장된 메시지·티켓은 삭제하지 않는다. |
| 티켓 저장 결과 미확인 | Spring의 결과 확인을 기다린다. | 새 티켓을 중복 생성하거나 성공/실패를 추정하지 않는다. |
| 사례 반영 일부 실패 | 저장소별 승인 버전 상태를 확인하고 원인을 수정한다. | 사례는 양쪽 검색에서 비공개. 승인·업무 기록은 유지한다. |

## 통합 계약과 완료 조건

1. 각 역할의 요청·결과에 필요한 문맥·식별자·선택 필드·근거·실패 형식을 담당자 간 합의한다. AI 내부 역할 계약과 Spring↔AI 외부 wire 계약은 구분한다.
2. 사용자·시스템·권한·문서/사례 버전과 기존 성공 업무는 서버가 제공·검증한다. 임시 문제 참조를 실제 DB 키나 저장 성공으로 사용하지 않는다.
3. 테스트용 Fake는 역할 교체·오류·경로 검증에 사용한다. 실제 모델·검색·Spring DB 저장 및 문의자 확인 E2E 완료의 근거는 아니다.
4. 사례 생성·승인·반영의 상태는 실제 서버 결과로 확인한다. 역할 호출 결과만으로 사례 공개를 확정하지 않는다.
5. 모델·임베딩·시간·호출·비용 한도와 서비스 인증은 실제 선택·구현·검증이 남았다. 코드에 존재하는 과거 예산을 새 제품 목표로 재확정하지 않는다.

## hops-system-1 실행 계약안

상태: 2026-10-31 팀 리뷰용 구체 제안. 위 역할의 제품 책임은 유지하며, 이 절이 새로운 서비스 간 전송·검증의 정본이다. 기존 agent-1 Python 모델·Fake endpoint는 그대로 보존한다. 현재 구현·팀 승인과 구분한다.

실제 필드 타입·nullable·조건부 필수성과 wire 형식은 [JSON Schema](hops-system-1.schema.json), 내부 State의 작성/병합/수명과 적용 조건은 [내부 State 계약](#내부-state와-wire-검증-계약), 가상 전송 예시는 [공용 wire 예시](../../specs/mocks/hops-system-1.examples.json)를 정본으로 한다. 아래 표는 역할·호출 경계 요약이며 Entity·외부 DTO·내부 State를 하나의 객체로 공유하지 않는다.

### 외부 전송·인증·생명주기

| 호출 | 요청·응답 | 규칙 |
|---|---|---|
| POST /internal/v2/runs | AiRunRequest → 202 AiAccepted | Spring 발급 runId를 멱등 키로 사용. 동일 ID·같은 payload는 기존 실행, 다른 payload는 409 |
| GET /internal/v2/runs/{runId} | AiRunSnapshot | 진행·종료·최종 결과 재확인. AI 프로세스 재시작으로 확인 불가면 UNKNOWN |
| GET /internal/v2/runs/{runId}/events | SSE progress, answer.delta, result | Python→Spring 전용. seq 단조 증가·중복 제거; final result 후 진행 무시 |
| POST /internal/v2/runs/{runId}/cancel | 202 취소 접수 | 최선 노력 취소. Spring의 최신 입력 버전 검증은 별도로 항상 수행 |

모든 호출은 환경의 AI_SERVICE_TOKEN을 사용하는 서비스 Bearer 인증이다. 사용자 JWT·쿠키·API 키는 payload에 넣지 않는다. 운영은 접근이 제한된 TLS 경로, 로컬 개발만 loopback HTTP를 허용하는 제안이다. 인증 실패는 401, runId 없음은 404, 잘못된 payload는 422, 과부하는 429와 Retry-After다. 내부 원시 오류는 브라우저에 전달하지 않는다.

실행 상태가 AI 프로세스 메모리에만 존재해 사라진 경우 성공으로 만들지 않는다. Spring은 업무 DB의 runId·입력 버전·기존 저장 결과를 보존하고 UNKNOWN/안전한 실패로 확인한다. 재분석은 새 runId이며 기존 성공 티켓·작업 ID를 전달한다. Python이 업무 저장을 재실행하지 않는다.

### 외부 필드

| 타입 | 필수 필드 | 의미 |
|---|---|---|
| AiRunRequest | contractVersion,runId,kind,inquiryId,inquiryVersion,problemIds,messages,problems,systems,accessContext,existingWorkItems,workRecord,expiresAt,limits,modelProfileId | contractVersion=hops-system-1. kind=INQUIRY_ANALYSIS 또는 CASE_DRAFT. CASE_DRAFT에만 workRecord가 있고 다른 kind는 null |
| AccessContext | actorId,purpose,systemIds,allowedDocumentScopes,policyRevision | Spring이 만든 최소 권한. purpose=EMPLOYEE_HELP 또는 SYSTEM_WORK 또는 CASE_DRAFT. 모델이 확장하지 못함 |
| ExecutionLimits | maxModelCalls,maxInputTokens,maxOutputTokens,maxToolCalls,maxRetries,maxCostUsd | 모두 양수, maxRetries만 0 허용. 전체 실행의 누적 한도 |
| AiAccepted | contractVersion,runId,status,eventsPath | status=QUEUED. 경로는 허용된 AI 서버 origin 안의 상대 경로만 허용 |
| AiRunSnapshot | contractVersion,runId,status,lastSeq,result,error | result/error는 조건부 nullable. status=QUEUED/RUNNING/SUCCEEDED/FAILED/TIMED_OUT/SUPERSEDED/UNKNOWN |
| AiResult | contractVersion,runId,kind,inquiryId,inquiryVersion,status,answer,problemProposals,questions,guide,ticketProposals,caseProposal,evidence,error | kind·종료 상태별 조합은 JSON Schema. 제안 성공이 DB 저장 성공은 아님 |

시각·ID·입력은 신규 데이터 계약의 공통 표현을 따른다. 없는 값은 null, 결과 없는 목록은 []로 반환한다. 필수 필드 누락·알 수 없는 시스템·실재하지 않는 근거는 Spring/실행기의 검증 오류다. Spring은 원문과 버전을 재검증하고 유효한 제안만 업무 저장에 사용한다. 내부 역할 이름·메모리 객체를 외부 wire DTO로 그대로 노출하지 않는다.

### 역할별 출력과 적용 검증

| 역할 | 입력·출력의 구체 경계 | 검증 |
|---|---|---|
| Supervisor | 현재 허용 행동과 전문 결과 → nextAction=CALL_ROLE/ASK_USER/PROVIDE_GUIDE/PROPOSE_TICKETS/FINISH, role nullable, 공개 요약 | 전문 역할은 INQUIRY/KNOWLEDGE/TICKET/CURATOR. runtime이 kind별 허용 경로·전체 예산 강제 |
| Inquiry | ProblemProposal(problemRef,summary,intent,processingType,reportedSystemCandidateId,facts,assumptions,unknowns,details), QuestionProposal(problemRef,bundleRef,text,requestedFields) | 기존 문제는 UUID, 신규는 new: 접두 참조. bundleRef는 같은 출력의 재전달에도 유지. remainingQuestions를 넘는 질문 거부 |
| Knowledge | 검색 목적·권한·표준 요청 → SearchResult와 조건부 원인 후보·다음 확인 항목 | EMPTY와 ERROR 구분. 확인되지 않은 원인을 사실·장애로 확정하지 않음 |
| Ticket | TicketProposal(problemRef,title,processingType,responsibleSystemCandidateId,evidenceIds,standardRequestSnapshot) | 담당자·티켓 번호·저장 성공 필드 없음. Spring이 PRIMARY와 유일 작업 검증 |
| Curator | 원 WorkRecord·확인 요청·허용 기존 엔티티 → CaseProposal(reusable,title,action,verification,applicability,limitations,appliesToSystemVersion,accessScope,sourceMessageIds,entityLinks) | reusable=false는 사례 생성 없음. 신규 관계 유형·새 인물/시스템 생성 제안 거부 |

GuideProposal은 messageText,evidenceIds,problemRef를 가지며 승인된 EMPLOYEE 근거가 실제 존재해야 한다. 저장된 AI 안내 메시지를 만든 뒤 Spring이 GUIDE 확인 요청을 생성한다. AI의 '해결됐습니다' 문구를 문의자 확인으로 저장하지 않는다.

Inquiry의 sourceMessageIds·evidenceIds는 Spring이 제공했거나 실제 검색에서 반환된 참조만 가능하다. 담당자 보완 대화의 대상이 불명확하면 확인 필요를 제안하고 임의의 티켓에 복사하지 않는다. Curator는 INQUIRY_ANALYSIS의 매 메시지 경로에 넣지 않고 검증 완료 WorkRecord의 outbox로 CASE_DRAFT를 실행한다.

### 검색·관계 스키마 제안

검색 결과는 [신규 KnowledgeEvidence/SearchResult](../../specs/contracts/DATA_TYPES.md#지식사례반영)를 사용한다. Vector와 Neo4j는 후보 검색이며 Spring이 문서/CASE 버전의 승인·공개·현재 ACL을 다시 검증한다. 내부 Knowledge가 검색 저장소를 직접 호출하더라도 서버의 권한 필터를 통과하지 않은 원문을 모델·직원에게 제공하지 않는다. 한 경로라도 실패하면 해당 검색은 ERROR로 기록하고 정상 0건과 구분한다.

| 허용 노드 | 식별·속성 |
|---|---|
| System, Feature | 시스템/기능의 업무 DB UUID. 담당자 권한 정본으로 사용하지 않음 |
| Symptom, Cause, Check | 검수한 카탈로그 ID·label·sourceDocumentId·sourceVersion·accessScope |
| Case | caseId와 caseVersion의 복합 키·systemId·accessScope·승인 버전 출처 |

허용 방향은 System-HAS_FEATURE→Feature, System-DEPENDS_ON→System, Feature-SHOWS_SYMPTOM→Symptom, Symptom-MAY_HAVE_CAUSE→Cause, Cause-CHECKED_BY→Check, Case-APPLIES_TO→System, Case-ADDRESSES→Symptom, Case-USES_CHECK→Check다. 모든 관계는 출처 문서/버전 또는 승인 CASE 버전을 가진다. Curator는 기존 엔티티 ID에 CASE를 연결하며 임의 label·relationship·Cypher를 실행하지 않는다. 조회는 파라미터 바인딩한 읽기 전용 허용 템플릿, 최대 2홉·50노드·100관계·3초의 리뷰 기준이다. 관계는 원인 확정이 아니다.

### 실행 제한·진행·실패

expiresAt은 Spring이 정한 실행 전체 기한이며 역할 전환 시 다시 시작하지 않는다. maxModelCalls·토큰·도구·비용·재시도는 모든 역할에 걸쳐 누적한다. 형식 오류 재시도도 같은 한도에서 계산한다. 공급자 응답이 timeout 뒤 도착해도 기한·현재 입력 버전을 다시 검사한다.

실제 OpenAI 모델·임베딩·Vector 제품·호출/토큰/비용 수치는 [QUESTIONS의 적용 전 선정 항목](../../specs/QUESTIONS.md#적용-전-선정-항목)에 둔다. 선정 manifest의 modelProfileId·모델·가격 기준일·각 한도가 없으면 real 모드를 시작하지 않는다. 기존 코드의 과거 제한값을 새 계약 확정값으로 복사하지 않는다. API 키는 AI 서버 환경에만 둔다.

AiEvent는 contractVersion/runId/seq/eventType/payload envelope다. progress payload는 role/displaySummary, answer.delta는 messageRef/chunkSeq/text, result는 AiResult다. seq는 실행 전체에서 증가하고 final result는 한 논리 결과로 고정한다. Spring은 원본 AI seq와 별도로 공개 eventId를 발급한다. seq 누락·연결 종료 시 snapshot을 재확인하며 업무 저장을 AI 이벤트 재생으로 재실행하지 않는다.

안전한 내부 오류는 INVALID_OUTPUT, SEARCH_UNAVAILABLE, TOOL_UNAVAILABLE, MODEL_UNAVAILABLE, DEADLINE_EXCEEDED, BUDGET_EXCEEDED, AUTH_CONTEXT_INVALID, SUPERSEDED다. 업무 오류·공개 HTTP 코드는 WIRE_CONTRACT에서 변환한다. 모델 내부 사고 과정·원시 프롬프트·비밀값은 진행/오류에 포함하지 않는다.

## 내부 State와 wire 검증 계약

상태: PRD 1.44 팀 리뷰용 상세 제안. 기존 runtime·Entity·DTO를 이 문서 변경으로 구현하지 않는다. State 설명은 이 문서에 통합하고 외부 형식의 정본은 JSON Schema에 둔다.

### S1. 구분할 세 가지 데이터

| 구분 | 정본·소유자 | 수명·외부 노출 |
|---|---|---|
| 업무 Entity | Spring·PostgreSQL | 문의·문제·티켓·처리·확인·승인·공개. 실제 저장 결과의 정본 |
| 외부 wire DTO | Spring↔AI 계약 | 검증된 실행 입력·진행·최종 제안. Entity/ORM 객체나 사용자 JWT를 직접 전송하지 않음 |
| 내부 SupervisorState | AI 실행기 | 한 run의 작업 상태. 전문 역할 출력·근거·예산을 병합. 브라우저나 Spring에 통째로 반환하지 않음 |

내부 State를 바꾸는 것과 업무 상태를 바꾸는 것은 별개다. AI의 TICKET 제안 성공이 Ticket Entity 저장이나 시스템 PRIMARY 배정을 뜻하지 않는다. 최종 결과를 Spring이 검증·저장한 뒤 공개 응답을 만든다.

### S2. 외부 wire 스키마

[hops-system-1.schema.json](hops-system-1.schema.json)은 요청·수락·snapshot·진행/결과 이벤트와 역할별 제안의 JSON Schema Draft-07 리뷰안이다. 최상위 oneOf로 메시지 종류를 판별한다. [공용 검토 예시](../../specs/mocks/hops-system-1.examples.json)는 가상 입력·출력이며 실행 Mock 대체물이 아니다.

- contractVersion은 hops-system-1이다. 모든 객체는 additionalProperties=false, 키는 필수, 모르는 nullable 값은 null이다. 선택적 의미를 키 생략으로 표현하지 않는다.
- UUID는 소문자 하이픈, 시각은 UTC Z, 정수·사용자 입력은 공통 DATA_TYPES의 규칙을 따른다. 배열 내 ID 유일성·실제 FK·원문 구간·권한·예산 누적은 JSON Schema 검사에 더해 실행기/Spring이 검증한다.
- INQUIRY_ANALYSIS 요청의 workRecord는 null이고 CASE_DRAFT에는 검증 완료 WorkRecordContext가 필수다. CASE_DRAFT는 원 기록·그 문제의 관련 원문만 받는다.
- InputProblem은 내부 상담 입력 DTO다. 문의자 화면의 Problem 투영과 구분하며 내부 StandardRequestSnapshot을 담을 수 있다. 이 객체를 InquiryResult로 그대로 반환하지 않는다.
- 동일 runId·동일 입력 digest 재전송은 같은 실행이다. 다른 입력이면 409다. 같은 runId에서 다른 최종 결과가 오면 INVALID_OUTPUT으로 차단하고 이미 저장된 업무를 유지한다.
- seq는 한 실행 전체의 이벤트 순번이며 chunkSeq는 messageRef별 답변 조각 순번이다. 두 값을 혼용하지 않는다. 공개 SSE eventId는 Spring이 별도 발급한다.

#### Spring에서 AI로

| 타입 | 입력 내용·검증 |
|---|---|
| AiRunRequest | 실행/문의/입력 버전·kind·대상 문제 ID, 허용 메시지/기존 문제/작업, 시스템 목록, accessContext, nullable 원 처리 기록, expiresAt·limits·modelProfileId |
| InputMessage | 원 messageId/revision/sequenceNo·sender·허용된 content·problemIds·시각. CASE_DRAFT는 관련 구간만 투영 |
| InputProblem | DB problemId·의도·처리 유형·보고 시스템/기능·상태·질문 수·표준 요청 스냅샷. questionCount는 Spring 값 |
| WorkRecordContext | 실제 기록 ID·티켓/문제/시스템·종류·조치/검증·확인 요청/상태. Curator에게 해결 확인 전 PENDING도 전달 가능 |
| AccessContext | actorId·purpose·systemIds·allowedDocumentScopes·policyRevision. Spring이 생성하며 모델은 수정 불가 |

메시지 목록이 길면 원문 저장을 자르지 않고 실행용 범위를 서버가 선택한다. 표준 요청과 출처 ID를 유지하고 토큰 한도를 넘으면 안전한 실패로 반환한다. AI가 접근 범위를 넓혀 과거 원문을 임의 조회하지 않는다.

#### AI에서 Spring으로

| 타입 | 반환 내용·검증 |
|---|---|
| AiAccepted | 같은 runId·QUEUED·허용 origin 안 상대 eventsPath. 실행 접수이며 업무 저장 성공 아님 |
| AiRunSnapshot | 실행 status·lastSeq·nullable result/error. UNKNOWN은 원격 실행 확인 불가 |
| AiEvent | contractVersion·runId·seq·eventType·payload. progress/answer.delta/result의 판별 union |
| AiResult | run/문의/버전/kind·종료 status·답변 제안·문제/질문/가이드/티켓/사례 제안·검색 근거·안전한 오류 |

SUCCEEDED INQUIRY_ANALYSIS는 answer가 있고 caseProposal=null이다. SUCCEEDED CASE_DRAFT는 caseProposal이 있고 answer/guide=null, 상담 문제·질문·티켓 목록은 []다. reusable=false면 사례 본문은 null이며 새 사례를 만들지 않는다. 실패/시간 초과/대체된 결과는 제안 목록을 비우고 error를 반환한다. 부분 성공한 전문 결과는 내부 추적에 남길 수 있지만 실패한 전체 실행의 새 저장 제안으로 적용하지 않는다. 이전 실행에서 이미 commit한 업무는 유지한다.

### S3. SupervisorState 필드와 변경 권한

아래는 목표 내부 State 설계다. Python TypedDict/Pydantic 클래스 작성은 별도 구현이며 기존 다섯 필드(data/intake/route/ticket/outcome)를 필드명만 바꿔 호환시키지 않는다.

| 필드 | 자료형·초기값 | 쓸 수 있는 주체·병합 규칙 |
|---|---|---|
| input | AiRunRequest·초기 입력 고정 | ingress 검증기만 생성. 모든 역할에서 읽기 전용 |
| phase | QUEUED/RUNNING/FINALIZING/SUCCEEDED/FAILED/TIMED_OUT/SUPERSEDED | 실행기만 허용 전이로 변경 |
| activeRole | SUPERVISOR/INQUIRY/KNOWLEDGE/TICKET/CURATOR 또는 null | 실행기만 변경, 역할 완료 후 null |
| nextAction | CALL_ROLE/ASK_USER/PROVIDE_GUIDE/PROPOSE_TICKETS/FINISH 또는 null | Supervisor 제안 → 실행기 검증 후 반영 |
| problems | Map<problemRef,ProblemProposal>·기존 문제에서 초기화 | Inquiry 출력 검증기. UUID는 기존 유지, 신규는 new: 참조 |
| questions | Map<(problemRef,bundleRef),QuestionProposal>·빈 map | Inquiry 출력 검증기. 동일 키·같은 내용은 no-op, 다른 내용은 오류 |
| evidence | Map<evidenceId,KnowledgeEvidence>·허용 스냅샷 | Knowledge 검증기. 같은 ID 다른 내용/버전은 오류·별도 ID 요구 |
| guide | GuideProposal 또는 null | Knowledge 결과를 Supervisor/실행기가 검증해 선택 |
| ticketProposals | Map<problemRef,TicketProposal>·빈 map | Ticket 검증기. 같은 실행 내 최종 유효 제안으로 교체, DB 상태는 쓰지 않음 |
| caseProposal | CaseProposal 또는 null | CASE_DRAFT의 Curator 검증기만 변경 |
| roleResults | Map<invocationId,검증된 역할 결과>·빈 map | 실행기. 같은 호출 ID 재전달은 중복 저장하지 않음 |
| pendingCalls | Set<invocationId>·빈 set | 실행기만 예약/해제. 역할이 다른 역할을 직접 호출하지 않음 |
| usage | calls/inputTokens/outputTokens/toolCalls/retries/costUsd 누적 | 실행기·도구 wrapper만 원자적으로 증가 |
| eventSeq | int·0 | 이벤트 발행기만 증가. 역할 출력의 임의 seq는 신뢰하지 않음 |
| cancellation | cancelled:boolean,reason:null 또는 안전한 코드 | runtime/서버 취소 경로만 변경 |
| error | AiError 또는 null | 실행기만 최초 종료 오류를 고정 |
| finalResult | AiResult 또는 null | FINALIZING에서 한 번 고정. 이후 새로운 제안/진행을 반영하지 않음 |

Map은 역할끼리 공유해서 직접 수정하는 가변 객체가 아니다. 노드는 검증된 patch를 반환하고 실행기가 키별 병합한다. 병렬 실행을 사용한다면 phase/input/usage/eventSeq 같은 단일 작성자 필드에 last-write-wins를 쓰지 않는다. 비용은 호출 직전 예약하고 결과 후 실제 사용량으로 정산하며 취소/실패 호출도 실제 발생 비용을 남긴다.

roleResults에는 구조화된 결과·오류·근거 참조만 저장한다. 모델 내부 사고 과정·토큰·API 키·서비스 Bearer를 포함하지 않는다. 원문은 input의 접근 범위 밖으로 확장하지 않는다.

### S4. 허용 경로와 State 전이

| 현재 phase | 사건 | 다음 상태·검사 |
|---|---|---|
| QUEUED | 유효 입력·예산·기한으로 실행 시작 | RUNNING |
| RUNNING | Supervisor가 CALL_ROLE 제안 | kind·허용 역할·남은 예산 검증 후 역할 호출. phase 유지 |
| RUNNING | 검증된 상담 답변/질문/티켓 또는 사례 제안 확정 | FINALIZING |
| FINALIZING | 스키마·출처·참조·전체 한도 검증 성공 | SUCCEEDED·finalResult 고정·result 이벤트 |
| QUEUED/RUNNING/FINALIZING | 기한 초과 | TIMED_OUT·안전한 오류·새 제안 없음 |
| QUEUED/RUNNING/FINALIZING | 입력 버전 대체·취소 | SUPERSEDED·후속 조각/저장 제안 차단 |
| QUEUED/RUNNING/FINALIZING | 모델/도구/형식/권한/예산 실패 | FAILED·기존 Spring 저장 업무 유지 |
| 종료 상태 | 늦은 진행·동일 결과 재전달 | 진행 무시·같은 최종 결과 재확인만 |

INQUIRY_ANALYSIS에서 Supervisor는 Inquiry·Knowledge·Ticket을 필요한 순서로 선택한다. Curator는 허용하지 않는다. CASE_DRAFT에서는 Curator와 필요한 읽기 전용 Knowledge만 허용한다. 모든 역할을 매번 호출하거나 고정 순서로 성공을 강제하지 않는다. 한도의 정본은 input.limits/expiresAt이며 역할 전환·재시도에서 초기화하지 않는다.

questionCount는 내부 State가 임의 증가시켜 Spring에 확정값으로 반환하지 않는다. 기존 문제별 `5-questionCount` 이내의 새 번들만 허용하며 Spring의 질문 메시지 commit 때만 집계한다. 기존 STORED 작업의 문제에는 새 티켓 제안을 허용하지 않는다. 기존 문제 ID/작업 ID/실제 담당자·저장 여부를 모델이 교체하지 못한다.

### S5. 저장·재시작·적용 경계

- Spring은 AiRun 입력·상태·최종 결과 digest와 실제 업무 결과를 보존한다. AI의 내부 State는 한 run용이며 업무 DB 정본을 대체하지 않는다.
- AI 실행 접수 기록(runId·입력 digest·상태·최종 결과/seq)은 RunLedger로 중복을 차단해야 한다. 실제 저장 방식은 담당자가 선정하되 재시작 후 같은 ID를 새 모델 호출로 무조건 실행하는 메모리 전용 방식은 real 통합 완료 기준이 아니다.
- MVP는 중간 그래프 checkpoint에서 자동 재개하지 않는다. 접수됐지만 실행 상태를 복원할 수 없으면 snapshot UNKNOWN으로 반환한다. Spring은 확인 불가를 성공으로 저장하지 않고 공개 Run을 안전한 실패로 처리해 새 runId 재분석을 제공한다. 기존 workItem·성공 티켓은 유지한다.
- 최종 결과 적용은 Spring의 최신 inquiryVersion·activeRunId·policyRevision·원 출처 검증 후 수행한다. 동일 runId·최종 digest는 한 번만 적용하고 같은 ID·다른 결과는 거부한다.
- 상담 AI는 티켓 저장 commit을 기다리는 무제한 내부 루프를 만들지 않는다. 최종 제안을 반환한 뒤 Spring이 생성/배정 결과를 확인해 사용자 결과를 구성한다. 진행 문구에서 저장 성공을 선행 확정하지 않는다.
- Curator run은 해당 원 WorkRecord만 대상으로 하고 상담 currentRun과 분리한다. 사용자 미해결 응답·권한 변경이 뒤늦게 발생해도 초안은 업무 이력으로만 유지하고 승인·공개 검증을 다시 통과해야 한다.

### S6. 계약 완료 조건

| 검사 | 확인할 내용 |
|---|---|
| JSON Schema | 정상 request/result/event와 잘못된 kind·필드·enum·결과 조합 거부 |
| State 병합 | 다른 역할의 input/권한/예산 변경 차단, 중복 patch·근거 충돌·종료 후 patch 처리 |
| 서비스 통신 | 같은 run 재접수·다른 입력 충돌·SSE 누락/snapshot·재시작 UNKNOWN·취소 |
| Spring 적용 | 이전 버전·중복 결과·가짜 시스템/근거·권한 변경·기존 성공 보존 |
| 업무 순환 | 질문/배정 → 실제 처리/확인 → Curator/승인 → 같은 버전 양쪽 반영/재검색 |

스키마/예시 검사는 실제 Java/JPA·Pydantic·LangGraph 구현·모델 품질·Spring 저장·E2E를 대신하지 않는다. 생산자와 소비자가 같은 스키마 예제를 통과하고 상태·경합·권한 검사를 함께 검토해야 계약 적용을 완료한다.
