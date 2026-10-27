# API 설계 — PRD 전체 흐름

기준: [PRD](PRD.md) v1.43 · 2026-10-27, [데이터 설계](DATA.md), [ERD 설명](ERD_설명.md). 기존 개발 기준과 칸반 MVP 추가 프로필(팀 리뷰 대상)·후속 계약 후보를 구분한다. MVP/후속 범위는 PRD 8장이다. 문서 반영은 팀 리뷰·서비스 구현·실제 통합 검증 완료가 아니다.

타입·필드·상태·오류는 [DATA_TYPES](../specs/contracts/DATA_TYPES.md), 경로·이벤트·시간·인증 정책은 [WIRE_CONTRACT](../specs/contracts/WIRE_CONTRACT.md) 문서가 원본이다. 10절은 확정 기준, 11절은 담당 구현·미검증 항목이다. 언어별 코드 생성은 제공하지 않으며 추가 OpenAPI/JSON Schema는 MVP 연결 선행 조건이 아니다.

## 1. 공통 계약과 책임

- React는 로그인·조회·티켓 업무 REST를 Spring에 요청하고 채팅 명령·실시간 수신은 Node.js Socket.IO 서버에 연결한다. Spring도 Socket.IO 클라이언트로 연결해 명령을 수신하고 결과를 발행한다. Node.js는 연결·구독·중계, Spring은 JWT 발급·인증·권한·문의 버전·업무 저장·배정·상태 확정을 담당한다. Python은 Spring이 제공한 권한 범위에서 분석·검색·읽기 전용 진단을 수행하고 제안과 근거를 반환한다. Node.js와 Python은 업무 DB에 직접 쓰지 않는다.
- JSON은 UTF-8, API 필드는 camelCase, 식별자는 문자열 UUID, 시각은 UTC RFC 3339 문자열로 표현한다. 문의/티켓 목록은 `{items, page, size, totalElements}`, 페이지 0부터 기본 20·최대 100, createdAt DESC·id DESC다. 채팅은 최신 30개를 sequenceNo 오름차순으로 반환하며 beforeSequenceNo로 이전 대화를 조회한다.
- 자체 로그인은 Spring이 발급하는 JWT로 인증한다. Access는 메모리 15분, REST Bearer·Socket.IO `auth.token` 전달이다. HttpOnly Refresh는 최초 로그인부터 절대 7일·매 갱신 회전이다. 키·쿠키/CSRF·실제 배포 설정은 담당자가 구현·검증한다. 서버는 서명·허용 알고리즘·발급자·대상·만료를 검증하고 역할·소유자·현재 처리 부서도 검사한다. 로그인 인증 수명과 문의의 대화 세션은 별개다. JWT 원문·비밀번호·서명키는 로그·이벤트 본문에 남기지 않는다.
- REST 업무 변경은 `Idempotency-Key`, Socket.IO 명령은 `requestId`와 업무 식별자로 중복 반영을 막는다. 같은 행위자·동작·키·같은 요청의 재전송은 기존 결과를 반환하고 같은 키·다른 요청은 충돌이다. REST 충돌은 409, Socket.IO 충돌은 명시적 오류 ACK로 반환한다. 낙관적 버전 충돌도 같은 원칙을 따른다. `202 Accepted`나 실행 접수 ACK는 접수만 뜻하며 업무 성공 상태는 저장 결과·조회로 확인한다.
- 사용자 메시지는 공백을 포함해 1~4,000 Unicode 코드 포인트이며 공백만 있는 입력은 거부한다. 초과 입력과 공백 입력은 저장하지 않고 원문을 유지해 수정할 수 있게 한다. 범위 밖 질문·분류 실패·모델/통신 장애·업무 저장 실패는 서로 다른 결과로 표현한다.
- 오류 외피(ApiErrorBody): `{ "code": "INQUIRY_VERSION_CONFLICT", "message": "최신 내용을 다시 불러와 주세요.", "requestId": "<UUID>", "retryable": false }`. 400(입력), 401(로그인), 403(권한), 404(없음), 409(충돌), 429(제한), 503(일시 장애)를 구분한다. 내부 예외·비밀값·프롬프트·모델 내부 사고 과정은 응답하지 않는다. 비동기 실행 실패는 원래 HTTP 응답을 수정하지 않고 실행·복구 상태에 기록한다.

## 2. 화면 → Spring

아래 MVP 경로는 공통 계약의 개발 기준이며 후속 경로는 상세 설계 후보이다. 채팅 명령은 2.4절의 Socket.IO로 전달하고 목록·상세의 영속 상태와 티켓 업무는 REST로 제공한다. 각 변경 요청의 필수 식별자·버전·멱등 범위·권한·오류와 성공/부분 성공 응답을 기능 구현 전에 고정한다. `GET`은 조회 권한과 데이터 범위를 서버가 강제한다.

### 2.1 MVP: 로그인·문의·분석·복수 티켓

| 경로 | 요청·응답의 최소 의미 | 권한·완료 조건 |
|---|---|---|
| `POST /api/auth/login` | `{loginId,password}` → 액세스 JWT·만료 정보·사용자 정보 | Spring이 계정 정보에서 사번·소속 부서를 읽어 발급. 요청자가 제출한 부서·역할로 발급하지 않음. LoginRequest → LoginResponse, HttpOnly Refresh 쿠키 발급 |
| `GET /api/auth/me`, `POST /api/auth/logout` | 검증된 사용자 정보 조회, 로그아웃 | 토큰 만료/무효는 401, 권한 부족은 403. 로그아웃은 브라우저 인증 정보 정리와 소켓 종료를 포함. 현재 로그인 세션의 Refresh·Access 인증/구독 폐기. 회전/CSRF의 실제 구현·검증은 백엔드 담당 |
| `POST /api/auth/refresh` | HttpOnly 쿠키 → LoginResponse | 매 갱신 이전 Refresh 폐기·최초 7일 기한 유지. 무효/만료 시 재로그인 |
| `GET /api/departments` | 활성 부서의 `{id,code,name,isRoutable}` | 인증된 사용자에게 기준 목록 제공. 부서 코드는 [DATA](DATA.md)의 카탈로그와 일치 |
| `GET /api/inquiries`, `GET /api/inquiries/{id}` | 본인 문의 목록·종합 상태, 상세의 문제별 상태·실제 티켓·생성 실패·현재 실행 | 타인 문의 거부. MVP는 세션 재진입·채팅 결과용 조회. 독립 상세 모달·숨김/복원은 후속 |
| `GET /api/inquiries/{id}/messages` | 대화순 메시지와 정정 이력 | 문의자는 자신의 대화 조회. 페이지가 바뀌어도 순서 안정적 |
| `GET /api/runs/{id}`, `POST /api/runs/{id}/retries` | 실행 상태·오류·작업별 생성 결과 조회, 실패/시간 초과 실행 재시도 | 실행 성공과 티켓 저장 성공 분리. 최신 문의 버전과 기존 성공 작업을 재검증 |
| `GET /api/tickets`, `GET /api/tickets/{id}` | 현재 처리 부서 및 BIZ_ADMIN 범위의 읽기 전용 목록과 상세. MVP 칸반 추가 프로필: 종료 두 상태 통합 조회(processingStatuses), 번호·제목 검색(q), 내 담당·긴급·높음·해결 신고(quickFilter), TicketListItem 표시 정보. routingStatus·임의 담당자 선택 필터는 후속 | 목록 필터가 권한을 넓히지 않음. `processingStatus`와 `routingStatus`를 분리. 컬럼별 전체 건수·더 보기, 종료 통합 정렬·페이지를 지원. 계약 확장안의 팀 리뷰·구현 검증은 별도 |

문의 생성·메시지 추가·정정은 MVP, 새 문의 분리 동의는 후속이며 2.4절의 이벤트를 사용한다. 서버가 후보 문맥과 출처를 제시하고 사용자 동의를 확인한 경우에만 새 문의·세션을 한 번 생성한다. 거절·무응답은 생성하지 않는다. 이 동의는 기존 문의의 티켓 자동 생성에 적용되지 않는다. 동의 대상 ID·버전·상태는 구현 전 확정한다.

문의 결과의 최소 표현은 `id, version, status, problems[], tickets[], creationFailures[], currentRun`이다. 문제는 `id, summary, category, status, questionCount`, 티켓 카드는 `id, inquiryId, title, department, processingStatus, routingStatus, createdAt`, 실패 항목은 `workItemId, problemId, status, errorCode`를 사용한다. 전체 필드·필수/nullable은 DATA_TYPES를 따른다. 일부 문제는 추가 질문 중이고 다른 티켓은 성공/실패일 수 있다. 단일 문의 상태로 이를 덮어쓰지 않는다.

### 2.2 후속: 담당자 처리·관리자·그룹·알림

| 경로 초안 | 요청·결과 | 서버 검사 및 상태 의미 |
|---|---|---|
| `GET /api/creation-failures` | 아직 티켓이 없는 생성 실패 항목과 기존 작업 ID | 업무 관리자·운영 관리자에게 각 역할에 맞는 정보만 제공. 기술 재시도 권한은 운영 관리자 |
| `GET /api/tickets/{id}/messages` | 해당 티켓에 관련된 대화·보완 정보·정정 이력 | 현재 처리 부서 구성원은 가져오기 전에도 조회 가능. 다른 문제의 대화와 운영 로그 제외. 메시지↔문제/티켓 연결 기준은 구현 전 확정 |
| `POST /api/tickets/{id}/claim`, `POST /api/tickets/{id}/return` | 개인 담당자 가져오기·반환 | 현재 부서 구성원만. 동시 가져오기는 한 명만 성공. 반환 전 다른 담당자 가져오기 거부 |
| `POST /api/tickets/{id}/handler-completions`, `POST /api/tickets/{id}/resolution-reports`, `POST /api/tickets/{id}/confirmations` | 담당자 처리 완료, 처리 중 사용자 해결 신고, 사용자 최종 해결/미해결 응답 | 처리 완료는 사용자 확인 대기. 선행 해결 신고는 담당자 작업 확인 필요. 미해결은 해당 티켓 재처리. 완료 응답은 다른 티켓을 일괄 완료하지 않음 |
| `POST /api/tickets/{id}/handoff-requests`, `GET /api/handoff-requests/{id}` | 이유를 붙인 재이관 요청·진행 조회 | 현재 담당자 권한, 제외 부서 이력, 관리자 수동 배정 여부 확인. 후보 소진은 배정 예외, 기술 실패는 복구 필요로 구분 |
| `POST /api/tickets/{id}/corrections`, `POST /api/tickets/{id}/manual-assignments` | AI 요약·분류·진단 정정, 업무 관리자 수동 배정 | 정정 전후·주체·사유 기록. 정정만으로 완료/재배정 불가. 수동 배정은 업무 관리자만 확정 |
| `GET /api/admin/routing-exceptions`, `POST /api/admin/routing-exceptions/{id}/decisions` | 배정 예외·관리자 재검토와 결정 | 업무 관리자만 업무 판단 변경. 생성 실패는 별도 항목으로 노출 |
| `GET /api/incident-groups/{id}`, `POST /api/incident-groups/{id}/completions`, `POST /api/incident-groups/{id}/rejoin-reviews/{reviewId}/decisions` | 그룹 근거·구성 조회, 공통 장애 일괄 완료, 분리 티켓 재결합 검토 | 같은 부서·미완료·공통 해결 근거를 검증. 일괄 반영은 티켓별 성공/실패 표시. 미해결 재문의는 해당 티켓만 분리·재오픈. 재결합은 업무 관리자 확인 |
| `GET /api/notifications`, `GET /api/statistics` | 본인 알림함, 허용 부서의 이관·상태·AI 해결·무티켓 해결 집계 | 알림 읽음은 해결 확인이 아님. 통계 범위가 원문 조회 권한을 넓히지 않음. 빈 결과·조회 실패 구분 |
| `GET /api/operator/runs`, `GET /api/operator/runs/{id}`, `GET /api/operator/recovery-cases`, `POST /api/operator/recovery-cases/{id}/retries` | 실행·단계·검증 결과, 기술 복구 이력, 기존 작업 재시도 | 운영 관리자만. 모델 제안과 Spring 확정 결과 분리. 재시도 접수는 복구 완료가 아님. 기술 복구 권한만으로 업무 결론 변경 불가 |

숨김·복원, 후속 배정/담당자 필터, 문제별 해결 확인, 종료 후 재오픈의 구체 경로와 상태 전이는 구현 전 합의한다. 삭제 API는 제안하지 않는다. 문의·티켓·그룹의 버전과 대상 ID를 변경 요청에 포함해 오래된 화면에서 덮어쓰지 않도록 한다.

### 2.3 무응답 종료와 결과 표시

담당자 처리 완료로 확인 대기에 들어간 뒤 24시간 무응답이면 해결 확인 알림을 저장한다. 그 저장 시각에서 48시간 추가 무응답일 때만 미확인 종료한다. 알림 저장 실패는 자동 종료를 보류한다. 미확인 종료는 해결 완료·AI 해결로 집계하지 않는다. 무티켓 안내도 사용자 해결 확인 전에는 AI 해결로 확정하지 않는다. 이 전이는 서버 작업이며 클라이언트 타이머나 에이전트 문구가 확정하지 않는다.

### 2.4 React ↔ Node.js ↔ Spring: Socket.IO

React와 Spring은 Node.js Socket.IO 서버의 클라이언트다. 서버·JavaScript 클라이언트·Java 클라이언트의 호환 버전을 통합 전에 선정한다. 일반 WebSocket/STOMP 클라이언트로 대체하지 않는다. MVP 이벤트명·외피는 DATA_TYPES/WIRE_CONTRACT를 따르며 분리 동의는 후속 후보이다. 추가 이벤트 JSON Schema는 필수 선행 조건이 아니다.

| 방향·이벤트 후보 | 최소 데이터와 의미 |
|---|---|
| React → Node.js → Spring: `inquiry.create` | `{requestId,content}`. 검증된 사용자로 문의·첫 메시지·실행 접수를 영속화 |
| React → Node.js → Spring: `message.send`, `message.revise` | `{requestId,inquiryId,expectedInquiryVersion,content}`. 정정은 `messageId`도 필수. 분석 중이면 최신 버전으로 실행하고 기존 티켓 유지. 모든 문제의 이관 뒤에는 정보 보완 저장만 수행하며 `runId`는 없을 수 있음 |
| 후속 React → Node.js → Spring: `inquiry.split.respond` | `{requestId,inquiryId,expectedInquiryVersion,consentId,accepted}`. 동의한 경우에만 원본·새 문의·문맥 출처를 연결하고 한 번 생성 |
| React → Node.js: `subscription.request`, `subscription.release` | 서버가 확인할 대상 종류·ID. Spring의 현재 권한 확인 후 Node.js가 room 입장/해제를 수행. 이름을 아는 것만으로 구독 불가 |
| Spring → Node.js → React: `agent.progress`, `answer.delta` | `{eventId,inquiryId,inquiryVersion,runId,payload}`. 답변 조각에는 메시지 식별자·순번 포함. 진행 표시이며 저장/해결 성공이 아님 |
| Spring → Node.js → React: `message.stored`, `state.changed` | Spring 저장 확정 뒤 발행. 메시지·버전·업무 대상 식별자로 임시 화면을 갱신하거나 REST 재조회. 대시보드·티켓·알림도 허용된 대상에게만 전달 |

내부 검증은 `POST /internal/realtime/authenticate`, `POST /internal/realtime/authorize-subscription`을 사용한다. 사용자 JWT와 Node 서비스 인증을 분리한다. 연결 중 인증 회수는 `auth.invalidated`(AuthInvalidatedEvent), 중계 단절/복구는 `relay.status`(RelayStatusEvent)다. 서버는 통지 전에 접근/구독을 회수한다. 정확한 입출력·행동과 아직 없는 서버/프론트 구현은 WIRE_CONTRACT 10·11절을 따른다.

저장 ACK 대기는 10초, 내부 검증 제한은 3초, 자동 재접속은 최대 30초다. 멱등 결과는 최소 24시간 보존하며 작업↔티켓 유일성은 이후에도 유지한다. 명령 ACK는 `requestId`와 Spring의 검증/저장 결과 또는 안전한 오류를 연결한다. Node.js 수신만으로 저장 성공 ACK를 반환하지 않는다. 저장 결과는 해당 동작에 따라 `inquiryId, inquiryVersion, messageId, revision, runId, effect` 등을 포함하며, 분석 실행과 정보 보완 저장을 구분한다. Node.js–Spring 단절이나 ACK 시간 초과는 실패 확정 대신 결과 미확인으로 표시하고 같은 `requestId`로 결과를 확인·재전송한다. 오류 코드는 REST와 의미를 맞추되 소켓 ACK에 HTTP 상태를 적용한 것으로 해석하지 않는다.

Spring은 연결에서 확인한 사용자 JWT 또는 검증 가능한 위임 문맥과 별도의 Node.js 서비스 인증을 검사한다. 임의의 `userId`·부서·역할을 신뢰하지 않는다. 사용자 연결이 Spring 서비스 연결로 위장하거나 임의로 `state.changed`를 발행하지 못하게 이벤트 방향과 발행 주체를 검증한다. 문의자용 전체 대화·담당자용 관련 티켓 대화·업무 관리자용 현황·운영 로그의 구독 범위를 분리한다. 재이관·소속 변경 시 이전 구독 권한을 회수한다.

Spring은 DB 커밋 뒤 확정 이벤트를 발행한다. UI는 답변 조각을 임시 표시하고 최종 메시지와 업무 상태는 저장 결과로 동기화한다. 재접속 시 인증과 구독 권한을 다시 확인하고 REST로 메시지·문의·티켓·실행 상태를 조회한다. 중복 이벤트 ID는 한 번만 적용하고 메시지별 순번과 입력 버전으로 뒤늦은 조각을 구분한다. 저장·정정 이력은 유지하며 옛 버전의 미반영 분석 결과만 차단한다. 자동 재연결이나 연결 상태 복구 기능만으로 누락이 모두 복구되었다고 가정하지 않는다. Spring 여러 인스턴스 중 명령 처리자 선택·ACK 라우팅, 전송 제한·재시도·이벤트 보존 방식은 통합 전에 합의한다.

### 2.5 JWT 클레임과 인증 수명

| 클레임 | 의미와 원본 |
|---|---|
| `sub` | 내부 회원 UUID. 사번이나 소켓 ID와 구분 |
| `employeeNo` | 문자열 사번. Spring이 관리하는 회원 정보에서 발급 |
| `departmentId`, `departmentCode`, `departmentName` | 사용자 소속 부서의 ID·코드·표시명. 티켓의 배정 대상과 별개 |
| `roles` | 부서와 별개인 역할 목록. 서버의 역할 카탈로그와 매핑을 확정 |
| `iss`, `aud`, `iat`, `exp` | 발급자·허용 대상 서버·발급 및 만료 시각. Access 수명 15분. 실제 iss/aud·허용 알고리즘/키 설정은 백엔드 담당 |

JWT는 Spring이 발급하고 Node.js와 Spring은 각자의 대상·서명·만료 등을 검증한 뒤 클레임을 사용한다. 서명 방식·키 배포/교체·허용 알고리즘을 고정하고 토큰의 단순 디코딩을 인증으로 인정하지 않는다. 부서명은 표시용으로 사용하고 현재 접근 권한은 부서 ID와 역할·업무 상태로 검사한다. 서명된 JWT의 내용은 보유자가 읽을 수 있으므로 불필요한 개인정보를 추가하지 않는다.

Socket.IO 연결 동안에도 Spring의 현재 권한을 검사한다. 만료는 refresh 후 재인증·구독·REST 복원, 실패는 재로그인이다. 일반 ACCESS_DENIED는 권한 없음 안내이며 AUTH_CONTEXT_CHANGED는 기존 인증/구독 회수 후 재로그인이다. 역할/부서 변경·차단 시 해당 사용자의 기존 인증을 무효화한다. Node는 Spring 내부 검증 결과로 room을 관리한다. 새 제어 이벤트와 실제 쿠키 회전·권한 회수는 아직 통합 검증하지 않았다. Python에는 사용자 JWT 대신 Spring이 검증한 최소 권한 문맥을 전달한다.

## 3. Spring ↔ Python 에이전트 계약

Spring이 `runId`, `inquiryId`, 입력 버전, 허용된 메시지·문제·기존 작업 식별자, 활성 부서·서비스 카탈로그, 제한 시간과 최소 사용자 권한 문맥을 전달한다. Python은 `schemaVersion`, 실행/문의 식별자와 버전, 단계 이벤트, 근거가 연결된 구조화 결과 또는 안전한 실패 코드를 반환한다. 새 문제/작업 임시 참조는 해당 실행 안에서 고유해야 하며 Spring이 실제 ID를 발급한다. Spring은 소유 관계·현재 버전·허용 부서·근거 출처·질문 3회 제한을 재검증하고 각 티켓의 저장·배정을 확정한다. 일부 실패 시 성공한 티켓은 유지하고 실패 작업은 같은 ID로 복구한다.

MVP의 최소 결과는 `answer`, `problems[]`(질문/티켓 제안/범위 밖), `ticketProposals[]`(문제 참조·작업 범위·부서·근거·불확실성)이다. 후속 결과에는 Knowledge 답변의 근거와 사용자 해결 확인, Diagnosis의 읽기 전용 도구 결과, 재이관 후보와 제외 이력, 동일 장애 그룹의 공통 해결 근거, 우선순위 제안을 구분해 추가한다. 특정 제안 필드·enum은 기능별 스키마로 확정한다. Fake는 연결 검사에만 사용하며 실제 단일 에이전트 평가를 대신하지 않는다.

Spring→Python은 HTTP로 실행 요청, Python→Spring 진행 전달은 **SSE**다. Spring은 권한·실행 버전을 확인해 공개 가능한 진행·답변 조각·상태 변화를 **Node.js Socket.IO 서버를 통해 React에 전달**한다. REST 상세가 영속 상태의 원본이다. 기존 callback 설계와 SSE를 동시에 요구하지 않는다. 최종 AI 결과 경로, SSE URL·이벤트 스키마·재연결·중복 수신·서비스 인증은 통합 전에 합의해야 한다. SSE 완료·Socket.IO 수신이나 모델 출력은 업무 저장 성공을 뜻하지 않는다.

## 4. 검증할 계약과 미정 항목

- **MVP 인수:** JWT의 사번·소속 부서·역할 검증, 만료·로그아웃·부서 변경 시 전송/구독 권한 처리, Socket.IO 양쪽 클라이언트 연결·재연결, 타인 문의 구독 차단, 저장 ACK 유실 후 중복 재전송·REST 동기화. 문의 정정 후 최신 버전만 반영, 문제별 추가 질문 최대 3회, 복합 문의의 복수 티켓·부서 배정, 부분 실패 보존, 관리자 조회, 실제 모델 실행과 기준선 기록.
- **후속 인수:** 무티켓 해결 확인, 처리·재이관·관리자 예외, 동일 장애 그룹의 공통 해결과 개별 재오픈, 알림 기준의 미확인 종료, 운영 관리자 복구, 역할별 통계와 안전 회귀.
- **확정 개발 기준:** WIRE_CONTRACT 10절의 인증·오류/상태·목록/정정·ACK·재접속·버전·멱등성·초기 AI 예산·MVP 범위를 적용한다. AI 시도/출력 제한은 SDK 내부 재시도까지 포함한다.
- **담당 설계·구현:** 백엔드 REST/DB·인증, Node+백엔드 내부 API/이벤트·서비스 인증·호환 버전/라우팅, AI+백엔드 실제 모델/SDK·금액 상한·SSE·결과 검증. 프론트 제어 이벤트·일부 상태/429 UI도 남아 있다.
- **후속 상세:** 분리 동의·티켓 관련 대화·운영 기술 복구의 payload/권한/보존과 그룹·알림 작업.
- **미검증:** 실제 서비스 E2E, DB 동시성/DDL, 토큰 회전/폐기·CSRF·구독 회수, 새 이벤트, 분석 예산/재시도, 실제 단일 에이전트 평가·비용/SLO. 상세 목록은 공통 계약 11절이다. 이번 문서 변경으로 기능 구현 완료를 선언하지 않는다.

기술 참조: [Socket.IO 소개·클라이언트](https://socket.io/docs/v4/), [Java 클라이언트 호환성](https://github.com/socketio/socket.io-client-java#compatibility), [전달 보장](https://socket.io/docs/v4/delivery-guarantees/), [연결 미들웨어](https://socket.io/docs/v4/middlewares/), [JWT 검증 지침](https://www.rfc-editor.org/rfc/rfc8725.html). 버전·설정 확정과 실제 연동 검증은 별도 작업이다.
