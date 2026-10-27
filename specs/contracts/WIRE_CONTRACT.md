# wire JSON·REST·SSE·AI 계약

상태: 2026-10-27 개발 기준 확정 / 작성자 외 팀원 리뷰·실제 서비스 구현 및 통합 검증 별도. 현재 Mock·임시 Live Adapter와 확정 계약의 차이는 11절에 명시한다. 전체 필드와 타입은 [DATA_TYPES.md](DATA_TYPES.md), 작업별 입출력은 이 문서의 연결표를 따른다. 언어별 코드 생성은 제공하지 않는다.

## 1. 공통 표현

- JSON 필드명은 camelCase. 부서 code·상태 code를 소문자/한글로 임의 변환하지 않는다.
- 모든 객체 키는 필수이며 nullable일 때만 null을 허용한다. 빈 배열은 `[]`이다.
- UUID는 소문자 하이픈 문자열, 시각은 UTC Z 문자열이다. 배열·객체 순서를 인덱스 ID로 사용하지 않는다.
- version·revision·순번은 1부터, page는 0부터다. 질문 횟수는 0~3이다. 숫자 문자열이나 boolean을 정수로 변환하지 않는다.
- 사용자 메시지는 1~4,000 Unicode 코드 포인트다. JS `[...content].length`, Java `codePointCount`, Python `len`을 사용한다. 단독 surrogate는 거부하며 원문을 trim하거나 자동 절단해 저장하지 않는다.
- 공백만 있는지 판단하는 문자 집합은 DATA_TYPES의 `UserContent` 정의를 따른다. 언어별 `trim`·`strip`·정규식 동작 차이에 의존하지 않는다.
- category 6개와 문의·처리 상태 코드는 DATA_TYPES의 값 목록을 따른다. 소비자는 미등록 문자열을 알 수 없는 상태로 안전하게 처리하고 성공으로 추정하지 않는다.

## 2. REST와 페이지

- React→Spring의 조회·로그인·채팅 변경 명령은 REST다.
- Spring→React의 진행·답변 조각·저장 확정·상태 변경은 SSE다.
- Python→Spring의 AI 진행·답변·결과도 내부 SSE다. 브라우저가 Python에 직접 연결하지 않는다.
- 별도의 Node.js 실시간 중계 서버는 두지 않는다. Node.js는 React 개발·빌드 런타임으로만 사용한다.

모든 REST 오류는 `ApiErrorBody`다. 400 입력, 401 인증, 403 권한, 404 없음, 409 버전·멱등 충돌, 429 제한, 503 일시 장애를 구분한다. `retryable`은 무조건 자동 재시도하라는 뜻이 아니다.

SSE 연결을 열기 전의 인증·권한·입력 오류도 HTTP 상태와 `ApiErrorBody`로 반환한다. 연결이 열린 뒤 재동기화나 인증 문맥 변경이 필요하면 이 문서의 제어 이벤트를 보낸 뒤 연결을 종료할 수 있다. 네트워크 단절만으로 인증 실패나 저장 실패를 확정하지 않는다.

### 지원 경로와 페이지 규칙

| 작업 | 요청 | 성공 |
|---|---|---|
| `POST /api/auth/login` | LoginRequest | 200 LoginResponse |
| `POST /api/auth/refresh` | 본문 없음, Refresh 쿠키 | 200 LoginResponse |
| `GET /api/auth/me` | Bearer 토큰 | 200 CurrentUser |
| `POST /api/auth/logout` | Bearer 토큰, 본문 없음 | 204 본문 없음 |
| `GET /api/departments` | 인증 | 200 Department[] |
| `GET /api/inquiries` | page, size 쿼리 | 200 InquiryPage |
| `POST /api/inquiries` | InquiryCreateCommand | 200 MutationResult |
| `GET /api/inquiries/{id}` | 문의 UUID | 200 InquiryResult |
| `GET /api/inquiries/{id}/messages` | beforeSequenceNo, size | 200 MessageHistoryPage |
| `POST /api/inquiries/{id}/messages` | MessageSendCommand | 200 MutationResult |
| `POST /api/inquiries/{id}/messages/{messageId}/revisions` | MessageReviseCommand | 200 MutationResult |
| `GET /api/runs/{id}` | 실행 UUID | 200 RunSummary |
| `POST /api/runs/{id}/retries` | RunRetryRequest | 200 RunSummary |
| `GET /api/tickets` | page, size, processingStatus | 200 TicketPage |
| `GET /api/tickets/{id}` | 티켓 UUID | 200 TicketDetail |

`MutationResult`는 기존 `CommandAck`의 저장 결과 의미를 REST에 맞게 이름을 바꾼 타입이다. `requestId`, 저장된 식별자, 현재 inquiryVersion, effect, nullable runId를 포함한다. 정확한 필드명과 기존 타입 제거는 DATA_TYPES 후속 수정에서 함께 확정한다.

page 기본 0, size 기본 20·최대 100이다. 음수·범위 초과·소수·알 수 없는 필터는 400이다. query의 null 필터는 URL에서 생략하며 문자열 `"null"`을 보내지 않는다.

문의·티켓 목록은 createdAt 내림차순, 같은 시각이면 id 내림차순이다. 권한 범위를 먼저 제한한 뒤 페이지를 계산한다. 전체 목록을 프론트에서 잘라 실제 API 페이지 검증을 대체하지 않는다.

대화는 최신 30개를 조회하고 items를 sequenceNo 오름차순으로 반환한다. `beforeSequenceNo`는 미만 조건이며 이전 항목이 더 있으면 items 첫 항목의 순번을 `nextBeforeSequenceNo`로, 없으면 null을 반환한다. 정정은 순번을 바꾸지 않고 id/revision으로 중복 제거한다.

## 3. 인증·권한

Mock은 메모리의 가짜 토큰을 계정 fixture와 연결한다. 실제 JWT 서명·Refresh 회전·저장·폐기를 구현한 것으로 보지 않는다. 실제 REST와 fetch 기반 SSE 요청은 `Authorization: Bearer <accessToken>`을 사용한다.

- 문의·전체 메시지·실행 조회와 `INQUIRY` SSE는 소유자만 허용한다. 업무 관리자 역할만으로 문의 전체 대화 조회 권한을 부여하지 않는다.
- 티켓 목록·상세와 `DEPARTMENT_TICKETS`·`TICKET` SSE는 현재 처리 부서 구성원 또는 BIZ_ADMIN만 허용한다.
- AGENT_OPS_ADMIN 역할만으로 업무 조회 권한이 생기지 않는다.
- 일반 부서원에게는 자기 현재 처리 부서 티켓만 반환한다. 클라이언트가 userId나 다른 부서 ID를 보내 권한을 넓힐 수 없다.
- 화면 메뉴는 CurrentUser를 참고하되 최종 권한은 Spring이 요청마다 판단한다.
- 다른 사용자 로그인·로그아웃 때 조회 캐시·초안·미확인 요청·열린 SSE를 정리한다. 로그아웃 요청 실패 시에도 브라우저 정리는 수행한다.

## 4. REST 변경 명령·멱등성

문의 생성·메시지 전송·정정은 각 REST endpoint로 수행한다. `MutationResult` 성공은 Spring이 해당 변경을 DB에 저장한 뒤에만 반환한다. runId는 분석 실행 접수 식별자이며 티켓 저장 성공을 뜻하지 않는다.

모든 변경 요청은 `Idempotency-Key: <requestId>` 헤더를 사용하고 본문의 requestId가 남아 있는 동안에는 두 값이 같아야 한다. 헤더를 계약 원본으로 통일한 뒤 본문 중복 필드는 제거할 수 있다.

| 상황 | 화면·키 처리 |
|---|---|
| HTTP 응답 대기 | 전송 중. 저장 성공으로 표시하지 않음 |
| 4xx·5xx 응답 | 코드별 안내와 입력 보존. 검증 오류를 같은 내용으로 자동 반복하지 않음 |
| timeout·연결 종료 | 결과 미확인. 실패로 확정하지 않음 |
| 같은 행위자·경로·키·동일 본문 | 최초 결과 재사용. 한 번만 저장 |
| 같은 키·다른 본문 | 409 `IDEMPOTENCY_KEY_REUSED` |
| 본문 또는 expectedInquiryVersion을 바꾼 새 의도 | 새 requestId. 기존 미확인 요청을 덮어쓰지 않음 |

클라이언트 응답 대기 상한은 10초다. timeout 뒤 자동 재전송하지 않고 사용자가 확인한 경우 동일 키·동일 본문으로 결과를 재확인한다. 처리 결과는 최소 24시간 보존하고, 이후에도 작업↔티켓 유일성으로 중복 생성을 막는다. 동일 키 재전송 검사는 버전 비교보다 먼저 한다.

## 5. SSE 연결·이벤트·복구

브라우저는 다음 endpoint에 대상 하나당 연결 하나를 연다.

```http
GET /api/realtime/events?targetType=INQUIRY&targetId={uuid}
Accept: text/event-stream
Authorization: Bearer {accessToken}
Last-Event-ID: {lastEventId}
```

`targetType`은 `INQUIRY`, `DEPARTMENT_TICKETS`, `TICKET`이다. Spring은 현재 인증 주체가 target에 접근할 수 있는지 연결 시점에 검사한다. 임의 채널명·클라이언트 userId를 신뢰하지 않는다. 화면을 떠나면 해당 연결을 닫고, 재연결·부서 변경 때 권한을 다시 확인한다.

응답은 UTF-8 `text/event-stream`이며 각 업무 이벤트는 `id`, `event`, `data`를 가진다. `data`는 이벤트 타입 JSON 객체다. 서버는 프록시·브라우저 idle timeout보다 짧은 주기로 comment heartbeat를 보낸다. heartbeat는 업무 이벤트가 아니며 상태를 바꾸지 않는다.

| event | data 타입 | 의미 |
|---|---|---|
| `agent.progress` | AgentProgressEvent | 공개 가능한 분석 진행 |
| `answer.delta` | AnswerDeltaEvent | 임시 답변 조각 |
| `message.stored` | MessageStoredEvent | DB 저장 확정 메시지·정정 |
| `state.changed` | StateChangedEvent | 대상 REST 재조회 신호 |
| `auth.invalidated` | AuthInvalidatedEvent | 인증·권한 문맥 무효화 |
| `resync.required` | ResyncRequiredEvent | replay 불가, REST 전체 복구 필요 |

eventId 중복은 한 번만 반영한다. `answer.delta`는 `(runId,messageId,seq)`로도 중복을 제거하고 순서가 뒤바뀐 조각을 버퍼링한다. 누락·충돌이 남으면 임시 답변을 확정하지 않고 REST 저장 메시지로 복구한다.

재연결 시 마지막으로 처리한 `id`를 `Last-Event-ID`로 보낸다. 서버가 보존 구간 안의 이벤트를 재생할 수 있으면 이후 이벤트를 순서대로 보낸다. 재생할 수 없으면 `resync.required`를 보내며 클라이언트는 현재 권한으로 문의·메시지·티켓·실행을 REST 재조회하고 새 기준점으로 연결한다. SSE 재연결만으로 누락 복구를 완료했다고 보지 않는다.

Access Token이 만료되면 현재 refresh 정책으로 갱신한 뒤 새 Bearer 토큰으로 연결을 다시 연다. `AUTH_CONTEXT_CHANGED`·Refresh 폐기는 재로그인한다. 로그아웃·계정 전환·권한 회수 때 기존 SSE를 닫으며, 서버 측 권한 검사가 이벤트 전달을 차단해야 한다.

## 6. 버전·저장·답변 조각

- 문의 생성은 version=1이며 새 사용자 메시지·정정 저장마다 문의 버전을 1 올린다. AI 메시지·진행·티켓 저장 자체로 입력 버전을 올리지 않는다.
- effect가 `ANALYSIS_STARTED`이면 runId가 있어야 한다. 모든 문제가 이관된 뒤 `SUPPLEMENT_SAVED`면 runId=null이며 자동 재분석·새 티켓 생성을 시작하지 않는다.
- 진행·답변 조각만 inquiryVersion과 활성 runId를 검사한다. 다른 버전 이벤트 때문에 이미 저장된 메시지·정정 이력·티켓을 삭제하지 않는다.
- `message.stored.requestId`는 사용자 REST 요청과 임시 메시지를 연결한다. AI 저장 메시지는 requestId=null이다. REST 응답과 SSE 도착 순서가 바뀌어도 requestId/messageId로 하나의 메시지로 합친다.
- `state.changed`는 target별 REST 재조회 신호다. inquiryVersion은 문의 target이면 현재 버전, 부서·티켓 target이면 null이다.
- SSE 전달 성공은 DB 저장 성공의 증거가 아니다. 저장 확정 이벤트는 DB commit 뒤 생성한다.

## 7. Spring–AI Mock 서비스 계약

`POST /internal/mock/v1/runs`의 AiRequest → 202 AiAccepted, `GET /internal/mock/v1/runs/{runId}/events`의 SSE `progress`·`answer.delta`·`result`를 제안한다. Mock 전용 경로이며 운영 경로·서비스 인증은 미확정이다. result는 AiResult 한 번이며 종료 뒤 늦은 진행을 사용하지 않는다.

AI SSE는 Python→Spring이고 공개 SSE는 Spring→React다. Spring이 공개 가능한 payload만 변환하고 eventId를 발급한다. Python이 브라우저에 직접 상태 변경이나 저장 성공을 보내지 않는다. 임시 참조·근거·부서·버전은 [DATA_TYPES](DATA_TYPES.md#서비스-내부-표현)의 의미 규칙을 따른다.

## 8. 작업별 데이터 타입 연결

| 전송 | 경로·이벤트 | 요청 타입 | 응답 타입 | 권한·방향 |
|---|---|---|---|---|
| REST | `POST /api/auth/login` | LoginRequest | LoginResponse | 인증 없음 |
| REST | `POST /api/auth/refresh` | 본문 없음·쿠키 | LoginResponse | Refresh cookie |
| REST | `GET /api/auth/me` | 없음 | CurrentUser | 사용자 |
| REST | `POST /api/auth/logout` | 없음 | 없음 | 사용자 |
| REST | `GET /api/departments` | 없음 | Department[] | 사용자 |
| REST | `GET /api/inquiries` | InquiryListQuery | InquiryPage | 소유자 |
| REST | `POST /api/inquiries` | InquiryCreateCommand | MutationResult | 사용자 |
| REST | `GET /api/inquiries/{id}` | 없음 | InquiryResult | 소유자 |
| REST | `GET /api/inquiries/{id}/messages` | MessageHistoryQuery | MessageHistoryPage | 소유자 |
| REST | `POST /api/inquiries/{id}/messages` | MessageSendCommand | MutationResult | 소유자 |
| REST | `POST /api/inquiries/{id}/messages/{messageId}/revisions` | MessageReviseCommand | MutationResult | 소유자 |
| REST | `GET /api/runs/{id}` | 없음 | RunSummary | 소유자 |
| REST | `POST /api/runs/{id}/retries` | RunRetryRequest | RunSummary | 소유자 |
| REST | `GET /api/tickets` | TicketListQuery | TicketPage | 현재 부서 또는 BIZ_ADMIN |
| REST | `GET /api/tickets/{id}` | 없음 | TicketDetail | 현재 부서 또는 BIZ_ADMIN |
| SSE | `GET /api/realtime/events` | targetType, targetId | 공개 이벤트 | Spring→React |
| AI_HTTP | `POST /internal/mock/v1/runs` | AiRequest | AiAccepted | 서비스 인증 미정 |
| AI_SSE | `GET /internal/mock/v1/runs/{runId}/events` | 없음 | AiProgress, AiDelta, AiResult | Python→Spring |

## 9. 프론트 live 연동 프로필

- REST는 Spring origin의 `/api/...`를 사용한다. 로그인은 LoginRequest JSON, 나머지 업무 요청은 Bearer 토큰이다. 응답에 임의의 `{data: ...}` wrapper를 추가하지 않는다.
- 브라우저 access token을 메모리에 두는 현재 정책 때문에 native `EventSource` 대신 fetch 기반 SSE 클라이언트를 사용한다. 이 클라이언트는 Bearer 헤더, `Last-Event-ID`, UTF-8 스트림 파싱, 여러 `data:` 줄 결합, comment heartbeat 무시, AbortSignal 종료를 지원해야 한다.
- HTTP 변경 요청과 SSE 이벤트는 별개다. REST 성공은 저장 결과이며 SSE는 다른 화면·연결에 변경을 전달한다. SSE가 먼저 도착하거나 REST 응답이 유실되어도 멱등 키와 저장 식별자로 병합한다.
- 연결 오류는 HTTP 상태와 `ApiErrorBody`를 우선 사용한다. 열린 스트림 뒤의 제어 이벤트는 `auth.invalidated`와 `resync.required`다. 단순 네트워크 오류를 인증 실패로 간주하지 않는다.
- 재연결은 1→2→4→최대 5초 backoff와 jitter를 사용하고 전체 자동 시도는 30초를 기본으로 한다. 이후 수동 다시 연결 또는 online 이벤트로 재개한다. 연결 뒤 권한 재검증과 REST 복구가 필요하며 저장 요청을 자동 반복하지 않는다.
- 브라우저 origin을 Spring CORS에 정확히 허용한다. 업무 REST·SSE는 Authorization을 허용하고 로그인·refresh·로그아웃 쿠키는 11절의 CORS·Origin·CSRF 정책을 함께 적용한다. HTTPS 화면에서 혼합 콘텐츠를 사용하지 않는다.

현재 프론트의 Socket.IO Live Adapter는 이전 계약의 구현이며 이 계약을 만족하지 않는다. fetch 기반 SSE 어댑터와 REST 변경 명령으로 교체하기 전에는 실제 연동 완료로 표시하지 않는다. AI 내부 `/internal/mock/...` 경로는 프론트가 호출하지 않는다.

## 10. 인증·재접속·최신 대화·분석 재시도 추가 프로필

### 인증과 시간

- Access Token은 메모리, 유효기간 15분이다. 만료 시 `POST /api/auth/refresh`로 갱신하고 동시 갱신은 한 요청을 공유한다.
- Refresh Token은 HttpOnly 쿠키이며 최초 로그인부터 최대 7일이다. 갱신 때 회전하고 이전 토큰을 무효화하며 종료 시각을 연장하지 않는다.
- 로그아웃은 현재 로그인 세션을 폐기하고 해당 사용자의 열린 SSE를 종료한다. 부서·역할 변경 또는 계정 차단도 기존 인증과 스트림 접근을 회수하며, Access Token의 남은 수명 동안 예전 권한을 허용하지 않는다.
- 로그인·refresh·로그아웃만 `credentials=include`, 업무 REST·SSE는 Bearer와 `credentials=omit`이다. 운영 쿠키는 Secure와 SameSite 정책, 정확한 CORS Origin과 CSRF 검증을 함께 적용한다.
- REST 변경 응답 대기는 10초다. SSE 연결 시도 timeout은 5초, 자동 재연결 기본 총량은 30초다.
- Refresh 만료·폐기는 입력을 보존한 재로그인으로 처리한다. 갱신 네트워크 오류·503은 인증 만료로 확정하지 않으며, 계정 전환 뒤 도착한 이전 refresh 응답은 반영하지 않는다.

### 오류와 화면 행동

- `UNAUTHENTICATED`·`ACCESS_TOKEN_EXPIRED`(401)는 갱신 가능 여부를 확인한다. 결과 미확인 REST 변경을 자동 재전송하지 않는다.
- `REFRESH_TOKEN_EXPIRED`·`REFRESH_TOKEN_REVOKED`·`AUTH_CONTEXT_CHANGED`(401)는 재로그인한다. `ACCESS_DENIED`(403)는 권한 안내 후 재시도 루프를 만들지 않는다.
- `INVALID_INPUT`(400)은 원문 보존·수정 안내, `NOT_FOUND`(404)는 주소·목록 확인이다.
- `INQUIRY_VERSION_CONFLICT`(409)는 최신 문의·대화를 조회해 초안과 비교한 뒤 새 requestId로 제출한다.
- `IDEMPOTENCY_KEY_REUSED`(409)는 같은 키·다른 내용이므로 자동 반복하지 않는다. `RUN_NOT_RETRYABLE`(409)은 최신 실행 상태를 조회한다.
- `RATE_LIMITED`(429)는 `Retry-After`를 지키고 사용자 재시도를 제공한다.
- `SERVICE_UNAVAILABLE`(503)은 조회 재시도와 저장 결과 재확인을 구분한다. `retryable=true`만으로 업무 변경을 자동 반복하지 않는다.

### 목록·최신 30개와 스크롤

- 문의·티켓 목록은 page 기본 0, size 기본 20·최대 100이며 createdAt 내림차순·동률 id 내림차순이다. 티켓 필터는 현재 `processingStatus`만 제공하고 범위는 Spring의 현재 부서·역할로 결정한다.
- `GET /api/inquiries/{id}/messages?size=30`은 최신 30개다. 이전 조회는 `beforeSequenceNo`를 사용하고 `nextBeforeSequenceNo=null`이면 끝이다. items는 오름차순이며 id와 높은 revision으로 중복 제거한다.
- 최초 최신 페이지를 확보한 뒤에만 전송·정정한다. 최신 재조회가 실패하면 입력을 보존하고 확인 전 전송을 막는다.
- 상단 120px 이내에서 이전 페이지를 추가하고 현재 위치를 보정한다. 하단 80px 이내를 보던 사용자는 새 답변을 따라가며, 나머지는 새 메시지 버튼으로 이동한다.

### 정정·분석 재시도·업무 저장

- 본인 사용자 메시지는 정정 기간·횟수 제한 없이 허용하고 원문·주체·시각·차수를 보존한다. 종료된 문의의 정보 보완은 자동 재오픈·재배정·새 티켓 생성을 시작하지 않는다.
- `POST /api/runs/{id}/retries`는 RunRetryRequest → 200 RunSummary다. 본인 문의의 retryable=true인 시간 초과·일시 실패만 허용하고 최신 버전·기존 성공 작업·현재 활성 실행을 검사한다.
- 같은 사용자·원본 runId·requestId·같은 본문은 최초 결과를 반환하고 같은 키·다른 본문은 충돌이다. 문의 버전이 다르면 `INQUIRY_VERSION_CONFLICT`, 허용되지 않는 상태는 `RUN_NOT_RETRYABLE`이다.
- 재시도는 기존 메시지·문제·workItemId·성공 티켓을 보존한다. 유효 저장 단계가 있으면 재사용하고 결과가 없으면 저장 입력으로 다시 분석한다.
- 문의 1:N 문제, 문제 1:N 처리 작업, 작업당 티켓 최대 1개다. 작업↔티켓 유일성과 티켓·부서·최초 배정 이력의 원자 저장, 여러 작업의 부분 성공 보존을 구현한다.
- 요청 처리 결과는 최소 24시간 보존하고 이후에도 작업↔티켓 유일성을 유지한다. 업무 기록 180일·운영 로그 30일 보존과는 별개다.

### AI 실행과 화면 표현

- 단일 에이전트 MVP 초기 상한은 전체 분석 60초, LLM 호출 시도 최대 3회, 일시 장애 자동 재시도 최대 1회, 호출당 출력 최대 1,500토큰이다. 재시도도 전체 횟수·시간 안에 포함한다.
- 모델·SDK 내부 자동 재시도를 포함한 전체 시도와 사용량·비용·시간을 기록한다. 지연·출력 잘림·스키마 실패·부분 결과·외부 호출 취소를 검증한다.
- Spring은 문의 버전·활성 실행·허용 식별자와 저장 결과를 최종 확인하고 지연 도착한 이전 실행을 반영하지 않는다.
- 화면에는 저장 답변 문자열·Problem 상태·TicketCard를 표시한다. 구조화 가이드·문서 링크 블록은 후속이며, 무티켓 안내를 사용자 확인 없이 해결로 확정하지 않는다.

### MVP 범위와 명세 관리

- MVP는 로그인·갱신, 새 문의·개인 채팅 재진입·정정, 실제 단일 AI 분석, 복수 티켓 생성·부서 배정·결과 카드, 읽기 전용 티켓 조회, 시간 초과·일시 실패 재시도다.
- 독립 내 문의 상세 모달, 새 문의 분리 동의, 티켓 관련 대화, 담당자 가져오기·처리·재이관, 기술 복구 UI, 구조화 가이드는 후속이다.
- DATA_TYPES와 WIRE_CONTRACT가 공통 계약 원본이다. REST·DB·브라우저 SSE는 백엔드, AI 입출력은 AI+백엔드, 화면 행동과 SSE 소비는 프론트가 관리하며 영향받는 담당자가 함께 검토한다.

## 11. 담당별 구현과 검증 상태

정책을 확정한 것과 실제 구현·테스트 통과를 구분한다. 이 문서 수정은 런타임 코드·DB·인프라를 변경하지 않는다.

### 백엔드 구현 대상

- Access 15분·Refresh 최대 7일, 회전·재사용 검출·로그아웃·권한 변경 시 기존 인증 무효화, HttpOnly 쿠키·CSRF·CORS와 안전한 검증값 저장.
- 문의 생성·메시지 전송·정정 REST, `Idempotency-Key`, 현재 소유자·부서·역할·실행 권한 검사, 메시지·목록·재시도 계약.
- inquiry.version, 작업과 티켓 FK·유일성, 최소 24시간 멱등 결과, 부분 실패·원자 저장·정정 이력·오래된 실행 차단과 마이그레이션.
- 대상별 SSE 연결, heartbeat, eventId·replay·`resync.required`, timeout·완료·오류 정리, 권한 회수 중 열린 연결 차단, DB commit 뒤 이벤트 생성.
- 정확한 이벤트 보존소와 기간, 다중 Spring 인스턴스 전달·순서·중복, 프록시 buffering/timeout과 최대 연결 수는 구현·부하 검증으로 확정한다.

### 프론트 구현 대상

- 기존 Socket.IO 의존성·환경 설정·adapter와 명령/구독 타입을 제거한다. 문의 생성·메시지 전송·정정은 REST API 함수로 옮기고 결과 미확인·초안 보존·동일 멱등 키 재확인을 유지한다.
- Bearer 헤더와 `Last-Event-ID`를 지원하는 fetch 기반 SSE parser, 대상별 연결 참조 수, AbortSignal 종료, heartbeat 무시, 재연결·`resync.required`·REST 복구를 구현한다.
- eventId와 `(runId,messageId,seq)` 중복 제거, 임시 답변과 저장 메시지 병합, `auth.invalidated` 처리, 429 대기시간과 QUEUED/SUPERSEDED 상태를 화면에 일관되게 반영한다.
- 현재 Mock Access 30분·메모리 Refresh 7일은 실제 15분·회전 쿠키·CSRF·폐기를 검증하지 않는다. 기존 Socket.IO 어댑터 테스트 통과도 새 SSE 계약의 통과가 아니다.

### AI 구현 대상

- 단일 에이전트 60초·최대 3시도·일시 장애 자동 재시도 최대 1회·호출당 1,500토큰 상한과 사용량·비용 기록. SDK 자체 재시도와 도구 대기를 전체 예산에 포함한다.
- inquiryVersion/runId와 기존 문제·작업·성공 티켓을 유지하고 공개 진행·답변 조각·최종 결과를 분리하며 실패·출력 잘림·스키마를 검증한다.
- 유효 저장 단계 재사용과 무결과 입력 재분석을 구분한다. 실제 모델·SDK·금액 상한, Spring↔Python 운영 경로·서비스 인증·재시작 복원은 담당자가 선정·검증한다.

### 공통 계약 후속

- DATA_TYPES의 `CommandAck`를 `MutationResult`로 정리하고 Socket 전용 `SubscriptionCommand`·`SubscriptionAck`·`RelayStatusEvent`를 제거하며 `ResyncRequiredEvent`를 추가한다.
- Mock fixture와 기능 스펙의 명령명·ACK·구독 표를 REST 경로·HTTP 결과·SSE 대상 연결로 바꾼다.

### 아직 검증하지 않은 항목

- 실제 Spring·DB·Python/LLM·React SSE 전체 통합과 자동 브라우저 E2E.
- Refresh 쿠키·CSRF/CORS·회전 경합/재사용·로그아웃/부서 변경 즉시 차단, SSE 통지 유실 시 권한 방어.
- 프록시 buffering·timeout, replay 보존 구간, 연결 해제·재개, 다중 인스턴스 이벤트 순서·중복과 부하 한계.
- 실제 DDL·마이그레이션·트랜잭션 경합·24시간 이후 중복 방지·부분 성공/재시도·최신 조회 동시성.
- 실제 모델 예산·지연 결과 차단·단계 재사용·정확도·비용·실측 SLO와 새 정책을 포함한 회귀 테스트.

문서 변경이나 기존 Mock·어댑터 테스트 통과를 위 항목의 완료로 보고하지 않는다.
