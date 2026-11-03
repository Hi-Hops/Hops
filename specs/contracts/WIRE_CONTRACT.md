# wire JSON·REST·SSE·AI 계약

> **PRD 1.44 전환 안내:** 1~10절은 기존 부서 기반 계약의 보존 기록이며 현재 프론트 실행에 적용하지 않는다. 11절은 서비스별 구현·검증 상태, 12절은 시스템별 주담당자·문제별 확인·사례 공개를 포함한 hops-system-1 계약이다. 현재 프론트는 12절을 사용하며 Spring·AI의 부분 구현과 전체 통합 검증을 구분한다. 제품 정책은 [PRD](../../docs/PRD.md)와 [API 설계](../../docs/API.md)를 따른다.

상태: 신규·전환 전 전송 계약과 서비스별 구현 현황을 분리한다. 현재 코드·Mock·실제 서비스 통합 상태는 11절에 명시한다. 전체 필드와 타입은 [DATA_TYPES.md](DATA_TYPES.md), 작업별 입출력은 해당 프로필의 연결표를 따른다. 언어별 코드 생성은 제공하지 않는다.

## PRD 1.44 목표 전송 원칙

React→Spring 업무 변경·조회는 REST, 허용된 진행과 상태 알림은 SSE를 사용한다. Spring이 사용자·시스템·담당 관계를 검사하고 DB 저장·배정·확인·승인을 확정한다. AI는 제안하며 이벤트 수신이나 모델 출력은 업무 성공이 아니다.

- 문제당 질문 최대 5회, 시스템 미확인 대기와 선택, 문제별 저장 성공·실패·미확인을 전달해야 한다.
- 티켓 재시도 전에 동일 문제의 저장 결과와 문의 버전을 확인한다. 미확인이면 새로 생성하지 않는다.
- 처리·기능 구현 완료, 문의자 확인, 사례 초안·승인과 두 저장소별 반영/공개 상태를 구분한다.
- 사용자·담당 시스템별 조회/변경과 SSE 접근은 현재 권한으로 검사하고 재접속 후 저장 상태를 재조회한다.
- API 경로·필드·상태·구독·복구의 채택값은 12절을 따른다. 1~10절은 전환 전 기록이며 11절에서 현재 구현 상태를 확인한다.

## 전환 전 wire 프로필

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

`MutationResult`는 DATA_TYPES의 프론트 REST 연동 프로필을 사용한다. requestId·inquiryId·inquiryVersion·messageId·revision·nullable runId·effect를 반환하며 wire의 ok 외피는 없다. 기존 API 설계에 기반한 프론트 연동 프로필이며 백엔드의 동일 DTO·저장 API 구현은 Q-INF-6으로 추적한다.

프론트는 성공 응답의 필수 필드·UUID·정수 범위·effect/runId 조합과 요청 식별자의 일치를 검사한다. HTTP 성공이어도 형식·식별자가 맞지 않으면 결과 미확인으로 취급하고 서버 저장 상태를 임의로 확정하지 않는다. 정확한 필드 의미와 가상 응답 예시는 DATA_TYPES의 MutationResult 절이 원본이다.

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

재연결 시 마지막으로 처리한 `id`를 `Last-Event-ID`로 보낸다. 서버가 보존 구간 안의 이벤트를 재생할 수 있으면 이후 이벤트를 순서대로 보낸다. 재생할 수 없으면 `ResyncRequiredEvent` 형식의 `resync.required`를 보낸 뒤 스트림을 종료한다. SSE id와 JSON eventId는 동일하다. 클라이언트는 현재 권한으로 해당 대상의 문의·메시지·티켓·실행을 REST 재조회하고 기존 커서와 제어 이벤트 ID를 비운 뒤 `Last-Event-ID` 없이 새 연결을 연다. SSE 재연결만으로 누락 복구를 완료했다고 보지 않는다.

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

프론트는 REST 변경 명령과 fetch SSE 어댑터를 제공한다. AI 내부 `/internal/mock/...` 경로는 호출하지 않는다. 대역 서버 테스트와 실제 Spring·DB·AI 전체 연결 완료를 구분한다.

### PR #59 병합 후 Spring 연동

PR #59는 develop에 병합됐다(dc44f2c). 현재 부서 SSE를 지원하고 INQUIRY·TICKET은 도메인 권한 연결 전 503이다. 프론트는 503을 인증 실패로 처리하지 않고 제한된 재접속 후 수동 재시도를 제공한다.

- Last-Event-ID 이후 이벤트를 재생할 수 없으면 ResyncRequiredEvent(eventId만 필수)를 보내고 종료한다. SSE id와 JSON eventId는 동일하다.
- 프론트는 현재 연결 대상의 REST 상태를 복구한 뒤 기존 커서와 제어 이벤트 ID를 비우고 Last-Event-ID 없이 재연결한다. 제어 알림 유실·일반 종료에도 REST 복구를 유지하며 comment 문구에는 의존하지 않는다.
- 새 연결은 스트림을 읽기 시작한 뒤 REST 조회를 다시 수행한다. 진행 중 변경은 대상별 조회를 갱신한다. 서버 스냅샷/재개 커서 API는 가정하지 않는다.
- 초기 연결·업무 이벤트에 따른 REST 조회 실패는 조회 영역에서 안내하고 정상 SSE를 종료하거나 연결 끊김으로 표시하지 않는다. `resync.required` 이후의 필수 REST 복구가 실패하면 재연결을 중단하고 재시도를 안내한다. SSE 연결 유지와 데이터 복구 완료는 별개다.
- state.changed.target은 공통 형식인 type·id를 사용한다.
- 커서는 대상 연결 메모리에 보관한다. 마지막 소비자가 나가거나 로그아웃·계정 전환하면 정리한다. 새로고침 후에는 새 연결과 REST 조회로 복구한다.

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

- AI 실행의 초기 상한은 단일·멀티 공통으로 전체 분석 60초, LLM 호출 시도 최대 8회(재시도 포함), 호출당 일시 장애 자동 재시도 최대 2회, 호출당 출력 최대 1,500토큰이다. 재시도도 전체 횟수·시간 안에 포함한다.
- 모델·SDK 내부 자동 재시도를 포함한 전체 시도와 사용량·비용·시간을 기록한다. 지연·출력 잘림·스키마 실패·부분 결과·외부 호출 취소를 검증한다.
- Spring은 문의 버전·활성 실행·허용 식별자와 저장 결과를 최종 확인하고 지연 도착한 이전 실행을 반영하지 않는다.
- 화면에는 저장 답변 문자열·Problem 상태·TicketCard를 표시한다. 구조화 가이드·문서 링크 블록은 후속이며, 무티켓 안내를 사용자 확인 없이 해결로 확정하지 않는다.

### MVP 범위와 명세 관리

- MVP는 로그인·갱신, 새 문의·개인 채팅 재진입·정정, 실제 단일 AI 분석, 복수 티켓 생성·부서 배정·결과 카드, 읽기 전용 티켓 조회, 시간 초과·일시 실패 재시도다.
- 독립 내 문의 상세 모달, 새 문의 분리 동의, 티켓 관련 대화, 담당자 가져오기·처리·재이관, 기술 복구 UI, 구조화 가이드는 후속이다.
- DATA_TYPES와 WIRE_CONTRACT가 공통 계약 원본이다. REST·DB·브라우저 SSE는 백엔드, AI 입출력은 AI+백엔드, 화면 행동과 SSE 소비는 프론트가 관리하며 영향받는 담당자가 함께 검토한다.

## 11. 담당별 구현과 검증 상태

현재 코드의 구현 범위와 검증 단계를 구분한다. 1~10절은 전환 전 부서 기반 계약 기록이고, 현재 프론트는 12절의 hops-system-1만 사용한다. 문서·Mock·대역 검사를 실제 서비스 통합 완료로 표시하지 않는다.

### 백엔드 구현과 남은 작업

- 인증은 `/api/v2/auth/login`·`me`·`refresh`·`logout`을 제공한다. PostgreSQL 계정 조회, JWT, Redis 기반 Refresh 회전·재사용 검출·절대 만료와 쿠키 삭제, 허용 Origin 검사를 구현했다.
- 로그인 세션 종료 이벤트는 해당 회원의 열린 SSE를 닫는다. Access에 세션 ID가 없어 기기별 연결을 구분하지 않으며 이미 발급한 Access의 즉시 폐기를 뜻하지 않는다. 역할·시스템 권한 변경의 즉시 `auth.invalidated` 통지·세대 검증은 남아 있다.
- 부서 SSE는 DEPARTMENT_TICKETS의 대상 권한, heartbeat·종료 정리, 토큰 만료·전송 전 DB 재검사, commit 후 state.changed와 replay 불가 시 resync.required를 제공한다. 사례 구독과 문의·업무 이벤트 생산자는 미연결이다.
- 업무 테이블·Entity와 V1~V22 Flyway는 존재한다. 테이블·Repository 검사는 문의·티켓·처리·확인·승인 API의 서비스 저장·권한·경합 구현을 대신하지 않는다.
- CurrentUser.primarySystemIds, 시스템 목록(`GET /systems`), 문의 생성(`POST /inquiries`: 문의·첫 메시지·QUEUED 상담 실행·불변 입력·currentRun을 한 거래로 저장), 본인 문의 목록·상세·대화 조회, 상담 실행 조회(`GET /runs/{runId}`)와 RunSummary 투영, 수동 재분석(`POST /runs/{runId}/retries`), 변경 요청의 멱등 journal과 operations 결과 확인을 구현했다. 문의 생성은 AI 서버에 전달하지 않으며 commit 후 전달이 연결돼야 실행이 시작된다. 문의 상세는 문제가 없는 문의만 반환하며 Problem 투영은 남아 있다. journal의 REJECTED 기록과 만료 응답 tombstone 정리도 후속이다.
- PRIMARY 조회, 티켓 목록·담당자 상세·관리 제한 상세·관련 대화·저장된 처리/확인/사례 생성 상태 조회와 현재 권한 검사를 구현했다. 조회 쿼리는 agent 실행·결과 저장과 분리했다. SYSTEM_TICKETS·TICKET은 연결·권한 재검사·heartbeat와 REST 재동기화를 제공하며 업무 변경 이벤트 생산자는 후속이다.
- 메시지·정정 저장 명령, 처리·확인 응답·사례 생성/승인/색인, AI 실행 전달·결과 저장 연동과 실제 서비스·브라우저 통합 검증은 남아 있다.

인증·쿠키·Redis 실행 설정과 현재 응답은 [Spring 안내](../../backend/spring/hops/README.md)를 따른다. 신규 SSE는 12절대로 replay 없이 REST 재동기화한다. 1~10절의 replay 목표를 신규 필수 작업으로 옮기지 않는다. 다중 인스턴스·프록시·부하와 실제 브라우저의 인증·권한 회수 검증은 별도다.

### 프론트 구현과 검증 범위

- 로그인·현재 사용자·갱신·로그아웃, 시스템 담당/BIZ_ADMIN 메뉴, 문의·메시지·원문 정정과 이력, 문제별 시스템 선택·보완 연결·접수 결과를 구현했다.
- 담당 시스템 보드/목록·상세 모달과 처리·검증 탭, 관리 제한 상세·책임 시스템 정정, 문의자 해결 응답과 사례 검토·편집·승인·반영 확인/실패 재시도를 제공한다.
- DTO·REST/fetch SSE·Mock은 hops-system-1 하나다. `VITE_DATA_MODE`만 mock/live를 선택하며 계약 선택 설정·통신 실패 시 Mock 대체는 없다. `CommandReceipt`·원 요청 결과 확인·후속 GET을 사용하고 이전 `MutationResult`는 사용하지 않는다.
- 공개 진행·임시 답변과 저장 메시지를 구분하고, 구독·재접속·종료·권한 무효화 후 원본을 조회한다. 미전송 입력·미확인 원 요청을 보존하며 업무 변경 POST를 자동 반복하지 않는다.
- 현재 검사는 컴포넌트·Mock·실제 loopback HTTP/SSE 대역 범위다. 전환 전 부서 SSE의 실서버 검사 이력은 신규 시스템 계약의 통합 결과가 아니다. 현재 `springBackend.integration.test.ts`는 신규 서버·PRIMARY 개발 계정을 명시했을 때만 실행한다.

화면·가상 계정·예시·검사 명령은 [Frontend 안내](../../frontend/README.md), 실제 등록 경로는 [ROUTES](../../docs/frontend/ROUTES.md)를 따른다. 메모리 Mock의 토큰·확인·색인 결과는 실제 쿠키·DB·AI·검색 동작을 검증하지 않는다.

### AI 구현 대상

- 기술 하네스는 기한·호출 상한·일시 LLM 오류 재시도·사용량·도구 허용/취소 확인을 제공한다. RunLedger와 접수 서비스는 실행 ID·입력 digest·상태·종료 결과 및 중단 실행 UNKNOWN 복구를 처리한다.
- 실행 wire DTO 검증과 명시 활성화한 `/internal/v2`의 서비스 인증·저장된 run snapshot 조회, 백그라운드 실행기를 구현했다. HTTP 접수는 파이프라인 주입 시 202이며 기본 실행은 파이프라인 미주입으로 503이다. 취소·이벤트 SSE와 실제 모델·Supervisor·전문 역할·검색·색인·Spring 업무 연결은 미연결이다.
- 모델·임베딩·비용·구체 실행 설정값은 별도 선정·검증 대상이다. 내부 인증·RunLedger 검사만으로 실제 상담이나 지식 선순환 완료를 표시하지 않는다.

현재 실행 모드·환경·내부 API·DB 검사는 [AI 안내](../../ai/README.md)를 따른다.

### 공통 계약 후속

신규 필드·HTTP/SSE·AI wire·기본 저장 대응의 채택값은 [결정 대장](../QUESTIONS.md)에 있다. 이미 채택한 계약을 다시 미정으로 두지 않는다. 남은 작업은 해당 계약의 서버 구현·생산자/소비자 연결과 서비스별 검증이다. 전환 전 공용 JSON은 [보존 자료](../mocks/README.md)이며 현재 프론트의 실행 fixture가 아니다.

### 아직 검증하지 않은 항목

- 실제 Spring·DB·Python/LLM·React 전체 흐름과 자동 브라우저 E2E.
- 신규 시스템 권한·접근 회수, 업무 저장·동시 명령·미확인 저장 복구와 중복 방지.
- 실제 Curator·문의자 확인·사례 승인·같은 버전의 양쪽 색인/ACL·다음 문의 재검색.
- 공용 환경의 쿠키·CSRF·CORS·회전/로그아웃·SSE 회수 경합과 프록시·다중 인스턴스 부하.

## 12. 신규 계약 hops-system-1

상태: PRD 1.44 hops-system-1 목표 계약·API/SSE/오류 정의. 이 절만 `/api/v2`에 적용한다. 1~11절·기존 `/api`·실행 Mock의 구현 상태를 변경하지 않는다. 타입은 [신규 데이터 계약](DATA_TYPES.md#신규-계약-hops-system-1), 상태 전이는 [TARGET_CONTRACT](TARGET_CONTRACT.md)가 정본이다.

### 12.1 인증·명령·공통 응답

- 로그인/갱신 외 API는 Bearer Access Token을 사용한다. 토큰 수명·회전 정책은 기존 10절의 Access 15분·Refresh 최초 로그인부터 절대 7일·매 갱신 회전을 유지한다. refresh/logout은 HttpOnly cookie와 허용 Origin 검증을 사용한다. 운영 HTTPS에서 Secure, SameSite=Lax, Path=/api/v2/auth이며 로컬 개발 예외는 환경 설정으로만 분리한다.
- 허용 프론트 Origin을 정확히 나열하고 credentials를 허용한다. Authorization·Idempotency-Key·Last-Event-ID 요청 헤더와 Retry-After 응답 헤더를 CORS에 반영한다. 쿠키 사용 변경은 Origin 검증 실패 시 403이다. 토큰·비밀번호를 URL/로그에 넣지 않는다.
- auth 외 변경 요청은 JSON의 requestId와 같은 Idempotency-Key를 보낸다. 200은 업무 저장 또는 기존 결과가 확정된 CommandReceipt, 202는 PENDING/UNKNOWN receipt다. 200 SUCCEEDED는 해당 명령 저장만 뜻하며 AI 실행·해결·검색 공개 완료를 뜻하지 않는다. 4xx/5xx는 ApiError를 반환한다.
- 로그인·갱신은 200 LoginResponse, logout은 204다. GET은 200·데이터 타입을 직접 반환한다. 응답 envelope는 추가하지 않는다.
- 같은 사용자·프로필·requestId에 HTTP 메서드·정규화한 경로·본문을 묶어 결과를 보존한다. 동일 키 재확인은 버전 비교보다 먼저 수행하며 이미 권한이 회수됐다면 기존 비공개 결과도 반환하지 않는다. 같은 키에 다른 경로/본문은 409 IDEMPOTENCY_KEY_REUSED다.
- 명령 journal과 업무 변경은 같은 DB 저장 경계에서 기록하고 commit 뒤 후속 AI·색인·SSE 호출을 수행한다. 영속 이벤트 전달 큐는 MVP에 추가하지 않는다. 상세 재확인 결과는 최소 24시간 보존하고 이후에도 요청 키 사용 이력과 업무 유일 제약으로 재실행을 막는다. 기간 이후 조회는 410이며 새로운 키로 자동 재생성하지 않는다.
- 클라이언트 응답 대기는 10초, 자동 업무 재전송은 하지 않는다. 입력·원본 키·본문을 보존하고 결과 확인을 제공한다. 같은 키·동일 본문의 원 명령 재전송은 최초 요청 재확인이며 새 업무 의도가 아니다. 서버는 현재 권한·journal·업무 유일성을 검사하고 진행/미확인 작업을 중복 실행하지 않는다. 티켓 생성 복구는 별도의 work-item API를 사용한다.
- operations/by-request 조회의 404는 원 명령이 도착하지 않았거나 확인할 수 없음을 뜻한다. DB 미저장 증거로 표시하지 않는다. 티켓 재시도는 서버가 NOT_CREATED를 확인한 경우에만 허용한다.

### 12.2 REST 연결표

아래 상대 경로 앞에는 모두 `/api/v2`를 붙인다. 변경 응답의 resource는 명령으로 저장한 참조이며 후속 GET으로 최신 상태를 읽는다.

| 메서드·상대 경로 | 요청 | 성공 응답·resource | 권한 |
|---|---|---|---|
| POST /auth/login | LoginRequest | 200 LoginResponse | 비로그인 |
| POST /auth/refresh | 없음·cookie | 200 LoginResponse | 유효 Refresh |
| POST /auth/logout | 없음·cookie | 204 | 현재 세션; 반복 시에도 쿠키 삭제 |
| GET /auth/me | 없음 | CurrentUser | 로그인 |
| GET /systems | 없음 | SupportedSystem[] | 로그인 |
| GET /systems/{systemId}/primary | 없음 | PrimaryAssignment | 현재 담당자 또는 관리자 |
| GET /inquiries | page,size | Page<InquirySummary> | 본인 |
| POST /inquiries | InquiryCreateCommand | CommandReceipt · INQUIRY | 로그인 |
| GET /inquiries/{inquiryId} | 없음 | InquiryResult | 본인 |
| GET /inquiries/{inquiryId}/messages | size,beforeSequenceNo | MessagePage | 본인 |
| POST /inquiries/{inquiryId}/messages | MessageSendCommand | CommandReceipt · INQUIRY | 본인 |
| POST /inquiries/{inquiryId}/messages/{messageId}/revisions | MessageReviseCommand | CommandReceipt · INQUIRY | 본인 USER 메시지 |
| POST /inquiries/{inquiryId}/messages/{messageId}/problem-links | MessageProblemLinkCommand | CommandReceipt · INQUIRY | 본인·미연결 보완 메시지 |
| POST /inquiries/{inquiryId}/problems/{problemId}/system-confirmations | SystemConfirmCommand | CommandReceipt · INQUIRY | 본인·미확정 시스템 |
| GET /runs/{runId} | 없음 | RunSummary | 원 문의 소유자 |
| POST /runs/{runId}/retries | RunRetryCommand | CommandReceipt · RUN | 본인·현재 실패/시간 초과 실행 |
| GET /work-items/{workItemId} | 없음 | TicketCreationResult | 원 문의 소유자 |
| POST /work-items/{workItemId}/retries | CreationRetryCommand | CommandReceipt · WORK_ITEM | 본인·NOT_CREATED |
| GET /operations/{operationId} | 없음 | CommandReceipt | 원 요청자 |
| GET /operations/by-request/{requestId} | 없음 | CommandReceipt | 원 요청자 |
| GET /tickets | scope,systemId,state,page,size | Page<TicketSummary> | ASSIGNED_SYSTEMS 또는 ADMIN 범위 |
| GET /tickets/{ticketId} | 없음 | TicketDetail | 현재 책임 시스템 담당자 |
| GET /management/tickets/{ticketId} | 없음 | TicketManagementDetail | 업무 관리자 |
| GET /tickets/{ticketId}/related-messages | size,cursor | RelatedMessagePage | 현재 책임 시스템 담당자 |
| POST /tickets/{ticketId}/work-records | WorkRecordCommand | CommandReceipt · TICKET | 현재 책임 시스템 담당자 |
| POST /tickets/{ticketId}/processing-type-corrections | ProcessingTypeCorrectionCommand | CommandReceipt · TICKET | 현재 PRIMARY·조사/장애 유형만 |
| POST /tickets/{ticketId}/assignment-corrections | AssignmentCorrectionCommand | CommandReceipt · TICKET | 현재 담당자 또는 업무 관리자 |
| GET /resolution-requests/{resolutionRequestId} | 없음 | ResolutionRequest | 본인 또는 해당 티켓 담당자 |
| POST /resolution-requests/{resolutionRequestId}/responses | ResolutionResponse | CommandReceipt · RESOLUTION_REQUEST | 문의 소유자 |
| GET /case-generations/{generationId} | 없음 | CaseGeneration | 원 티켓의 현재 담당자 |
| POST /case-generations/{generationId}/retries | requestId | CommandReceipt · CASE_GENERATION | 현재 담당자·FAILED |
| GET /cases | systemId,state,page,size | Page<CaseSummary> | 현재 담당 시스템 |
| GET /cases/{caseId} | caseVersion 선택·현재 버전만 | CaseDetail | 현재 사례 시스템 담당자. 과거 버전 지정은404 |
| POST /cases/{caseId}/versions | CaseEditCommand | CommandReceipt · CASE | 현재 담당자·현재 DRAFT·최신 버전 일치. 승인 후 편집은409 INVALID_STATE |
| POST /cases/{caseId}/approvals | CaseApproveCommand | CommandReceipt · CASE | 현재 담당자·승인 전제 충족 |
| POST /cases/{caseId}/index-checks | IndexCheckCommand | CommandReceipt · CASE | 현재 담당자·현재 요청 확인. 유효한 원격 NOT_FOUND이면 보존한 같은 요청의 멱등 재접수 |
| POST /cases/{caseId}/index-retries | IndexRetryCommand | CommandReceipt · CASE | 현재 담당자·FAILED |

scope 기본은 ASSIGNED_SYSTEMS이며 ADMIN은 BIZ_ADMIN만 허용한다. systemId 생략은 현재 허용된 모든 시스템이며 타 시스템 지정은 403이다. state는 해당 객체 enum 한 개만 받는다. 문의·티켓·사례는 updatedAt DESC,id DESC로 정렬한다. 보드 건수는 현재 페이지 기준이며 전체 상태별 집계로 표현하지 않는다.

메시지 기본 size=30, 최신 페이지와 이전 페이지를 시간순으로 반환한다. beforeSequenceNo는 배타적 상한이다. 관련 메시지 size=30·최대100이며 불투명 cursor를 사용한다. 일반 Page size=20·최대100이다. 잘못된 필터·범위는 400이며 임의 보정하지 않는다. REST 재조회·화면 활성 중 폴링은 읽기이므로 업무 재시도가 아니다.

시스템 목록에는 PRIMARY 인물 정보를 넣지 않는다. 원문 전체·담당자 내부 자료를 포함한 TicketDetail을 관리자 DTO로 재사용하지 않는다. 문의자는 InquiryResult 안의 허용된 티켓 요약을 사용하며 담당자 상세 API를 호출하지 않는다.

### 12.3 오류·버전·재시도

| HTTP | code | 화면·서버 처리 |
|---|---|---|
| 400 | INVALID_INPUT | fieldErrors 표시·입력 보존; 자동 반복 없음 |
| 401 | UNAUTHENTICATED, ACCESS_TOKEN_EXPIRED, REFRESH_TOKEN_EXPIRED, REFRESH_TOKEN_REVOKED | 만료만 1회 refresh 후 읽기 재조회. 미확인 업무는 원 키로 확인 |
| 403 | ACCESS_DENIED, AUTH_CONTEXT_CHANGED, ORIGIN_DENIED | 내용·캐시 제거/마스킹, SSE 종료, 허용 화면 또는 재로그인 |
| 404 | NOT_FOUND | 찾을 수 없음을 안내. 비공개 존재 여부를 추가 반환하지 않음 |
| 409 | INQUIRY_VERSION_CONFLICT, MESSAGE_REVISION_CONFLICT, TICKET_REVISION_CONFLICT, RESOLUTION_REVISION_CONFLICT, CASE_VERSION_CONFLICT | 초안 보존·최신 GET; 자동 덮어쓰기 없음 |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 키의 다른 의도 거부 |
| 409 | RUN_NOT_RETRYABLE, WORK_ITEM_NOT_RETRYABLE, INVALID_STATE, APPROVAL_PRECONDITION_FAILED, INDEX_NOT_RETRYABLE | 최신 상태 확인; 허용 조작만 제공 |
| 410 | OPERATION_RESULT_EXPIRED | 상세 재확인 기간 만료. 원 업무 조회·재조정 없이 새 키 실행 금지 |
| 429 | RATE_LIMITED | Retry-After 초 단위; 해당 시간까지 버튼 비활성·입력 보존 |
| 503 | SERVICE_UNAVAILABLE, ASSIGNMENT_UNAVAILABLE, SEARCH_UNAVAILABLE, MODEL_UNAVAILABLE, AI_INVALID_OUTPUT, AI_RUN_STATE_UNKNOWN | 성공으로 표시하지 않음. 저장 여부 불명은 receipt UNKNOWN 또는 재확인. AI 실행 확인 불가는 공개 Run FAILED로 표시하고 새 run 재분석 제공 |
| 504 | DEADLINE_EXCEEDED | 분석 실패와 업무 저장 미확인을 구분 |

retryable은 수동 재시도 후보임을 뜻하며 상태·권한 검사를 생략하거나 자동 재전송하라는 뜻이 아니다. timeout·연결 오류는 프론트 로컬 오류이며 서버 DB 미저장 코드로 바꾸지 않는다. 검색 EMPTY와 ERROR, 모델 오류와 OUT_OF_SCOPE를 구분한다. 서비스 내부 오류는 에이전트 계약의 안전한 코드로 변환한다.

색인 index-checks는 현재 담당자·expectedCaseVersion·PENDING/RUNNING/UNKNOWN을 검증한 수동 명령이다. 서버는 source_metadata.indexRequests의 현재 요청을 읽고 같은 원격 jobId/attempt를 조회한다. 유효한 원격 404 NOT_FOUND이면 보존한 같은 요청 전체를 한 번 멱등 재접수할 수 있다. 404를 FAILED나 새 시도의 근거로 바꾸지 않는다. timeout·5xx·인증/권한·형식 오류·메타데이터 누락에는 재전송하지 않는다. 기존 원격 UNKNOWN이면 조회만 반영한다. index-retries는 FAILED에서만 같은 jobId의 다음 attempt를 먼저 저장한 뒤 전달한다. 공개 명령의 requestId와 원격 jobId/attempt는 서로 다른 식별자다. 동일 공개 명령 키 재전달은 기존 receipt를 반환하고 새로운 확인 클릭만 새 requestId를 만든다. 상세는 [에이전트 I1.1](../../contracts/agents/README.md#i11-승인-후-미전달-요청의-수동-복구)을 따른다. GET·보조 폴링은 색인을 시작하지 않는다.

티켓 생성 재시도는 현재 inquiryVersion·workItem 잠금·기존 티켓 유일성을 확인한 뒤 수행한다. 이미 STORED면 기존 참조를 반환한다. UNKNOWN/PENDING이면 409 WORK_ITEM_NOT_RETRYABLE이며 확인만 가능하다. NOT_CREATED라도 입력 버전이 달라졌으면 충돌로 반환하고 새 표준 요청 적용 후 같은 작업을 재시도한다.

### 12.4 SSE와 복구

GET `/api/v2/realtime/events?targetType={type}&targetId={uuid}`에 Bearer를 보내고 fetch stream을 읽는다. 대상은 INQUIRY, TICKET, SYSTEM_TICKETS, CASE다. INQUIRY는 소유자, TICKET/SYSTEM_TICKETS/CASE는 현재 담당자만 허용한다. 관리자는 SYSTEM_TICKETS의 메타데이터 변화만 허용하며 내부 진행/대화 payload는 받지 않는다.

| 이벤트 | JSON 필드 | 소비 동작 |
|---|---|---|
| agent.progress | eventId,inquiryId,inquiryVersion,runId,stepNo,role,displaySummary | 현재 버전·실행의 안전한 진행만 임시 표시 |
| answer.delta | eventId,inquiryId,inquiryVersion,runId,messageId,seq,text | 임시 조각; runId/messageId/seq 중복 제거 |
| message.stored | eventId,inquiryId,messageId,requestId nullable | 저장 메시지·정정 GET. USER 명령은 requestId, AI는 null |
| state.changed | eventId,targetType,targetId,resourceType,resourceId,revision | 해당 문의·업무·사례·목록 GET. revision은 단조 증가 알림 순번이며 도메인 버전과 다름 |
| auth.invalidated | eventId,code,message | 표시 중 보호 데이터 마스킹·연결 중지·권한/세션 재확인 |
| resync.required | eventId | 현재 대상 REST 재조회 후 새 연결 |

이 절의 JSON 필드들은 모두 필수이고 eventId·messageId는 UUID, stepNo/seq/revision은 양의 정수다. role은 SUPERVISOR/INQUIRY/KNOWLEDGE/TICKET/CURATOR다. role=CURATOR의 내부 진행을 직원 INQUIRY에 전달하지 않는다. SSE id와 JSON eventId는 동일하며 payload의 전송 대상은 서버가 고정한다.

업무 저장 commit 뒤만 stored/state 이벤트를 만든다. 이벤트 도착 순서는 HTTP 응답과 독립이다. 영속 이벤트 전달은 보장하지 않으므로 기존 REST 재조회·보조 폴링으로 저장 상태를 확인한다. 이벤트는 GET 무효화 신호이며 AI 답변 조각·알림만으로 티켓·해결·사례 공개를 확정하지 않는다.

첫 신규 프로필의 replay는 제공하지 않는다. Last-Event-ID가 오면 권한 확인 후 resync.required를 보내고 종료한다. 클라이언트는 REST 조회 성공 뒤 마지막 ID·임시 조각을 비우고 ID 없이 연결한다. 실패 시 복구 중 상태를 유지한다. 재접속 간격은 1/2/5/10/30초 상한이며 화면 이탈·권한 거부 시 중단한다. 15초 comment heartbeat는 상태를 변경하지 않는다.

담당 관계 변경 시 서버는 기존 구독의 전송 권한도 즉시 무효화한다. 메시지를 보내기 전에 현재 권한 세대를 검증하고 오래된 구독에 내부 payload를 보내지 않는다. 무효화 신호가 유실돼도 새 GET은 거부한다. 화면은 연결을 잃으면 보호 영역을 가리고 재조회 후 복원한다.

내 문의·업무/사례 목록은 활성 화면에서 5초 간격 보조 폴링을 사용한다. 채팅은 실행이 QUEUED/RUNNING일 때만 1초 간격 Run 조회를 병행하고 종료를 관찰하면 문의와 메시지를 재조회한다. 숨겨진 탭에서는 폴링을 멈추고 포커스 복귀 시 재조회한다. SSE 또는 폴링 어느 쪽도 신규 업무 명령을 만들지 않는다. 채팅의 접수·임시 답변·최종 저장과 동기화 순서는 12.8절을 따른다.

### 12.5 전환 확인

현재 프론트 요청은 hops-system-1이며 Spring의 공개 인증·부서 SSE도 /api/v2 경로를 사용한다. Spring의 시스템 권한 DTO·업무 API와 신규 SSE 대상 연결은 남아 있다. 미구현 응답을 빈 결과·Mock 성공으로 처리하지 않는다. 실제 배포 전 DTO·인증·저장·SSE·권한·AI 연결과 브라우저 CORS를 검증하며 프론트에 계약 선택 설정을 추가하지 않는다.

### 12.6 계약 범위와 spec 연결

| API 묶음 | 계약 자료 | spec |
|---|---|---|
| auth·시스템/PRIMARY | Login/CurrentUser/SupportedSystem, 401/403·권한 회수 | F001/F002 |
| 문의·메시지·정정·시스템 선택·problem-links | 명령/receipt·입력 버전·커서·보완 대상 | F003/F004/F008 |
| Run 조회/재분석·operation 결과 | RunSummary·CommandReceipt·키 재확인·실행 오류 | F002/F003/F004 |
| 작업 결과·티켓 생성 재시도 | TicketCreationResult·workItemId=problemId·미생성 선확인 | F005/F010 |
| 티켓·관리 상세·관련 메시지·수동 정정 | 권한별 DTO·티켓 버전·실제 PRIMARY·원문 구간 | F007/F009 |
| 처리 기록·해결 확인 | WorkRecord/ResolutionRequest·문제별 처리 주기 | F003/F006/F009 |
| 사례/초안/승인/반영 확인·재실행 | CaseDetail/Generation/Publication·같은 버전/attempt | F009/F011 |
| SSE·재접속·REST 복구 | 12.4 이벤트와 현재 권한 검사 | F002 및 업무별 spec |

보완 메시지의 대상이 불명확하면 pendingTargetMessageIds에 저장하고 기존 문제 선택 UI를 제공한다. problem-links는 저장된 원문을 바꾸지 않고 같은 문의의 기존 문제와 연결한다. 최신 문의 버전 검사 후 입력 버전을1 올리고 배정된 티켓에는 보완만 연결한다. 질문 횟수 증가·자동 새 티켓·자동 재배정은 없다. 이미 연결된 메시지의 임의 재연결은409 INVALID_STATE다.

프론트가 받는 오류/상태·조작은 이 표와 각 spec, 서비스 간 실행/색인/검색 권한은 에이전트 Markdown 계약을 정본으로 한다. 동일 규칙을 spec마다 복사하지 않는다.

### 12.7 병합 ERD와 외부 계약의 경계

REST 경로와 필터 state는 신규 wire enum을 유지한다. Spring이 [DATA11절](../../docs/DATA.md#11-완성-erd-대조-결과와-병합-기준)의 DB 상태와 version을 변환하며 프론트에 OPEN/WAITING_CONFIRM·DB version0을 노출하지 않는다. 현재 PRIMARY 조회는 systemId/primaryUser만 반환한다.

WorkRecordCommand의 FAILED_ATTEMPT는 진행 이력 저장이며 해결 확인·CASE 초안을 예약하지 않는다. confirmedCause/appliesToSystemVersion은 nullable 실제 입력이다. CaseEditCommand의 problemStatement는 새 버전의 문제 설명이며 원 기록/확인된 원인은 변경하지 않는다. 구체 필드·길이는 DATA_TYPES 신규 절을 따른다.

알림 테이블 신설은 REST/SSE 경로나 메뉴 신설을 뜻하지 않는다. 본 프로필은 기존 INQUIRY/TICKET/SYSTEM_TICKETS/CASE 구독과 현재 GET 결과로 화면을 갱신한다. 응답 본문·같은 요청 키의 만료 뒤 tombstone 및 410 동작은 이 절의 원 규칙을 유지한다.

처리 유형 정정은 현재 담당자만 INVESTIGATION↔INCIDENT 사이에서 수행한다. reason·허용 메시지 또는 직접 관측한 evidenceNote를 검증하고 ticket.version 증가와 WORK_TYPE_CHANGED 이벤트를 같은 거래로 저장한다. 상태/확인 요청은 바꾸지 않는다. 완료 티켓·다른 처리 유형·관리자 역할만 가진 사용자는409 INVALID_STATE 또는403이다.

### 12.8 채팅 송수신 완료 규칙

아래 연결 방식을 신규 프로필의 구현 기준으로 채택한다. 모델·역할 내부 구현과 관계없이 같은 입력·출력·저장 의미를 유지한다. AI의 본문 조합과 ID 검증은 [상담 답변 연결](../../contracts/agents/README.md#상담-답변과-저장-메시지-연결), 필드는 DATA_TYPES 신규 절과 에이전트 Markdown 계약이 정본이다.

1. **입력 저장:** 최초 `POST /inquiries`는 문의·첫 USER 메시지·inquiryVersion=1을 저장한다. 이후 메시지·정정은 expected 버전을 검사한 뒤 입력 버전을 1 올린다. USER 메시지 생성의 requestId는 최초 명령 ID로 보존한다. 같은 거래에서 새 상담 runId와 answerMessageId를 발급하고 불변 실행 입력·QUEUED·currentRun 연결·멱등 결과를 저장한다. 정정/새 입력은 이전 활성 run을 SUPERSEDED로 만든다. 기존 티켓과 질문 횟수는 유지한다.
2. **명령 응답:** 정상 저장은 200 SUCCEEDED CommandReceipt·resource=INQUIRY다. 이는 사용자 입력과 실행 접수 정보의 저장이며 답변 완료가 아니다. 답변을 기다리느라 REST 응답을 유지하지 않는다. 기존 202 PENDING/UNKNOWN·원 키 재확인 규칙은 그대로 적용한다. 같은 명령 재전송은 기존 저장 결과를 반환하고 새 run을 만들지 않는다.
3. **AI 실행:** Spring은 commit 후 이전 실행을 최선 노력 취소하고 `POST /internal/v2/runs`에 새 입력을 보낸다. 이어 내부 SSE를 연결하고 snapshot도 즉시 조회한다. QUEUED/RUNNING 동안 1초 간격 snapshot 조회를 병행해 SSE 접속 전에 끝난 실행도 확인한다. 이벤트와 snapshot의 같은 최종 결과는 한 번만 적용한다. 접수·조회 실패나 기한 초과를 안전한 공개 Run 오류로 남기며 영속 전달 큐를 추가하지 않는다.
4. **화면 진입:** 문의 ID를 얻으면 INQUIRY SSE 연결을 시작하고 InquiryResult와 최신 MessagePage를 조회한다. 요청이 겹쳐도 메시지는 id로 합친다. USER 임시 말풍선은 ChatMessage.requestId로 저장 메시지와 합치고, 서버 GET의 revision/content/sequenceNo를 사용한다. SSE 연결 이전의 저장 결과도 초기 GET에서 복구한다. 실행 중이면 공개 Run 조회를 병행한다.
5. **임시 답변:** 내부 delta의 messageId=input.answerMessageId를 검증하고 공개 answer.delta의 messageId로 그대로 전달한다. 내부 chunkSeq를 공개 seq로 사용하며 내부 이벤트 전체 순번과 혼용하지 않는다. 현재 inquiryVersion/runId/answerMessageId가 맞는 조각만 임시 말풍선 하나에 누적한다. 같은 seq·같은 text는 무시하고 다른 text 충돌·누락은 임시 본문을 확정하지 않는다. 앞뒤 순서가 바뀐 조각은 seq로 정렬해 연결한다. 이미 GET으로 저장된 같은 messageId가 있으면 늦은 조각은 무시한다.
6. **최종 저장:** 검증된 AiResult.answer와 질문·가이드 상태를 Spring이 같은 거래로 저장한 뒤 공개 Run SUCCEEDED를 반환한다. 최종 AI 메시지는 run의 answerMessageId 한 건이다. questions[].text·guide.messageText는 answer 안에 포함되며 별도 말풍선이나 추가 저장 메시지로 만들지 않는다. questionCount 증가와 GUIDE 확인 요청 생성은 이 commit 때만 수행한다. 답변 저장·질문 규칙과 기존 문제별 티켓 저장 결과는 별도로 확정한다.
7. **완료 표시:** message.stored/state.changed 또는 Run 종료를 관찰하면 InquiryResult와 최신 MessagePage를 모두 재조회한다. 최종 ChatMessage.id와 임시 messageId가 같으면 content를 서버 본문으로 통째로 교체하고 임시 상태를 해제한다. 조각을 다시 붙이지 않는다. 이 GET 성공 전에는 답변을 저장 완료로 표시하지 않는다. 진행 중 currentRun이 바뀌어도 먼저 같은 재조회를 수행해 직전에 저장된 답변을 놓치지 않는다. 종료한 run의 폴링은 멈추고 재조회한 currentRun이 QUEUED/RUNNING이면 그 run을 조회한다.
8. **실패·다음 입력:** FAILED/TIMED_OUT/SUPERSEDED 실행의 미저장 임시 답변은 제거하고 저장된 대화·입력을 유지한다. 실패는 안전한 Run 오류와 허용된 재분석 UI로 표시하며 가짜 AI 저장 메시지나 해결 상태를 만들지 않는다. 재분석은 새 runId·새 answerMessageId다. 새 메시지·정정 중 이전 조각은 무시하고 기존 저장 메시지는 보존한다. 시스템 선택과 GUIDE 미해결의 재분석도 같은 접수·답변 연결 방식을 사용하며 질문 수를 초기화하지 않는다.

메시지 GET은 신규 프로필에서 message_status=COMPLETED인 저장 행만 반환한다. 예약 answerMessageId만으로 빈 메시지를 조회 결과에 넣지 않는다. answer.delta가 한 건도 없어도 SUCCEEDED 뒤 GET으로 최종 답변을 표시하며, SSE 유실·재접속·포커스 복귀 시 동일한 조회/병합 규칙을 적용한다. Run 종료 조회 후 최종 메시지 조회가 실패하면 동기화 실패와 재조회 조작을 표시한다. 사용자의 새 입력이나 AI 재분석을 자동 생성하지 않는다.

권한 있는 기존 Run의 조회는 실패 상태여도 HTTP200 RunSummary다. 실패의 error는 ApiError 형식을 사용하고 requestId에는 해당 run 접수를 만든 공개 명령 ID를 넣는다. 내부 INVALID_OUTPUT은 FAILED/AI_INVALID_OUTPUT, MODEL_UNAVAILABLE·SEARCH_UNAVAILABLE은 FAILED/동명 코드, TOOL_UNAVAILABLE·BUDGET_EXCEEDED는 FAILED/SERVICE_UNAVAILABLE, DEADLINE_EXCEEDED는 TIMED_OUT/DEADLINE_EXCEEDED로 변환한다. 실행 확인 불가는 FAILED/AI_RUN_STATE_UNKNOWN이다. 이들 오류의 retryable=true는 최신 FAILED/TIMED_OUT run에 수동 RunRetryCommand를 제공한다는 뜻이며 업무 명령 자동 재전송은 아니다. AUTH_CONTEXT_INVALID는 FAILED/AUTH_CONTEXT_CHANGED·retryable=false이며 먼저 현재 권한을 확인한다. 이전 입력으로 대체된 run은 SUPERSEDED·error=null이며 재분석 대상이 아니다. 원시 오류·내부 응답 본문을 그대로 사용자에게 반환하지 않는다.
