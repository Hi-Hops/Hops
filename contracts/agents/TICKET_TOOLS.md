# Ticket 생성·조회 도구 REST 계약

Ticket은 MCP 없이 Python 도구 어댑터로 Spring 내부 REST를 호출해 실제 생성·배정 결과를 확인한다. Spring은 인증·현재 권한·버전·지원 시스템·주담당자·저장을 검증하고 확정한다. 모델 출력이나 AI 실행 SUCCEEDED를 티켓 저장 성공으로 해석하지 않는다.

## 구현 상태

Spring API·전달 journal과 Python REST 어댑터·역할별 업무 도구 허용 목록·제한된 생성/확인 루프·Supervisor 결과 투영을 구현했다. real 모드는 기본적으로 ExecutingTicketAgent를 사용한다. 공개 work-item API·문의 상세의 Problem 투영·SSE 연결과 전체 브라우저 E2E는 별도다.

Python은 `HOPS_AI_SPRING_BASE_URL`과 `SPRING_SERVICE_TOKEN`을 사용한다. `HOPS_AI_TICKET_TOOLS_ENABLED=true`가 기본이며 real 모드에서 주소·인증이 없으면 시작을 거부한다. 명시적으로 false를 설정한 경우만 이전 ModelTicketAgent의 제안 전용 실행을 사용하고 저장 성공을 표시하지 않는다. MCP 서버는 필요하지 않다.

## 인증

- 고정된 Spring origin의 내부 경로만 호출한다. 모델은 origin·토큰·runId를 지정하지 않는다. 런타임이 Spring 발급 runId를 고정하고 검증한 제안만 전달한다.
- AI→Spring은 `SPRING_SERVICE_TOKEN` Bearer를 사용한다. 32바이트 이상 난수를 padding 없는 base64url로 인코딩한다. 비어 있으면 내부 요청을 모두 401로 거부하고, 설정한 값의 형식이 잘못되면 Spring 시작을 거부한다.
- 사용자 JWT·쿠키 및 반대 방향 `AI_SERVICE_TOKEN`과 분리한다. 내부 경로는 별도 인증 chain을 사용하며 공용 환경은 서비스 네트워크/TLS로 제한한다. 브라우저에는 토큰을 전달하지 않는다.
- Spring은 저장된 실행 입력으로 문의자·policyRevision·접근 범위를 다시 확인한다. body에 사용자/담당자/티켓 ID를 지정하는 필드는 없다.

## API

공통 prefix: `/internal/v2/runs/{runId}/ticket-creations`

- `POST {prefix}`: `{inquiryVersion, problem: ProblemProposal, proposal: TicketProposal, messageLinks}`. 각 제안 필드는 [에이전트 계약](README.md)의 wire 정의를 따른다. 표준 요청의 사실·추정·미확인·상세가 원 문제와 같아야 한다. 서버 확인 없는 CONFIRMED 수용 조건·허위 출처·권한 밖 지식을 거부한다.
- `GET {prefix}/{problemRef}`: 기존 결과만 확인하며 생성을 시작하지 않는다. 종료/과거 실행도 현재 접근 권한이 유효하면 조회 가능하다.
- `POST {prefix}/{problemRef}/retries`: `{inquiryVersion, expectedAttempt}`. 최초 보존한 전체 제안을 사용하고 새 내용을 받지 않는다.

problemRef는 실행 입력의 기존 문제 UUID 또는 `new:[A-Za-z0-9_-]+`다(최대 100자). 신규 UUID는 Spring이 발급한다. `(runId, problemRef)`로 전달을 식별하며 같은 키의 다른 본문은 409 IDEMPOTENCY_KEY_REUSED다. 서버 발급 후 workItemId=problemId는 재실행에도 유지한다. 새 실행에서는 입력의 기존 문제 UUID를 사용하며 이전 실행의 new: 참조로 새 작업을 만들지 않는다.

messageLinks는 이 요청이 출처로 사용한 현재 입력 메시지마다 `{messageId, revision, spans:[{start,end}]}`를 보낸다. spans는 Unicode 코드 포인트의 반열린 구간이며 정렬·비중복·실제 원문 길이 안이어야 한다. 생략하거나 메시지 전체를 서버가 임의 공개하지 않는다. Spring은 허용된 메시지·차수·구간만 문제별 연결에 추가하고 기존 연결·과거 차수를 보존한다. 담당자 조회는 기존 구간 투영 정책을 그대로 사용한다. 과거 표준 요청의 생략된 원문 출처는 기존 연결을 사용하며 새 구간을 만들지 않는다.

신규 생성은 현재 문의 버전·currentRun·INQUIRY_ANALYSIS·QUEUED/RUNNING·expiresAt를 확인한다. Python 접수 직후 도구 요청이 Spring의 RUNNING 기록보다 먼저 올 수 있어 QUEUED도 허용한다. 이미 보존한 동일 요청의 재전송은 쓰기 없이 기존 결과를 반환한다. 문의 행 잠금과 문제당 티켓 유일 제약으로 동시 요청을 직렬화한다.

## 결과

정상 응답 HTTP 200에는 다음 키가 모두 있다.

```text
contractVersion: hops-system-1
runId: UUID
problemRef: 원 요청 참조
workItemId: UUID (= problemId)
problemId: UUID
inquiryVersion: 생성 작업 기준 버전
outcome: STORED | NOT_CREATED | PENDING | UNKNOWN
ticketId: UUID | null
responsibleSystemId: UUID | null
assigneeId: UUID | null
attempt: 양의 정수
maxAttempts: 1..3
retryable: boolean
errorCode: string | null
updatedAt: UTC timestamp
```

- STORED는 티켓·현재 배정 이력의 시스템/담당자가 일치하고 commit한 경우다. 실제 ticketId·책임 시스템·담당자를 반환한다. 현재 PRIMARY 변경은 기존 배정을 자동 변경하지 않는다. 다른 상태의 위 세 참조는 null이다.
- ASSIGNMENT_UNAVAILABLE은 실제 티켓이 없고 담당 관계를 확인하지 못해 NOT_CREATED로 보존한 경우다. 정상 생성은 동기 거래이며 성공 또는 확인된 미생성을 반환한다. 기존 PENDING/UNKNOWN은 재생성하지 않는다. 티켓/배정 기록 불일치는 UNKNOWN으로 확인하며 새 티켓을 만들지 않는다.
- 문제·불변 표준 요청·전달 journal·티켓·AUTO 배정·creation outcome·resultRevision을 한 거래로 저장한다. 문제마다 독립 호출이므로 일부 실패가 다른 문제의 commit을 취소하지 않는다. 거래 롤백 시 성공이나 미생성을 꾸며 반환하지 않는다.
- 생성 후 AI가 실패/취소돼도 업무 저장은 유지한다. 다음 AI 입력은 journal로 업무 적용된 표준 요청과 작업 결과를 읽는다. Spring 최종 결과 처리기는 같은 제안을 다시 생성하지 않는다.

## 제한·재전송·오류

- 최초 attempt=1. Spring maxAttempts=min(3, 1+maxRetries), maxRetries는 실행 입력의 값이다. Python 생성 상한은 기본 3회(`HOPS_AI_TICKET_MAX_ATTEMPTS`, 1~3)이며 서버의 더 작은 상한을 따른다. 업무 도구 재시도는 LLM과 같은 RunBudget.retries_used/maxRetries를 소비하고, 모든 실제 HTTP 호출은 기존 실행 전체 toolCalls/maxToolCalls를 소비한다. 역할 전환·문제 전환에서 한도를 초기화하지 않는다.
- Python 조회 상한은 문제당 기본 4회(`HOPS_AI_TICKET_MAX_QUERIES`, 1~5), 조회 간격은 기본 0.25초(`HOPS_AI_TICKET_POLL_INTERVAL_SECONDS`, 0~2), 요청 전체 timeout은 기본 5초(`HOPS_AI_TICKET_HTTP_TIMEOUT_SECONDS`, 0초 초과~30초)다. 각 호출과 대기는 기존 실행의 더 짧은 남은 기한 안에서만 수행한다. 기한·취소는 상위 실행기가 대기 중에도 중단하고 이미 commit한 업무를 취소하지 않는다.
- 최초 POST 동일 재전송은 횟수를 소비하지 않는다. retries의 expectedAttempt가 현재 값이면 정확히 한 번 증가시킨다. 지나간 값은 최신 결과만 반환하고 미래 값은 409다. 이미 저장됐으면 기존 결과만 반환한다.
- PENDING/UNKNOWN, timeout·5xx는 생성 재시도 근거가 아니다. 먼저 같은 runId/problemRef를 GET한다. 404도 DB 미저장 증명이 아니며 새 작업 ID를 만들지 않는다. 최초 요청의 응답 유실 복구는 보존한 동일 키·동일 전체 본문의 멱등 재전달만 허용한다.
- NOT_CREATED·ASSIGNMENT_UNAVAILABLE·현재 실행/버전/기한·횟수/예산이 모두 유효할 때만 생성 재시도를 허용한다. 입력 수정은 새 실행에서 기존 문제 UUID로 처리한다.
- Python은 생성 응답이 유실되면 POST를 자동 재전송하지 않고 같은 참조만 GET한다. 조회 404·5xx·timeout에서도 새 작업을 만들지 않는다. 재시도 응답이 유실되면 그 전의 NOT_CREATED를 현재 결과로 재사용하지 않으며, 요청한 시도 이후의 유효한 조회 전까지 UNKNOWN으로 다룬다. 응답 형식·실행/문제/버전·작업 식별·시도 이력·지원 시스템을 검사하며 redirect를 따르지 않고 환경 proxy를 사용하지 않는다.
- 오류는 Spring 공통 ApiError다. 400 INVALID_INPUT, 401 UNAUTHENTICATED, 403 ACCESS_DENIED, 404 NOT_FOUND, 409 INQUIRY_VERSION_CONFLICT/IDEMPOTENCY_KEY_REUSED/INVALID_STATE, 429 TOO_MANY_REQUESTS, 503 SERVICE_UNAVAILABLE. 권한·입력·버전 오류는 자동 재시도하지 않는다.

## 검증

Python 어댑터의 업무 도구 이름은 `create_ticket`, `get_ticket_creation_result`, `retry_ticket_creation`이다. TICKET 역할만 사용하며 공용 읽기 Mock의 ToolResult/Evidence로 저장 성공을 위장하지 않는다. 이름/필수 인자 등록·역할 정책·누적 예산·타입 검증을 별도 업무 경계에서 적용한다. 모델은 제목·책임 후보·실제 인용만 제안하며, 생성 이후의 조회·재시도는 코드로 제어해 추가 모델 호출을 하지 않는다. 공개 구간은 Inquiry의 원문 근거와 Ticket의 실제 인용에서 코드 포인트로 조립하며 근거를 찾지 못하면 첫 쓰기 전에 거부한다.

### 역할·State·최종 결과 확장

- 내부 TicketResult에 `creationResults: TicketExecution[]`, `messageQuotes: Map<problemRef,{sourceId,quote}[]>`를 추가한다. 순수 제안 구현은 빈 목록을 사용한다.
- TicketExecution은 `{problemRef, result: SpringTicketResult 또는 null, errorCode: string 또는 null}`다. result는 위 Spring 정상 응답 전체이며, HTTP 응답을 검증하지 못하면 UUID나 ticketId를 만들어 채우지 않고 null과 안전한 오류 분류를 사용한다. NOT_CREATED는 정상 실행의 업무 미생성 결과이며 AI 실행 실패/성공과 다르다.
- 생성 어댑터의 검증 결과를 RoleResponse.verified_ticket_results로 별도 전달하고 병합기가 결과와 대조한다. 모델 JSON만으로 저장 결과를 State에 넣을 수 없다. State.ticketCreationResults는 problemRef별 최종 확인 결과를 보존하며 이전 input/질문 수/기존 WorkItem을 바꾸지 않는다.
- AiResult에 `ticketCreationResults: TicketExecution[]`를 가산한다. 이전 제안 전용 생산자의 필드 생략은 []로 읽으며 신규 출력에는 키를 포함한다. 기존 Spring의 JsonNode 소비자는 이 가산 필드를 수용한다. 엄격한 별도 소비자는 이 필드를 함께 갱신해야 한다. 실패/취소 시에도 이미 병합한 서버 결과는 보존할 수 있고 신규 제안은 기존 규칙대로 비운다. CASE_DRAFT에는 빈 목록만 허용한다.
- Supervisor 모델에는 확인된 결과 요약을 제공하고 최종 본문은 코드가 STORED/NOT_CREATED/PENDING/UNKNOWN을 구별해 조립한다. 검증된 STORED에서만 생성·배정 확인을 안내한다. Spring 최종 결과 처리기는 티켓을 다시 생성하지 않는다. 생성 후 AI 실패/취소나 다음 모델 호출 실패가 Spring의 기존 저장을 취소하지 않는다.

`ai/tests/integration/test_ticket_spring_http.py`는 제품 Supervisor→Inquiry→ExecutingTicketAgent→REST 어댑터→실제 Spring HTTP→PostgreSQL을 검사한다. LLM만 명시적 대역이며, HTTP fault transport도 실제 서버 응답을 받은 뒤 유실/담당자 복구를 주입한다. 정상 확인·응답 유실·담당자 복구·시도/조회 상한·부분 실패·저장 후 취소/다음 입력·인증 거부를 검증한다. 전용 `hops_ticket_tools_test` DB와 `HOPS_TICKET_HTTP_TEST_ORIGIN/DSN/TOKEN/JWT_SECRET`을 설정한 경우에만 실행하며 사용자 DB에서 실행하지 않는다.

TicketCreationApiTest는 MockMvc와 실제 PostgreSQL로 서비스 인증·원자적 저장/배정·동일/동시 요청·본문 충돌·미생성 복구·상한·부분 성공·AI 실패 후 기존 결과 보존을 검사한다. 실제 모델 품질·Python HTTP 도구·브라우저 E2E 검증과 구분한다.

Knowledge가 해당 사용법 문제를 오류 없이 검색했지만 직원 안내를 만들지 못한 경우에는 기존 전달 계약대로 `KNOWLEDGE_QA`를 `REVIEW_REQUIRED` 티켓으로 넘길 수 있다. 모델 Ticket도 같은 문제별 검색 결과 검증을 적용하며 Spring 생성 요청의 problem과 proposal에 동일한 처리 유형을 사용한다. 미검색·검색 오류·다른 문제의 검색 결과로는 이 예외를 허용하지 않는다.

실제 OpenAI 연결 API E2E에서는 격리된 로컬 환경과 가상 문의로 로그인·문의 입력부터 Spring 자동 실행 전달, Supervisor·Inquiry·Ticket 모델 호출, 도구의 생성·조회, 최종 답변 저장 및 담당자 티켓 상세 조회까지 확인했다. 정상 시나리오의 도구 호출은 최초 생성 POST 1회와 결과 확인 GET 1회이며 재시도하지 않았다. 동일 문의 재전송도 기존 실행·티켓을 유지했다. 최신 develop의 ProblemProjector 반영 후 문의 상세 200과 생성된 Problem 표시도 확인했다. 부분 답변 후 Supervisor가 Inquiry를 반복 호출해 호출 상한에 도달한 실패와 질문 상태 미저장이 남아 있어 멀티턴·브라우저 전체 E2E 완료로 보고하지 않는다. 실행 조건과 검증 범위는 [AI 실행 안내](../../ai/README.md)를 따른다.

로컬 bootJar 서버에 실제 TCP/HTTP 요청 27회와 응답/DB 검증 38개를 수행했다. 생성·조회·재시도, 중복/동시 요청, 최대 3회 제한, 서비스 인증/버전/출처 오류, 부분 성공 및 AI 종료 후 저장 보존을 확인했다. 문의 접수와 담당자 티켓 상세도 실제 HTTP로 확인했으며 최종 티켓·배정 이력은 각각 3개였다. 가상 데이터·검증 전용 JWT/서비스 토큰과 별도 PostgreSQL·Redis를 사용했고 AI 자동 전달은 비활성화했다. 로그인·실제 모델·제품 Python 도구 어댑터·브라우저 E2E 검증은 포함하지 않는다.
