# wire JSON·REST·SSE·AI 계약

> **PRD 1.44 전환 안내:** 아래 타입·REST/SSE와 enum은 기존 부서 기반 구현·Mock의 호환 프로필이다. 시스템별 주담당자, 문제별 확인, Curator·사례 공개와 두 저장소 반영의 새 필드·상태·경로는 12절 hops-system-1 리뷰 계약안에 정의하며 팀 승인·구현은 별도다. 새 제품 정책은 [PRD](../../docs/PRD.md)와 [API 설계](../../docs/API.md)를 따르며, 이전 단일 MVP·CS 배정·무응답 종료는 새 목표가 아니다. 실제 코드와 계약은 전환 전까지 서로 구분해 유지한다.

상태: 2026-10-27 개발 기준 확정 / 작성자 외 팀원 리뷰·실제 서비스 구현 및 통합 검증 별도. 현재 Mock·임시 Live Adapter와 확정 계약의 차이는 11절에 명시한다. 전체 필드와 타입은 [DATA_TYPES.md](DATA_TYPES.md), 작업별 입출력은 이 문서의 연결표를 따른다. 언어별 코드 생성은 제공하지 않는다.

## PRD 1.44 목표 전송 원칙

React→Spring 업무 변경·조회는 REST, 허용된 진행과 상태 알림은 SSE를 사용한다. Spring이 사용자·시스템·담당 관계를 검사하고 DB 저장·배정·확인·승인을 확정한다. AI는 제안하며 이벤트 수신이나 모델 출력은 업무 성공이 아니다.

- 문제당 질문 최대 5회, 시스템 미확인 대기와 선택, 문제별 저장 성공·실패·미확인을 전달해야 한다.
- 티켓 재시도 전에 동일 문제의 저장 결과와 문의 버전을 확인한다. 미확인이면 새로 생성하지 않는다.
- 처리·기능 구현 완료, 문의자 확인, 사례 초안·승인과 두 저장소별 반영/공개 상태를 구분한다.
- 사용자·담당 시스템별 조회/변경과 SSE 접근은 현재 권한으로 검사하고 재접속 후 저장 상태를 재조회한다.
- API 경로·필드·상태·구독·복구의 구체 제안은 12절에서 검토한다. 1~11절의 기존 개발 프로필을 자동 전환하지 않는다.

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

정책을 확정한 것과 실제 구현·테스트 통과를 구분한다. 이 문서 수정은 런타임 코드·DB·인프라를 변경하지 않는다.

### 백엔드 구현과 남은 작업

- 구현: PostgreSQL 계정·부서 마이그레이션과 개발 seed, JWT 로그인 `POST /api/auth/login`, Bearer 내 정보 `GET /api/auth/me`, 부서 SSE `GET /api/realtime/events`와 대상 권한 검사.
- 부서 SSE는 comment heartbeat, timeout·완료·오류 정리, 토큰 만료 종료와 전송 전 DB 권한 재검사, 커밋 후 `state.changed` 전달 기반을 제공한다. `Last-Event-ID` 요청은 `resync.required`를 보낸 뒤 종료한다. 이벤트 보존/replay는 제공하지 않는다.
- 문의·개별 티켓 SSE는 권한 저장소 연결 전 503이다. 문의·티켓 조회/변경, 분석 재시도, Refresh·서버 로그아웃 API와 업무/AI 이벤트 생산자 연결은 미완료다. `auth.invalidated`의 서버 발행과 권한 변경 즉시 통지도 남아 있다.

남은 구현·운영 검증:

- Access 15분·Refresh 최대 7일, 회전·재사용 검출·로그아웃·권한 변경 시 기존 인증 무효화, HttpOnly 쿠키·CSRF·CORS와 안전한 검증값 저장.
- 문의 생성·메시지 전송·정정 REST, `Idempotency-Key`, 현재 소유자·부서·역할·실행 권한 검사, 메시지·목록·재시도 계약.
- inquiry.version, 작업과 티켓 FK·유일성, 최소 24시간 멱등 결과, 부분 실패·원자 저장·정정 이력·오래된 실행 차단과 마이그레이션.
- 문의·개별 티켓 SSE의 소유권 연결, 실제 업무 저장 트랜잭션의 이벤트 발행, replay, 권한 변경 즉시 통지와 회수 경합 처리.
- 정확한 이벤트 보존소와 기간, 다중 Spring 인스턴스 전달·순서·중복, 프록시 buffering/timeout과 최대 연결 수는 구현·부하 검증으로 확정한다.

### 프론트 구현과 검증 범위

- Socket.IO 의존성·환경 설정·adapter와 명령/구독 타입을 제거하고 문의 생성·메시지 전송·정정을 REST로 전환했다. 결과 미확인·초안 보존·동일 멱등 키 재확인을 유지하며 실제 Spring DTO·업무 연결은 별도 검증한다.
- Bearer 헤더와 `Last-Event-ID`를 지원하는 fetch 기반 SSE parser, 대상별 연결 참조 수, AbortSignal 종료, heartbeat 무시, 재연결·`resync.required`·REST 복구를 구현했다. 초기 REST 조회 실패가 정상 SSE를 끊지 않도록 처리했다.
- eventId와 `(runId,messageId,seq)` 중복 제거, 임시 답변과 저장 메시지 병합, `auth.invalidated` 처리, 429 대기 안내와 QUEUED/SUPERSEDED 표시를 구현했다. Mock·화면·HTTP/SSE 대역 서버 테스트로 검증하며 서버 생산자 구현 완료와 구분한다.
- 실제 Spring·PostgreSQL 연결 검사에서 로그인 성공/실패, Bearer 내 정보와 프론트 세션 반영, 현재 부서 SSE 연결/해제, 미구현 티켓 REST의 404에도 SSE 유지, `resync.required`의 id/eventId와 EOF, 타 부서 403·미구현 문의 SSE 503을 확인했다. 브라우저에서는 로그인·사용자/부서 표시와 티켓 조회 실패 중 SSE 유지를 확인했다. 실행 방법은 frontend/README와 `springBackend.integration.test.ts`를 따른다.
- 부서·관리자 티켓의 읽기 전용 목록·필터·페이지·상세·요약·AI 진단·배정 이력과 오류/권한 화면은 Mock·대역 테스트 범위다. 실제 티켓 데이터 조회와 변경 이벤트 후 원본 갱신은 백엔드 조회 API·생산자 연결 후 통합 검증해야 한다.
- 현재 Mock Access 15분·메모리 Refresh 7일은 실제 15분·회전 쿠키·CSRF·폐기를 검증하지 않는다. 실제 서버의 재동기화 알림 수신 검증은 미구현 업무 REST를 포함한 복구 완료 검증을 뜻하지 않는다.

### AI 구현 대상

- 단일·멀티 공통 60초·최대 8시도(재시도 포함)·호출당 일시 장애 자동 재시도 최대 2회·호출당 1,500토큰 상한과 사용량·비용 기록. SDK 자체 재시도와 도구 대기를 전체 예산에 포함한다.
- inquiryVersion/runId와 기존 문제·작업·성공 티켓을 유지하고 공개 진행·답변 조각·최종 결과를 분리하며 실패·출력 잘림·스키마를 검증한다.
- 유효 저장 단계 재사용과 무결과 입력 재분석을 구분한다. 실제 모델·SDK·금액 상한, Spring↔Python 운영 경로·서비스 인증·재시작 복원은 담당자가 선정·검증한다.

### 공통 계약 후속

- MutationResult는 DATA_TYPES의 프론트 연동 프로필로 구현했다. 백엔드는 동일 필드와 HTTP 응답으로 저장 API/DTO를 구현해야 한다. ResyncRequiredEvent는 develop에 확정된 형식을 사용하며 스냅샷 커서·replay는 별도 구현 대상이다.
- 공용 Mock fixture와 MVP 기능 스펙을 REST 경로·HTTP 결과·SSE 대상 연결로 전환했다. 후속 분리 동의는 API 문서의 REST 후보 경로를 따르되 상세 성공 응답·후보 전달·저장 모델은 별도 설계한다.

### 아직 검증하지 않은 항목

- 실제 Spring·DB·Python/LLM·React SSE 전체 통합과 자동 브라우저 E2E.
- Refresh 쿠키·CSRF·회전 경합/재사용·로그아웃/부서 변경 즉시 차단, SSE 통지 유실 시 권한 방어. 개발용 로그인·내 정보·부서 SSE의 브라우저 CORS 연결 확인은 운영 CORS/CSRF 검증을 대신하지 않는다.
- 프록시 buffering·timeout, replay 보존 구간, 연결 해제·재개, 다중 인스턴스 이벤트 순서·중복과 부하 한계.
- 문의·메시지·티켓 업무 DDL·마이그레이션·트랜잭션 경합·24시간 이후 중복 방지·부분 성공/재시도·최신 조회 동시성. 계정·부서 마이그레이션 실행과 구분한다.
- 실제 모델 예산·지연 결과 차단·단계 재사용·정확도·비용·실측 SLO와 새 정책을 포함한 회귀 테스트.

문서 변경이나 기존 Mock·어댑터 테스트 통과를 위 항목의 완료로 보고하지 않는다.

## 12. 신규 계약 hops-system-1

상태: PRD 1.44 팀 리뷰용 API·SSE 계약안. 이 절만 `/api/v2`에 적용한다. 1~11절·기존 `/api`·실행 Mock의 구현 상태를 변경하지 않는다. 타입은 [신규 데이터 계약](DATA_TYPES.md#신규-계약-hops-system-1), 상태 전이는 [TARGET_CONTRACT](TARGET_CONTRACT.md)가 정본이다.

### 12.1 인증·명령·공통 응답

- 로그인/갱신 외 API는 Bearer Access Token을 사용한다. 토큰 수명·회전 정책은 기존 10절의 Access 15분·Refresh 최초 로그인부터 절대 7일·매 갱신 회전을 유지하는 제안이다. refresh/logout은 HttpOnly cookie와 허용 Origin 검증을 사용한다. 운영 HTTPS에서 Secure, SameSite=Lax, Path=/api/v2/auth이며 로컬 개발 예외는 환경 설정으로만 분리한다.
- 허용 프론트 Origin을 정확히 나열하고 credentials를 허용한다. Authorization·Idempotency-Key·Last-Event-ID 요청 헤더와 Retry-After 응답 헤더를 CORS에 반영한다. 쿠키 사용 변경은 Origin 검증 실패 시 403이다. 토큰·비밀번호를 URL/로그에 넣지 않는다.
- auth 외 변경 요청은 JSON의 requestId와 같은 Idempotency-Key를 보낸다. 200은 업무 저장 또는 기존 결과가 확정된 CommandReceipt, 202는 PENDING/UNKNOWN receipt다. 200 SUCCEEDED는 해당 명령 저장만 뜻하며 AI 실행·해결·검색 공개 완료를 뜻하지 않는다. 4xx/5xx는 ApiError를 반환한다.
- 로그인·갱신은 200 LoginResponse, logout은 204다. GET은 200·데이터 타입을 직접 반환한다. 응답 envelope는 추가하지 않는다.
- 같은 사용자·프로필·requestId에 HTTP 메서드·정규화한 경로·본문을 묶어 결과를 보존한다. 동일 키 재확인은 버전 비교보다 먼저 수행하며 이미 권한이 회수됐다면 기존 비공개 결과도 반환하지 않는다. 같은 키에 다른 경로/본문은 409 IDEMPOTENCY_KEY_REUSED다.
- 명령 journal과 업무 변경·outbox는 같은 DB 저장 경계에서 기록한다. 상세 재확인 결과는 최소 24시간 보존하고 이후에도 요청 키 사용 이력과 업무 유일 제약으로 재실행을 막는다. 기간 이후 조회는 410이며 새로운 키로 자동 재생성하지 않는다.
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
| POST /tickets/{ticketId}/assignment-corrections | AssignmentCorrectionCommand | CommandReceipt · TICKET | 현재 담당자 또는 업무 관리자 |
| GET /resolution-requests/{resolutionRequestId} | 없음 | ResolutionRequest | 본인 또는 해당 티켓 담당자 |
| POST /resolution-requests/{resolutionRequestId}/responses | ResolutionResponse | CommandReceipt · RESOLUTION_REQUEST | 문의 소유자 |
| GET /case-generations/{generationId} | 없음 | CaseGeneration | 원 티켓의 현재 담당자 |
| POST /case-generations/{generationId}/retries | requestId | CommandReceipt · CASE_GENERATION | 현재 담당자·FAILED |
| GET /cases | systemId,state,page,size | Page<CaseSummary> | 현재 담당 시스템 |
| GET /cases/{caseId} | caseVersion 선택 | CaseDetail | 현재 사례 시스템 담당자 |
| POST /cases/{caseId}/versions | CaseEditCommand | CommandReceipt · CASE | 현재 담당자·최신 버전 일치 |
| POST /cases/{caseId}/approvals | CaseApproveCommand | CommandReceipt · CASE | 현재 담당자·승인 전제 충족 |
| POST /cases/{caseId}/index-checks | IndexCheckCommand | CommandReceipt · CASE | 현재 담당자·결과 재확인 |
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
| 503 | SERVICE_UNAVAILABLE, ASSIGNMENT_UNAVAILABLE, SEARCH_UNAVAILABLE, MODEL_UNAVAILABLE | 성공으로 표시하지 않음. 저장 여부 불명은 receipt UNKNOWN 또는 재확인 |
| 504 | DEADLINE_EXCEEDED | 분석 실패와 업무 저장 미확인을 구분 |

retryable은 수동 재시도 후보임을 뜻하며 상태·권한 검사를 생략하거나 자동 재전송하라는 뜻이 아니다. timeout·연결 오류는 프론트 로컬 오류이며 서버 DB 미저장 코드로 바꾸지 않는다. 검색 EMPTY와 ERROR, 모델 오류와 OUT_OF_SCOPE를 구분한다. 서비스 내부 오류는 에이전트 계약의 안전한 코드로 변환한다.

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

업무 저장·outbox commit 뒤만 stored/state 이벤트를 만든다. 이벤트 도착 순서는 HTTP 응답과 독립이다. 이벤트는 GET 무효화 신호이며 AI 답변 조각·알림만으로 티켓·해결·사례 공개를 확정하지 않는다.

첫 신규 프로필의 replay는 제공하지 않는 안이다. Last-Event-ID가 오면 권한 확인 후 resync.required를 보내고 종료한다. 클라이언트는 REST 조회 성공 뒤 마지막 ID·임시 조각을 비우고 ID 없이 연결한다. 실패 시 복구 중 상태를 유지한다. 재접속 간격은 1/2/5/10/30초 상한이며 화면 이탈·권한 거부 시 중단한다. 15초 comment heartbeat는 상태를 변경하지 않는다.

담당 관계 변경 시 서버는 기존 구독의 전송 권한도 즉시 무효화한다. 메시지를 보내기 전에 현재 권한 세대를 검증하고 오래된 구독에 내부 payload를 보내지 않는다. 무효화 신호가 유실돼도 새 GET은 거부한다. 화면은 연결을 잃으면 보호 영역을 가리고 재조회 후 복원한다.

내 문의·업무/사례 목록은 활성 화면에서 5초 간격 보조 폴링을 사용한다. 채팅은 실행이 QUEUED/RUNNING일 때만 1초 간격 Run 조회를 병행한다. 숨겨진 탭에서는 폴링을 멈추고 포커스 복귀 시 재조회한다. SSE 또는 폴링 어느 쪽도 신규 업무 명령을 만들지 않는다.

### 12.5 전환 확인

새 경로·응답은 계약안이다. 현행 프론트 요청과 Spring의 로그인/부서 SSE는 기존 프로필을 따른다. 신규 경로가 등록되지 않은 환경을 빈 결과·Mock 성공으로 처리하지 않는다. 새 DTO·인증·저장·SSE·권한·AI 연결과 브라우저 CORS를 확인한 뒤 배포 설정에서 신규 프로필을 선택한다.
