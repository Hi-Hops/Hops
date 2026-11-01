# PRD 1.44 공통 계약 결정 대장

기준: PRD 1.44 · 결정안 작성일 2026-11-01 · PR #68의 공통 계약·실행 기준을 기록한다. LLM·임베딩 모델 선정, 단가·예상 비용·금액 예산과 구체 실행 설정값은 이 문서의 결정 범위에서 제외한다. **계약안 작성**, **팀 PR 승인**, **코드 적용**, **실행 검증**을 별도 상태로 관리하며 문서 기입을 승인·병합이나 구현 완료로 취급하지 않는다.

## 1. 상태를 읽는 방법

| 구분 | 의미·현재 상태 |
|---|---|
| PRD 정책 | 시스템 PRIMARY·5회 질문·문제별 확인·같은 승인 버전 양쪽 반영을 유지 |
| 계약 채택값 | C01~C11의 공통 계약과 S01~S08의 실행·저장 기준을 기록. 모델·비용·구체 실행 설정값은 고정하지 않음 |
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
| C06 | 현재 초안 편집·승인 후 읽기 전용. 승인과 함께 기존 문서 버전의 indexRequests를 보존하고 미전달은 같은 키로 수동 재접수. FAILED에서만 새 attempt, 현재 승인 버전의 양쪽 성공 전 비공개 | [F011](F011-case-lifecycle/spec.md)·[수동 복구](../contracts/agents/README.md#i11-승인-후-미전달-요청의-수동-복구) |
| C07 | AiRunRequest/Result/Event + IndexApplyRequest/Snapshot + KnowledgeAuthorizationRequest/Response, 서비스 Bearer 인증 | [에이전트 계약](../contracts/agents/README.md#hops-system-1-실행-계약안) |
| C08 | 현재 frontend UI를 구현 기준으로 채택. 독립 티켓 상세와 사례 최소 목록·채팅 안 해결 확인을 기존 구성/토큰으로 확장하고 기능 책임·상태 소유를 고정 | [UI 기준](../docs/frontend/README.md#ui-기준과-이번-작업-범위)·[목표 구조](../docs/frontend/STRUCTURE.md#6-hops-system-1-목표-구조)·[ROUTES](../docs/frontend/ROUTES.md) |
| C09 | workItemId=problemId, 생성 상태는 inquiry_problem, 티켓은 ticket.inquiry_problem_id UNIQUE. 현재 PRIMARY는 it_system의 FK, 당시 배정은 ticket_assignment 이력 | [DATA](../docs/DATA.md#9-hops-system-1-저장-제약안) |
| C10 | AI input 읽기 전용·State 단일 작성자·검증된 patch 병합, 종료 결과 불변. 외부 DTO의 필드·타입·조건을 Markdown 계약으로 정의 | [State 계약](../contracts/agents/README.md#내부-state와-wire-검증-계약) |
| C11 | 상담 run당 최종 AI 메시지 하나. Spring 발급 answerMessageId를 입력·delta·최종 저장에 사용. answer가 채팅 본문 정본, questions/guide는 본문에 포함된 내용의 메타데이터. 답변·질문/가이드 상태 저장 뒤 공개 Run 성공·REST 본문으로 임시 답변 교체 | [채팅 송수신](contracts/WIRE_CONTRACT.md#128-채팅-송수신-완료-규칙)·[AI 답변 연결](../contracts/agents/README.md#상담-답변과-저장-메시지-연결) |

문의 종합 상태는 [TARGET 4절](contracts/TARGET_CONTRACT.md#4-문의문제질문)의 집계를 채택한다. PRD 3.1절의 범위 안내와 3.4절의 해결 확인을 구분하도록 모든 문제가 OUT_OF_SCOPE인 문의에만 CLOSED를 사용한다. DATA_TYPES의 누락값과 DATA11.2절/ERD6.1절의 저장 보완안을 일치시켰으며, 무응답 자동 종료는 추가하지 않는다. 화면 확인 조건은 F006-CONTRACT-07~09이며, [호출과 진행 예시](../contracts/agents/README.md#호출과-진행-예시)에서 조회·표시 코드를 확인한다.

<a id="적용-전-선정-항목"></a>

## 실행·검색 기본값

다음은 서비스 연결·검색·저장의 검토 기준이며 담당자 리뷰·승인 후 적용한다. 특정 LLM·임베딩 모델명, 역할별 모델 배분, reasoning 설정, 모델 프로필, 임베딩 차원·단가·예상 비용·금액 예산과 실행 상한의 구체 수치는 정하지 않는다. 모델·과금 설정은 서비스 입출력 계약과 분리하며 문서 작성만으로 유료 호출이나 새 인프라 배포를 실행하지 않는다.

| ID | 채택값 | 선정 이유·적용 규칙 |
|---|---|---|
| S01 | OpenAI API·역할별 구조화 출력·서버의 API 키 관리 원칙 유지. 모델·과금 설정은 이 계약에서 제외 | AiRunRequest는 모델 선택·가격·금액 예산을 전달하지 않음. 모델 접근·형식 호환·품질은 실제 구현에서 검증 |
| S01-E | 구체 임베딩 모델·차원·단가는 선정 범위에서 제외. 코사인 검색·청크512토큰·겹침64·상위5건·후보20건은 검색 검토 기준 | 모델·토크나이저 선정 후 청크와 차원을 검증. 문서와 CASE의 검색 설정 버전을 기록하고 호환되지 않는 벡터를 혼용하지 않음 |
| S02 | 실행 요청의 expiresAt과 ExecutionLimits 형식만 정의. 기한·호출·토큰·도구·재시도 상한의 구체 수치는 실행 설정에서 별도 관리 | 실행기가 유효한 서버 설정과 요청을 검증하고 실행 전체에서 누적. 역할 전환으로 한도를 초기화하지 않음 |
| S03 | 기존 Spring PostgreSQL DB의 hops_knowledge schema에 pgvector 0.8.7, collection=hops_knowledge_v1, 정확 코사인 검색. 벡터 차원은 모델 선정 후 결정. Neo4j Community 자체 호스팅·database=neo4j | 초기 작은 데이터에 ANN 인덱스·유료 관리형 Vector 서비스를 추가하지 않음. 그래프는 에이전트 계약의 6종 노드·8종 관계·2홉 제한 |
| S04 | catalogVersion=hops-demo-v1, 시스템3·기능10·현재 PRIMARY3. 결정된 UUID/가상 사용자/관계는 [DATA 2절](../docs/DATA.md#2-고정-카탈로그)의 seed 작성 코드 | UUIDv5 고정 가상 카탈로그. 한 담당자가 전자결재·공통 로그인, 다른 담당자가 파일 공유 담당. 실제 인물/계정 비밀값 없음 |
| S05 | #69 원본22개 테이블 사용. 승인과 현재 요청을 기존 source_metadata.indexRequests에 원자 저장하고 commit 뒤 전달. 미전달은 기존 확인 조작에서 같은 요청으로 수동 복구 | 새 테이블·추가 컬럼·자동 전달 큐 없음. 복수 승인 버전·전체 시도 이력은 후순위. DDL/코드 적용은 별도이며 DATA/ERD6절의 기존 컬럼·DTO 대응을 검토 |
| S06 | 개발 frontend=http://localhost:5173, Spring=http://localhost:8080, AI=http://localhost:8000. 공용 환경은 PUBLIC_WEB_ORIGIN 하나에서 프론트와 /api/v2 제공, /internal/v2는 서비스망에서만 접근 | 배포 도메인/비밀번호는 환경 입력, 임의 Origin 허용 없음. Access15분·Refresh 절대7일/매 회전, HttpOnly·운영Secure·SameSite=Lax, Path=/api/v2/auth. 세션 회전/폐기는 #58 Redis, 현재 권한은 PostgreSQL |
| S07 | 데이터 구축·평가 수량과 분할은 [데이터 구축 계획](../docs/knowledge/DATASETS.md#소량-검증과-수량-제안)의 검토안으로 관리하며 실제 선정은 별도 | 계약 정상/거부/실패 검사와 모델 품질 평가를 구분하고 미래 해결·정답은 KB와 모델 입력에서 분리 |
| S08 | 같은 PostgreSQL 인스턴스의 별도 hops_ai_runtime DB·AI 전용 권한. RunLedger PK=runId, 입력 digest 불변·동일 ID 새 모델 실행 금지. index job은 (jobId,attempt) 유일 | AI 계정은 Spring 업무 원본 쓰기 권한 없음. 검색 조각 권한과 ledger 권한은 각각 제한. 재시작 후 진행 중 실행은 UNKNOWN, 중간 checkpoint 자동 재개 없음. 안전한 실패 뒤 새 runId. 상세24시간·사용 ID/digest 표식 유지 |

### 서비스 인증과 실행 입력

서비스 인증은 Spring→AI의 AI_SERVICE_TOKEN, AI→Spring의 SPRING_SERVICE_TOKEN을 분리한다. 각 토큰은 최소32바이트 난수를 base64url로 환경 주입하고 미설정/빈 값이면 신규 프로필을 기동하지 않는다. PUBLIC_WEB_ORIGIN·SPRING_SERVICE_ORIGIN·AI_SERVICE_ORIGIN은 경로/query/fragment 없는 고정 origin이며 공용 환경은 HTTPS만 허용한다. 개발 CORS는 위 frontend origin과 credentials만 허용하고 wildcard를 사용하지 않는다.

Spring은 서버에서 준비한 expiresAt·limits를 AI 실행 입력에 넣고 AI는 기한·누적 한도를 검증한다. 이 문서는 실행당·일일 금액, 단가·예상 비용, 모델 프로필과 구체 실행 상한을 정의하지 않는다. 추가 사용자 발화의 재분석은 새 run이며 모델 접근 불가·형식 거부를 임의의 다른 모델로 우회하거나 업무 성공으로 처리하지 않는다.

### 카탈로그와 검증 기준

기능10개는 전자결재(문서작성·문서조회·결재상태·첨부열람), 공통 로그인(로그인/SSO·계정접근·권한신청), 파일 공유(파일열람·파일공유·접근권한)다. 이 목록과 DATA2절의 ID는 계약 호출 검토용 데모 카탈로그다. 실제 지식·평가 데이터의 선정 완료를 뜻하지 않으며 기능·업무 규칙은 가상 기업 명세와 데이터 검토 결과에 맞춰 별도 선정한다. Neo4j 카탈로그의 System/Feature는 같은 업무 ID를 쓰며 Symptom/Cause/Check는 검수 seed의 출처 ID, CASE는 caseId+caseVersion을 쓴다.

평가 사건 수·분포·개발/테스트 분할·정확도 목표는 [데이터 구축 계획](../docs/knowledge/DATASETS.md#검수와-평가)을 따르며 이 계약에서 선정 완료로 표시하지 않는다.

출시 게이트는 계약별 정상/오류 검사 전부 통과, 무권한 공개·중복 티켓·5회 초과·무응답 자동 종료·양쪽 반영 전 CASE 공개 0건, 근거 없는 해결 성공 단정0건, 승인→양쪽 반영→재검색 실제 연결1건 이상이다. API 저장 ack p95≤2초·첫 공개 진행 p95≤3초를 목표로 측정하고 상담·초안은 요청의 expiresAt 안에서 종료되는지 검사한다. 이는 합격 기준이며 아직 측정한 결과가 아니다.

## 3. 계약 범위와 spec 연결

| 경계 | 문서 정본 | 연결 spec | 포함 범위 |
|---|---|---|---|
| Frontend↔Spring | DATA_TYPES 신규 절·WIRE12절 | F001~F011 | 명령/조회·nullable/enum·버전·HTTP/오류·멱등/복구·SSE·화면 조작 |
| Spring↔AI 실행 | 에이전트 README의 외부 DTO·검증 규칙 | F002/F003/F004/F005/F008/F010/F011 | 요청/수락/snapshot/진행/최종결과/취소·State·인증·예산 |
| Spring→AI 반영 | 에이전트 README·IndexApplyRequest/Snapshot | F011 | 승인 스냅샷·저장소·attempt·상태·digest·부분 실패 |
| AI→Spring 검색 권한 | 에이전트 README·KnowledgeAuthorizationRequest/Response | F001/F003/F009/F011 | 현재 run·policyRevision·후보 문서/CASE 버전·허용 참조 |
| wire→저장 | DATA9/10절 | F004/F005/F006/F007/F009/F010/F011 | 논리 작업 키·당시/현재 담당·원 기록·버전·잠금·원자 공개 |

## 4. 변경·승인 기록

PR #68에 위 채택값을 제출한다. 팀원은 빈 항목을 대신 설계하는 대신 이 결정값의 수용 여부와 변경 사유를 리뷰한다. 승인·병합 뒤 채택 계약으로 적용하며 실제 코드/DB/환경 연결과 검증 결과는 구현 PR에서 별도 기록한다. 후속 제안은 기존 기준을 유지한 채 비교하고 공통값을 바꾸는 PR을 작성한다.

## 5. 완성 ERD 반영 상태

**PR #69로 병합된 docs/ERD.md(기준 `d896faeb2a0b132da2c3212cba408bf880d797cf`)의 E01~E10 대조 완료.** [DATA11절](../docs/DATA.md#11-완성-erd-대조-결과와-병합-기준)에 기존 컬럼·DB↔wire 상태·JSONB 대응을 기록했다. 현재 MVP는 원본22개 테이블로 진행하고 4개 추가 테이블 제안은 반영하지 않는다. 확인은 기존 이벤트·메시지 메타데이터, 사례는 현재 행·승인 뒤 읽기 전용, 반영은 기존 두 상태 컬럼을 사용한다. 상세 매핑과 후순위 범위는 [ERD6절](../docs/ERD.md#6-hops-system-1-공통-계약-보완)을 따른다.

현재 PRIMARY DTO, 실패 처리 기록과 원인/적용 버전, CASE 문제 설명/종류, 공통 POLICY의 null systemId, ticketRevision=DB version+1을 채택한다. 프론트는 wire 상태를 사용하고 기존 디자인·경로/메뉴를 재사용한다. FK를 유지하기 위해 지식 검색은 같은 PostgreSQL DB의 hops_knowledge schema에 두며 AI 계정의 업무 원본 쓰기는 금지한다.

남은 절차는 보완 설계의 백엔드 리뷰·팀원 승인과 실제 코드/DB/검색/E2E 구현 검증이다. ERD 대조를 다시 기다리는 상태로 두지 않는다. 문서 병합 조건은 DATA11.4절, 실제 구현 우선순위는 프론트 README를 따른다.

## 6. 이번 단계에서 고정한 적용 기준

| 범위 | 작업 결정값 | 구현·승인과의 구분 |
|---|---|---|
| Contract | 외부 DTO·권한·HTTP/SSE·AI wire 형식 유지. 저장은 기존22개 테이블, 사례는 현재 초안 편집·승인 후 읽기 전용 | 서비스 구현과 DB 적용 완료를 뜻하지 않음 |
| Spec | F001~F011의 MVP 정상 흐름과 기본 상태 검증 우선. 복수 승인 버전·반영 전체 이력·영속 전달 복구는 후순위 | 현재 코드의 이전 AC 검사로 신규 기능 완료를 대신하지 않음 |
| UI | develop d896fae의 실제 프론트 UI와 공통 컴포넌트를 기준으로 기능 확장. 새 Figma를 구현 기준에서 제외 | 화면 세부 구현/접근성/브라우저 검증은 실제 작업에서 수행 |
| Frontend 구조 | app→pages→features→shared, auth/realtime/chat/inquiry/ticket/case의 책임·데이터 소유·프로필 경계 | 목표 파일/폴더는 구현 때 생성하고 현재 frontend 소스/Mock을 유지 |
| 물리 저장 | 원본22개 테이블과 ERD6절의 기존 컬럼·DTO 대응 사용. 새 테이블 없음 | 기존 컬럼 보완도 실제 DB 적용·코드 구현과 구분하며 예외 복구·이력 확장은 후순위 |

이 결정은 이번 브랜치에서 사용할 구체 기준이며 팀 공통 기준의 효력은 PR 리뷰/병합 절차를 따른다. 이번 단계에서는 신규 필드·엔드포인트·물리 테이블·모델 기본값을 추가 선택하지 않고, 현재 계약과 구현 범위를 기준으로 다음 프론트 작업을 진행한다.
