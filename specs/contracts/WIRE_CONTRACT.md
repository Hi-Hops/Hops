# wire JSON·REST·Socket.IO·AI 계약

상태: 2026-10-25 개발 기준 확정 / 작성자 외 팀원 리뷰·실제 서비스 구현 및 통합 검증 별도. 현재 Mock·임시 Live Adapter와 확정 계약의 차이는 11절에 명시한다. 전체 필드와 타입은 [DATA_TYPES.md](DATA_TYPES.md), 작업별 입출력은 이 문서의 연결표를 따른다. 언어별 코드 생성은 제공하지 않는다. 2026-10-27 칸반 MVP 추가 프로필은 팀 리뷰 대상 계약 확장안이며 구현·검증 별도다.

## 1. 공통 표현

- JSON 필드명은 camelCase. 부서 code·상태 code를 소문자/한글로 임의 변환하지 않는다.
- 모든 객체 키는 필수이며 nullable일 때만 null 허용. 빈 배열은 []. 예: 보완 저장 ACK의 runId는 null이며 생략하지 않는다. 이 필수/nullable 표현은 DATA_TYPES의 확정 개발 기준이다.
- UUID는 소문자 하이픈 문자열, 시각은 UTC Z 문자열이다. 배열·객체의 순서를 인덱스 ID로 쓰지 않는다.
- 버전·revision·순번은 1부터, page는 0부터. 질문 횟수는 0~3. 모든 정수는 필드별 최소값부터 2,147,483,647 이하이며 문자열·boolean을 숫자로 바꾸지 않는다.
- 사용자 메시지는 1~4,000 Unicode 코드 포인트. JS `[...content].length`, Java `codePointCount`, Python `len`을 사용한다. 단독 surrogate는 거부한다. 원문을 trim/자동 절단해 저장하지 않는다.
- 공백만 있는지 판단하는 문자 집합은 데이터 타입 문서의 UserContent 공백 집합에 명시한다. 언어별 `trim/strip/\\s`의 서로 다른 동작에 의존하지 않는다. 서버도 같은 검사를 한다.
- category 6개와 기존 문의·처리 상태 코드는 데이터 타입 문서의 값 목록을 따른다. 문제·배정·실행·오류 코드는 DATA_TYPES의 개발 기준 목록을 따른다. 소비자는 버전 차이에 따른 미등록 문자열도 안전하게 처리한다. 미등록 값은 알 수 없는 상태로 표시하고 성공으로 추정하지 않는다.

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
| GET /api/tickets | page, size, processingStatus / 칸반 추가 프로필: processingStatuses, q, quickFilter | 200 TicketPage |
| GET /api/tickets/{id} | 티켓 UUID | 200 TicketDetail |

page 기본 0, size 기본 20/최대 100은 목록 개발 기준이다. 음수·범위 초과·소수·알 수 없는 필터는 400. URL 쿼리는 문자열이므로 HTTP 어댑터에서 정해진 십진 정수 형식을 확인한 뒤 숫자로 변환한다. query 타입의 null 필터는 URL에서 생략하며 문자열 "null"을 보내지 않는다.

문의·티켓 목록은 createdAt 내림차순, 같은 시각이면 id 내림차순이다. 티켓 필터·페이지는 현재 사용자 권한 범위를 먼저 제한한 뒤 계산한다. 전체 목록을 프론트에서 잘라 페이지를 만든 것으로 실제 API 검증을 대체하지 않는다.

채팅은 최신 30개를 조회하고 items를 sequenceNo 오름차순으로 반환한다. beforeSequenceNo는 미만 조건이며 이전 항목이 더 있으면 items 첫 항목의 순번을 nextBeforeSequenceNo로, 없으면 null을 반환한다. 정정은 순번을 바꾸지 않고 id/revision으로 중복 제거한다. 기존 afterSequenceNo/nextAfterSequenceNo 전방 조회는 호환 예제로 유지하며 beforeSequenceNo와 동시 사용은 400이다. 스냅샷 고정은 제공하지 않는다. 상세는 10절을 따른다.

## 3. 인증·권한

Mock은 메모리의 가짜 토큰을 계정 fixture와 연결한다. 토큰 수명 1,800초는 테스트 시계용이며 실제 JWT 서명·저장·갱신·폐기를 구현한 것이 아니다. 실서비스 인증 어댑터와 혼용하지 않는다. 실제 Socket.IO auth.token·REST Bearer·Refresh는 10절의 확정 개발 계약을 따른다.

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

Mock ACK 대기는 10초, 자동 재전송 없이 사용자 확인 후 동일 요청을 재전송한다. 처리 결과는 최소 24시간 보존하고, 이후에도 작업↔티켓 유일성으로 중복 생성을 막는다. 동일 키 재전송 검사는 **버전 비교보다 먼저** 기존 결과를 확인해야 한다. 최초 저장으로 버전이 올랐다는 이유로 동일 재전송을 버전 충돌로 바꾸지 않는다.

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
| REST | `POST /api/auth/refresh` | 본문 없음·쿠키 | LoginResponse | {"successStatus": 200, "auth": "REFRESH_COOKIE"} |
| REST | `GET /api/auth/me` | 없음 | CurrentUser | {"successStatus": 200, "auth": "USER"} |
| REST | `POST /api/auth/logout` | 없음 | 없음 | {"successStatus": 204, "auth": "USER"} |
| REST | `GET /api/departments` | 없음 | Department | {"responseArray": true, "successStatus": 200, "auth": "USER"} |
| REST | `GET /api/inquiries` | InquiryListQuery | InquiryPage | {"requestLocation": "query", "successStatus": 200, "auth": "OWNER"} |
| REST | `GET /api/inquiries/{id}` | 없음 | InquiryResult | {"successStatus": 200, "auth": "OWNER"} |
| REST(전방 조회 호환 예제) | `GET /api/inquiries/{id}/messages` | MessageListQuery | MessagePage | {"requestLocation": "query", "successStatus": 200, "auth": "OWNER"} |
| REST | `GET /api/runs/{id}` | 없음 | RunSummary | {"successStatus": 200, "auth": "OWNER"} |
| REST | `GET /api/tickets` | TicketListQuery | TicketPage | {"requestLocation": "query", "successStatus": 200, "auth": "CURRENT_DEPARTMENT_OR_BIZ_ADMIN"} |
| REST | `GET /api/tickets/{id}` | 없음 | TicketDetail | {"successStatus": 200, "auth": "CURRENT_DEPARTMENT_OR_BIZ_ADMIN"} |
| SOCKET_COMMAND | `inquiry.create` | InquiryCreateCommand | CommandAck | {"direction": "React -> Node -> Spring -> Node -> React"} |
| SOCKET_COMMAND | `message.send` | MessageSendCommand | CommandAck | {"direction": "React -> Node -> Spring -> Node -> React"} |
| SOCKET_COMMAND | `message.revise` | MessageReviseCommand | CommandAck | {"direction": "React -> Node -> Spring -> Node -> React"} |
| SOCKET_COMMAND | `subscription.request` | SubscriptionCommand | SubscriptionAck | {"direction": "React -> Node (Spring 현재 권한 검증)"} |
| SOCKET_COMMAND | `subscription.release` | SubscriptionCommand | SubscriptionAck | {"direction": "React -> Node (Spring 현재 권한 검증)"} |
| SOCKET_EVENT(추가 구현 필요) | `auth.invalidated` | 없음 | AuthInvalidatedEvent | 사용자 대상 제어 이벤트, 10절 |
| SOCKET_EVENT(추가 구현 필요) | `relay.status` | 없음 | RelayStatusEvent | 중계 상태 변경, 10절 |
| REST | `GET /api/inquiries/{id}/messages` | MessageHistoryQuery | MessageHistoryPage | 최신 기본 30·이전 커서, 10절 |
| REST | `POST /api/runs/{id}/retries` | RunRetryRequest | RunSummary | 소유권·버전·멱등성 검사, 10절 |
| SOCKET_EVENT | `agent.progress` | 없음 | AgentProgressEvent | {"direction": "Spring -> Node -> React"} |
| SOCKET_EVENT | `answer.delta` | 없음 | AnswerDeltaEvent | {"direction": "Spring -> Node -> React"} |
| SOCKET_EVENT | `message.stored` | 없음 | MessageStoredEvent | {"direction": "Spring -> Node -> React"} |
| SOCKET_EVENT | `state.changed` | 없음 | StateChangedEvent | {"direction": "Spring -> Node -> React"} |
| AI_HTTP | `POST /internal/mock/v1/runs` | AiRequest | AiAccepted | {"successStatus": 202, "auth": "SERVICE_AUTH_PENDING"} |
| AI_SSE | `GET /internal/mock/v1/runs/{runId}/events` | 없음 | progress: AiProgress, answer.delta: AiDelta, result: AiResult | {"auth": "SERVICE_AUTH_PENDING"} |

## 9. 프론트 live 연동 프로필

설정은 [프론트 README](../../frontend/README.md#2-실행-모드와-env)를 따른다. 현재 임시 Live Adapter는 2절·8절의 URL/이벤트와 [DATA_TYPES](DATA_TYPES.md)의 타입을 기대한다. 실제 서버에 적용할 표현은 담당자 간 확인·합의한다. 이 절은 현재 어댑터 연결을 설명한다. 정책 원본은 10절, [남은 담당 작업](README.md#확정-기준과-남은-담당-작업)과 현재 구현 차이는 11절을 따른다.

- REST는 설정한 Spring origin에 `/api/...`를 붙인다. 로그인은 `LoginRequest` JSON을 보내고 `LoginResponse` 자체를 받는다. 나머지 인증 요청은 `Authorization: Bearer <accessToken>`이다. `/api/auth/me` 응답으로 사용자·부서·역할을 확정한다. 응답은 `{ data: ... }` wrapper를 추가하지 않는다. 로그아웃은 204 본문 없음이다.
- Socket.IO 4.x 호환 서버를 사용한다. 기본 namespace `/`, 접속 path `/socket.io`이며 path는 환경 설정으로 바꿀 수 있다. path는 namespace나 룸 이름이 아니다. 연결마다 `socket.handshake.auth.token`에 토큰을 받는다. URL 쿼리·이벤트 payload로 토큰을 요구하지 않는다. polling과 WebSocket 업그레이드를 지원한다.
- Node는 연결 주체와 `SubscriptionCommand.target` 접근 권한을 Spring에 확인하고 허용된 target만 룸에 참여시킨다. 룸 내부 이름은 서버가 결정한다. Spring↔Node 내부 검증 입출력은 10절을 따르며 실제 서비스 인증·중계 구현은 서버 담당자가 맡는다. 프론트는 직접 호출하지 않는다.
- 명령명은 `inquiry.create`, `message.send`, `message.revise`, `subscription.request`, `subscription.release`다. 8절에 연결한 JSON을 단일 payload로 받고 **Socket.IO ACK 콜백의 첫 번째 인자 하나에** `CommandAck` 또는 `SubscriptionAck` 객체를 반환한다. 서버 콜백에 `(null, result)`를 보내거나 별도 `ack` 이벤트로 응답하지 않는다. 클라이언트 `.timeout()` 콜백의 첫 번째 오류 인자는 Socket.IO가 추가하는 값이다.
- `CommandAck.ok=true`는 Spring 저장 확인 이후에만 보낸다. 프론트는 requestId·대상/저장 식별자·필수 ACK 필드를 확인한다. 잘못된 ACK·10초 시간 초과·전송 중 연결 종료는 결과 미확인으로 둔다. 자동 저장 재시도나 오프라인 명령 적재는 하지 않으며 재확인은 같은 requestId·같은 본문이다.
- 현재 어댑터의 업무 이벤트는 `agent.progress`, `answer.delta`, `message.stored`, `state.changed`다. 10절에서 추가한 `auth.invalidated`·`relay.status`는 아직 프론트 구현이 필요하다. 각 Event 객체 하나를 보낸다. `message.stored`·`state.changed`는 REST 재조회 신호이며 답변 조각을 DB 저장 완료로 취급하지 않는다. 티켓 목록/상세는 신호 외에도 주기적으로 REST를 조회한다.
- 연결 거부는 Socket.IO `connect_error`로 전달하고 `error.data`에 `ApiErrorBody`를 넣는다. `UNAUTHENTICATED`는 refresh 대상, `AUTH_CONTEXT_CHANGED`는 재로그인, `ACCESS_DENIED`는 권한 거부, `SERVICE_UNAVAILABLE`은 중계 서버/업무 서버 장애다. 단순 네트워크 오류는 인증 실패로 간주하지 않는다. 명령·구독 거부는 해당 오류 ACK로 반환한다.
- 재접속 때 토큰으로 다시 인증하고 화면의 target을 다시 구독한다. 서버는 이전 socket의 룸을 새 socket에 무조건 복구하지 말고 현재 권한을 검증한다. 같은 target의 여러 소비자는 클라이언트 참조 횟수를 관리하고 마지막 해제 때만 서버에 release한다. 로그아웃·계정 전환·연결 종료는 이전 구독과 대기 전송을 정리한다.
- 브라우저 origin을 Spring REST와 Node Socket.IO 양쪽 CORS에 허용한다. Spring은 GET/POST/OPTIONS와 Authorization·Content-Type을 허용한다. 업무 요청은 Bearer, 로그인/refresh/로그아웃은 쿠키를 포함하며 10절의 CORS·Origin/CSRF 조건을 함께 적용한다. 실제 쿠키 발급·부서 변경 구독 회수는 서버 구현이 필요하다. HTTPS 화면에서 혼합 콘텐츠 요청은 사용하지 않는다.

현재 프론트는 `GET /api/departments`를 직접 호출하지 않으며 `/api/auth/me`와 티켓 응답 안의 Department를 사용한다. 분석 재시도·refresh·최신 대화 조회는 아래 추가 프로필을 따른다. F009 관련 대화·티켓 처리 명령과 F008·F010은 이 연동 범위 밖이다. AI 내부 `/internal/mock/...` 경로는 Python↔Spring의 Mock 제안이며 프론트 live에서 호출하거나 운영 경로로 확정하지 않는다.

## 10. 인증·재접속·최신 대화·분석 재시도 추가 프로필

상태: 2026-10-25 개발 기준 확정. 이미 선택한 정책을 다시 미정으로 다루지 않는다. 이 결정은 작성자 외 팀원 리뷰·병합이나 서비스 구현·통합 검증 완료를 뜻하지 않는다. 11절에 구현·검증과 남은 환경 선정을 분리한다. 값 목록은 DATA_TYPES.md가 원본이다.

### 인증과 권한

- Access Token은 메모리, 유효기간 15분이다. 만료 시 POST /api/auth/refresh(본문 없음)로 갱신하고 LoginResponse를 받는다. 동시 갱신은 한 요청을 공유한다.
- Refresh Token은 HttpOnly 쿠키이며 최초 로그인 시점부터 최대 7일이다. 갱신마다 새 토큰으로 회전하고 이전 토큰을 무효화하며 최초 7일의 종료 시각을 연장하지 않는다. Spring은 같은 갱신 요청의 경합·재사용 검출·안전한 검증값 저장·폐기를 구현한다. 토큰 원문을 JSON·로그·업무 이벤트에 노출하지 않는다.
- 로그아웃은 현재 로그인 세션과 해당 소켓/구독을 종료한다. 부서·역할 변경 또는 계정 차단은 해당 사용자의 기존 인증·구독을 무효화하고 재로그인한다. Access Token의 잔여 15분 동안 예전 권한을 허용하는 방식이 아니다. 실제 세션 무효화 저장·조회 방식과 키 관리의 구현은 백엔드 책임이다.
- 로그인/refresh/로그아웃만 credentials=include, 업무 REST는 Bearer와 credentials=omit이다. 운영 HTTPS의 Refresh 쿠키는 Secure, 같은 사이트 배포와 SameSite=Lax를 기본으로 한다. Spring은 정확한 CORS 허용 Origin·Allow-Credentials와 Origin/CSRF 검증을 함께 구현한다. 실제 도메인·TLS·키 배포 설정은 서버 담당자가 선정한다. 다른 사이트 배포가 필요하면 SameSite=None/Secure 및 CSRF 방어를 함께 재검토한다.
- Refresh 만료·폐기(401)는 입력을 보존한 재로그인이다. 갱신 네트워크/503은 재연결 안내이며 인증 만료로 확정하지 않는다. ACCESS_DENIED(403)는 권한 안내다. AUTH_CONTEXT_CHANGED는 서버가 현재 부서/역할의 차이를 확인한 응답이며 재로그인한다.
- 갱신한 권한이 이전과 달라져도 재로그인한다. 로그아웃·계정 전환 뒤 도착한 이전 refresh 응답은 반영하지 않는다. 요청이 없는 동안의 권한 회수도 서버가 보장한다.

### 시간과 Node→Spring 내부 검증

- 저장 ACK는 10초이며 분석 완료가 아닌 Spring 저장 결과를 기다린다. Node→Spring 검증은 요청당 최대 3초, 초과하면 SERVICE_UNAVAILABLE이며 ACCESS_DENIED로 바꾸지 않는다.
- 자동 재접속은 연결 끊김 감지 후 최대 30초다. 기본 간격 1→2→4→최대 5초에 jitter를 적용하고 각 연결 시도는 최대 5초다. 이후 수동 다시 연결 또는 online 이벤트로 재개한다. 재연결 뒤 인증·대상 권한 재검증·REST 재조회가 필요하다. 저장 명령을 자동 반복하지 않는다.
- POST /internal/realtime/authenticate: Authorization Bearer는 Node 서비스 자격증명, 본문은 `{accessToken: string}`이다. 성공 200은 `{user: CurrentUser, expiresAt: IsoDateTime}`, 무효 401은 ApiErrorBody다.
- POST /internal/realtime/authorize-subscription: 같은 서비스 인증에 `{accessToken: string, target: SubscriptionTarget}`을 전달한다. 성공 200은 `{target: SubscriptionTarget}`, 거부 403/권한 문맥 변경 401·AUTH_CONTEXT_CHANGED/장애 503은 ApiErrorBody다. 클라이언트 userId·룸 이름을 권한 근거로 인정하지 않는다.
- Node 서비스 인증과 사용자 JWT는 분리한다. 개발 서비스 자격증명은 환경 변수로 전달하고 저장소·로그에 넣지 않는다. 실제 발급·배포·회전·운영 인증 방식은 백엔드와 Node 담당자가 설계·검증한다. 프론트는 내부 검증 경로를 직접 호출하지 않는다.
- Spring→Node의 인증 무효화 통지를 받은 Node는 서버 권한·구독부터 회수하고 해당 사용자에게 auth.invalidated(AuthInvalidatedEvent)를 전달한다. ACCESS_TOKEN_EXPIRED는 갱신 후 재연결, AUTH_CONTEXT_CHANGED/REFRESH_TOKEN_REVOKED는 재로그인이다. 알림 유실 여부와 무관하게 서버 검증이 차단해야 한다.
- Node는 Spring 중계 단절/복구 때 relay.status(RelayStatusEvent)를 보낸다. UNAVAILABLE은 입력·저장 결과를 유지하고 전송을 보류, AVAILABLE은 현재 권한 재검증·재구독·REST 복원이다. 기존 저장 결과·진행 중 업무를 연결 단절만으로 실패 처리하지 않는다. 전체 업무 데이터를 방송하지 않는다.

### 오류와 화면 행동

- UNAUTHENTICATED/ACCESS_TOKEN_EXPIRED(401): 갱신 가능 여부를 확인한다. 저장 소켓 명령은 결과 미확인을 보존하고 자동 재전송하지 않는다.
- REFRESH_TOKEN_EXPIRED/REFRESH_TOKEN_REVOKED(401), AUTH_CONTEXT_CHANGED(401): 재로그인. ACCESS_DENIED(403): 권한 안내, 자동 갱신 반복 금지.
- INVALID_INPUT(400): 원문 보존·수정 안내. NOT_FOUND(404): 주소/목록 확인.
- INQUIRY_VERSION_CONFLICT(409): 저장하지 않고 최신 문의·대화를 조회한다. 최신 원문과 정정 초안을 비교해 사용자가 새 requestId로 다시 제출한다. 현재 버전은 재조회로 확인한다.
- IDEMPOTENCY_KEY_REUSED(409): 같은 키·다른 내용, 자동 반복 금지. RUN_NOT_RETRYABLE(409): 최신 실행 상태 조회 후 허용되지 않는 재시도 버튼을 제공하지 않는다.
- RATE_LIMITED(429): REST는 Retry-After 초를 전달하며 소켓 오류 메시지에도 대기 시간을 안내한다. 대기 후 사용자 재시도를 제공하고 서버가 제한을 재검사한다. 오류 외피는 기존 ApiErrorBody를 유지한다.
- SERVICE_UNAVAILABLE(503): 조회 재시도와 저장 결과 재확인을 구분한다. DEADLINE_EXCEEDED/MODEL_UNAVAILABLE은 비동기 실행 오류로 RunSummary.error에 기록하고 이미 반환된 HTTP 응답을 뒤늦게 바꾸지 않는다. retryable=true만으로 업무 변경을 자동 반복하지 않는다.
- 대표 코드는 INVALID_INPUT, SERVICE_UNAVAILABLE로 통일한다. VALIDATION_ERROR/TEMPORARY_UNAVAILABLE를 같은 의미의 새 생산자 코드로 추가하지 않는다. 미등록 코드·프론트 로컬 오류는 안전한 일반 안내로 처리한다.

### 목록·최신 30개와 스크롤

- 문의·티켓 목록은 page 기본 0, size 기본 20/최대 100, createdAt 내림차순·동률 id 내림차순이다. 기존 티켓 필터는 processingStatus이며 아래 칸반 추가 프로필에서 검색·빠른 필터·다중 상태를 확장한다. 배정 상태 필터·임의 담당자 선택은 후속이고 내 담당 필터는 MVP 추가 대상이다. 범위는 Spring의 현재 부서/역할로 결정한다.
- GET /api/inquiries/{id}/messages?size=30은 최신 30개다. 추가 조회는 &beforeSequenceNo=<첫 순번>, 기본 30·최대 100이다. MessageHistoryPage.nextBeforeSequenceNo가 null이면 끝이다. 순번은 불변이며 items는 오름차순, 화면 맨 아래가 최신이다. id와 높은 revision으로 중복 제거한다.
- 처음 최신 페이지 확보 후에만 전송·정정한다. 이전 페이지 조회/실패는 최신 페이지의 유효성을 없애지 않는다. 최신 재조회 실패는 입력을 보존하고 확인 전 전송을 막는다.
- 상단 120px 이내에서 이전 페이지를 추가하며 현재 위치를 보정한다. 초기 진입은 맨 아래, 하단 80px 이내를 보던 사용자는 새 답변을 따라가고 나머지는 새 메시지 버튼으로 이동한다. 이전 조회 실패는 수동 재시도한다.
- agent.progress/answer.delta는 분석 중·답변 작성 중의 공개 상태 표시이며 Spring 저장 확인 후 최종 답변이다. 임의 퍼센트나 모델 내부 사고 과정을 표시하지 않는다.

### 부서 칸반·검색 추가 프로필 (2026-10-27 · 팀 리뷰 대상)

이 절은 PRD 4.6·8.2와 F009-AC11~AC20의 MVP 확장 계약안이다. 기존 Mock·프론트·Spring이 지원한다는 의미가 아니다. 팀 리뷰·담당 구현과 11절 검증 후 연결한다. 보기 기본값·접근성·상세 복원 등 화면 동작의 원본은 F009다.

- 기존 `GET /api/tickets`를 확장한다. WAITING/IN_PROGRESS/WAITING_CONFIRM은 각각 기존 단일 processingStatus로 조회하고 종료는 `processingStatuses=RESOLVED&processingStatuses=CLOSED_UNCONFIRMED`로 조회한다. 보드 첫 로딩은 컬럼별 4개 독립 요청이다. 다중 상태의 OR 조건을 서버가 먼저 적용한 뒤 하나의 정렬·페이지·totalElements를 반환한다. 두 상태의 개별 첫 페이지만 합쳐 종료 페이지로 간주하지 않는다. 종료 기간 제한은 없다.
- processingStatuses는 반복 쿼리 키로 직렬화하며 쉼표 문자열·빈 배열·중복·미등록 상태·단일 processingStatus와 동시 전달은 400 INVALID_INPUT이다. page/size의 기존 제한을 그대로 적용한다. 각 컬럼은 독립 page를 관리하고 `(page + 1) * size < totalElements`이면 더 보기를 제공한다. totalElements는 현재 권한·검색·필터·컬럼 상태 조건을 모두 적용한 전체 건수다.
- `q`는 제출/초기화 시 반영하는 기본 검색이다. 앞뒤 공백을 제거하고 빈 값은 생략하며 최대 100 Unicode 코드 포인트다. 티켓 번호 또는 제목의 대소문자 구분 없는 부분 문자열 OR 검색을 수행한다. `%`, `_` 등의 문자는 와일드카드가 아닌 문자로 검색하고 정규식/전문 검색 문법을 제공하지 않는다. 제한 초과는 400 INVALID_INPUT이다. 검색 문자열은 URL 인코딩하며 로컬에서 조회한 페이지만 검색하지 않는다.
- `quickFilter=MINE|URGENT|HIGH|RESOLUTION_REPORTED` 중 하나를 지정한다. 생략하면 전체다. MINE은 인증된 회원 ID와 담당자 ID가 같은 티켓, URGENT/HIGH는 해당 우선순위, RESOLUTION_REPORTED는 저장된 해결 신고가 있는 티켓이다. 검색·상태·빠른 필터는 AND로 결합한다. 임의 userId로 MINE의 범위를 넓히지 않는다. 미등록 값은 400이다.
- 응답은 TicketPage이며 items는 DATA_TYPES의 TicketListItem 추가 프로필이다. 조회권한은 기존과 같다. 그룹 정보도 현재 부서/업무 조회 권한 범위를 검사한다. 카드·그룹은 읽기 전용이며 해당 응답이 가져오기/그룹 생성/해결 변경 권한을 부여하지 않는다.
- `view=board|list`는 클라이언트 URL 상태다. 보드에서는 목록용 processingStatus를 보존하더라도 API에서 제외하고 컬럼 상태 조건으로 치환한다. 검색·빠른 필터·size는 컬럼 요청마다 동일하게 적용한다. 보기/조건 변경 시 페이지를 초기화한다.
- 컬럼별 초기 실패/추가 조회 실패를 구분하고 성공한 결과를 유지한다. 실패한 건수는 알 수 없음으로 표시한다. 그룹 구역의 화면 카드 수는 현재 불러온 수이며 그룹 전체 건수와 구분한다. 변경 이벤트·재접속 후에는 현재 조건과 불러온 깊이까지 REST 재조회하고 ID 중복·이전 컬럼 카드를 제거한다. 페이지 조회 중 업무 변경에 대한 완전한 스냅샷 격리를 보장하는 계약은 아니며 갱신 후 최신 상태로 맞춘다.

### 정정·분석 재시도·업무 저장

- 본인 사용자 메시지는 정정 기간·횟수 제한 없이 허용하고 원문·주체·시각·차수를 보존한다. 종료된 문의도 정보 보완 정정은 가능하지만 자동 재오픈·재배정·새 티켓 생성은 하지 않는다. AI 메시지·타인 메시지는 이 사용자 정정 권한에 포함하지 않는다.
- POST /api/runs/{id}/retries는 RunRetryRequest → 200 RunSummary로 실행 접수 또는 기존 실행을 반환한다. 본인 문의의 retryable=true인 시간 초과/일시 실패만 허용하고 최신 버전·기존 성공 작업·현재 활성 실행을 검사한다. 접수는 복구 완료가 아니다.
- 같은 사용자·원본 runId·requestId·같은 본문은 최초 결과, 같은 키·다른 본문은 충돌이다. 최신 버전이 다르면 INQUIRY_VERSION_CONFLICT, 허용되지 않는 상태는 RUN_NOT_RETRYABLE이다. 같은 문의/버전의 활성 실행이 있으면 기존 실행을 반환한다. 프론트는 접수 중 중복 클릭을 막고 응답 유실 시 같은 요청으로 확인한다.
- 재시도는 기존 메시지·문제·workItemId·성공 티켓을 보존한다. 유효 결과가 저장된 실패 작업만 재적용할 수 있고 유효 결과가 없으면 저장 입력으로 다시 분석한다. LLM 생성 중간 체크포인트 재개와 티켓/재이관 기술 복구 UI는 후속이다.
- 문의 1:N 문제, 문제 1:N 처리 작업, 작업당 티켓 최대 1개다. inquiry.version 초기 1, 추가·정정 때 증가하며 동일 요청 결과 재사용을 버전 비교보다 먼저 한다. 작업↔티켓 유일성과 티켓/부서/최초 배정 이력의 원자 저장, 여러 작업의 부분 성공 보존을 구현한다.
- 요청 처리 결과는 최소 24시간 보존하고 이후에도 작업↔티켓 유일성을 유지한다. 이 기간은 PRD의 업무 기록 180일·운영 로그 30일과 별개다. 실제 테이블/FK/인덱스/트랜잭션/마이그레이션은 백엔드가 구현한다.
- 부서 배정 후 부서 구성원이 미담당 티켓을 가져가 개인 담당자가 되는 PRD 3.3의 원칙을 유지한다. 부서·담당자·processingStatus·routingStatus는 별개다. 가져오기/처리/재이관은 후속 범위다.

### AI 실행과 화면 표현

- 단일 에이전트 MVP 초기 상한은 전체 분석 60초, LLM 호출 시도 최대 3회, 일시 장애 자동 재시도 최대 1회, 호출당 출력 최대 1,500토큰이다. 재시도도 3회·60초 안에 포함하며 남은 예산을 초과하면 새 호출을 시작하지 않는다. 이 수치는 실측 SLO 달성이나 모델 품질을 보장하지 않는다.
- AI 담당자는 모델/SDK의 내부 자동 재시도를 포함한 전체 시도를 계산하고 사용량·비용·시간을 기록한다. AI 응답 지연·출력 잘림·스키마 실패·부분 결과·외부 호출 취소를 검증한다. 출력 한도 도달을 정상 결과로 확정하지 않는다. 모델명·SDK의 실제 설정 방식·금액 상한·후속 멀티 에이전트 예산은 측정 후 별도 선정한다.
- Spring은 문의 버전·활성 실행·허용 식별자와 저장 결과를 최종 확인하고 지연 도착한 이전 실행을 반영하지 않는다. AI 서버 제한만으로 DB 중복 방지를 대체하지 않는다.
- 이번 화면 표현은 저장 답변 문자열·Problem 상태·TicketCard다. 구조화 가이드/문서 링크 블록은 후속이다. 무티켓 안내를 사용자 확인 없이 해결로 확정하지 않는다.

### MVP 범위와 명세 관리

- 이번 MVP: 로그인/갱신, 새 문의/개인 채팅 세션 재진입/정정, 실제 단일 AI 분석/복수 티켓 생성/부서 배정/결과 카드, 부서/업무 관리자 읽기 전용 티켓 조회, 부서 칸반·목록 전환과 전체 표시 정보·기본 검색·빠른 필터·컬럼별 건수/더 보기·종료 통합 조회, 분석 시간 초과/일시 실패 재시도. 실제 단일 에이전트 평가·기준선 보존은 PRD대로 필수다. 칸반 프로필은 팀 리뷰·구현 검증 별도다.
- 후속: 독립 내 문의 상세 모달·예시 질문·새 문의 분리 동의·티켓 관련 대화·담당자 가져오기/처리/재이관·운영 기술 복구 UI·구조화 가이드. 후속 제품 목표를 삭제하거나 미구현을 완료로 표시하지 않는다. 이슈 #30/#31의 기존 목록과 범위 차이는 담당자가 조율하며 문서 변경만으로 이슈를 종료하지 않는다.
- 공통 계약 원본은 DATA_TYPES.md/WIRE_CONTRACT.md다. 언어별 생성 코드는 추가하지 않으며 OpenAPI/JSON Schema는 이번 연결의 필수 조건이 아니다. REST/DB는 백엔드, 소켓/내부 검증은 Node+백엔드, AI 입출력은 AI+백엔드, 화면 행동은 프론트가 관리하며 영향받는 담당자가 함께 검토한다.

## 11. 담당별 구현과 검증 상태

아래 내용은 공유 구현 책임과 인수 검증 목록이다. 정책을 확정한 것과 실제 구현·테스트 통과를 혼동하지 않는다. 이 문서 수정에서는 런타임 코드·DB·인프라를 변경하지 않았다.

### 백엔드 구현 대상

- 칸반 추가 프로필: 다중 상태 종료 조회·검색·빠른 필터를 페이지와 집계 전에 적용, TicketListItem/그룹 조회 권한·null 처리, 정렬·전체 건수·페이지 구현. 기존 단일 상태 조회와 문의 결과용 TicketCard의 호환을 검증한다.

- 15분 Access/최대 7일 Refresh, 회전·재사용 검출·현재 세션 로그아웃·사용자 권한 변경 시 기존 인증 무효화, HttpOnly 쿠키/CSRF/CORS 및 안전한 검증값 저장.
- 내부 authenticate/authorize-subscription, 현재 소유자·부서·역할·실행 권한 검사, Node에 만료/무효화 통지, REST 메시지/목록/재시도 계약.
- inquiry.version, 생성 작업과 티켓/FK/유일성, 최소 24시간 멱등 결과, 부분 실패·원자 저장·정정 이력·오래된 실행 차단, 마이그레이션.
- 상태·대표 오류 코드·허용 전이와 Retry-After, AI 전체 실행/저장 결과 검증. 정확한 DB 물리명·인덱스·키 관리·Spring 다중 처리자 라우팅은 구현 설계·검증 대상이다.

### Node 구현 대상

- 별도 서비스 자격증명·사용자 JWT를 Spring에 검증하고 대상 권한 확인 후 룸 참여. 내부 검증 3초·저장 ACK 10초, 이벤트별 외피·수신/저장 구분.
- auth.invalidated/relay.status 발행과 제한된 대상 전달, 서버가 회수한 권한의 룸/전송 차단, 재접속/중계 복구 시 재검증 및 누락 데이터 복원 연계.
- Node/Socket.IO/Java 클라이언트의 정확한 버전·환경별 Origin/주소/TLS·서비스 자격증명 발급/배포/회전은 백엔드와 선정·통합 검사한다.

### AI 구현 대상

- 단일 에이전트 60초·최대 3시도·일시 장애 자동 재시도 최대 1회·호출당 출력 1,500토큰 상한과 사용량/비용 기록. SDK 자체 재시도와 도구 대기를 전체 예산에 포함한다.
- inquiryVersion/runId와 기존 문제·작업·성공 티켓을 유지한 입출력, 공개 진행·조각/최종 결과 분리, 안전한 실패·출력 잘림·스키마 검증.
- 유효 저장 단계 재사용과 무결과 입력 재분석을 구분한다. 실제 모델/SDK·금액 상한, Spring↔Python 최종 경로/SSE·서비스 인증·재시작 복원은 담당자 선정·검증 대상이다. 체크포인트 재개는 현재 구현 완료가 아니다.

### 프론트 현재 지원과 추가 구현 대상

- 칸반 추가 구현 대상: 보드·목록 전환/URL 복원, 전체 카드 정보/그룹 가로 구역, 검색/빠른 필터, 컬럼별 요청/더 보기/부분 실패, 키보드/좁은 화면, 실시간 재조회. 현재 TicketBoard 이름의 컴포넌트는 테이블 구현이며 시안 추가만으로 칸반 지원 완료로 기록하지 않는다.

- 현재 지원: 메모리 Access·단일 refresh 요청·일반 403/권한 문맥 변경 구분, 30초 자동 재접속·수동/online 재연결, 저장 ACK 10초·결과 미확인·초안 보존, 최신 30개/이전 스크롤, 정정 비교, 분석 재시도 버튼과 같은 요청 키 재확인.
- 미구현/보완 필요: auth.invalidated/relay.status의 EventMap·수신·화면 처리, 전체 QUEUED/SUPERSEDED·후속 상태 표시, 429 대기시간의 일관된 표시/재시도 제한, 서버 실제 쿠키/즉시 무효화·종료 문의 정정 정책 통합. 이벤트명 문서 추가만으로 프론트 연결이 된 것은 아니다.
- 현재 Mock Access 30분·메모리 Refresh 7일은 가상 동작이며 이번 실제 인증 정책의 15분/회전·쿠키·CSRF/폐기를 검증하지 않는다. Mock/임시 어댑터가 모든 새 정책과 상태를 구현했다고 보지 않는다.

### 아직 검증하지 않은 항목

- 칸반 F009-AC11~AC20의 화면 검증과 실제 서버 통합: 100건을 넘는 컬럼 추가 조회, 서로 섞인 두 종료 상태의 정렬·전체 건수·추가 페이지, 검색/필터와 권한 조합, 그룹 중복 방지, 일부 컬럼 실패, 재조회 후 컬럼 이동, URL 복원·키보드·반응형. 기존 테스트 통과는 새 프로필 지원을 증명하지 않는다.

- 실제 Spring·Node·DB·Python/LLM의 전체 통합과 자동 브라우저 E2E.
- Refresh 쿠키 속성·CSRF/CORS·회전 경합/재사용·로그아웃/부서 변경 즉시 차단과 알림 유실 시 권한 방어.
- 내부 서비스 인증·새 제어 이벤트·중계 단절/복구·Java 호환·서버 다중 인스턴스 ACK 라우팅.
- 실제 DDL/마이그레이션·트랜잭션 경합·24시간 이후 중복 방지·부분 성공/재시도·최신 조회/스크롤의 실제 서버 동시성.
- 실제 모델의 60초/3시도/1,500토큰·자동 재시도 상한, 지연 결과 차단·단계 재사용·정확도·비용·실측 SLO.
- 새 정책·신규 제어 이벤트를 포함한 회귀 테스트와 후속 화면. 기존 Mock/어댑터 테스트 통과를 위 항목의 통과로 보고하지 않는다.
