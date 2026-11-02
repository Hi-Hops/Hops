# 에이전트 공통 입출력 계약 — PRD 1.44

상태: hops-system-1 계약 · 역할/State/wire/저장 정의 · LLM·임베딩 모델·비용·구체 실행 설정값은 제외. [PRD](../../docs/PRD.md), [API 설계](../../docs/API.md), [목표 공통 계약](../../specs/contracts/TARGET_CONTRACT.md)을 따른다. 현재 Python은 실행 wire 검증·RunLedger·명시 활성화한 서비스 인증/저장된 실행 조회를 제공한다. 역할 실행·모델·검색·실행 SSE와 Spring 업무 통합은 미구현이다. [내부 의미 타입](../../ai/src/hops_ai/contracts/domain.py)과 [AI 서버 안내](../../ai/README.md)를 확인하며 옛 agent-1 실행 모델은 활성 코드에서 제거됐다.

AI·검색·도구와 외부 출력은 [보안 개발 명세](../../docs/SECURITY.md)의 해당 SEC 요구사항을 적용한다. Markdown 형식 일치만으로 현재 권한·안전한 표시·저장 성공을 확정하지 않는다.

## 역할과 최소 입출력

루트 [AGENTS.md](../../AGENTS.md) → [개발·통합 안내](../../docs/AGENT_DEVELOPMENT.md) → 이 문서 순서로 읽는다. 아래에서 역할별 입력·결과·실패 의미를 확인하고, [호출과 진행 예시](#호출과-진행-예시)에서 서비스 연결 방법을 확인한다. 정확한 필수 필드·자료형·조건은 이 문서의 전송 형식과 공통 데이터 계약을 따른다.

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

실행·문의·문제·작업·사례의 식별자/버전·역할별 필드·실패·인증·예산·이벤트·중복 방지·공개 검증은 이 문서 신규 계약·결정 대장의 채택값을 사용한다. 이 문서는 새 역할이 구현되거나 실제 통합이 검증됐다는 뜻이 아니다.

## 공통 실행 문맥과 결과 — 설계 제안

여기서 말하는 항목은 역할 간 전달해야 할 **의미**다. 실제 Python 내부 클래스는 별도이며 외부 wire·필수성의 구체 제안은 [실행 계약안](#hops-system-1-실행-계약안), 업무 데이터는 [신규 데이터 계약](../../specs/contracts/DATA_TYPES.md#신규-계약-hops-system-1)을 따른다. 실제 구현은 별도다.

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

문의 의도는 사용자가 무엇을 원하는지, 내부 처리 유형은 조직이 어떤 업무로 처리할지를 뜻한다. 예를 들어 파일이 열리지 않는 문의는 OBSERVED_SYMPTOM/INVESTIGATION, 일괄 다운로드 기능 추가는 FEATURE_REQUEST/CHANGE_REQUEST로 구분한다. AI가 사용자의 '장애' 표현만으로 INCIDENT를 확정하지 않는다. 의도·처리 유형의 코드 목록은 [공통 데이터 계약](../../specs/contracts/DATA_TYPES.md#enum)을 따른다.

질문 출력에는 대상 문제·bundleRef·질문 내용·확인할 항목을 담고, questionCount는 Spring이 질문 메시지와 묶음을 저장할 때 올린다. 같은 묶음의 재전달은 다시 세지 않는다. 5회 후 시스템이 확인됐으면 누락 정보를 남겨 담당자에게 전달하고, 미확인이면 WAITING_SYSTEM을 보존한다. 정보 부족으로 질문·대기를 제안한 결과는 기술 실패와 구분한다.

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

보고 시스템은 사용자가 현상을 경험했다고 확인한 시스템이고 책임 시스템은 티켓을 맡아 조사·처리할 시스템이다. 전자결재에서 로그인 실패를 경험한 경우 보고 시스템을 전자결재로 유지하면서, 허용된 근거가 있으면 책임 시스템 후보로 공통 로그인을 제안할 수 있다. 시스템 간 연결 관계만으로 원인을 확정하지 않는다.

StandardRequest에는 요약, confirmedFacts·assumptions·unknowns, 유형별 details, 책임 시스템 후보·근거와 관련 지식을 보존한다. '동료도 같은 현상이라고 사용자가 보고함'은 출처가 있는 사용자 진술이며 전체 직원의 장애가 관측됐다는 뜻은 아니다. 실제 원인·영향 범위는 확인되지 않았다면 미확인으로 남긴다. Inquiry의 facts와 저장된 표준 요청의 confirmedFacts는 검증·변환 경계를 거치며 같은 객체를 그대로 공유하지 않는다.

### Curator

- **입력:** 서버에 저장된 담당자 처리·검증 기록, 연결 문의·원문, 기존 지식·관계 스키마와 접근 범위.
- **결과 제안:** 사례 초안, 원문·처리 기록과의 출처, 확인된 원인/미확인, 실제 조치·검증 결과·적용 조건, 중복 후보와 기존 시스템·기능·증상 연결 후보.
- **검증:** 새로운 개념·관계 유형을 임의 게시하지 않는다. 사례 초안 작성은 문의자 확인·담당자 승인·두 저장소 반영과 별개다. 실행 실패는 저장된 처리 기록이나 업무 완료를 취소하지 않는다.
- **공개:** 문의자 확인과 해당 시스템 담당자 승인, 같은 승인 버전의 Vector·Neo4j 반영 성공 전 검색 공개하지 않는다. 담당자에게 적용 시스템·버전·조건을 전달하고 미확인은 추정하지 않는다.

Spring이 VERIFIED_RESOLUTION 또는 IMPLEMENTED_VERIFIED 기록을 저장한 뒤 CASE_DRAFT를 예약한다. PROGRESS/FAILED_ATTEMPT와 티켓 없는 가이드 해결은 자동 실행 대상이 아니다. 입력에는 원 처리 기록·관련 문제·티켓·시스템과 허용 원문, 실제 조치·검증·확인된 원인·적용 버전, 해결 확인 요청과 현재 상태를 담는다. 문의자 확인 전 PENDING 상태에서도 초안을 만들 수 있다. reusable=false는 재사용할 사례 없음이라는 정상 결과이며 생성 오류와 구분한다.

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

역할 성공은 유효한 제안 반환, 정보 부족은 추가 확인이 필요한 업무 상태, 기술 실패는 실행 과정의 오류다. Knowledge의 EMPTY와 Curator의 reusable=false는 기술 실패가 아니다. 역할별 내부 결과 타입·모델 출력 스키마는 AI 담당자가 구체화하고, 외부 AiResult·오류·상태에 영향을 주는 변경은 Spring 담당자와 함께 검토한다. 프론트는 Spring이 권한에 맞게 투영한 필드·상태·SSE와 화면 동작을 검토하며 AI 내부 상태를 직접 소비하지 않는다.

## 통합 계약과 완료 조건

1. 각 역할의 요청·결과에 필요한 문맥·식별자·선택 필드·근거·실패 형식을 담당자 간 합의한다. AI 내부 역할 계약과 Spring↔AI 외부 wire 계약은 구분한다.
2. 사용자·시스템·권한·문서/사례 버전과 기존 성공 업무는 서버가 제공·검증한다. 임시 문제 참조를 실제 DB 키나 저장 성공으로 사용하지 않는다.
3. 테스트용 Fake는 역할 교체·오류·경로 검증에 사용한다. 실제 모델·검색·Spring DB 저장 및 문의자 확인 E2E 완료의 근거는 아니다.
4. 사례 생성·승인·반영의 상태는 실제 서버 결과로 확인한다. 역할 호출 결과만으로 사례 공개를 확정하지 않는다.
5. 실행 입력의 기한·누적 한도 형식과 서비스 인증은 신규 실행 계약을 따른다. 구체 LLM·임베딩 모델·차원·단가·예상 비용·금액 예산과 실행 상한의 수치는 이 문서에서 정하지 않는다. 코드에 존재하는 과거 설정을 새 제품 목표로 재확정하지 않는다.

## hops-system-1 실행 계약안

상태: hops-system-1 목표 계약. 위 역할의 제품 책임은 유지하며, 이 절이 새로운 서비스 간 전송·검증의 정본이다. 이 계약은 현재 Python 골격에 아직 연결되지 않았고 옛 agent-1 실행 endpoint는 제공하지 않는다. 현재 구현과 구분한다.

실제 필드 타입·nullable·조건부 필수성과 wire 형식은 [외부 전송 형식](#s2-외부-wire-형식), 내부 State의 작성/병합/수명과 적용 조건은 [내부 State 계약](#내부-state와-wire-검증-계약)을 정본으로 한다. [호출과 진행 예시](#호출과-진행-예시)는 Markdown 안의 코드로 제공한다. 아래 표는 역할·호출 경계 요약이며 Entity·외부 DTO·내부 State를 하나의 객체로 공유하지 않는다.

### 외부 전송·인증·생명주기

| 호출 | 요청·응답 | 규칙 |
|---|---|---|
| POST /internal/v2/runs | AiRunRequest → 202 AiAccepted | Spring 발급 runId를 멱등 키로 사용. 동일 ID·같은 payload는 기존 실행, 다른 payload는 409 |
| GET /internal/v2/runs/{runId} | AiRunSnapshot | 진행·종료·최종 결과 재확인. AI 프로세스 재시작으로 확인 불가면 UNKNOWN |
| GET /internal/v2/runs/{runId}/events | SSE progress, answer.delta, result | Python→Spring 전용. seq 단조 증가·중복 제거; final result 후 진행 무시 |
| POST /internal/v2/runs/{runId}/cancel | 본문 없는 요청 → 202 빈 본문 | 최선 노력 취소. 반복 접수도 202이며 상태는 snapshot으로 확인. Spring의 최신 입력 버전 검증은 별도로 항상 수행 |

위 Spring→AI 호출은 환경의 AI_SERVICE_TOKEN을 사용하는 서비스 Bearer 인증이다. AI→Spring 검색 권한 요청은 별도의 SPRING_SERVICE_TOKEN을 사용한다. 사용자 JWT·쿠키·API 키는 payload에 넣지 않는다. 공용 환경은 접근이 제한된 TLS 경로, 개발은 지정 서비스 origin의 HTTP를 사용한다. 배포 domain과 secret의 입력/검증 규칙은 결정 대장에 고정한다. 인증 실패는 401, runId 없음은 404, 잘못된 payload는 422, 과부하는 429와 Retry-After다. 내부 원시 오류는 브라우저에 전달하지 않는다.

실행 상태가 AI 프로세스 메모리에만 존재해 사라진 경우 성공으로 만들지 않는다. Spring은 업무 DB의 runId·입력 버전·기존 저장 결과를 보존하고 UNKNOWN/안전한 실패로 확인한다. 재분석은 새 runId이며 기존 성공 티켓·작업 ID를 전달한다. Python이 업무 저장을 재실행하지 않는다.

### 외부 필드

| 타입 | 필수 필드 | 의미 |
|---|---|---|
| AiRunRequest | contractVersion,runId,kind,inquiryId,inquiryVersion,answerMessageId,problemIds,messages,problems,systems,accessContext,existingWorkItems,workRecord,expiresAt,limits | contractVersion=hops-system-1. INQUIRY_ANALYSIS의 answerMessageId는 Spring 발급 UUID·workRecord=null. CASE_DRAFT는 answerMessageId=null·workRecord 필수 |
| AccessContext | actorId,purpose,systemIds,allowedDocumentScopes,policyRevision | Spring이 만든 최소 권한. purpose=EMPLOYEE_HELP 또는 SYSTEM_WORK 또는 CASE_DRAFT. 모델이 확장하지 못함 |
| ExecutionLimits | maxModelCalls,maxInputTokens,maxOutputTokens,maxToolCalls,maxRetries | 양의 정수, maxRetries만 0 허용. 전체 실행의 누적 한도 형식이며 구체 수치는 서버 실행 설정에서 관리 |
| AiAccepted | contractVersion,runId,status,eventsPath | status=QUEUED. 경로는 허용된 AI 서버 origin 안의 상대 경로만 허용 |
| AiRunSnapshot | contractVersion,runId,status,lastSeq,result,error | result/error는 조건부 nullable. status=QUEUED/RUNNING/SUCCEEDED/FAILED/TIMED_OUT/SUPERSEDED/UNKNOWN |
| AiResult | contractVersion,runId,kind,inquiryId,inquiryVersion,status,answer,problemProposals,questions,guide,ticketProposals,caseProposal,evidence,error | kind·종료 상태별 조합은 S2의 조건부 규칙. 제안 성공이 DB 저장 성공은 아님 |

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

Inquiry의 sourceMessageIds·evidenceIds는 Spring이 제공했거나 실제 검색에서 반환된 참조만 가능하다. 담당자 보완 대화의 대상이 불명확하면 확인 필요를 제안하고 임의의 티켓에 복사하지 않는다. Curator는 INQUIRY_ANALYSIS의 매 메시지 경로에 넣지 않고 검증 완료 WorkRecord commit 뒤 Spring이 CASE_DRAFT를 호출한다. 영속 이벤트 전달 큐는 MVP에 추가하지 않는다.

### 검색·관계 스키마

검색 결과는 [신규 KnowledgeEvidence/SearchResult](../../specs/contracts/DATA_TYPES.md#지식사례반영)를 사용한다. Vector와 Neo4j는 후보 검색이며 Spring이 문서/CASE 버전의 승인·공개·현재 ACL을 다시 검증한다. 내부 Knowledge가 검색 저장소를 직접 호출하더라도 서버의 권한 필터를 통과하지 않은 원문을 모델·직원에게 제공하지 않는다. 한 경로라도 실패하면 해당 검색은 ERROR로 기록하고 정상 0건과 구분한다.

| 허용 노드 | 식별·속성 |
|---|---|
| System, Feature | 시스템/기능의 업무 DB UUID. 담당자 권한 정본으로 사용하지 않음 |
| Symptom, Cause, Check | 검수한 카탈로그 ID·label·sourceDocumentId·sourceVersion·accessScope |
| Case | caseId와 caseVersion의 복합 키·systemId·accessScope·승인 버전 출처 |

허용 방향은 System-HAS_FEATURE→Feature, System-DEPENDS_ON→System, Feature-SHOWS_SYMPTOM→Symptom, Symptom-MAY_HAVE_CAUSE→Cause, Cause-CHECKED_BY→Check, Case-APPLIES_TO→System, Case-ADDRESSES→Symptom, Case-USES_CHECK→Check다. 모든 관계는 출처 문서/버전 또는 승인 CASE 버전을 가진다. Curator는 기존 엔티티 ID에 CASE를 연결하며 임의 label·relationship·Cypher를 실행하지 않는다. 조회는 파라미터 바인딩한 읽기 전용 허용 템플릿, 최대 2홉·50노드·100관계·3초의 채택 한도다. 관계는 원인 확정이 아니다.

### 실행 제한·진행·실패

expiresAt은 Spring이 정한 실행 전체 기한이며 역할 전환 시 다시 시작하지 않는다. maxModelCalls·토큰·도구·재시도는 모든 역할에 걸쳐 누적한다. 형식 오류는 유효한 저장 제안으로 적용하지 않고 INVALID_OUTPUT으로 처리한다. 공급자 응답이 timeout 뒤 도착해도 기한·현재 입력 버전을 다시 검사한다.

구체 LLM·임베딩 모델·reasoning 설정·벡터 차원·단가·예상 비용·금액 예산과 실행 상한의 수치는 이 계약에서 선정하지 않는다. 모델·과금 설정은 AI 서버에서 별도 관리하며 AiRunRequest에 모델 프로필이나 금액 필드를 두지 않는다. 서버는 요청의 기한·누적 한도를 실행 설정에 대조해 검증한다. 저장·서비스 연결 기준은 [결정 대장](../../specs/QUESTIONS.md#실행검색-기본값)을 따르고 API 키는 AI 서버 환경에만 주입한다.

AiEvent는 contractVersion/runId/seq/eventType/payload envelope다. progress payload는 role/displaySummary, answer.delta는 messageId/chunkSeq/text, result는 AiResult다. messageId는 입력의 answerMessageId와 같아야 하며 임시 new: 참조를 사용하지 않는다. seq는 실행 전체에서 증가하고 final result는 한 논리 결과로 고정한다. Spring은 원본 AI seq와 별도로 공개 eventId를 발급한다. seq 누락·연결 종료 시 snapshot을 재확인하며 업무 저장을 AI 이벤트 재생으로 재실행하지 않는다.

안전한 내부 오류는 INVALID_OUTPUT, SEARCH_UNAVAILABLE, TOOL_UNAVAILABLE, MODEL_UNAVAILABLE, DEADLINE_EXCEEDED, BUDGET_EXCEEDED, AUTH_CONTEXT_INVALID, SUPERSEDED다. 업무 오류·공개 HTTP 코드는 WIRE_CONTRACT에서 변환한다. 모델 내부 사고 과정·원시 프롬프트·비밀값은 진행/오류에 포함하지 않는다.

### 상담 답변과 저장 메시지 연결

이 절은 INQUIRY_ANALYSIS의 적용 방식으로 채택한다. 역할 내부 구현을 바꾸더라도 외부 요청·결과·저장 연결은 유지한다.

- Spring이 run을 만들 때 사용하지 않은 answerMessageId UUID를 발급해 불변 입력에 저장한다. 이 시점에는 빈 AI message 행이나 sequenceNo를 만들지 않는다. 한 run의 최종 AI 메시지는 이 ID 한 건이며 같은 run 재접수·최종 결과 재전달에도 바뀌지 않는다. 새 run은 새 ID를 사용한다. CASE_DRAFT는 null이며 채팅 답변 조각을 발행하지 않는다.
- AiResult.answer는 사용자에게 보여줄 전체 본문의 유일한 정본이다. Supervisor는 공개 가능한 설명, questions[].text와 guide.messageText를 이 본문에 통합한다. Spring과 프론트는 질문·가이드 본문을 별도 말풍선으로 다시 붙이지 않는다. 최종 answer는 1~4,000 코드 포인트이며 자동 절단하지 않는다.
- questions는 문제별 질문 묶음 메타데이터다. 같은 결과에서 문제당 하나만 허용하고 각 text는 answer 안에 정확히 한 번 포함해야 한다. guide가 있으면 guide.messageText도 answer 안에 정확히 한 번 포함하고 승인된 EMPLOYEE 근거를 가져야 한다. 동일 문제에 질문과 가이드 확인 또는 티켓 생성을 동시에 제안하지 않는다. 서로 다른 문제의 질문·가이드는 같은 answer에 함께 담을 수 있다. 참조·본문·남은 질문 횟수 검증 실패는 INVALID_OUTPUT이며 새 답변이나 질문 수를 저장하지 않는다.
- answer.delta는 같은 answerMessageId의 임시 공개 본문만 전송한다. chunkSeq는 해당 메시지에서 1부터 증가하며 공개 SSE의 seq에 대응한다. 내부 역할 결과·도구 원문을 그대로 흘리지 않는다. 조각 발행은 선택 사항이며 없어도 최종 result/snapshot으로 완료한다. 임시 조각과 최종 answer가 다르면 최종 저장 본문으로 교체한다.
- Spring은 SUCCEEDED result의 runId·문의/입력 버전·정책·근거를 검증한 뒤 문의 행 잠금 안에서 신규 문제 참조를 매핑하고 answerMessageId의 AI 메시지·질문 번들/횟수·가이드/확인 요청·문제 상태·결과 적용 표식·공개 Run SUCCEEDED를 한 거래로 저장한다. content=answer, revision=1, message_status=COMPLETED, sequenceNo는 그 거래에서 다음 순번이다. 질문은 메시지 commit 때만 집계하며 GUIDE의 sourceId와 GuideAnswer.messageId는 이 메시지 ID다. 전체 message 본문을 resolution resultSummary로 노출하는 대신 해당 guide.messageText만 사용한다.
- Python의 SUCCEEDED는 제안 완료이고 공개 RunSummary의 SUCCEEDED는 위 답변 저장 완료다. 기존 티켓 저장·배정은 별도 문제별 처리 결과로 유지하며 답변 저장 완료가 티켓 성공을 뜻하지 않는다. 답변 저장 후 message.stored와 INQUIRY의 state.changed를 발행한다. 후속 티켓 저장 실패로 이미 commit한 답변을 지우지 않는다.
- 실패·시간 초과·대체된 run은 새 AI 메시지나 질문 수를 만들지 않는다. 실패 안내는 RunSummary.error의 UI로 표시한다. 진행 중 새 사용자 입력이 저장되면 기존 run을 SUPERSEDED로 표시하고 새 run을 만든다. 최종 적용 시 다시 버전을 검사하며 이미 저장된 이전 대화는 유지한다. UI 수신·저장·복구 순서는 [WIRE 12.8절](../../specs/contracts/WIRE_CONTRACT.md#128-채팅-송수신-완료-규칙)을 따른다.

## 내부 State와 wire 검증 계약

상태: PRD 1.44 목표 계약·State 필드/병합/수명 정의. 기존 runtime·Entity·DTO를 이 문서 변경으로 구현하지 않는다. State와 외부 DTO 형식의 정본은 이 Markdown 문서다.

### S1. 구분할 세 가지 데이터

| 구분 | 정본·소유자 | 수명·외부 노출 |
|---|---|---|
| 업무 Entity | Spring·PostgreSQL | 문의·문제·티켓·처리·확인·승인·공개. 실제 저장 결과의 정본 |
| 외부 wire DTO | Spring↔AI 계약 | 검증된 실행 입력·진행·최종 제안. Entity/ORM 객체나 사용자 JWT를 직접 전송하지 않음 |
| 내부 SupervisorState | AI 실행기 | 한 run의 작업 상태. 전문 역할 출력·근거·예산을 병합. 브라우저나 Spring에 통째로 반환하지 않음 |

내부 State를 바꾸는 것과 업무 상태를 바꾸는 것은 별개다. AI의 TICKET 제안 성공이 Ticket Entity 저장이나 시스템 PRIMARY 배정을 뜻하지 않는다. 최종 결과를 Spring이 검증·저장한 뒤 공개 응답을 만든다.

### S2. 외부 wire 형식

요청·수락·snapshot·진행/결과 이벤트와 역할별 제안은 이 절의 필드·타입·조건부 규칙을 따른다. 검증용 Schema와 샘플 JSON은 개인 로컬 자료이며 PR에 포함하지 않는다. 팀원은 이 Markdown 계약으로 각 서비스의 DTO와 검증을 구현한다. 문서를 받는 것만으로 자동 검증이 실행되지는 않는다. [호출과 진행 예시](#호출과-진행-예시)는 가상 입력·출력을 사용하며 실행 Mock 대체물이 아니다.

- contractVersion은 hops-system-1이다. 명시한 필드만 전송하고 모든 키는 필수이며 모르는 nullable 값은 null이다. 선택적 의미를 키 생략으로 표현하지 않는다.
- UUID는 소문자 하이픈, 시각은 UTC Z, 정수·사용자 입력은 공통 DATA_TYPES의 규칙을 따른다. 배열 내 ID 유일성·실제 FK·원문 구간·권한·예산 누적은 필드 형식 검사와 별도로 실행기/Spring이 검증한다.
- INQUIRY_ANALYSIS 요청의 workRecord는 null이고 CASE_DRAFT에는 검증 완료 WorkRecordContext가 필수다. CASE_DRAFT는 원 기록·그 문제의 관련 원문만 받는다.
- InputProblem은 내부 상담 입력 DTO다. 문의자 화면의 Problem 투영과 구분하며 내부 StandardRequestSnapshot을 담을 수 있다. 이 객체를 InquiryResult로 그대로 반환하지 않는다.
- 동일 runId·동일 입력 digest 재전송은 같은 실행이다. 다른 입력이면 409다. 같은 runId에서 다른 최종 결과가 오면 INVALID_OUTPUT으로 차단하고 이미 저장된 업무를 유지한다.
- seq는 한 실행 전체의 이벤트 순번이며 chunkSeq는 answerMessageId별 답변 조각 순번이다. 두 값을 혼용하지 않는다. 공개 SSE eventId는 Spring이 별도 발급한다.

#### Spring에서 AI로

| 타입 | 입력 내용·검증 |
|---|---|
| AiRunRequest | 실행/문의/입력 버전·kind·answerMessageId·대상 문제 ID, 허용 메시지/기존 문제/작업, 시스템 목록, accessContext, nullable 원 처리 기록, expiresAt·limits |
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

#### 외부 DTO 필드와 조건

표의 모든 키는 필수이며 `또는 null`만 null을 허용한다. Uuid·time·int·문자열 길이의 공통 규칙과 SupportedSystem·SystemFeature·KnowledgeEvidence·EvidenceStatement·AcceptanceCriterion·RequestDetails는 [공통 데이터 계약](../../specs/contracts/DATA_TYPES.md#신규-계약-hops-system-1)을 따른다. problemRef는 기존 문제 UUID 또는 `new:`로 시작하는 영문·숫자·밑줄·하이픈 참조다. 버전·순번·실행 한도는 양의 정수, questionCount는0~5, lastSeq/maxRetries는0도 허용한다. contentDigest는 SHA-256 소문자 hex 64자다.

| DTO | 필수 필드·자료형 |
|---|---|
| AiError | code:INVALID_OUTPUT / SEARCH_UNAVAILABLE / TOOL_UNAVAILABLE / MODEL_UNAVAILABLE / DEADLINE_EXCEEDED / BUDGET_EXCEEDED / AUTH_CONTEXT_INVALID / SUPERSEDED / INDEX_UNAVAILABLE / UPSTREAM_PROTOCOL_ERROR / NOT_FOUND / CONFLICT / INVALID_REQUEST / RATE_LIMITED / SERVICE_UNAVAILABLE, message:string, retryable:boolean |
| AccessContext | actorId:Uuid, purpose:EMPLOYEE_HELP / SYSTEM_WORK / CASE_DRAFT, systemIds:Uuid[], allowedDocumentScopes:EMPLOYEE / SYSTEM_STAFF[], policyRevision:int |
| ExecutionLimits | maxModelCalls:int, maxInputTokens:int, maxOutputTokens:int, maxToolCalls:int, maxRetries:int |
| InputMessage | id:Uuid, revision:int, sequenceNo:int, sender:USER / AI, content:string, problemIds:Uuid[], createdAt:time |
| StandardRequestBody | summary:string, confirmedFacts:EvidenceStatement[], assumptions:EvidenceStatement[], unknowns:string[], responsibleSystemCandidateId:Uuid 또는 null, responsibilityEvidenceIds:Uuid[], details:RequestDetails, knowledge:KnowledgeEvidence[] |
| InputProblem | id:Uuid, summary:string, intent:HOW_TO / ACCESS_REQUEST / OBSERVED_SYMPTOM / FEATURE_REQUEST / IMPROVEMENT_REQUEST / UNKNOWN / OUT_OF_SCOPE, processingType:KNOWLEDGE_QA / SERVICE_REQUEST / INVESTIGATION / INCIDENT / CHANGE_REQUEST / REVIEW_REQUIRED / OUT_OF_SCOPE, reportedSystemId:Uuid 또는 null, reportedFeatureId:Uuid 또는 null, state:ANALYZING / WAITING_USER / WAITING_SYSTEM / TICKET_PENDING / IN_SERVICE / WAITING_CONFIRMATION / RESOLVED / OUT_OF_SCOPE, questionCount:int, standardRequest:StandardRequestBody 또는 null, resolutionContext:ResolutionContext 또는 null |
| ExistingWorkItem | workItemId:Uuid, problemId:Uuid, inquiryVersion:int, outcome:PENDING / STORED / NOT_CREATED / UNKNOWN, ticketId:Uuid 또는 null |
| WorkRecordContext | id:Uuid, ticketId:Uuid, problemId:Uuid, systemId:Uuid, kind:VERIFIED_RESOLUTION / IMPLEMENTED_VERIFIED, action:string, verification:string, createdAt:time, resolutionRequestId:Uuid, confirmationState:PENDING / RESOLVED / UNRESOLVED, confirmedCause:string 또는 null, appliesToSystemVersion:string 또는 null |
| AiRunRequest | contractVersion:hops-system-1, runId:Uuid, kind:INQUIRY_ANALYSIS / CASE_DRAFT, inquiryId:Uuid, inquiryVersion:int, answerMessageId:Uuid 또는 null, problemIds:Uuid[], messages:InputMessage[], problems:InputProblem[], systems:SupportedSystem[], accessContext:AccessContext, existingWorkItems:ExistingWorkItem[], workRecord:WorkRecordContext 또는 null, expiresAt:time, limits:ExecutionLimits |
| ProblemProposal | problemRef:string, summary:string, intent:HOW_TO / ACCESS_REQUEST / OBSERVED_SYMPTOM / FEATURE_REQUEST / IMPROVEMENT_REQUEST / UNKNOWN / OUT_OF_SCOPE, processingType:KNOWLEDGE_QA / SERVICE_REQUEST / INVESTIGATION / INCIDENT / CHANGE_REQUEST / REVIEW_REQUIRED / OUT_OF_SCOPE, reportedSystemCandidateId:Uuid 또는 null, facts:EvidenceStatement[], assumptions:EvidenceStatement[], unknowns:string[], details:RequestDetails |
| QuestionProposal | problemRef:string, bundleRef:string, text:string, requestedFields:string[] |
| GuideProposal | messageText:string, evidenceIds:Uuid[], problemRef:string |
| TicketProposal | problemRef:string, title:string, processingType:SERVICE_REQUEST / INVESTIGATION / CHANGE_REQUEST / REVIEW_REQUIRED, responsibleSystemCandidateId:Uuid, evidenceIds:Uuid[], standardRequestSnapshot:StandardRequestBody |
| EntityLink | relation:APPLIES_TO / ADDRESSES / USES_CHECK, targetId:string, evidenceIds:Uuid[] |
| CaseProposal | reusable:boolean, title:string 또는 null, action:string 또는 null, verification:string 또는 null, applicability:string 또는 null, limitations:string[], appliesToSystemVersion:string 또는 null, accessScope:EMPLOYEE / SYSTEM_STAFF 또는 null, sourceMessageIds:Uuid[], entityLinks:EntityLink[], problemStatement:string 또는 null |
| AiResult | contractVersion:hops-system-1, runId:Uuid, kind:INQUIRY_ANALYSIS / CASE_DRAFT, inquiryId:Uuid, inquiryVersion:int, status:SUCCEEDED / FAILED / TIMED_OUT / SUPERSEDED, answer:string 또는 null, problemProposals:ProblemProposal[], questions:QuestionProposal[], guide:GuideProposal 또는 null, ticketProposals:TicketProposal[], caseProposal:CaseProposal 또는 null, evidence:KnowledgeEvidence[], error:AiError 또는 null |
| AiAccepted | contractVersion:hops-system-1, runId:Uuid, status:QUEUED, eventsPath:string |
| AiRunSnapshot | contractVersion:hops-system-1, runId:Uuid, status:QUEUED / RUNNING / SUCCEEDED / FAILED / TIMED_OUT / SUPERSEDED / UNKNOWN, lastSeq:int, result:AiResult 또는 null, error:AiError 또는 null |
| ProgressPayload | role:SUPERVISOR / INQUIRY / KNOWLEDGE / TICKET / CURATOR, displaySummary:string |
| DeltaPayload | messageId:Uuid, chunkSeq:int, text:string |
| AiEvent | contractVersion:hops-system-1, runId:Uuid, seq:int, eventType:progress / answer.delta / result, payload:ProgressPayload / DeltaPayload / AiResult |
| KnowledgeCandidate | documentId:Uuid, documentVersion:int, kind:GUIDE / POLICY / RUNBOOK / CASE |
| AuthorizedKnowledgeRef | documentId:Uuid, documentVersion:int, kind:GUIDE / POLICY / RUNBOOK / CASE, accessScope:EMPLOYEE / SYSTEM_STAFF, contentDigest:string |
| KnowledgeAuthorizationRequest | contractVersion:hops-system-1, requestId:Uuid, runId:Uuid, policyRevision:int, candidates:KnowledgeCandidate[] |
| KnowledgeAuthorizationResponse | contractVersion:hops-system-1, requestId:Uuid, runId:Uuid, policyRevision:int, allowedRefs:AuthorizedKnowledgeRef[] |
| ApprovedCaseBody | workRecordId:Uuid, resolutionRequestId:Uuid, approvedBy:Uuid, approvedAt:time, title:string, action:string, verification:string, applicability:string, limitations:string[], appliesToSystemVersion:string 또는 null, accessScope:EMPLOYEE / SYSTEM_STAFF, origin:SYNTHETIC / DEMO_VERIFIED / OPERATIONAL, confirmationOrigin:SYNTHETIC_PRESET / HOPS_USER, sourceMessageIds:Uuid[], entityLinks:EntityLink[], systemId:Uuid, confirmedCause:string 또는 null, problemStatement:string, caseType:RESOLUTION / IMPLEMENTATION |
| IndexApplyRequest | contractVersion:hops-system-1, jobId:Uuid, caseId:Uuid, caseVersion:int, store:VECTOR / NEO4J, attempt:int, contentDigest:string, approvedCase:ApprovedCaseBody |
| IndexApplySnapshot | contractVersion:hops-system-1, jobId:Uuid, caseId:Uuid, caseVersion:int, store:VECTOR / NEO4J, attempt:int, state:PENDING / RUNNING / SUCCEEDED / FAILED / UNKNOWN, contentDigest:string, error:AiError 또는 null, updatedAt:time |
| ResolutionContext | requestId:Uuid, source:GUIDE / WORK_RECORD, sourceId:Uuid, sourceVersion:int, state:PENDING / RESOLVED / UNRESOLVED |

- INQUIRY_ANALYSIS의 answerMessageId는 UUID·workRecord=null이며 accessContext.purpose는 EMPLOYEE_HELP 또는 SYSTEM_WORK다. CASE_DRAFT는 answerMessageId=null·WorkRecordContext 필수·purpose=CASE_DRAFT다.
- SUCCEEDED 상담은 answer 필수·caseProposal=null, SUCCEEDED CASE_DRAFT는 caseProposal 필수·answer/guide=null·문제/질문/티켓 목록=[]다. 실패·시간 초과·대체된 AiResult는 error 필수·answer/guide/caseProposal=null·제안/근거 목록=[]다.
- AiRunSnapshot의 QUEUED/RUNNING/UNKNOWN은 result=null이다. 종료 상태에는 같은 status의 AiResult가 필수이며 SUCCEEDED의 error=null, FAILED/TIMED_OUT/SUPERSEDED의 error는 AiError다.
- AiEvent.eventType별 payload는 각각 ProgressPayload/DeltaPayload/AiResult로 고정한다. DeltaPayload.messageId는 입력 answerMessageId와 같아야 하며 CASE_DRAFT에는 답변 조각을 발행하지 않는다.
- ExistingWorkItem은 STORED에만 ticketId UUID이고 나머지는null이다. CaseProposal.reusable=true면 제목·문제 설명·조치·검증·적용 조건·공개 범위가 필수다. false면 본문·적용 버전·공개 범위는null, limitations/sourceMessageIds/entityLinks는[]다.
- IndexApplySnapshot은 SUCCEEDED이면 error=null, FAILED이면 AiError 필수다. 나머지 상태의 error는 nullable이며 UNKNOWN을 반영 실패나 성공으로 확정하지 않는다.

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
| answer | string 또는 null·초기 null | Supervisor의 공개 본문 조합. FINALIZING에서 질문·가이드 본문 포함과 권한을 검증해 고정. CASE_DRAFT는 null |
| ticketProposals | Map<problemRef,TicketProposal>·빈 map | Ticket 검증기. 같은 실행 내 최종 유효 제안으로 교체, DB 상태는 쓰지 않음 |
| caseProposal | CaseProposal 또는 null | CASE_DRAFT의 Curator 검증기만 변경 |
| roleResults | Map<invocationId,검증된 역할 결과>·빈 map | 실행기. 같은 호출 ID 재전달은 중복 저장하지 않음 |
| pendingCalls | Set<invocationId>·빈 set | 실행기만 예약/해제. 역할이 다른 역할을 직접 호출하지 않음 |
| usage | calls/inputTokens/outputTokens/toolCalls/retries 누적 | 실행기·도구 wrapper만 원자적으로 증가 |
| eventSeq | int·0 | 이벤트 발행기만 증가. 역할 출력의 임의 seq는 신뢰하지 않음 |
| answerChunkSeq | int·0 | 이벤트 발행기만 input.answerMessageId의 공개 조각 발행 때 증가. eventSeq와 별도이며 역할 전환 때 초기화하지 않음 |
| cancellation | cancelled:boolean,reason:null 또는 안전한 코드 | runtime/서버 취소 경로만 변경 |
| error | AiError 또는 null | 실행기만 최초 종료 오류를 고정 |
| finalResult | AiResult 또는 null | FINALIZING에서 한 번 고정. 이후 새로운 제안/진행을 반영하지 않음 |

Map은 역할끼리 공유해서 직접 수정하는 가변 객체가 아니다. 노드는 검증된 patch를 반환하고 실행기가 키별 병합한다. 병렬 실행을 사용한다면 phase/input/usage/eventSeq 같은 단일 작성자 필드에 last-write-wins를 쓰지 않는다. 누적 실행 사용량은 실행기가 관리하며 역할 출력으로 초기화하지 않는다.

roleResults에는 구조화된 결과·오류·근거 참조만 저장한다. 모델 내부 사고 과정·토큰·API 키·서비스 Bearer를 포함하지 않는다. 원문은 input의 접근 범위 밖으로 확장하지 않는다.

### S4. 허용 경로와 State 전이

| 현재 phase | 사건 | 다음 상태·검사 |
|---|---|---|
| QUEUED | 유효 입력·예산·기한으로 실행 시작 | RUNNING |
| RUNNING | Supervisor가 CALL_ROLE 제안 | kind·허용 역할·남은 예산 검증 후 역할 호출. phase 유지 |
| RUNNING | 검증된 상담 답변/질문/티켓 또는 사례 제안 확정 | FINALIZING; 상담은 answer에 질문·가이드 본문을 통합하고 고정 |
| FINALIZING | 스키마·출처·참조·전체 한도 검증 성공 | SUCCEEDED·finalResult 고정·result 이벤트 |
| QUEUED/RUNNING/FINALIZING | 기한 초과 | TIMED_OUT·안전한 오류·새 제안 없음 |
| QUEUED/RUNNING/FINALIZING | 입력 버전 대체·취소 | SUPERSEDED·후속 조각/저장 제안 차단 |
| QUEUED/RUNNING/FINALIZING | 모델/도구/형식/권한/예산 실패 | FAILED·기존 Spring 저장 업무 유지 |
| 종료 상태 | 늦은 진행·동일 결과 재전달 | 진행 무시·같은 최종 결과 재확인만 |

INQUIRY_ANALYSIS에서 Supervisor는 Inquiry·Knowledge·Ticket을 필요한 순서로 선택한다. Curator는 허용하지 않는다. CASE_DRAFT에서는 Curator와 필요한 읽기 전용 Knowledge만 허용한다. 모든 역할을 매번 호출하거나 고정 순서로 성공을 강제하지 않는다. 한도의 정본은 input.limits/expiresAt이며 역할 전환·재시도에서 초기화하지 않는다.

questionCount는 내부 State가 임의 증가시켜 Spring에 확정값으로 반환하지 않는다. 기존 문제별 `5-questionCount` 이내의 새 번들만 허용하며 Spring의 질문 메시지 commit 때만 집계한다. 기존 STORED 작업의 문제에는 새 티켓 제안을 허용하지 않는다. 기존 문제 ID/작업 ID/실제 담당자·저장 여부를 모델이 교체하지 못한다.

### S5. 저장·재시작·적용 경계

- Spring은 AiRun 입력·상태·최종 결과 digest와 실제 업무 결과를 보존한다. AI의 내부 State는 한 run용이며 업무 DB 정본을 대체하지 않는다.
- 병합된 ERD와의 run/kind·입력/정책 버전·원 기록·결과 적용 매핑은 [DATA11절 E04/E07~E10](../../docs/DATA.md#11-완성-erd-대조-결과와-병합-기준)에 대조 완료했다. Entity의 컬럼을 AI State에 그대로 복제하지 않으며 wire 필드 의미가 바뀌면 이 문서·예시·영향 spec을 함께 갱신한다.
- AI 실행 접수 기록(runId·입력 digest·상태·최종 결과/seq)은 RunLedger로 중복을 차단해야 한다. 저장은 같은 PostgreSQL 인스턴스의 별도 hops_ai_runtime DB로 채택한다. AI 계정에 업무 원본 쓰기 권한을 주지 않는다. 검색 schema 권한은 별도로 제한한다. runId PK·입력 digest·원자적 접수·결과/순번·24시간 상세와 ID 표식을 보존한다. 재시작 뒤 같은 ID로 새 모델 호출을 시작하지 않는다.
- MVP는 중간 그래프 checkpoint에서 자동 재개하지 않는다. 접수됐지만 실행 상태를 복원할 수 없으면 snapshot UNKNOWN으로 반환한다. Spring은 이를 공개 RunSummary.status=FAILED·error.code=AI_RUN_STATE_UNKNOWN으로 변환해 새 runId 재분석을 제공한다. 기존 workItem·성공 티켓은 유지하고 이전 run의 늦은 결과는 적용하지 않는다.
- 최종 결과 적용은 Spring의 최신 inquiryVersion·activeRunId·policyRevision·원 출처 검증 후 수행한다. 동일 runId·최종 digest는 한 번만 적용하고 같은 ID·다른 결과는 거부한다.
- 상담 AI는 티켓 저장 commit을 기다리는 무제한 내부 루프를 만들지 않는다. 최종 제안을 반환하면 Spring이 답변 저장과 기존 문제별 생성/배정 처리를 수행한다. 티켓 결과는 저장된 InquiryResult로 구성하며 answer나 진행 문구에서 저장 성공을 선행 확정하지 않는다.
- Curator run은 해당 원 WorkRecord만 대상으로 하고 상담 currentRun과 분리한다. 사용자 미해결 응답·권한 변경이 뒤늦게 발생해도 초안은 업무 이력으로만 유지하고 승인·공개 검증을 다시 통과해야 한다.

### S6. 계약 완료 조건

| 검사 | 확인할 내용 |
|---|---|
| 전송 형식 | 이 문서의 필드·타입·필수/null·kind·enum·결과 조합을 각 서비스 DTO로 검증 |
| State 병합 | 다른 역할의 input/권한/예산 변경 차단, 중복 patch·근거 충돌·종료 후 patch 처리 |
| 서비스 통신 | 같은 run 재접수·다른 입력 충돌·SSE 누락/snapshot·재시작 UNKNOWN·취소 |
| Spring 적용 | 이전 버전·중복 결과·가짜 시스템/근거·권한 변경·기존 성공 보존 |
| 업무 순환 | 질문/배정 → 실제 처리/확인 → Curator/승인 → 같은 버전 양쪽 반영/재검색 |

문서/예시 검사는 실제 Java/JPA·Pydantic·LangGraph 구현·모델 품질·Spring 저장·E2E를 대신하지 않는다. 각 서비스에서 계약 검사·상태·경합·권한 검증을 구현하고 실행한 결과를 기록해야 계약 적용을 완료한다.


## 반영·검색 권한 서비스 계약

상담/Curator 실행과 저장소 반영을 구분한다. 업무 상태·필드 값은 결정 완료이며 실제 배포/호출은 구현 PR의 검증 대상이다. 아래 타입의 필드·검증 규칙은 [외부 전송 형식](#s2-외부-wire-형식)을 따르고 [호출과 진행 예시](#호출과-진행-예시)를 참고한다.

| 방향·API | 요청 → 결과 | 상태·멱등·오류 |
|---|---|---|
| Spring→AI POST /internal/v2/index-jobs | IndexApplyRequest → 202 IndexApplySnapshot | 승인 commit 뒤 현재 버전의 jobId/attempt로 호출. 동일 키·같은 digest는 기존 결과, 다른 내용409 |
| Spring→AI GET /internal/v2/index-jobs/{jobId}?attempt={n} | 없음 → 200 IndexApplySnapshot | job/attempt/승인 버전/store/digest 대조. 404·timeout은 실패 확정이 아님 |
| AI→Spring POST /internal/v2/knowledge/authorizations | KnowledgeAuthorizationRequest → 200 KnowledgeAuthorizationResponse | 조회 성격. 현재 run·policyRevision·사용자 범위 검증. 무권한 후보는 allowedRefs에서 제외 |

모든 내부 API는 고정된 Spring/AI origin·서비스 Bearer만 사용한다. 브라우저에 노출하지 않으며 모델이 임의 URL·토큰·runId를 구성하지 못한다. 401은 서비스 인증, 403은 현재 접근/정책 문맥 무효, 409는 동일 작업 내용/버전 충돌, 422는 필드/조건 오류, 429는 한도, 503은 저장소/서비스 불가다. 원격 malformed/다른 job 결과는 UPSTREAM_PROTOCOL_ERROR로 차단한다.

내부 HTTP 오류 본문은 AiError(code,message,retryable) 객체다. 401/403=AUTH_CONTEXT_INVALID, 404=NOT_FOUND, 409=CONFLICT, 422=INVALID_REQUEST, 429=RATE_LIMITED 또는 BUDGET_EXCEEDED, 503=SERVICE_UNAVAILABLE 또는 해당 MODEL/SEARCH/INDEX_UNAVAILABLE을 사용한다. retryable=true도 자동 모델 재시도나 새 업무 실행을 허용하지 않으며 같은 작업의 상태 확인·수동 재분석 조건을 따른다.

### I1. 승인 CASE 반영

시도별 저장 격리와 UNKNOWN 복구는 [변경안](../../specs/F011-case-lifecycle/RECOVERY_PROPOSAL.md)을 참고한다.

IndexApplyRequest는 contractVersion/jobId/caseId/caseVersion/store/attempt/contentDigest/approvedCase를 가진다. approvedCase는 systemId·caseType·problemStatement·원 기록의 confirmedCause·원 workRecordId/resolutionRequestId·승인자/시각·실제 조치/검증·조건/한계·적용 버전·공개 범위·출처·기존 엔티티 연결이다. Spring이 현재 승인·같은 원 기록의 해결 확인·기능 검증을 확인한 스냅샷만 보낸다. AI는 승인·공개를 확정하지 않는다.

digest는 `{caseId,caseVersion,store,approvedCase}` 객체를 키 오름차순·UTF-8·공백 없는 JSON·Unicode 원문·배열 순서 유지로 직렬화한 SHA-256 소문자 hex다. Java/Python의 기본 key 순서나 Unicode escape 설정에 의존하지 않는다. 숫자는 양의 정수 버전만 사용한다. AI는 받는 시점에 digest를 재계산해 불일치 시409로 거부한다.

VECTOR는 caseId/caseVersion 청크를 upsert하고 NEO4J는 승인된 기존 노드/관계만 같은 버전 키로 upsert한다. 결과는 jobId/caseId/caseVersion/store/attempt/contentDigest/state/error/updatedAt다. 성공은 원격 저장 검증 후 SUCCEEDED, 실패는 FAILED, 확인 불가는 UNKNOWN이다. Spring은 현재 승인 버전·최신 attempt·digest가 모두 맞는 성공만 기록한다. 이전 attempt의 늦은 성공이 새 반영 상태를 덮지 못한다.

Spring은 접수 후5초 간격으로 읽기 확인하고30초 안에 확정되지 않으면 UNKNOWN으로 유지한다. FAILED의 다음 attempt는 현재 담당자의 수동 명령으로만 만든다. UNKNOWN을 실패로 간주해 새 attempt를 만들지 않는다. 성공한 저장소는 유지하며 두 성공 전 Spring의 공개 gate는 PRIVATE다.

#### I1.1 승인 후 미전달 요청의 수동 복구

1. **승인과 요청 보존:** Spring은 CASE 행을 잠그고 승인·현재 검색 문서 버전·VECTOR/NEO4J의 최초 IndexApplyRequest·두 PENDING 상태를 같은 DB 거래로 저장한다. 요청은 기존 knowledge_document_version.source_metadata.indexRequests의 VECTOR/NEO4J 키에 각각 전체 객체로 보존한다. 각 요청은 jobId·attempt=1·승인 버전·contentDigest·approvedCase를 포함하며 이 거래가 실패하면 승인도 저장하지 않는다. 외부 호출은 commit 뒤 수행한다.
2. **현재 요청만 유지:** 같은 CASE/승인 버전의 검색 문서 버전은 CASE 행 잠금 아래 하나로 생성·선택한다. indexRequests는 저장소별 현재 요청 두 개만 보존하고 journal 상세 만료 뒤에도 삭제하지 않는다. 승인 본문·출처·버전은 불변이다. 이 기술 메타데이터는 CASE 본문 contentDigest의 대상과 공개 DTO·모델 입력에서 제외한다. 새 테이블·영속 큐·자동 복구 작업은 추가하지 않는다.
3. **담당자 확인:** 현재 담당자가 POST /cases/{caseId}/index-checks를 호출하면 Spring은 현재 권한·expectedCaseVersion·PENDING/RUNNING/UNKNOWN 상태를 확인하고 저장한 현재 요청을 읽는다. 먼저 같은 jobId/attempt를 GET한다. 정상 snapshot이면 버전·저장소·attempt·digest를 검증해 상태만 반영한다. 원격에 존재하는 UNKNOWN 작업을 새로 실행하지 않는다.
4. **같은 요청 재전달:** 인증된 고정 AI 서비스가 형식이 유효한 404 NOT_FOUND를 반환한 경우에만 저장한 요청 전체를 같은 POST /internal/v2/index-jobs로 한 번 재전달할 수 있다. 재전달 직전 현재 권한·승인 버전·저장된 현재 요청을 다시 대조하고 변경됐으면 중단한다. 404를 실패나 미실행 증거로 확정하는 것은 아니다. 원격 멱등 접수에 원 요청의 존재 확인·최초 접수를 맡기는 절차이며 jobId/attempt/승인 버전/digest/approvedCase를 바꾸지 않는다. timeout·5xx·인증/권한 오류·형식 오류이면 상태를 미확인으로 유지하고 재전달하지 않는다. 새 버튼 클릭은 새 공개 requestId를 사용하되 같은 원격 작업을 확인한다.
5. **AI 멱등 접수:** AI는 (jobId,attempt)를 유일 키로 원자적으로 접수한다. 같은 키·같은 요청은 기존 snapshot을 반환하고 실행을 추가하지 않으며 다른 내용은409다. 최초 요청과 재전달이 동시에 도착해도 실행은 한 번이다. 상세 보존 기간이 지나도 사용 키·digest 표식을 유지한다. 상세 결과를 복원할 수 없는 기존 키는 UNKNOWN을 반환하며 404나 새 접수로 바꾸지 않는다. 이미 접수된 실행의 재시작 자동 복원은 이번 최소 복구 범위에 추가하지 않는다.
6. **실패 후 새 시도:** FAILED에서만 index-retries가 같은 jobId의 attempt를1 올린 현재 요청과 PENDING 상태를 DB에 먼저 저장한다. 승인 버전·본문·digest와 성공한 저장소는 유지한다. 새 시도의 commit 뒤 호출이 누락돼도 위 확인/재전달을 적용한다. 늦은 이전 attempt의 결과는 현재 상태를 바꾸지 않는다. 요청 메타데이터 누락·불일치는 안전한 프로토콜 오류이며 새 ID 생성·자동 재승인으로 우회하지 않는다.

이 복구는 기존 담당자 확인 조작의 서버 처리다. 화면의 GET·보조 폴링은 읽기만 수행하며 색인을 시작하지 않는다. 같은 키 재전달의 202 접수는 검색 공개 성공이 아니며 양쪽의 같은 승인 버전 SUCCEEDED를 확인한 뒤에만 공개한다.

### K1. 검색 후보의 최신 권한

AI는 전달받은 목적/systemIds/scopes로 pgvector·Neo4j에서 최대20개 ID/버전 후보를 찾는다. 시스템 후보 조건은 허용 systemId 또는 systemId=null인 공통 POLICY이며 현재 접근 범위로 다시 거른다. 원문을 모델에 넣기 전에 Spring의 authorization API를 호출한다. 요청은 contractVersion/requestId/runId/policyRevision/candidates, 후보는 documentId/documentVersion/kind다. Spring은 전달된 사용자 역할을 신뢰하지 않고 저장된 AiRun의 문맥과 현재 권한을 사용한다.

응답은 같은 request/run/policyRevision과 허용된 documentId/documentVersion/kind/accessScope/contentDigest 목록이다. CASE는 같은 승인 버전의 두 반영 성공·현재 ACL을 확인한다. GUIDE/POLICY/RUNBOOK도 현재 승인·공개 범위를 확인한다. 없는 항목·비공개 항목은 제외하며 거부 원문/존재 상세를 반환하지 않는다. policyRevision이 달라졌으면403으로 실행을 중단한다.

AI는 허용된 참조의 본문만 가져오고 정렬한 상위5건을 KnowledgeEvidence로 전달한다. GUIDE/POLICY/RUNBOOK은 승인된 UTF-8 원문 바이트의 SHA-256, CASE는 승인 CaseVersion의 canonical JSON SHA-256으로 contentDigest를 확인한다. CASE의 digest 대상은 caseId/caseVersion/approvedCase이며 저장소별 IndexApplyRequest digest와 구분한다. 청크는 같은 원문의 digest·버전과 청크 위치를 보존한다. 읽은 버전/digest가 바뀌면 다시 확인하거나 안전한 ERROR로 끝낸다. 최종 결과를 Spring에 적용할 때도 출처/공개/현재 권한을 재검사한다. gate 통신 실패를 검색 EMPTY로 바꾸거나 cached 허용을 계속 쓰지 않는다.

GUIDE/POLICY/RUNBOOK의 권한 정본은 Spring 업무 DB의 knowledge_document/knowledge_document_version이다. 승인 manifest는 버전 고정 반입 자료다. documentId/documentVersion/kind/systemId/accessScope/contentDigest/sourceRef/reviewPr을 필수로 두고 sourceRef는 저장소 상대 경로 또는 공개 출처 URL이다. 작성자 외 팀원 리뷰를 거친 버전만 manifest에 포함한다. Spring이 반입 자료의 출처/해시/문서 버전을 검증한다. 검색 시 문서와 버전의 APPROVED·data_split=RAG·현재 ACL을 검사하고 DEV/EVAL은 제외한다. AI는 허용된 버전의 조각만 같은 DB의 hops_knowledge schema에서 처리하며 업무 원본을 쓰지 않는다. 별도 운영 콘텐츠 관리 화면·승인 취소·자동 폐기 기능은 이번 PRD 범위에 추가하지 않는다.

### M1. 모델 출력과 외부 DTO의 경계

서비스 전송 형식의 정본은 이 Markdown 계약이다. AI 담당자가 역할별 구조화 출력과 검증 코드를 구현하되 공개 필드·필수 키·추가 필드 금지·nullable·조건부 규칙을 유지한다. 로컬 검증 Schema를 팀 공통 자동 검증이 구현된 것으로 취급하지 않는다. 모델 refusal·불완전 JSON·시간/예산 초과는 새 저장 제안 없는 안전한 실패다. [공식 Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)를 따른다.

### X1. spec 추적

| 서비스 계약 | 관련 spec | 관찰 조건 |
|---|---|---|
| Run 접수/조회/이벤트/취소·State | F002/F003/F004/F005/F008/F010 | 늦은 버전·중복 결과·순번 누락·종료 뒤 patch를 차단 |
| CASE_DRAFT | F009/F011 | 실제 검증 기록과 상담 실행 분리·초안 실패가 업무 기록을 취소하지 않음 |
| IndexApplyRequest/Snapshot | F011 | 승인 버전/attempt/digest 일치·두 성공 전 양쪽 비공개 |
| KnowledgeAuthorizationRequest/Response | F001/F003/F009/F011 | 현재 run/policy/ACL 확인 전 본문을 모델에 넣지 않음 |

F006의 해결 응답과 F007의 수동 정정은 Spring의 업무 명령이며 AI를 통해 저장하지 않는다. 그 결과가 다음 AI 입력·승인·권한 검증에 반영된다.


### E1. 병합 ERD의 역할·자료 매핑

runId=agent_run.id, 외부 CASE_DRAFT=DB task CURATION, 외부 role INQUIRY=DB agent_type INQUERY로 Spring이 변환한다. 작업 ID와 source_work_log_id·기존 이벤트/메시지의 확인 응답을 함께 검사한다. INQUERY나 CURATION을 외부 DTO에 별도 역할/kind로 추가하지 않는다. QUEUED는 실행 시작 전이며 DB started_at=null, CANCELLED는 외부 SUPERSEDED다.

WorkRecordContext에는 실제 담당자가 입력한 confirmedCause와 appliesToSystemVersion을 nullable로 전달한다. Curator는 모델 추정을 확인된 원인으로 확정하지 않으며 서버가 원 기록에서 CASE의 confirmedCause/caseType을 투영한다. 재사용 가능한 CaseProposal에는 problemStatement가 필수이고 reusable=false에는 null이다. 신규 TicketProposal은 SERVICE_REQUEST/INVESTIGATION/CHANGE_REQUEST/REVIEW_REQUIRED만 허용하며 같은 problemRef의 ProblemProposal.processingType과 일치해야 한다. 실제 INCIDENT 확정은 현재 담당자의 처리 유형 정정 명령으로 수행한다. 제목은200자·적용 버전은100자 제한이다.

공통 POLICY만 KnowledgeEvidence.systemId=null을 허용한다. 시스템 없는 직원 정책은 EMPLOYEE 문맥에, STAFF 공통 정책은 현재 PRIMARY가 있는 담당자 문맥에만 허용한다. null을 특정 시스템 후보로 바꾸지 않는다. chunk의 source content_digest는 authorization 응답과, chunk_digest는 실제 조각 바이트의 SHA-256과 각각 대조한다. 기존 승인 버전/문서의 출처 메타데이터는 버전 스냅샷으로 읽고 현재 ACL은 다시 검사한다.

MVP는 기존 resolved_case의 현재 승인 버전·두 반영 상태와 기존 문서 버전의 source_metadata.indexRequests를 사용하며 추가 테이블을 요구하지 않는다. 승인 뒤 사례 본문은 읽기 전용이고 현재 기술 요청만 FAILED 재시도 때 교체한다. Source CASE 해시는 store 없는 approvedCase, 반영 요청 해시는 store 포함 스냅샷이며 indexRequests나 journal을 포함하지 않는다. AI 내부 State/별도 RunLedger는 업무 Entity와 구분한다. 복수 승인 버전·반영 전체 이력·자동 후속 전달 복구는 후순위다.

InputProblem.resolutionContext는 같은 문제의 현재 확인 상태를 기존 message.payload 또는 ticket_event에서 requestId/source/sourceId/sourceVersion/state로 투영하며 없으면 null이다. GUIDE 미해결 후에는 이 문맥을 같은 문제·누적 질문 수와 함께 새 run으로 보낸다. 문의 입력 버전은 유지하고 activeRunId를 바꿔 옛 결과를 차단한다. ResolutionRequest는 외부 DTO 이름이며 별도 물리 테이블을 요구하지 않는다. 모델이 원 확인 상태나 다른 문제의 출처를 만들어내지 못하도록 Spring이 소속을 검증한다.

## 호출과 진행 예시

아래 JavaScript 코드는 신규 계약의 요청 작성·호출·반환값 소비를 설명한다. 목표 API의 예시이며 현재 서버의 구현 완료를 뜻하지 않는다. 사용자 토큰은 로그인 결과로, 서비스 토큰과 고정 origin은 서버 환경에서 주입한다. Spring↔AI 호출 코드를 브라우저에서 실행하지 않는다. 가상 시스템·사용자 식별자는 [DATA 2절](../../docs/DATA.md#2-고정-카탈로그)을 사용한다.

### 공통 HTTP 호출

아래 helper는 자동 재시도를 하지 않고 HTTP 오류를 호출자에게 전달한다. 명령의 requestId는 최초 전송 전에 생성해 입력과 함께 보존한다. 응답 유실 뒤에는 같은 명령의 결과를 확인하며 새 키로 자동 재전송하지 않는다.

```javascript
async function requestJson({ origin = "", path, token, method = "GET", body, requestId }) {
  const headers = { Authorization: `Bearer ${token}` };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (requestId !== undefined) headers["Idempotency-Key"] = requestId;
  const response = await fetch(`${origin}${path}`, {
    method, headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = response.status === 204 ? null : await response.json();
  if (!response.ok) throw Object.assign(new Error("요청 실패"), { status: response.status, data });
  return { status: response.status, data };
}
```

### 문의 시작과 메시지와 시스템 선택

```javascript
async function createInquiry(accessToken, command) {
  // command = { requestId, content, reportedSystemId: null 또는 선택한 UUID }
  return requestJson({
    path: "/api/v2/inquiries", token: accessToken, method: "POST",
    requestId: command.requestId, body: command,
  });
}

async function sendMessage(accessToken, inquiryId, command) {
  // command = { requestId, expectedInquiryVersion, content, problemIds: [] }
  return requestJson({
    path: `/api/v2/inquiries/${encodeURIComponent(inquiryId)}/messages`,
    token: accessToken, method: "POST", requestId: command.requestId, body: command,
  });
}

async function confirmSystem(accessToken, inquiryId, problemId, command) {
  // command = { requestId, expectedInquiryVersion, systemId }
  return requestJson({
    path: `/api/v2/inquiries/${encodeURIComponent(inquiryId)}/problems/${encodeURIComponent(problemId)}/system-confirmations`,
    token: accessToken, method: "POST", requestId: command.requestId, body: command,
  });
}

async function readInquiry(accessToken, inquiryId) {
  return requestJson({
    path: `/api/v2/inquiries/${encodeURIComponent(inquiryId)}`, token: accessToken,
  });
}

async function readMessages(accessToken, inquiryId) {
  return requestJson({
    path: `/api/v2/inquiries/${encodeURIComponent(inquiryId)}/messages?size=30`,
    token: accessToken,
  });
}
```

변경 명령의 응답은 CommandReceipt다. SUCCEEDED는 해당 명령의 저장 확인이며 AI 답변 완료가 아니다. PENDING/UNKNOWN이면 입력·키를 보존하고 operation 상태를 확인한다. 문의 ID를 확인하면 SSE 연결을 시작하고 readInquiry/readMessages로 공개 상태를 읽는다. WAITING_SYSTEM에서는 confirmSystem을 호출하며 질문 수를 초기화하지 않는다.

### AI 분석 접수와 진행 수신

context는 Spring이 저장된 원문·문제·기존 작업과 검증한 권한으로 준비한다. answerMessageId는 Spring이 run 접수 거래에서 발급·보존한 UUID다. 모델이 사용자·권한·시스템 목록을 구성하지 않는다. expiresAt·limits는 별도 서버 실행 설정에서 준비한 값이며 아래 예시에서 수치를 정하지 않는다. 모델·과금 설정은 이 요청에 포함하지 않는다.

```javascript
function makeAnalysisRequest(context) {
  return {
    contractVersion: "hops-system-1", runId: context.runId, kind: "INQUIRY_ANALYSIS",
    inquiryId: context.inquiryId, inquiryVersion: context.inquiryVersion,
    answerMessageId: context.answerMessageId,
    problemIds: context.problemIds, messages: context.messages, problems: context.problems,
    systems: context.systems, accessContext: context.accessContext,
    existingWorkItems: context.existingWorkItems, workRecord: null,
    expiresAt: context.expiresAt,
    limits: context.limits,
  };
}

async function startAiRun(aiOrigin, serviceToken, input) {
  return requestJson({
    origin: aiOrigin, path: "/internal/v2/runs", token: serviceToken,
    method: "POST", body: input,
  });
}

async function readAiRun(aiOrigin, serviceToken, runId) {
  return requestJson({
    origin: aiOrigin, path: `/internal/v2/runs/${encodeURIComponent(runId)}`,
    token: serviceToken,
  });
}

async function openEvents({ origin = "", path, token, signal }) {
  const response = await fetch(`${origin}${path}`, {
    headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" }, signal,
  });
  if (!response.ok || !response.body) throw new Error("진행 연결 실패");
  return response.body; // SSE 파서에 전달한다. 네트워크 chunk 하나가 이벤트 하나는 아니다.
}

function openInquiryEvents(accessToken, inquiryId, signal) {
  return openEvents({
    path: `/api/v2/realtime/events?targetType=INQUIRY&targetId=${encodeURIComponent(inquiryId)}`,
    token: accessToken, signal,
  });
}

function openAiEvents(aiOrigin, serviceToken, runId, signal) {
  return openEvents({
    origin: aiOrigin, path: `/internal/v2/runs/${encodeURIComponent(runId)}/events`,
    token: serviceToken, signal,
  });
}
```

Spring은 makeAnalysisRequest로 입력을 만들고 startAiRun으로 접수한다. 응답은 202 AiAccepted이며 같은 runId로 readAiRun/openAiEvents를 사용한다. SSE 연결 직후와 실행 중 snapshot 조회도 병행한다. 내부 progress/answer.delta/result를 검증해 소비하고 최종 결과를 현재 문의 버전과 대조한다. 브라우저의 openInquiryEvents는 Spring 공개 이벤트만 받는다. answer.delta는 임시 표시, message.stored/state.changed와 Run 종료는 readInquiry/readMessages 재조회 신호다. 최종 ChatMessage.id가 answerMessageId와 같으면 서버 content로 교체한다. 정확한 접수·병합·실패·복구는 [WIRE 12.8절](../../specs/contracts/WIRE_CONTRACT.md#128-채팅-송수신-완료-규칙)을 따른다.

### 질문·가이드 결과와 답변 조각

아래 코드는 wire 조립 예시다. 역할 출력·문제 참조·허용된 근거를 검증한 뒤 사용하며 모델 실행이나 DB 저장 구현이 아니다. 질문과 가이드 본문은 answer에 포함하고 별도 채팅 메시지를 추가하지 않는다.

```javascript
function makeQuestionResult(input, problemProposal, question) {
  return {
    contractVersion: "hops-system-1", runId: input.runId, kind: "INQUIRY_ANALYSIS",
    inquiryId: input.inquiryId, inquiryVersion: input.inquiryVersion, status: "SUCCEEDED",
    answer: question.text, problemProposals: [problemProposal], questions: [question],
    guide: null, ticketProposals: [], caseProposal: null, evidence: [], error: null,
  };
}

function makeGuideResult(input, problemProposal, guide, evidence) {
  return {
    contractVersion: "hops-system-1", runId: input.runId, kind: "INQUIRY_ANALYSIS",
    inquiryId: input.inquiryId, inquiryVersion: input.inquiryVersion, status: "SUCCEEDED",
    answer: guide.messageText, problemProposals: [problemProposal], questions: [],
    guide, ticketProposals: [], caseProposal: null, evidence, error: null,
  };
}

function makeAnswerEvent(input, eventSeq, chunkSeq, text) {
  return {
    contractVersion: "hops-system-1", runId: input.runId, seq: eventSeq,
    eventType: "answer.delta",
    payload: { messageId: input.answerMessageId, chunkSeq, text },
  };
}
```

makeAnswerEvent의 eventSeq는 progress를 포함한 run 전체 순번이고 chunkSeq는 답변 본문 조각만의 순번이다. Spring은 공개 answer.delta.seq에 chunkSeq를 넣고 messageId는 바꾸지 않는다. 최종 result는 위 결과 전체이며 조각을 하나도 보내지 않아도 유효하다. Spring은 성공 result를 검증·저장한 뒤 message.stored를 발행하고, 프론트는 같은 ID의 저장 메시지 하나로 임시 본문을 교체한다.

### 표준 요청과 추가 질문

아래는 모델 제안을 검증해 표준 요청 스냅샷으로 옮길 때의 필드 예시다. messageId는 실제 입력 메시지 참조다. 책임 시스템 후보는 근거가 없으면 null로 둔다. 저장된 StandardRequest의 id/problemId/inquiryVersion은 Spring이 연결한다.

```javascript
function makeStandardRequestSnapshot(messageId) {
  return {
    summary: "전자결재 첨부파일 열람 실패",
    confirmedFacts: [{
      text: "사용자가 오늘 오전부터 첨부파일 열람 실패를 보고함",
      sourceMessageIds: [messageId], knowledgeEvidenceIds: [],
    }],
    assumptions: [{
      text: "권한 또는 파일 제공 경로 문제일 가능성",
      sourceMessageIds: [], knowledgeEvidenceIds: [],
    }],
    unknowns: ["정확한 오류 문구", "실제 원인과 영향 범위"],
    responsibleSystemCandidateId: null, responsibilityEvidenceIds: [],
    details: {
      taskOrScreen: "첨부파일 열람", requestedAccess: null, businessPurpose: null,
      expectedBehavior: "첨부파일 열람", actualBehavior: "열람 실패",
      occurredAt: "오늘 오전", currentWorkflow: null, desiredChange: null,
      acceptanceCriteria: [],
    },
    knowledge: [],
  };
}

function makeQuestion(problemRef, bundleRef) {
  return {
    problemRef, bundleRef,
    text: "어떤 시스템에서 발생했으며 화면에 표시된 오류 문구는 무엇인가요?",
    requestedFields: ["reportedSystemId", "errorMessage"],
  };
}
```

makeQuestion은 남은 질문 횟수가 있을 때만 제안한다. bundleRef는 재전달에도 유지한다. 5회 후 시스템 미확인 결과는 WAITING_SYSTEM으로 저장하고 프론트가 시스템 선택을 요청한다. 선택·재진입·재시도로 질문 수를 초기화하지 않는다.

### 범위 밖 문의 조회와 표시

모든 문제의 범위 밖 안내가 저장된 뒤 GET 결과의 문의 status는 CLOSED, 문제 state는 OUT_OF_SCOPE다. 일부 문제가 진행·대기 중이면 해당 집계 상태를 유지한다. 프론트는 readInquiry로 받은 서버 상태를 아래처럼 표시하고 AI 문구나 화면 종료로 상태를 변경하지 않는다. 정확한 집계는 [TARGET 4절](../../specs/contracts/TARGET_CONTRACT.md#4-문의문제질문), 확인 조건은 F006-CONTRACT-07~09를 따른다.

```javascript
function inquiryStatusLabel(inquiry) {
  const labels = {
    IN_PROGRESS: "진행 중", WAITING_USER: "문의자 응답 대기",
    RESOLVED: "해결 확인 완료", CLOSED: "지원 범위 밖 안내 종료",
  };
  return labels[inquiry.status] ?? "상태 확인 필요";
}
```

### Curator 초안 생성과 사례 없음

Spring은 검증 완료 WorkRecord를 저장한 뒤 원 기록 관련 messages/problems와 CASE_DRAFT 권한 문맥을 준비한다. 아래 context는 그 검사를 마친 입력이다. 원 기록·관련 원문·확인 상태는 WorkRecordContext를 따르며 상담 실행과 별도 runId를 사용해 startAiRun으로 접수한다.

```javascript
function makeCaseDraftRequest(context) {
  return {
    ...makeAnalysisRequest(context),
    kind: "CASE_DRAFT", problemIds: [context.workRecord.problemId],
    answerMessageId: null,
    workRecord: context.workRecord,
    accessContext: context.accessContext, // purpose=CASE_DRAFT, 서버가 검증한 범위
    expiresAt: context.expiresAt,
    limits: context.limits,
  };
}

const noReusableCase = {
  reusable: false, title: null, problemStatement: null, action: null,
  verification: null, applicability: null, limitations: [],
  appliesToSystemVersion: null, accessScope: null, sourceMessageIds: [], entityLinks: [],
};
```

재사용 가능한 결과는 문제 설명·실제 조치·검증·조건·제한·출처와 기존 엔티티 연결 후보를 담는다. noReusableCase는 SUCCEEDED CASE_DRAFT 결과의 caseProposal에 넣을 수 있는 정상 결과다. 모델 오류는 이 값으로 바꾸지 않고 실패 결과로 반환한다. 초안 생성과 문의자 확인·담당자 승인·양쪽 검색 반영은 각각 별도로 검증한다.

### 승인 사례 반영과 검색 권한 확인

반영 입력은 Spring이 승인된 같은 사례 버전과 digest를 검증해 만든 IndexApplyRequest다. 검색 권한 입력은 AI가 현재 run의 후보 참조를 담은 KnowledgeAuthorizationRequest다. 사용자 JWT 대신 해당 호출 방향의 서비스 토큰을 사용한다.

```javascript
async function applyApprovedCase(aiOrigin, serviceToken, input) {
  return requestJson({
    origin: aiOrigin, path: "/internal/v2/index-jobs", token: serviceToken,
    method: "POST", body: input,
  });
}

async function checkIndexJob(aiOrigin, serviceToken, jobId, attempt) {
  return requestJson({
    origin: aiOrigin,
    path: `/internal/v2/index-jobs/${encodeURIComponent(jobId)}?attempt=${attempt}`,
    token: serviceToken,
  });
}

async function checkOrDeliverIndex(aiOrigin, serviceToken, storedRequest, assertCurrentRequest) {
  // Spring이 현재 권한·승인 버전·대기 상태를 검사한 수동 index-checks에서만 호출한다.
  // storedRequest는 승인 시 source_metadata.indexRequests에 저장한 원 요청이다.
  await assertCurrentRequest(storedRequest);
  let response;
  try {
    response = await checkIndexJob(
      aiOrigin, serviceToken, storedRequest.jobId, storedRequest.attempt,
    );
  } catch (error) {
    const detail = error.data;
    const notFound = error.status === 404 && detail !== null
      && typeof detail === "object" && detail.code === "NOT_FOUND"
      && typeof detail.message === "string" && detail.message.trim().length > 0
      && typeof detail.retryable === "boolean" && Object.keys(detail).length === 3;
    if (!notFound) throw error;
    await assertCurrentRequest(storedRequest); // 조회 사이 권한·버전·현재 attempt가 바뀌면 중단
    response = await applyApprovedCase(aiOrigin, serviceToken, storedRequest);
  }
  for (const field of ["jobId", "caseId", "caseVersion", "store", "attempt", "contentDigest"]) {
    if (response.data[field] !== storedRequest[field]) throw new Error("반영 응답 불일치");
  }
  return response; // 접수·현재 결과다. 이 값만으로 검색 공개를 확정하지 않는다.
}

async function authorizeKnowledge(springOrigin, serviceToken, input) {
  return requestJson({
    origin: springOrigin, path: "/internal/v2/knowledge/authorizations",
    token: serviceToken, method: "POST", body: input,
  });
}
```

checkOrDeliverIndex는 [I1.1의 수동 복구](#i11-승인-후-미전달-요청의-수동-복구)를 설명하는 호출 예시다. 브라우저 GET·폴링·자동 반복에서 호출하지 않는다. 반영의 202 접수만으로 검색 공개를 확정하지 않는다. 같은 승인 버전의 두 저장소 성공을 Spring이 확인한 뒤 현재 ACL에 맞게 공개한다. 권한 확인 오류를 검색 0건으로 바꾸지 않으며 허용된 참조의 원문만 모델 입력에 사용한다.
