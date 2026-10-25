# wire JSON·REST·Socket.IO·AI 계약

상태: `hops-mock-1` 기반 개발 연동용 초안. Mock과 프론트 live 어댑터가 같은 JSON 계약을 사용한다. 운영 정책 승인·팀원 서버 통합 완료를 뜻하지 않는다. 전체 필드와 타입은 [DATA_TYPES.md](DATA_TYPES.md), 작업별 입출력은 이 문서의 연결표를 따른다. 언어별 코드 생성은 제공하지 않는다.

## 1. 공통 표현

- JSON 필드명은 camelCase. 부서 code·상태 code를 소문자/한글로 임의 변환하지 않는다.
- 모든 객체 키는 필수이며 nullable일 때만 null 허용. 빈 배열은 []. 예: 보완 저장 ACK의 runId는 null이며 생략하지 않는다. 이는 기존 API의 생략 가능성을 이번 Mock에서 명시적으로 좁힌 제안이다.
- UUID는 소문자 하이픈 문자열, 시각은 UTC Z 문자열이다. 배열·객체의 순서를 인덱스 ID로 쓰지 않는다.
- 버전·revision·순번은 1부터, page는 0부터. 질문 횟수는 0~3. 모든 정수는 필드별 최소값부터 2,147,483,647 이하이며 문자열·boolean을 숫자로 바꾸지 않는다.
- 사용자 메시지는 1~4,000 Unicode 코드 포인트. JS `[...content].length`, Java `codePointCount`, Python `len`을 사용한다. 단독 surrogate는 거부한다. 원문을 trim/자동 절단해 저장하지 않는다.
- 공백만 있는지 판단하는 문자 집합은 데이터 타입 문서의 UserContent 공백 집합에 명시한다. 언어별 `trim/strip/\\s`의 서로 다른 동작에 의존하지 않는다. 서버도 같은 검사를 한다.
- category 6개와 기존 문의·처리 상태 코드는 데이터 타입 문서의 값 목록을 따른다. 문제·배정·실행·오류 코드는 운영 미정이므로 string으로 받는다. Mock 대표값만 mock-profile.json에 둔다. 미등록 값은 알 수 없는 상태로 표시하고 성공으로 추정하지 않는다.

## 2. REST와 페이지

모든 REST 오류는 `ApiErrorBody`다. 400 입력, 401 인증, 403 권한, 404 없음, 409 버전/멱등 충돌, 429 제한, 503 일시 장애를 구분한다. `retryable`은 무조건 자동 재시도를 하라는 뜻이 아니다.

| 작업 | 요청 | 성공 |
|---|---|---|
| POST /api/auth/login | LoginRequest | 200 LoginResponse |
| POST /api/auth/refresh | 본문 없음, Refresh 쿠키 | 200 LoginResponse |
| GET /api/auth/me | Bearer 토큰 | 200 CurrentUser |
| POST /api/auth/logout | Bearer 토큰, 본문 없음 | 204 본문 없음 |
| GET /api/departments | 인증 | 200 Department[] |
| GET /api/inquiries | page, size 쿼리 | 200 InquiryPage |
| GET /api/inquiries/{id} | 문의 UUID | 200 InquiryResult |
| GET /api/inquiries/{id}/messages | beforeSequenceNo(미지정=최신), size(기본 30) | 200 MessageHistoryPage |
| GET /api/runs/{id} | 실행 UUID | 200 RunSummary |
| POST /api/runs/{id}/retries | RunRetryRequest | 200 RunSummary(접수/기존 실행) |
| GET /api/tickets | page, size, processingStatus(선택) | 200 TicketPage |
| GET /api/tickets/{id} | 티켓 UUID | 200 TicketDetail |

page 기본 0, size 기본 20/최대 100은 Mock 제안값이다. 음수·범위 초과·소수·알 수 없는 필터는 400. URL 쿼리는 문자열이므로 HTTP 어댑터에서 정해진 십진 정수 형식을 확인한 뒤 숫자로 변환한다. query 타입의 null 필터는 URL에서 생략하며 문자열 "null"을 보내지 않는다.

문의·티켓 목록은 createdAt 내림차순, 같은 시각이면 id 내림차순이다. 티켓 필터·페이지는 현재 사용자 권한 범위를 먼저 제한한 뒤 계산한다. 전체 목록을 프론트에서 잘라 페이지를 만든 것으로 실제 API 검증을 대체하지 않는다.

채팅은 최신 30개를 조회하고 items를 sequenceNo 오름차순으로 반환한다. beforeSequenceNo는 미만 조건이며 이전 항목이 더 있으면 items 첫 항목의 순번을 nextBeforeSequenceNo로, 없으면 null을 반환한다. 정정은 순번을 바꾸지 않고 id/revision으로 중복 제거한다. 기존 afterSequenceNo/nextAfterSequenceNo 전방 조회는 호환 예제로 유지하며 beforeSequenceNo와 동시 사용은 400이다. 스냅샷 고정은 제공하지 않는다. 상세는 10절을 따른다.

## 3. 인증·권한

Mock은 메모리의 가짜 토큰을 계정 fixture와 연결한다. 토큰 수명 1,800초는 테스트 시계용이며 실제 JWT 서명·저장·갱신·폐기를 구현한 것이 아니다. 실서비스 인증 어댑터와 혼용하지 않는다. 실제 Socket.IO auth.token과 REST Bearer는 API의 계약 후보를 따른다.

- 문의/전체 메시지/실행 조회는 소유자만 허용한다. 업무 관리자 역할만으로 문의 전체 대화 조회를 허용하지 않는다.
- 티켓 목록·상세는 현재 처리 부서 구성원 또는 BIZ_ADMIN만 허용한다. AGENT_OPS_ADMIN만 가진 계정에는 업무 조회 권한이 생기지 않는다.
- 일반 부서원에게는 자기 현재 처리 부서 티켓만 반환한다. BIZ_ADMIN은 업무 조회 범위의 티켓을 반환한다. 다른 부서 ID나 userId를 요청에 넣어 권한을 넓히지 못한다.
- 부서 티켓 메뉴는 CurrentUser.department.isRoutable, 추가 역할 메뉴는 roles로 표시한다. 최종 권한은 서버가 판단한다.
- 같은 사용자 재로그인 때만 초안·미확인 요청을 복구한다. 다른 사용자 로그인·로그아웃 시 이전 사용자 조회 캐시·초안·대기 명령·구독을 정리한다. 로그아웃 요청 실패도 브라우저 정리는 수행한다.

## 4. 명령·ACK·멱등성

`inquiry.create`, `message.send`, `message.revise`는 아래 작업 연결표의 명령 타입을 쓰고 **CommandAck**로 응답한다. ok=true는 Spring의 해당 메시지 저장 확인이다. ACK의 runId는 분석 실행 접수 식별자이며 티켓 저장 성공을 뜻하지 않는다.

구독 request/release는 **SubscriptionAck**다. 성공 응답에는 target이 있으며 inquiryId/messageId 같은 저장 결과 필드는 없다. 다른 variant의 필드를 섞으면 계약 위반이다. error.requestId는 바깥 requestId와 같아야 한다.

| 상황 | 화면·키 처리 |
|---|---|
| ACK 대기 | 전송 중. 저장 성공 표시 안 함 |
| ok=false | 오류 코드별 안내, 입력 보존. 검증 오류를 같은 내용으로 자동 반복하지 않음 |
| ACK 시간 초과·연결 종료 | 결과 미확인. 실패로 확정하지 않음 |
| 같은 행위자·명령·requestId·동일 본문 | 최초 결과 재사용. 같은 문의·메시지·정정이 한 번만 저장됨 |
| 같은 키·다른 본문 | IDEMPOTENCY_KEY_REUSED 오류 ACK. REST 대응 의미는 409 |
| 본문 또는 expectedInquiryVersion을 고쳐 새 의도로 제출 | 새 requestId. 기존 미확인 요청을 덮어쓰지 않음 |

Mock ACK 대기는 10초, 자동 재전송 없이 사용자 확인 후 동일 요청을 재전송한다. 운영 한도·보존 기간은 미정이다. 동일 키 재전송 검사는 **버전 비교보다 먼저** 기존 결과를 확인해야 한다. 최초 저장으로 버전이 올랐다는 이유로 동일 재전송을 버전 충돌로 바꾸지 않는다.

## 5. 버전·저장·답변 조각

- 문의 생성은 version=1, 새 사용자 메시지·정정 저장마다 문의 버전을 1 올린다. AI 메시지·진행·티켓 저장 자체로 입력 버전을 올리지 않는다. 정정한 메시지만 revision을 올린다.
- ACK의 effect=ANALYSIS_STARTED이면 runId가 있어야 한다. 모든 문제의 이관 뒤 SUPPLEMENT_SAVED이면 runId=null이며 자동 재분석·새 티켓 생성을 시작하지 않는다. 아직 분석 대상이 남았는지는 서버가 판단한다.
- 진행·답변 조각만 입력 버전과 활성 runId를 검사한다. 같은 버전의 이전 runId도 차단한다. version이 다르다는 이유로 저장 메시지·정정 이력·이미 생성된 티켓을 삭제하지 않는다.
- eventId 중복은 한 번만 반영한다. answer.delta는 `(runId,messageId,seq)`로도 중복을 제거한다. seq=1,3,2이면 3을 버퍼링하고 2가 오면 순서대로 조합한다. 아직 안 받은 낮은 seq를 무조건 버리지 않는다.
- 서로 다른 내용이 같은 조각 키로 오거나 누락이 남으면 임시 답변을 확정하지 않고 REST 저장 메시지로 복구한다. 저장 답변은 임시 답변에 덧붙이지 않고 messageId로 교체한다.
- 재접속은 재인증 → 현재 권한으로 재구독 → 메시지/문의/티켓/실행 REST 재조회다. 재연결만으로 누락 복구를 완료했다고 보지 않는다.
- message.stored의 requestId는 사용자 저장 명령과 임시 메시지를 연결한다. AI 저장 메시지는 requestId=null이다. 이벤트가 ACK보다 먼저 와도 requestId/messageId로 하나의 메시지로 합친다.
- state.changed는 target별 재조회 신호다. inquiryVersion은 문의 target이면 현재 버전, 부서/티켓 target이면 null이다. Node는 Spring 저장 확정 이벤트만 중계한다.

## 6. 구독

SubscriptionTarget은 INQUIRY(id=문의), DEPARTMENT_TICKETS(id=부서), TICKET(id=티켓)이다. Node는 사용자 연결에서 검증한 주체를 Spring에 확인한다. 임의 room 이름·client userId를 신뢰하지 않는다. TICKET 구독은 티켓 상세 변경 신호용이며 관련 대화 조회 권한을 새로 부여하지 않는다.

화면을 떠나면 해당 target을 release하고, 재접속/부서 변경 때 현재 권한을 다시 확인한다. release는 이미 해제된 target에도 성공한다. 여러 소비자가 같은 target을 사용하면 클라이언트 참조 횟수를 관리해 한 화면 종료가 다른 화면의 구독을 끊지 않게 한다. 구독 거부 403 의미는 ACCESS_DENIED 오류 ACK로 전달한다.

## 7. Spring–AI Mock 서비스 계약

`POST /internal/mock/v1/runs`의 AiRequest → 202 AiAccepted, `GET /internal/mock/v1/runs/{runId}/events`의 SSE progress/answer.delta/result를 제안한다. **Mock 전용 경로이며 운영 경로·서비스 인증은 미확정**이다. result는 AiResult 한 번이며 종료 후 늦은 진행을 사용하지 않는다. 재연결 이력/Last-Event-ID 보존 정책은 운영 계약에서 정한다. 실제 서비스 인증 없이 외부에 공개하지 않는다.

AI_SSE는 Python→Spring, Socket 이벤트는 Spring→Node→React다. Spring이 공개 가능한 payload만 변환하고 eventId를 발급한다. Python이 프론트에 바로 state.changed·티켓 저장 ACK를 보내지 않는다.

AI 결과의 임시 참조·근거·부서·버전은 [데이터 타입 문서](DATA_TYPES.md#서비스-내부-표현)의 의미 규칙도 지켜야 한다. 데이터의 형식 확인만으로 참조 유효성·권한·실제 저장을 보장하지 않는다.

## 8. 작업별 데이터 타입 연결

| 전송 | 경로·이벤트 | 요청 타입 | 응답 타입 | 조건 |
|---|---|---|---|---|
| REST | `POST /api/auth/login` | LoginRequest | LoginResponse | {"successStatus": 200, "auth": "NONE"} |
| REST | `GET /api/auth/me` | 없음 | CurrentUser | {"successStatus": 200, "auth": "USER"} |
| REST | `POST /api/auth/logout` | 없음 | 없음 | {"successStatus": 204, "auth": "USER"} |
| REST | `GET /api/departments` | 없음 | Department | {"responseArray": true, "successStatus": 200, "auth": "USER"} |
| REST | `GET /api/inquiries` | InquiryListQuery | InquiryPage | {"requestLocation": "query", "successStatus": 200, "auth": "OWNER"} |
| REST | `GET /api/inquiries/{id}` | 없음 | InquiryResult | {"successStatus": 200, "auth": "OWNER"} |
| REST | `GET /api/inquiries/{id}/messages` | MessageListQuery | MessagePage | {"requestLocation": "query", "successStatus": 200, "auth": "OWNER"} |
| REST | `GET /api/runs/{id}` | 없음 | RunSummary | {"successStatus": 200, "auth": "OWNER"} |
| REST | `GET /api/tickets` | TicketListQuery | TicketPage | {"requestLocation": "query", "successStatus": 200, "auth": "CURRENT_DEPARTMENT_OR_BIZ_ADMIN"} |
| REST | `GET /api/tickets/{id}` | 없음 | TicketDetail | {"successStatus": 200, "auth": "CURRENT_DEPARTMENT_OR_BIZ_ADMIN"} |
| SOCKET_COMMAND | `inquiry.create` | InquiryCreateCommand | CommandAck | {"direction": "React -> Node -> Spring -> Node -> React"} |
| SOCKET_COMMAND | `message.send` | MessageSendCommand | CommandAck | {"direction": "React -> Node -> Spring -> Node -> React"} |
| SOCKET_COMMAND | `message.revise` | MessageReviseCommand | CommandAck | {"direction": "React -> Node -> Spring -> Node -> React"} |
| SOCKET_COMMAND | `subscription.request` | SubscriptionCommand | SubscriptionAck | {"direction": "React -> Node (Spring 현재 권한 검증)"} |
| SOCKET_COMMAND | `subscription.release` | SubscriptionCommand | SubscriptionAck | {"direction": "React -> Node (Spring 현재 권한 검증)"} |
| SOCKET_EVENT | `agent.progress` | 없음 | AgentProgressEvent | {"direction": "Spring -> Node -> React"} |
| SOCKET_EVENT | `answer.delta` | 없음 | AnswerDeltaEvent | {"direction": "Spring -> Node -> React"} |
| SOCKET_EVENT | `message.stored` | 없음 | MessageStoredEvent | {"direction": "Spring -> Node -> React"} |
| SOCKET_EVENT | `state.changed` | 없음 | StateChangedEvent | {"direction": "Spring -> Node -> React"} |
| AI_HTTP | `POST /internal/mock/v1/runs` | AiRequest | AiAccepted | {"successStatus": 202, "auth": "SERVICE_AUTH_PENDING"} |
| AI_SSE | `GET /internal/mock/v1/runs/{runId}/events` | 없음 | progress: AiProgress, answer.delta: AiDelta, result: AiResult | {"auth": "SERVICE_AUTH_PENDING"} |

## 9. 프론트 live 연동 프로필

설정은 [프론트 README](../../frontend/README.md#2-실행-모드와-env)를 따른다. 현재 임시 Live Adapter는 2절·8절의 URL/이벤트와 [DATA_TYPES](DATA_TYPES.md)의 타입을 기대한다. 실제 서버에 적용할 표현은 담당자 간 확인·합의한다. 이 절은 기존 Mock 계약을 개발 서버에 연결하는 최소 제안 프로필이며 [합의 전 항목](README.md#팀-합의-전-항목)이나 Q-BE-2·Q-INF-1/2/4/6의 운영 정책 합의를 대신하지 않는다.

- REST는 설정한 Spring origin에 `/api/...`를 붙인다. 로그인은 `LoginRequest` JSON을 보내고 `LoginResponse` 자체를 받는다. 나머지 인증 요청은 `Authorization: Bearer <accessToken>`이다. `/api/auth/me` 응답으로 사용자·부서·역할을 확정한다. 응답은 `{ data: ... }` wrapper를 추가하지 않는다. 로그아웃은 204 본문 없음이다.
- Socket.IO 4.x 호환 서버를 사용한다. 기본 namespace `/`, 접속 path `/socket.io`이며 path는 환경 설정으로 바꿀 수 있다. path는 namespace나 룸 이름이 아니다. 연결마다 `socket.handshake.auth.token`에 토큰을 받는다. URL 쿼리·이벤트 payload로 토큰을 요구하지 않는다. polling과 WebSocket 업그레이드를 지원한다.
- Node는 연결 주체와 `SubscriptionCommand.target` 접근 권한을 Spring에 확인하고 허용된 target만 룸에 참여시킨다. 룸 내부 이름은 서버가 결정한다. Spring↔Node 내부 검증·중계 경로는 서버 담당자의 별도 계약이며 프론트는 직접 호출하지 않는다.
- 명령명은 `inquiry.create`, `message.send`, `message.revise`, `subscription.request`, `subscription.release`다. 8절에 연결한 JSON을 단일 payload로 받고 **Socket.IO ACK 콜백의 첫 번째 인자 하나에** `CommandAck` 또는 `SubscriptionAck` 객체를 반환한다. 서버 콜백에 `(null, result)`를 보내거나 별도 `ack` 이벤트로 응답하지 않는다. 클라이언트 `.timeout()` 콜백의 첫 번째 오류 인자는 Socket.IO가 추가하는 값이다.
- `CommandAck.ok=true`는 Spring 저장 확인 이후에만 보낸다. 프론트는 requestId·대상/저장 식별자·필수 ACK 필드를 확인한다. 잘못된 ACK·10초 시간 초과·전송 중 연결 종료는 결과 미확인으로 둔다. 자동 저장 재시도나 오프라인 명령 적재는 하지 않으며 재확인은 같은 requestId·같은 본문이다.
- 서버→프론트 이벤트는 `agent.progress`, `answer.delta`, `message.stored`, `state.changed`다. 각 Event 객체 하나를 보낸다. `message.stored`·`state.changed`는 REST 재조회 신호이며 답변 조각을 DB 저장 완료로 취급하지 않는다. 티켓 목록/상세는 신호 외에도 주기적으로 REST를 조회한다.
- 연결 거부는 Socket.IO `connect_error`로 전달하고 `error.data`에 `ApiErrorBody`를 넣는다. `UNAUTHENTICATED`는 refresh 대상, `AUTH_CONTEXT_CHANGED`는 재로그인, `ACCESS_DENIED`는 권한 거부, `SERVICE_UNAVAILABLE`은 중계 서버/업무 서버 장애다. 단순 네트워크 오류는 인증 실패로 간주하지 않는다. 명령·구독 거부는 해당 오류 ACK로 반환한다.
- 재접속 때 토큰으로 다시 인증하고 화면의 target을 다시 구독한다. 서버는 이전 socket의 룸을 새 socket에 무조건 복구하지 말고 현재 권한을 검증한다. 같은 target의 여러 소비자는 클라이언트 참조 횟수를 관리하고 마지막 해제 때만 서버에 release한다. 로그아웃·계정 전환·연결 종료는 이전 구독과 대기 전송을 정리한다.
- 브라우저 origin을 Spring REST와 Node Socket.IO 양쪽 CORS에 허용한다. Spring은 GET/POST/OPTIONS와 Authorization·Content-Type을 허용한다. 업무 요청은 Bearer, 로그인/refresh/로그아웃은 쿠키를 포함하며 10절의 CORS·Origin/CSRF 조건을 함께 적용한다. 실제 쿠키 발급·부서 변경 구독 회수는 서버 구현이 필요하다. HTTPS 화면에서 혼합 콘텐츠 요청은 사용하지 않는다.

현재 프론트는 `GET /api/departments`를 직접 호출하지 않으며 `/api/auth/me`와 티켓 응답 안의 Department를 사용한다. 분석 재시도·refresh·최신 대화 조회는 아래 추가 프로필을 따른다. F009 관련 대화·티켓 처리 명령과 F008·F010은 이 연동 범위 밖이다. AI 내부 `/internal/mock/...` 경로는 Python↔Spring의 Mock 제안이며 프론트 live에서 호출하거나 운영 경로로 확정하지 않는다.

## 10. 인증·재접속·최신 대화·분석 재시도 추가 프로필

상태: 2026-10-25 개발 방향 반영 · 작성자 외 팀원 리뷰 전. 이 절은 2·3·9절의 이전 최소 프로필보다 우선한다. 프론트/Mock 구현과 실제 Spring·Node·AI 구현 완료는 구분한다. Q-BE-2/7/8, Q-INF-4/5/6의 팀 합의를 완료 처리하지 않는다.

### 인증과 권한

- Access Token은 메모리에 보관한다. 만료 시 POST /api/auth/refresh(본문 없음)로 갱신하고 LoginResponse를 받는다. 동시 갱신은 한 요청을 공유한다. Refresh Token 원문은 JS·JSON·로그에 제공하지 않는 HttpOnly 쿠키를 권장 프로필로 사용한다. 발급·회전·재사용 검출·폐기는 Spring이 구현한다.
- 로그인/refresh/로그아웃만 credentials=include, 업무 REST는 Bearer와 credentials=omit이다. 쿠키 발급/삭제, 허용 Origin 검증·CSRF 방어, 정확한 CORS origin과 Allow-Credentials는 Spring 담당자가 함께 구현해야 한다. 운영 HTTPS의 Secure, 같은 사이트의 SameSite=Lax를 기본으로 검토하고 다른 사이트 배포는 SameSite=None/Secure와 별도 CSRF 정책을 합의한다. 프론트만으로 쿠키 보안을 구현했다고 보지 않는다.
- Refresh 만료·폐기(401)는 입력을 보존한 재로그인이다. 갱신 네트워크/503은 재연결 안내이며 인증 만료로 확정하지 않는다. ordinary ACCESS_DENIED(403)는 권한 안내다. 서버가 현재 부서/역할과 토큰 문맥의 차이를 확인하면 AUTH_CONTEXT_CHANGED로 응답하고 기존 소켓/구독을 회수하며 프론트는 재로그인을 요청한다.
- 갱신한 권한이 이전과 달라져도 재로그인한다. 로그아웃/계정 전환 뒤 도착한 이전 refresh 응답은 반영하지 않는다. 실제 서버 푸시 권한 회수는 아직 서버 작업이다.

### 시간과 Node→Spring 내부 검증 제안

- 저장 ACK는 10초이며 분석 완료를 기다리지 않는다. Node→Spring 검증은 요청당 최대 3초, 초과하면 SERVICE_UNAVAILABLE로 응답하고 ACCESS_DENIED로 바꾸지 않는다.
- 자동 재접속은 연결 끊김 감지 후 최대 30초다. 기본 간격 1→2→4→최대 5초에 jitter를 적용하고 각 연결 시도는 최대 5초다. 이후 수동 다시 연결 또는 online 이벤트로 재개한다. 네트워크 끊김은 재로그인 사유가 아니다. 재연결 뒤 인증·대상 권한 재검증·REST 재조회가 필요하다.
- 기존 문서는 Spring 검증 책임만 정했고 구체 내부 API는 없었다. 아래 경로는 서버 담당자가 구현·리뷰할 새 제안이다. 프론트에서 직접 호출하지 않는다.
- POST /internal/realtime/authenticate: Authorization Bearer에 Node 서비스 토큰, JSON 본문에 `{accessToken: string}`을 전달한다. 성공 200은 `{user: CurrentUser, expiresAt: IsoDateTime}`, 무효 401은 ApiErrorBody다. 사용자/서비스 토큰을 URL/로그에 넣지 않는다.
- POST /internal/realtime/authorize-subscription: 같은 서비스 인증에 `{accessToken: string, target: SubscriptionTarget}`을 전달한다. 성공 200은 `{target: SubscriptionTarget}`, 거부 403/변경 401·AUTH_CONTEXT_CHANGED/장애 503은 ApiErrorBody다. 클라이언트 userId·룸 이름은 권한 근거로 인정하지 않는다. 실제 서비스 토큰 발급·키 배포는 서버 간 통합 전 확정한다.

### 오류와 화면 행동

- ACCESS_TOKEN_EXPIRED 또는 UNAUTHENTICATED: refresh 후 인증 요청 복구. 저장 소켓 명령은 결과 미확인 상태를 보존하고 자동 재전송하지 않는다.
- REFRESH_TOKEN_EXPIRED/REVOKED, AUTH_CONTEXT_CHANGED: 재로그인. ACCESS_DENIED: 권한 안내, 자동 갱신 반복 금지.
- INVALID_INPUT/VALIDATION_ERROR(400): 원문 보존·수정 안내. NOT_FOUND(404): 주소/목록 확인.
- INQUIRY_VERSION_CONFLICT(409): 저장하지 않고 최신 문의·대화를 조회한다. 최신 원문과 정정 초안을 비교해 사용자가 다시 제출한다. 새 의도는 새 requestId다. 기존 ApiErrorBody를 유지하며 현재 버전은 재조회로 확인한다.
- IDEMPOTENCY_KEY_REUSED(409): 같은 키·다른 내용, 자동 반복 금지. RATE_LIMITED(429)는 Retry-After 이후 사용자 재시도, SERVICE_UNAVAILABLE/TEMPORARY_UNAVAILABLE(503)는 조회 재시도·저장 결과 재확인을 구분한다. retryable=true만으로 업무 변경을 자동 반복하지 않는다.

### 최신 30개와 스크롤

- GET /api/inquiries/{id}/messages?size=30은 최신 30개다. 추가 조회는 &beforeSequenceNo=<첫 순번>이며 기본 30·최대 100이다. MessageHistoryPage의 nextBeforeSequenceNo가 null이면 끝이다. 순번은 불변이며 items는 오름차순, 화면 맨 아래가 최신이다. 추가 중 수신 메시지는 id와 높은 revision으로 중복 제거한다.
- 처음 최신 페이지 확보 후에만 전송·정정한다. 이전 페이지 조회/실패는 최신 페이지의 유효성을 없애지 않는다. 최신 재조회 실패는 입력을 보존하고 확인 전 전송을 막는다.
- 상단 120px 이내에서 이전 페이지를 추가하며 화면 위치를 보정한다. 초기 진입은 맨 아래, 하단 80px 이내를 보던 사용자는 새 답변을 따라가고 나머지는 새 메시지 버튼으로 이동한다. 이전 조회 실패에는 수동 재시도를 제공한다.
- 분석 중·답변 작성 중은 agent.progress/answer.delta로 표시하고 최종 결과는 Spring 저장 확인으로 확정한다. 임의 퍼센트는 표시하지 않는다.

### 분석 재시도와 업무 저장

- POST /api/runs/{id}/retries에 RunRetryRequest를 보낸다. 소유자만 허용하고 서버가 최신 입력 버전·실패 종류·기존 성공 작업·현재 활성 실행을 검증한다. 시간 초과/일시 장애이며 retryable=true인 실행에만 버튼을 제공한다. 성공 응답은 200 RunSummary로 접수/기존 실행을 알리며 복구 완료가 아니다.
- 같은 사용자·원본 runId·requestId·같은 본문은 최초 결과를 반환하고 같은 키·다른 본문은 충돌이다. 최신 버전이 다르면 INQUIRY_VERSION_CONFLICT, 허용되지 않는 상태는 RUN_NOT_RETRYABLE이다. 같은 문의/버전의 활성 실행이 있으면 기존 실행을 반환한다. 프론트는 접수 중 중복 클릭을 막고 응답 유실 시 같은 요청을 확인한다.
- 재시도는 기존 메시지·문제·workItemId·성공 티켓을 보존한다. 유효 결과가 저장된 실패 작업만 재적용할 수 있고, 유효 분석 결과가 없으면 저장 입력으로 다시 분석한다. LLM 생성 중간부터 재개하는 체크포인트는 이번 구현이 아니다. 티켓/배정/재이관 기술 복구는 운영 관리자 기능이며 문의자 버튼으로 권한을 넓히지 않는다.
- PRD 3.3에 따라 부서 배정 후 부서 구성원이 미담당 티켓을 가져가 개인 담당자가 된다. 부서·담당자·processingStatus·routingStatus는 별개다. 분석/생성 실행 상태를 티켓 처리 상태에 섞지 않는다. 현재 티켓 조회 구현은 담당자 가져오기/재이관을 추가하지 않는다.
- DATA 4·5의 원칙대로 작업↔티켓 유일성, 티켓/최초 배정 원자 저장, 부분 성공 보존을 적용한다. 요청 결과 최소 24시간 보존을 제안하며 기간 이후에도 작업↔티켓 유일성은 유지한다. 실제 DDL·트랜잭션·이벤트 outbox/유실 복구는 Spring 구현 및 통합 검증이 필요하다. Mock 메모리 중복 방지는 영속 DB 검증이 아니다.
