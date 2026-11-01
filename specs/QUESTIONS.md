# PRD 1.44 공통 계약 결정 대장

기준: PRD 1.44 · 결정안 작성일 2026-11-01 · PR #68에 올리는 채택값. 이 문서는 구현자가 선택할 항목을 비워 두지 않는 결정 대장이다. **결정안 작성 완료**, **팀 PR 승인**, **코드 적용**, **실행 검증**을 별도 상태로 관리한다. 현재 결정값은 기입 완료이며 팀의 승인·병합이나 실제 구현 완료를 주장하지 않는다.

## 1. 상태를 읽는 방법

| 구분 | 의미·현재 상태 |
|---|---|
| PRD 정책 | 시스템 PRIMARY·5회 질문·문제별 확인·같은 승인 버전 양쪽 반영을 유지 |
| 계약 채택값 | 아래 C01~C10·S01~S08에 사용할 값/방식을 모두 기입 |
| 팀 PR 리뷰 | #68에서 채택·수정 승인. 리뷰 대기는 빈 설계 항목을 뜻하지 않음 |
| 구현·검증 | 코드·migration·실제 모델·DB·E2E를 실행한 뒤 별도로 완료 표시 |
| 배포 입력 | 비밀키·접속 비밀번호·공용 도메인은 환경 주입. 필드/검증 규칙은 고정하며 비밀값을 문서에 기록하지 않음 |

## 2. 서비스 간 계약 결정

| ID | 이번 PR의 채택값 | 정본 |
|---|---|---|
| C01 | contractVersion=hops-system-1, React↔Spring=/api/v2, 서비스 전용=/internal/v2. 기존 프로필 유지·자동 혼용 없음 | [WIRE 12절](contracts/WIRE_CONTRACT.md#12-신규-계약-hops-system-1) |
| C02 | 문의 소유자·현재 PRIMARY·BIZ_ADMIN을 서버에서 검사. 관리자 상세는 제한 DTO, 보호 구독은 현재 권한 세대로 차단 | [TARGET](contracts/TARGET_CONTRACT.md) |
| C03 | UUID·UTC Z·필수 키/null 규칙, 문의 입력 버전과 메시지/업무/확인/사례 버전 분리. 원문 1~4,000 코드 포인트 | [DATA_TYPES](contracts/DATA_TYPES.md#신규-계약-hops-system-1) |
| C04 | 생성 결과 PENDING/STORED/NOT_CREATED/UNKNOWN. 결과 확인 뒤 확정 미생성만 같은 논리 작업 재시도 | [F005](F005-result-cards/spec.md)·[F010](F010-creation-failures/spec.md) |
| C05 | 검증 완료 WorkRecord별 PENDING 확인 요청 하나. 해결·미해결은 문제별 한 번, 무응답 대기·미해결 같은 티켓 계속 | [F006](F006-my-inquiries/spec.md)·[F009](F009-department-tickets/spec.md) |
| C06 | 원 기록에 고정한 불변 사례 버전·버전별 승인. VECTOR/NEO4J 두 반영 성공 전 양쪽 비공개. 실패만 수동 다음 attempt | [F011](F011-case-lifecycle/spec.md) |
| C07 | AiRunRequest/Result/Event + IndexApplyRequest/Snapshot + KnowledgeAuthorizationRequest/Response, 서비스 Bearer 인증 | [에이전트 계약](../contracts/agents/README.md#hops-system-1-실행-계약안) |
| C08 | 기존 UI 재사용, 독립 티켓 상세와 사례 최소 목록, 채팅 안 해결 확인. 모든 기능에 로딩/오류/권한/충돌/미확인 조작 정의 | [기능 목록](README.md)·[ROUTES](../docs/frontend/ROUTES.md) |
| C09 | workItemId=problemId, 생성 상태는 inquiry_problem, 티켓은 ticket.inquiry_problem_id UNIQUE. 현재 PRIMARY는 it_system의 FK, 당시 배정은 ticket_assignment 이력 | [DATA](../docs/DATA.md#9-hops-system-1-저장-제약안) |
| C10 | AI input 읽기 전용·State 단일 작성자·검증된 patch 병합, 종료 결과 불변. JSON Schema Draft-07로 서비스 wire 검증 | [State 계약](../contracts/agents/README.md#내부-state와-wire-검증-계약) |

<a id="적용-전-선정-항목"></a>

## 실행·검색 기본값

다음은 선택 후보 목록이 아니라 공통 구현에서 사용할 기본값이다. 변경하려면 profileId를 올리고 영향·가격·검증을 함께 PR로 수정한다. 문서 선택은 실제 유료 호출·새 인프라 배포를 실행하는 행위가 아니다.

| ID | 채택값 | 선정 이유·적용 규칙 |
|---|---|---|
| S01 | modelProfileId=hops-mvp-20261101, 모든 역할 gpt-5.6-luna, reasoning.effort=none, Responses API·역할별 Structured Outputs, store=false | 비용 우선의 단일 모델 설정. 역할마다 모델을 승격하거나 다른 공급자로 fallback하지 않음 |
| S01-E | text-embedding-3-small, dimensions=1536, 코사인 거리, 입력 청크 512토큰·겹침64, 상위5건·후보20건 | 문서·CASE 모두 같은 모델/차원. 재임베딩은 별도 버전 컬렉션으로 생성·검증 후 전환 |
| S02 | 아래 실행 한도 표와 요청 전 토큰/비용 예약. SDK 자동 재시도 0, 429/형식 오류 포함 자동 모델 재호출 0 | 호출 수·기한·입출력 토큰·도구·비용을 실행 전체에서 누적 |
| S03 | 기존 PostgreSQL 인스턴스에 pgvector 0.8.7, 지식 DB=hops_knowledge, collection=hops_knowledge_v1, vector(1536) 정확 코사인 검색. Neo4j Community 자체 호스팅·database=neo4j | 초기 작은 데이터에 ANN 인덱스·유료 관리형 Vector 서비스를 추가하지 않음. 그래프는 에이전트 계약의 6종 노드·8종 관계·2홉 제한 |
| S04 | catalogVersion=hops-demo-v1, 시스템3·기능10·현재 PRIMARY3. 결정된 UUID/가상 사용자/관계는 [공용 예시](mocks/hops-system-1.examples.json)의 systems/users/primaryAssignments | UUIDv5 고정 가상 카탈로그. 한 담당자가 전자결재·공통 로그인, 다른 담당자가 파일 공유 담당. 실제 인물/계정 비밀값 없음 |
| S05 | #66의 시스템·문제·티켓 명칭으로 논리 키 매핑, READ COMMITTED + 문의→문제→티켓 순서 행 잠금, 낙관 버전 충돌은409. journal 상세24시간·키 표식/업무 이력 유지, DB outbox·공개 gate 원자화 | DDL 적용·migration 파일 작성은 백엔드 구현 작업. 테이블 수가 달라도 같은 외부 키·불변식 유지. 추가 엔티티 사전 생성 없음 |
| S06 | 개발 frontend=http://localhost:5173, Spring=http://localhost:8080, AI=http://localhost:8000. 공용 환경은 PUBLIC_WEB_ORIGIN 하나에서 프론트와 /api/v2 제공, /internal/v2는 서비스망에서만 접근 | 배포 도메인/비밀번호는 환경 입력, 임의 Origin 허용 없음. Access15분·Refresh 절대7일/매 회전, HttpOnly·운영Secure·SameSite=Lax, Path=/api/v2/auth. 세션 회전/폐기는 #58 Redis, 현재 권한은 PostgreSQL |
| S07 | 최초 평가30건(개발18/보류12), 시스템·의도·대표 예외 분포와 게이트는 아래 검증 기준. prompt/profile/catalog 해시로 동결 | 미래 해결·정답은 KB와 모델 입력에서 분리. 일정은 D1 계약→D2 선정값 적용→통합→동결→시연 순서로 고정하며 완료 날짜를 실행 전 실적으로 기록하지 않음 |
| S08 | 같은 PostgreSQL 인스턴스의 별도 hops_ai_runtime DB·AI 전용 권한. RunLedger PK=runId, 입력 digest 불변·동일 ID 새 모델 실행 금지. index job은 (jobId,attempt) 유일 | AI 계정은 Spring 업무 DB 권한 없음. 재시작 후 진행 중 실행은 UNKNOWN, 중간 checkpoint 자동 재개 없음. 안전한 실패 뒤 새 runId. 상세24시간·사용 ID/digest 표식 유지 |

### 실행 한도

서비스 인증은 Spring→AI의 AI_SERVICE_TOKEN, AI→Spring의 SPRING_SERVICE_TOKEN을 분리한다. 각 토큰은 최소32바이트 난수를 base64url로 환경 주입하고 미설정/빈 값이면 신규 프로필을 기동하지 않는다. PUBLIC_WEB_ORIGIN·SPRING_SERVICE_ORIGIN·AI_SERVICE_ORIGIN은 경로/query/fragment 없는 고정 origin이며 공용 환경은 HTTPS만 허용한다. 개발 CORS는 위 frontend origin과 credentials만 허용하고 wildcard를 사용하지 않는다.

| 값 | INQUIRY_ANALYSIS | CASE_DRAFT |
|---|---:|---:|
| 전체 기한 | 60초 | 30초 |
| 모델 호출 합계 | 8 | 3 |
| 입력 토큰 합계 | 20,000 | 12,000 |
| 출력 토큰 합계(과금 reasoning 포함) | 4,000 | 2,500 |
| 도구 호출 합계 | 8 | 4 |
| 자동 모델 재시도 | 0 | 0 |
| 실행당 비용 한도 USD | 0.02 | 0.01 |

개별 모델 호출 timeout=min(15초,남은 실행 기한), 개별 max_output_tokens=min(1,024,남은 출력 예산)이다. 그래프의 최대 전이 수는32이며 토큰/시간 제한을 대신하지 않는다. 매 호출 전에 예상 입력+최대 출력 비용을 예약하고 실제 usage로 정산한다. 결과 유실은 예약을 즉시 환불하지 않는다. 공유 가상 시연 환경의 모델+임베딩 일일 합계는 USD1.00(UTC 자정 경계)로 제한하며 초과 시 429 AI_BUDGET_EXHAUSTED·Retry-After를 반환한다. 외부 과금 상한을 보장하는 결제 설정이 아니라 애플리케이션에서 새 호출을 차단하는 한도다.

가격 계산 기준일은 2026-11-01, 100만 토큰당 USD 기준 gpt-5.6-luna 입력0.20/출력1.20, text-embedding-3-small 입력0.02를 사용한다. 할인·캐시 적중을 가정하지 않는 보수적 계산이다. [OpenAI 모델](https://developers.openai.com/api/docs/models/gpt-5.6-luna), [임베딩 모델](https://developers.openai.com/api/docs/models/text-embedding-3-small), [차원 안내](https://developers.openai.com/api/docs/guides/embeddings), [pgvector](https://github.com/pgvector/pgvector)를 확인했다. 모델 접근 불가·스키마 거부는 안전한 오류로 종료하고 임의 대체하지 않는다. alias의 실제 반환 모델 ID·가격 기준을 실행 기록에 남기고 조건 변경은 profile 재검증으로 처리한다.

### 카탈로그와 검증 기준

기능10개는 전자결재(문서작성·문서조회·결재상태·첨부열람), 공통 로그인(로그인/SSO·계정접근·권한신청), 파일 공유(파일열람·파일공유·접근권한)다. 지원 기능 ID와 담당 관계는 공용 예시의 고정 카탈로그가 정본이다. Neo4j 카탈로그의 System/Feature는 같은 업무 ID를 쓰며 Symptom/Cause/Check는 검수 seed의 출처 ID, CASE는 caseId+caseVersion을 쓴다.

평가30건의 범위는 전자결재10·공통 로그인10·파일 공유8·범위 밖2, 개발/보류 분할은 각6/4·6/4·5/3·1/1이다. 의도는 사용법6·권한6·현상8·기능추가4·개선4·범위밖2로 구성한다. UNKNOWN은 중간 불확실성으로 따로 검증한다. 표현 변형은 원 사건과 같은 분할에 둔다.

출시 게이트는 계약별 정상/오류 검사 전부 통과, 무권한 공개·중복 티켓·5회 초과·무응답 자동 종료·양쪽 반영 전 CASE 공개 0건, 보류12건의 의도/책임 시스템 정답 각각10/12 이상, 근거 없는 해결 성공 단정0건, 승인→양쪽 반영→재검색 실제 연결1건 이상이다. API 저장 ack p95≤2초·첫 공개 진행 p95≤3초·상담/초안 전체는각60/30초 이내를 목표로 측정한다. 이는 합격 기준이며 아직 측정한 결과가 아니다.

## 3. 계약 범위와 spec 연결

| 경계 | 문서 정본 | 연결 spec | 포함 범위 |
|---|---|---|---|
| Frontend↔Spring | DATA_TYPES 신규 절·WIRE12절 | F001~F011 | 명령/조회·nullable/enum·버전·HTTP/오류·멱등/복구·SSE·화면 조작 |
| Spring↔AI 실행 | 에이전트 README·wire JSON Schema | F002/F003/F004/F005/F008/F010/F011 | 요청/수락/snapshot/진행/최종결과/취소·State·인증·예산 |
| Spring→AI 반영 | 에이전트 README·IndexApplyRequest/Snapshot | F011 | 승인 스냅샷·저장소·attempt·상태·digest·부분 실패 |
| AI→Spring 검색 권한 | 에이전트 README·KnowledgeAuthorizationRequest/Response | F001/F003/F009/F011 | 현재 run·policyRevision·후보 문서/CASE 버전·허용 참조 |
| wire→저장 | DATA9/10절 | F004/F005/F006/F007/F009/F010/F011 | 논리 작업 키·당시/현재 담당·원 기록·버전·잠금·원자 공개 |

## 4. 변경·승인 기록

PR #68에 위 채택값을 제출한다. 팀원은 빈 항목을 대신 설계하는 대신 이 결정값의 수용 여부와 변경 사유를 리뷰한다. 승인·병합 뒤 채택 계약으로 적용하며 실제 코드/DB/환경 연결과 검증 결과는 구현 PR에서 별도 기록한다. 후속 제안은 기존 기준을 유지한 채 비교하고 공통값을 바꾸는 PR을 작성한다.
