# MVP 합의 질문 목록

상태: **초안 · 팀 답변 대기** · 작성 2026-10-21 · 작성: frontend
기준 문서: PRD v1.41, API.md, DATA.md, ERD_설명.md (develop 기준)

[프론트엔드 기능 스펙](README.md)을 확정하려면 아래 질문에 답이 필요하다. 원본 문서가 "구현 전 확정", "초안", "후보"로 남겨 둔 항목과 문서끼리 다른 항목만 모았다. 답이 정해지면 해당 원본 문서(API.md·DATA.md·ERD)를 고치고, 스펙의 판정을 "확정"으로 바꾼다.

- **우선순위** 1: 첫 E2E 착수 전에 필요(PRD 8.5) · 2: 해당 기능 구현 전에 필요 · 3: 화면 표시 수준
- **막힘**: 답이 없을 때 프론트가 할 수 있는 정도. "목 가능"은 가짜 데이터로 화면을 먼저 만들 수 있다는 뜻이다.
- **프론트 제안**은 결정이 아니라 논의 출발점이다.
- **#22/#38** 열: 이슈 #22(MVP 최소 계약 확정)와 Draft PR #38(Spring–Python 입출력 논의 자료)과의 관계. 표기는 아래 0절을 따른다.

## Mock 구현 프로필 추가 (2026-10-23)

[공통 데이터 타입 문서](contracts/README.md)와 [공용 fixture](mocks/README.md)에 로그인·채팅·부서 티켓 조회의 테스트 표현을 추가했다. 아래 질문을 확정 처리한 것은 아니다. 관련 질문과 Mock 제안의 대응은 공통 계약 README를 따른다. 출시 범위·JWT 운영 정책·서비스 인증은 계속 팀 합의 대상이다.

## 0. 이슈 #22·PR #38과의 연결

이슈 #22는 첫 MVP 연결에 필요한 최소 계약을 담당자 간에 합의하는 이슈다. JWT·Node.js Socket.IO 채택과 역할 경계는 PR #15로 반영됐으므로 다시 묻지 않는다(이 목록의 Q-INF-1은 채택이 아니라 버전 선정이다). PR #38은 Draft 논의 자료이며 승인된 계약이 아니다. 따라서 이 목록에서 **답이 확정된 질문은 없다.** PR #38에 제안 형태의 근거가 있는 질문만 "참고"로 표시한다.

#22 완료 조건은 체크리스트 순서대로 번호를 붙여 부른다(1·2는 완료).

| 번호 | #22 완료 조건 | 이 목록의 관련 질문 |
|---|---|---|
| #22-3 | Spring–Python 요청·결과의 필드명·타입·필수/선택·null·식별자·근거·오류 표현 | Q-ALL-3, Q-ALL-4, Q-BE-3, Q-BE-4, Q-AI-3, Q-AI-5 |
| #22-4 | 실행 접수·SSE 진행·최종 결과·서비스 인증·단절/재시작 복구·중복/늦은 결과·취소 | Q-AI-1, Q-AI-2, Q-BE-8 |
| #22-5 | 공유 코드·서버 검증 책임과 실행/모델/재시도 예산 | Q-ALL-2, Q-AI-4, Q-BE-8, Q-BE-15 |
| #22-6 | #20 인증과 채팅/문의 담당의 연결 계약: JWT 상세 정책, 명령/저장 ACK, 저장 전용 메시지 응답, 새 문의 동의와 관련 대화 권한 | Q-ALL-7, Q-BE-1, Q-BE-2, Q-BE-10, Q-BE-13, Q-BE-14, Q-INF-4, Q-INF-5, Q-INF-6 |
| #22-7 | 합의 내용을 API 문서와 최소 계약 안내에 반영 | Q-BE-9 |

표기:
- **겹침 #22-n**: #22의 해당 완료 조건이 같은 결정을 다룬다. 그 논의에서 함께 답을 받는다.
- **참고 #38**: PR #38에 제안 형태의 근거가 있다. 확정이 아니므로 스펙 판정은 바꾸지 않는다.
- **—**: #22·#38 범위 밖. 해당 담당자에게 따로 묻는다.

## 요약

| 담당 | 질문 수 | 우선순위 1 | #22와 겹침 |
|---|---|---|---|
| 전원 | 7 | Q-ALL-2, Q-ALL-4 | 4 |
| backend | 15 | Q-BE-1, Q-BE-2, Q-BE-3, Q-BE-4, Q-BE-9 | 10 |
| infra | 7 | Q-INF-1, Q-INF-2, Q-INF-3, Q-INF-6 | 3 |
| ai | 5 | Q-AI-1 | 5 |
| frontend | 2 | — | 0 |

## 1. 전원

| ID | 질문 | 근거·현재 상태 | 관련 스펙 | 우선 | 막힘 | 프론트 제안 | #22/#38 |
|---|---|---|---|---|---|---|---|
| Q-ALL-1 | 내 문의 목록·상세를 MVP에 넣는가? | API 2.1 MVP 표에는 `GET /api/inquiries`가 있으나, PRD 8.2 MVP 설명에는 없고 8.3에 "본인 티켓 목록"이 후속으로 있음. 이슈 #31 작업 목록에는 포함 | F006 | 2 | 목 가능 | 상세는 MVP(채팅 재진입 경로), 목록은 단순 최신순만 MVP | — |
| Q-ALL-2 | 상태값 코드와 화면 표시명을 하나로 정한다 | ERD: 문의 `IN_PROGRESS/WAITING_USER/RESOLVED/CLOSED_UNCONFIRMED/CLOSED`, 처리 `WAITING/IN_PROGRESS/WAITING_CONFIRM/RESOLVED/CLOSED_UNCONFIRMED`는 코드 있음. 배정 상태·실행 상태·복구 상태(`recovery_status`)는 한글 설명만, 문제 상태·새 문의 동의 상태는 값 목록 없음. 문의 유형 6개(PRD 3.1)의 코드도 없음 | F003, F005, F006, F007, F008, F009, F010 | 1 | 목 가능(이름만 임시) | 상태별 표 하나(코드·표시명·설명·색)를 API.md 부록이나 OpenAPI enum으로 고정 | 겹침 #22-5(공유 코드) · 참고 #38(문제별 `action`: `PROPOSE_TICKETS`, `ASK_USER` 제안) |
| Q-ALL-3 | API 필드 이름과 ERD 컬럼 이름의 대응 | API `processingStatus`·`routingStatus`·`questionCount` ↔ ERD `ticket_status`·`routing_status`·`clarify_count`. 메시지 발신자 ERD `USER/AI` | F005 | 2 | 목 가능 | API는 camelCase 이름을 따르고, 대응표를 DATA.md에 둠 | 겹침 #22-3(필드명) · 참고 #38(camelCase 키 사용) |
| Q-ALL-4 | 오류 코드(`code`) 목록 | API 1은 오류 외피와 예시 `INQUIRY_VERSION_CONFLICT` 하나뿐. Socket.IO ACK 오류도 "REST와 의미를 맞춘다"(API 2.4) | 전체 | 1 | 목 가능 | MVP 코드 목록(인증·권한·검증·버전 충돌·중복·일시 장애·시간 초과)을 먼저 정하고 화면 문구를 붙임 | 겹침 #22-3(오류 표현) · 참고 #38("기술 실패와 재시도 가능 여부"를 결과 정보로 제안) |
| Q-ALL-5 | 화면 접근 권한 표 | ERD 역할은 `BIZ_ADMIN`, `AGENT_OPS_ADMIN`뿐이고 문의자·담당자는 소속 부서로 판단. 한 사람이 여러 역할을 가질 수 있음(PRD 2) | F001, F007, F009, F010 | 2 | 목 가능 | 화면별 허용 조건 표(문의자=로그인 사용자, 부서 티켓=현재 처리 부서 구성원, 관리자 화면=`BIZ_ADMIN`, 복구 화면=`AGENT_OPS_ADMIN`)를 라우팅 명세(#6)에 둠 | — |
| Q-ALL-6 | 채팅 예시 질문을 MVP에 넣는가? | PRD 4.4 "예시 질문은 선택 즉시 해당 질문을 문의". 최신 ERD 설명에는 예시 질문 테이블(`support_scenario`)이 없음. PRD 8.2에는 없음 | F003 | 3 | 영향 적음 | 후속 | — |
| Q-ALL-7 | 이슈 #30·#31에는 있지만 원본 문서가 후속으로 둔 항목을 MVP 완료 조건에 넣는가? | ① 새 문의 분리 동의: 이슈 #30 포함, PRD 8.2에 없음, DATA 7 "후속 기능별" ② 티켓 관련 대화 `GET /api/tickets/{id}/messages`: 이슈 #31 포함, API 2.2(후속) ③ 생성 실패 재시도·복구 완료 구분: 이슈 #31 포함, API 2.2 `/api/operator/recovery-cases`(후속), PRD 8.3 "기술 복구 화면" ④ 부서 티켓 목록 화면: API 2.1(MVP)에 경로 있음, PRD 8.3 "부서·본인 티켓 목록" 후속 | F008, F009, F010 | 2 | 목 가능 | 이슈 문구("합의된 새 문의 동의", "허용된 관리자")대로 계약이 합의된 항목만 MVP에 넣는다. 합의되지 않으면 화면 틀만 만들고 이슈에 후속 이동을 기록 | 겹침 #22-6(새 문의 동의와 관련 대화 권한) |

## 2. backend (Spring)

| ID | 질문 | 근거·현재 상태 | 관련 스펙 | 우선 | 막힘 | 프론트 제안 | #22/#38 |
|---|---|---|---|---|---|---|---|
| Q-BE-1 | 로그인 응답과 `/api/auth/me` 응답 필드 | API 2.1: 로그인은 "액세스 JWT·만료 정보·사용자 정보", 필드는 "인증 계약에서 확정" | F001 | 1 | 목 가능 | 로그인 `{accessToken, expiresAt, user}`, `/me` `{id, employeeNo, name, department:{id,code,name}, roles[]}` | 겹침 #22-6(JWT 상세 정책, #20 인증) |
| Q-BE-2 | JWT 브라우저 보관·갱신·CSRF (infra와 함께) | API 2.5·PRD 8.5 "첫 E2E 착수 전" 결정 항목. 리프레시 토큰 도입 여부 미정 | F001, F002 | 1 | **구현 보류** | 결정 필요. 선택지: ① 메모리 보관 + 만료 시 재로그인(단순, 새로고침 시 재로그인) ② HttpOnly 쿠키 리프레시 + 메모리 액세스 토큰(CSRF 범위 정해야 함) | 겹침 #22-6(JWT 상세 정책, #20 인증) |
| Q-BE-3 | 문의 버전을 어디에 저장하는가? | API·DATA는 `inquiryVersion`/`expectedInquiryVersion`과 "추가·정정 시 문의 버전 증가"를 쓰지만 ERD `inquiry`에 버전 컬럼이 없음 | F003, F004, F008 | 1 | 목 가능 | `inquiry.version` 추가 | 겹침 #22-3(식별자) · 참고 #38(요청·결과에 정수 `inquiryVersion` 사용) |
| Q-BE-4 | 문제·처리 작업·티켓 관계 | ERD는 문제 1개 = 티켓 1개(UNIQUE), DATA 2는 문제 하나에 작업·티켓 여러 개와 `ticket_work_item` 후보. ERD 스스로 "조정 필요" 표기. 생성 실패의 `workItemId`가 가리킬 테이블이 없음 | F005, F010 | 1 | 목 가능 | DATA 기준으로 ERD 갱신 | 겹침 #22-3(식별자) · 참고 #38(입력에 "기존 문제·생성 작업과 식별자", 결과 `ticketProposals[]`가 `problemRef`로 문제를 가리킴) |
| Q-BE-5 | 목록 페이지·정렬·필터 | API 1 "기본 20건·최대 100건은 구현 전 확정". 문의·티켓·생성 실패 목록의 정렬·필터 파라미터 없음 | F006, F007, F009, F010 | 2 | 목 가능 | 기본 20, 최대 100, MVP 정렬은 최신순 고정. 필터는 쿼리 이름을 ERD 컬럼명 기준 camelCase로 | — |
| Q-BE-6 | 티켓 목록 범위와 상세 응답 | `GET /api/tickets`가 부서·개인·업무 관리자 범위를 함께 다룸(API 2.1). 범위 구분 방식, 배정 상태 필터, 상세 응답 필드가 없음 | F007, F009 | 2 | 목 가능 | 역할·소속 부서로 범위 결정, `routingStatus`·`processingStatus` 필터 지원. 상세는 문의 요약·AI 진단·현재 부서·배정 이력 | — |
| Q-BE-7 | 대화 조회 형식 | `GET /api/inquiries/{id}/messages` "대화순 메시지와 정정 이력". 페이지 방식(순번 커서/페이지 번호)과 이력 포함 방식 미정 | F003, F004, F009 | 2 | 목 가능 | `sequenceNo` 기준 커서, 메시지마다 `revisions[]` 포함. 티켓 관련 대화(F009)도 같은 형식 | 참고 #38(분석 입력 메시지에 `messageId`, `revision` 사용) |
| Q-BE-8 | 실행 상태 응답과 재시도 조건 | `GET /api/runs/{id}`, `POST /api/runs/{id}/retries`. 문의자가 재시도할 수 있는 상태(시간 초과·실패)와 생성 실패 재시도 권한(운영 관리자만, API 2.1)의 경계 | F003, F005, F010 | 2 | 목 가능 | 문의자는 실행 시간 초과·실패만 재시도, 생성 실패는 안내만 | 겹침 #22-4(복구), #22-5(재시도 예산) |
| Q-BE-9 | MVP OpenAPI 파일 위치와 작성 담당 | API.md "OpenAPI/JSON Schema와 계약 테스트로 고정". 현재 저장소에 OpenAPI 파일 없음(`contracts/agents/`만 있음) | 전체 | 1 | 장기적으로 막힘 | `contracts/api/openapi.yaml`(REST), `contracts/socket/`(이벤트 스키마) | 겹침 #22-7 · 참고 #38(`contracts/mvp/README.md`를 추가하며 "기계 판독 명세의 원본·생성 방식은 #24에서" 정한다고 함) |
| Q-BE-10 | 메시지 저장 결과의 `effect` 값 | API 2.4 저장 결과에 `effect` 포함, "분석 실행과 정보 보완 저장을 구분". 값 목록 없음 | F003, F004 | 2 | 목 가능 | `ANALYSIS_STARTED`, `SUPPLEMENT_SAVED` 등 | 겹침 #22-6(저장 전용 메시지 응답) |
| Q-BE-11 | 정정 제한 | 정정 가능한 메시지 범위·횟수·기간 제한 여부가 문서에 없음 | F004 | 3 | 목 가능 | 본인 메시지, 제한 없음 | — |
| Q-BE-12 | 티켓 카드의 번호·부서 형식 | API 카드 필드에 티켓 번호가 없음(ERD `ticket_number` TK-0001 있음). `department` 형식 미정 | F005, F007, F009 | 3 | 목 가능 | 카드에 `ticketNumber` 추가, `department:{id,code,name}` | 참고 #38(Python 제안에 `departmentCode` 사용. 화면 응답 형식과는 별개) |
| Q-BE-13 | 새 문의 분리 동의의 후보 전달·ACK·연결 표시 | API 2.4에 응답 이벤트 `inquiry.split.respond`만 있고, 후보를 React에 보내는 방법(메시지 `payload` 또는 별도 이벤트), 후보 문맥·출처 형식, 동의 상태 값과 만료 여부, ACK의 새 문의 ID 필드, 원본·새 문의 연결을 조회할 필드가 없음. ERD에 저장 위치 없음(DATA 2). API 2.1 "동의 대상 ID·버전·상태는 구현 전 확정" | F008 | 2 | 목 가능 | 후보는 AI 메시지 `payload`에 `{consentId, contextSummary, sourceMessageIds[]}`로 싣고, ACK는 `{inquiryId, newInquiryId?, consentStatus}`. 문의 상세에 `splitFrom`/`splitTo` 연결 | 겹침 #22-6(새 문의 동의) |
| Q-BE-14 | 티켓 관련 대화의 범위와 권한 | `GET /api/tickets/{id}/messages`(API 2.2): "메시지↔문제/티켓 연결 기준은 구현 전 확정". 업무 관리자가 관련 대화를 볼 수 있는지 문서에 없음(API 2.2는 "현재 처리 부서 구성원"만 언급). 재이관 뒤 이전 부서의 접근 회수 시점 | F007, F009 | 2 | 목 가능 | 응답은 Q-BE-7과 같은 메시지 형식, 업무 관리자는 읽기 허용 여부를 명시 | 겹침 #22-6(관련 대화 권한) |
| Q-BE-15 | 생성 실패의 역할별 필드와 재시도 경로 | API 2.1 "역할에 맞는 정보만 제공, 기술 재시도 권한은 운영 관리자"이나 역할별 필드 목록이 없음. MVP 2.1 표에 생성 실패 재시도 경로가 없고, 후속 2.2 `POST /api/operator/recovery-cases/{id}/retries`의 대상 ID가 생성 실패 `workItemId`와 같은지, ERD `recovery_case`와 어떻게 연결되는지 미정. 재시도 멱등 키 전달 방식 | F010 | 2 | 목 가능 | 생성 실패 목록에 `recoveryCaseId`를 함께 주고, 재시도는 `Idempotency-Key` 헤더. 업무 관리자 응답에서 기술 오류 상세 제외 | 겹침 #22-5(재시도 예산) |

## 3. infra (Node.js Socket.IO 서버·배포)

| ID | 질문 | 근거·현재 상태 | 관련 스펙 | 우선 | 막힘 | 프론트 제안 | #22/#38 |
|---|---|---|---|---|---|---|---|
| Q-INF-1 | Socket.IO 서버·JS·Java 클라이언트 버전과 서버 런타임 | PRD 5.1 "통합 전에 선정". 프론트 확인: `socket.io-client` 4.8.4는 React 19.3.0·Vite 8.3.4와 빌드·테스트 통과, 서버 4.x 필요. Java 클라이언트 2.x는 서버 3.x/4.x 지원 | F002 | 1 | 목 가능 | 서버 4.x, JS 클라이언트 4.8.x, Java 클라이언트 2.x | — (채택은 PR #15로 결정, 버전 선정은 별도) |
| Q-INF-2 | 개발·배포 환경의 연결 주소·경로·CORS | 문서에 없음 | F002 | 1 | 목 가능 | 개발: Spring과 Node.js를 Vite 프록시로 같은 출처처럼 연결. 환경 변수 `VITE_API_BASE_URL`, `VITE_SOCKET_URL` | — |
| Q-INF-3 | 구독 대상 형식과 응답 | API 2.4 `subscription.request`는 "서버가 확인할 대상 종류·ID". 종류 목록(문의·티켓 관련 대화·부서 티켓·관리자 현황·복구 목록 등), 허용·거부 응답 형식 미정 | F002, F007, F009, F010 | 1 | 목 가능 | `{requestId, target:{type:'INQUIRY'|'TICKET'|'DEPARTMENT_TICKETS'|'ADMIN_DASHBOARD', id?}}` → ACK `{ok, code?}` | — |
| Q-INF-4 | 연결 중 토큰 만료와 권한 변경 시 구독 회수 처리 | API 2.5 "인증 갱신 또는 재연결", "부서/역할 변경 시 … 구독 해제 전달을 정해야 한다". 서버가 끊는지, 재인증·구독 해제 이벤트를 보내는지 미정 | F002 | 2 | 목 가능 | 서버가 만료 사유를 담아 끊고, 클라이언트가 새 토큰으로 재연결 | 겹침 #22-6(JWT 상세 정책) |
| Q-INF-5 | ACK 대기 시간과 재전송 정책 (backend와 함께) | API 2.4 "ACK 시간 초과는 결과 미확인, 같은 requestId로 확인·재전송". 시간·횟수 미정 | F003, F004, F008 | 2 | 목 가능 | 대기 10초, 자동 재전송 없이 사용자 확인 후 재전송 | 겹침 #22-6(명령/저장 ACK) |
| Q-INF-6 | 이벤트 이름·외피·ACK 결과 필드 확정과 스키마 위치 | API 2.4 "이벤트명과 외피는 검토할 계약안, JSON Schema로 고정". ACK 결과는 "`inquiryId, inquiryVersion, messageId, revision, runId, effect` 등" | F002~F005, F008 | 1 | 목 가능 | 이벤트별 JSON Schema를 `contracts/socket/`에 두고 공통 외피 `{eventId, inquiryId, inquiryVersion, runId, payload}` 고정 | 겹침 #22-6(명령/저장 ACK) |
| Q-INF-7 | Node.js–Spring 단절을 React에 알리는 방법 | PRD 4.11 "구간별 오류 표시". 알림 이벤트가 없음 | F002 | 3 | 영향 적음 | 서버가 상태 이벤트(예: `relay.status`)를 보내거나, ACK 오류 코드로 구분 | — |

## 4. ai (Python 에이전트)

| ID | 질문 | 근거·현재 상태 | 관련 스펙 | 우선 | 막힘 | 프론트 제안 | #22/#38 |
|---|---|---|---|---|---|---|---|
| Q-AI-1 | `agent.progress`에 담을 내용 | API 2.4 `payload`만 정의. ERD `agent_step`에 `display_summary`(진행 문구), `step_type`, `agent_type` 있음. 근거 요약 공개 범위 미정 | F003 | 1 | 목 가능 | `{stepNo, stepType, displaySummary, status}`. 화면은 `displaySummary`만 표시 | 겹침 #22-4(SSE 진행). #38은 진행 형식을 다루지 않음 |
| Q-AI-2 | `answer.delta`의 메시지 식별자·순번 | API 2.4 "답변 조각에는 메시지 식별자·순번 포함". 최종 저장 메시지와 연결 방법 미정 | F003 | 2 | 목 가능 | `{messageId, seq, text}`, 최종은 `message.stored`의 같은 `messageId`로 교체 | 겹침 #22-4(SSE 진행·늦은 결과) |
| Q-AI-3 | AI 답변의 구조화 형태 | ERD `message.payload`(JSONB) "단계별 가이드·티켓 정보 카드·문서 링크". 추가 질문·범위 밖 안내·가이드를 화면이 구분할 형태 미정 | F003 | 2 | 목 가능 | `payload.kind`: `QUESTION / GUIDE / OUT_OF_SCOPE / NOTICE` | 겹침 #22-3 · 참고 #38(결과 `answer` 문자열과 문제별 `action`·`question`. Spring이 화면에 줄 형태는 별도로 정해야 함) |
| Q-AI-4 | 분석 시간 초과 기준 | PRD 4.4 시간 초과를 구분하라고 하나 값 없음 | F003 | 3 | 목 가능 | backend·ai가 정하고 화면은 실행 상태만 따름 | 겹침 #22-5(실행 예산) · 참고 #38("종료 시각·호출 예산" 미정으로 둠) |
| Q-AI-5 | 별개 문의 후보를 결과에 어떻게 표현하는가? | PRD 4.4 "별개 문의로 판단되면 … 묻는다". PR #38 결과 예시의 문제별 `action`에는 `PROPOSE_TICKETS`, `ASK_USER`만 있고 분리 후보 표현이 없음 | F008 | 2 | 목 가능 | 결과에 분리 후보 목록(가져갈 문맥 요약·출처 메시지)을 따로 두고, Spring이 검증 후 `consentId`를 발급 | 겹침 #22-3 · 참고 #38 |

## 5. frontend (공유용, 다른 담당 확인 요청)

| ID | 내용 | 관련 스펙 | 요청 | #22/#38 |
|---|---|---|---|---|
| Q-FE-1 | 화면 경로와 역할별 첫 화면(이슈 #6 라우팅 명세) | F001, 전체 | 경로 이름을 ERD 테이블명 기준으로 정함. 관리자 첫 화면을 `/admin/tickets`로 할지, 부서 티켓 상세(`/tickets/:ticketId`)와 관리자 티켓 상세(`/admin/tickets/:ticketId`)를 하나로 합칠지 확인 | — |
| Q-FE-2 | 상태 표시명·오류 문구 | F003, F005 | Q-ALL-2·Q-ALL-4 답을 받은 뒤 프론트가 문구 초안을 내고 리뷰 요청 | — |

## 6. 문서 간 불일치 (원본 수정이 필요한 것)

| 항목 | 내용 | 관련 질문 |
|---|---|---|
| 문의 버전 | API·DATA는 사용, ERD `inquiry`에 컬럼 없음 | Q-BE-3 |
| 문제와 티켓 | ERD 1:1(UNIQUE), DATA 1:N | Q-BE-4 |
| 필드 이름 | API camelCase 이름과 ERD 컬럼 이름이 다름 | Q-ALL-3 |
| 상태값 | 코드가 있는 것과 한글 설명만 있는 것이 섞임 | Q-ALL-2 |
| ERD에 없는 저장 위치 | 숨김, 멱등키, 메시지 관련성, 새 문의 동의, 생성 작업(DATA 2가 직접 언급) | Q-BE-4, Q-BE-13, Q-BE-14 외 후속 |
| 이슈와 문서의 MVP 범위 | 이슈 #30·#31 작업 목록에 있는 새 문의 동의·티켓 관련 대화·생성 실패 재시도를 PRD 8.2·DATA 7·API 2.2는 후속으로 둠 | Q-ALL-7 |
