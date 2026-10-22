# wire JSON·REST·Socket.IO·AI 계약

상태: `hops-mock-1` Mock 개발용 초안. 경로·타입의 기계 판독 목록은 [operations.json](operations.json), 전체 필드는 [DATA_TYPES.md](DATA_TYPES.md)가 원본에서 생성된다.

## 1. 공통 표현

- JSON 필드명은 camelCase. 부서 code·상태 code를 소문자/한글로 임의 변환하지 않는다.
- 모든 객체 키는 필수이며 nullable일 때만 null 허용. 빈 배열은 []. 예: 보완 저장 ACK의 runId는 null이며 생략하지 않는다. 이는 기존 API의 생략 가능성을 이번 Mock에서 명시적으로 좁힌 제안이다.
- UUID는 소문자 하이픈 문자열, 시각은 UTC Z 문자열이다. 배열·객체의 순서를 인덱스 ID로 쓰지 않는다.
- 버전·revision·순번은 1부터, page는 0부터. 질문 횟수는 0~3. 모든 정수는 필드별 최소값부터 2,147,483,647 이하이며 문자열·boolean을 숫자로 바꾸지 않는다.
- 사용자 메시지는 1~4,000 Unicode 코드 포인트. JS `[...content].length`, Java `codePointCount`, Python `len`을 사용한다. 단독 surrogate는 거부한다. 원문을 trim/자동 절단해 저장하지 않는다.
- 공백만 있는지 판단하는 문자 집합은 UserContent.pattern에 명시한다. 언어별 `trim/strip/\\s`의 서로 다른 동작에 의존하지 않는다. 서버도 같은 검사를 한다.
- category 6개와 기존 문의·처리 상태 코드는 Schema enum을 따른다. 문제·배정·실행·오류 코드는 운영 미정이므로 string으로 받는다. Mock 대표값만 mock-profile.json에 둔다. 미등록 값은 알 수 없는 상태로 표시하고 성공으로 추정하지 않는다.

## 2. REST와 페이지

모든 REST 오류는 `ApiErrorBody`다. 400 입력, 401 인증, 403 권한, 404 없음, 409 버전/멱등 충돌, 429 제한, 503 일시 장애를 구분한다. `retryable`은 무조건 자동 재시도를 하라는 뜻이 아니다.

| 작업 | 요청 | 성공 |
|---|---|---|
| POST /api/auth/login | LoginRequest | 200 LoginResponse |
| GET /api/auth/me | Bearer 토큰 | 200 CurrentUser |
| POST /api/auth/logout | Bearer 토큰, 본문 없음 | 204 본문 없음 |
| GET /api/departments | 인증 | 200 Department[] |
| GET /api/inquiries | page, size 쿼리 | 200 InquiryPage |
| GET /api/inquiries/{id} | 문의 UUID | 200 InquiryResult |
| GET /api/inquiries/{id}/messages | afterSequenceNo(미지정=0), size | 200 MessagePage |
| GET /api/runs/{id} | 실행 UUID | 200 RunSummary |
| GET /api/tickets | page, size, processingStatus(선택) | 200 TicketPage |
| GET /api/tickets/{id} | 티켓 UUID | 200 TicketDetail |

page 기본 0, size 기본 20/최대 100은 Mock 제안값이다. 음수·범위 초과·소수·알 수 없는 필터는 400. URL 쿼리는 문자열이므로 HTTP 어댑터에서 정해진 십진 정수 형식을 확인한 뒤 숫자로 변환한다. query 타입의 null 필터는 URL에서 생략하며 문자열 "null"을 보내지 않는다.

문의·티켓 목록은 createdAt 내림차순, 같은 시각이면 id 내림차순이다. 티켓 필터·페이지는 현재 사용자 권한 범위를 먼저 제한한 뒤 계산한다. 전체 목록을 프론트에서 잘라 페이지를 만든 것으로 실제 API 검증을 대체하지 않는다.

메시지는 sequenceNo 오름차순이다. afterSequenceNo보다 큰 항목을 size개 반환하고 다음 항목이 있으면 마지막 sequenceNo를 nextAfterSequenceNo로, 끝이면 null을 보낸다. 정정은 sequenceNo를 바꾸지 않는다. 페이지 간 수정·신규 입력이 있으면 재조회하고 id/revision으로 중복 제거한다. 데이터 스냅샷 고정 기능은 이번 제안에 포함하지 않는다.

## 3. 인증·권한

Mock은 메모리의 가짜 토큰을 계정 fixture와 연결한다. 토큰 수명 1,800초는 테스트 시계용이며 실제 JWT 서명·저장·갱신·폐기를 구현한 것이 아니다. 실서비스 인증 어댑터와 혼용하지 않는다. 실제 Socket.IO auth.token과 REST Bearer는 API의 계약 후보를 따른다.

- 문의/전체 메시지/실행 조회는 소유자만 허용한다. 업무 관리자 역할만으로 문의 전체 대화 조회를 허용하지 않는다.
- 티켓 목록·상세는 현재 처리 부서 구성원 또는 BIZ_ADMIN만 허용한다. AGENT_OPS_ADMIN만 가진 계정에는 업무 조회 권한이 생기지 않는다.
- 일반 부서원에게는 자기 현재 처리 부서 티켓만 반환한다. BIZ_ADMIN은 업무 조회 범위의 티켓을 반환한다. 다른 부서 ID나 userId를 요청에 넣어 권한을 넓히지 못한다.
- 부서 티켓 메뉴는 CurrentUser.department.isRoutable, 추가 역할 메뉴는 roles로 표시한다. 최종 권한은 서버가 판단한다.
- 같은 사용자 재로그인 때만 초안·미확인 요청을 복구한다. 다른 사용자 로그인·로그아웃 시 이전 사용자 조회 캐시·초안·대기 명령·구독을 정리한다. 로그아웃 요청 실패도 브라우저 정리는 수행한다.

## 4. 명령·ACK·멱등성

`inquiry.create`, `message.send`, `message.revise`는 operations.json의 명령 타입을 쓰고 **CommandAck**로 응답한다. ok=true는 Spring의 해당 메시지 저장 확인이다. ACK의 runId는 분석 실행 접수 식별자이며 티켓 저장 성공을 뜻하지 않는다.

구독 request/release는 **SubscriptionAck**다. 성공 응답에는 target이 있으며 inquiryId/messageId 같은 저장 결과 필드는 없다. 다른 variant의 필드를 섞으면 스키마 검사가 실패한다. error.requestId는 바깥 requestId와 같아야 한다.

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

AI 결과의 임시 참조·근거·부서·버전은 [언어 매핑](LANGUAGE_MAPPING.md)의 의미 검사도 통과해야 한다. JSON Schema의 모양 검사만으로 참조 유효성·권한·실제 저장을 보장하지 않는다.
