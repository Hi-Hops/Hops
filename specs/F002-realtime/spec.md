# F002 실시간 진행·상태 복구

상태: PRD 1.44 목표 명세 · PRD 정책 유지 / hops-system-1 계약 기준·구현/검증 별도.

## 1. 목표와 범위

React는 Spring REST로 명령을 보내고 권한이 확인된 Spring SSE로 공개 가능한 진행·변화를 받는다.

근거: [PRD](../../docs/PRD.md), [API](../../docs/API.md), [데이터](../../docs/DATA.md), [목표 공통 계약](../contracts/TARGET_CONTRACT.md).

## 2. 인수 조건

- **F002-TARGET-01:** SSE 대상은 현재 사용자와 문의·티켓·시스템 업무에 대한 접근 권한을 서버가 검사한다.
- **F002-TARGET-02:** 진행이나 답변 조각은 임시 표시이며 저장된 메시지·티켓·해결 상태의 성공으로 처리하지 않는다.
- **F002-TARGET-03:** 동일 이벤트 중복과 이전 문의 버전의 늦은 결과를 현재 결과에 중복 적용하지 않는다.
- **F002-TARGET-04:** 연결 종료 시 입력과 저장된 결과를 보존한다. 재연결 뒤 인증·접근 권한을 확인하고 REST로 관련 저장 상태를 재조회한다.
- **F002-TARGET-05:** SSE 재연결만으로 누락 이벤트가 복구됐다고 판단하지 않는다.

## 화면과 사용자 흐름

### 화면

- 문의 채팅, 티켓 상세와 담당 시스템 업무에서 연결 상태·진행 문구를 표시한다. 새 시스템 대상은 아래 hops-system-1 SSE 계약안을 따른다.

### 흐름

1. 화면 진입 시 현재 인증과 서버 권한에 맞는 대상을 구독한다.
2. 진행 이벤트는 임시 표시하고 저장 메시지·업무 상태는 서버 확정 결과나 REST 조회로 동기화한다.
3. 연결이 끊기면 입력을 보존하고 재인증·재조회 뒤 허용된 연결을 다시 연다.

## 상세 인수 조건과 예외

- **F002-DETAIL-01 권한 없는 대상:** 문의·티켓 식별자를 알고 있어도 서버가 현재 소유권·담당 관계를 검사하고 허용되지 않은 데이터와 이벤트를 보내지 않는다.
- **F002-DETAIL-02 중복 이벤트:** 같은 이벤트를 다시 받아도 저장 메시지나 업무 변경을 중복 표시하지 않는다. 이벤트 ID와 세부 형식은 전송 계약에서 확정한다.
- **F002-DETAIL-03 늦은 진행:** 현재 문의 버전·실행과 일치하지 않는 진행·답변 조각은 최신 결과에 섞지 않는다. 이미 저장된 이전 메시지와 티켓은 보존한다.
- **F002-DETAIL-04 재접속:** 연결이 끊겼다가 복구되면 현재 권한을 재검사하고 문의·메시지·티켓·실행의 저장 상태를 REST로 재조회한다.
- **F002-DETAIL-05 재생 불가:** 서버가 이벤트를 재생하지 못한다는 신호를 보내거나 보존 여부를 알 수 없으면 재연결만으로 복구 완료를 표시하지 않는다.
- **F002-DETAIL-06 권한 변경:** 담당 관계가 바뀐 후 새 조회·재연결은 현재 권한을 따른다. 기존 연결 즉시 회수의 구체 방식은 통합 전 합의한다.

## 계약·화면 상태

신규 `hops-system-1`의 계약이다. 필드·enum은 [DATA_TYPES](../contracts/DATA_TYPES.md#신규-계약-hops-system-1), REST·오류·SSE·멱등성은 [WIRE_CONTRACT 12절](../contracts/WIRE_CONTRACT.md#12-신규-계약-hops-system-1), 업무 전이는 [TARGET_CONTRACT](../contracts/TARGET_CONTRACT.md)가 정본이다. 아래 내용은 구현·통합 완료 표시가 아니다.

| 조작 | 계약·검증 |
|---|---|
| 대상 연결 | INQUIRY/TICKET/SYSTEM_TICKETS/CASE와 Bearer. 관리자 SYSTEM_TICKETS에는 메타데이터 변화만 전달 |
| 진행·답변 조각 | 현재 inquiryVersion/runId만 임시 표시. eventId와 조각 seq로 중복 제거 |
| 저장·업무 변경 | message.stored/state.changed를 받은 대상 GET을 무효화·재조회. 메시지 정정·처리·확인·사례 반영을 포함 |
| 복구 | 신규 초기 프로필은 replay 없음. resync.required 후 REST 성공 → 커서/임시 조각 초기화 → 새 연결 |
| 조회 보완 | 목록 활성 화면 5초 폴링, 활성 분석 Run 1초 조회. 숨김·화면 이탈 시 중단 |

| 화면 상태 | 관찰 가능한 동작 |
|---|---|
| 연결 끊김 | 입력·성공 티켓 보존, 보호 데이터 마스킹·복구 중 표시 |
| REST 복구 실패 | 연결됐다는 이유만으로 복구 완료 표시 금지 |
| 권한 회수 | 자동 반복 연결 중단·현재 사용자/메뉴 재확인 |

## hops-system-1 검증 시나리오

- **F002-CONTRACT-01:** 같은 eventId와 answer.delta 조각을 반복 전달해도 한 번만 표시한다.
- **F002-CONTRACT-02:** 정정 전 runId의 늦은 조각은 무시하고 저장된 이전 대화는 유지한다.
- **F002-CONTRACT-03:** Last-Event-ID에 resync.required를 받으면 REST 조회 성공 전까지 복구 중이다.
- **F002-CONTRACT-04:** 업무 저장 이벤트가 HTTP 응답보다 먼저 도착해도 requestId/messageId로 하나의 결과를 표시한다.

문서/JSON 검사·Mock·실제 서버 저장/권한·실제 AI/검색·브라우저 E2E를 분리해 결과를 기록한다. 기존 테스트의 이전 AC 번호를 새 시나리오 통과로 간주하지 않는다.

## 범위·연관 기능

- 접속 성공·이벤트 수신은 DB 저장·배정·해결을 보장하지 않는다. 인증 흐름은 F001, 업무 저장은 각 기능을 따른다.

## 현재 구현과 남은 작업

현재 프론트는 v2의 INQUIRY/TICKET/SYSTEM_TICKETS/CASE 구독·공개 이벤트 검증·진행/임시 답변·REST 재동기화와 권한 무효화를 구현하고 Mock·HTTP/SSE 대역으로 검사한다. Spring SSE는 아직 DEPARTMENT_TICKETS 범위이며 신규 대상·업무 이벤트 생산자·권한 회수의 실제 통합은 남아 있다. 데모의 정적 대화·짧은 실행만으로 진행 이벤트가 눈에 보이거나 실제 AI가 연결됐다고 판단하지 않는다.

실행법과 현재 화면·호출 범위는 [프론트 안내](../../frontend/README.md), 서비스별 실제 구현은 [공통 계약 11절](../contracts/WIRE_CONTRACT.md#11-담당별-구현과-검증-상태)을 따른다. 신규 계약의 프론트 검사를 실제 Spring 저장·권한, AI·검색 및 전체 E2E 검증으로 대신하지 않는다.

## 서비스 간 계약 추적

| 경계 | 이 기능에 적용할 계약 |
|---|---|
| Frontend↔Spring | SSE envelope·순번/중복·재조회·인증. 필드·응답·오류·멱등성은 [DATA_TYPES](../contracts/DATA_TYPES.md#신규-계약-hops-system-1)·[WIRE12절](../contracts/WIRE_CONTRACT.md#12-신규-계약-hops-system-1) |
| Spring↔AI | AiAccepted/Snapshot/Event와 취소. 내부 seq와 공개 eventId를 분리. 실제 wire 필드는 [에이전트 계약](../../contracts/agents/README.md#반영검색-권한-서비스-계약) |
| 기본값 | [결정 대장](../QUESTIONS.md#실행검색-기본값)의 저장·서비스 인증·검증 게이트를 참조. 비밀값은 배포 환경 주입 |


## 병합 ERD 적용 조건

[DATA11절](../../docs/DATA.md#11-완성-erd-대조-결과와-병합-기준)의 E01~E10 대조와 [ERD6절](../../docs/ERD.md#6-hops-system-1-공통-계약-보완)의 저장 보완을 이 기능에 적용한다. 외부 필드/상태는 DATA_TYPES·WIRE12절을 정본으로 유지하며 DB 코드를 UI에 직접 노출하지 않는다.

- **F002-CONTRACT-05:** DB INQUERY/CURATION/CANCELLED를 외부 INQUIRY/CASE_DRAFT/SUPERSEDED로 변환한다. 답변 조각은 임시 표시이며 최종 message_status=COMPLETED 행을 조회한 뒤 확정한다.

- **F002-CONTRACT-06:** 내부 delta.messageId=input.answerMessageId이며 공개 messageId도 같다. 내부 이벤트 전체 seq와 답변 chunkSeq를 혼용하지 않고 공개 seq에는 chunkSeq를 사용한다.
- **F002-CONTRACT-07:** 조각의 순서 변경·중복·저장 이벤트 선도착에도 같은 messageId의 말풍선은 하나다. GET으로 최종 본문을 읽은 뒤 도착한 늦은 조각은 무시한다.
- **F002-CONTRACT-08:** SSE 연결 전에 AI가 완료됐거나 message.stored가 유실돼도 내부 snapshot·공개 Run 조회와 최종 메시지 GET으로 완료한다. Run 종료 또는 currentRun 교체 시 문의·메시지를 재조회하고 새 업무 명령을 만들지 않는다.
- **F002-CONTRACT-09:** Run 성공 후 최종 GET이 실패하면 동기화 실패·재조회 조작을 표시한다. 실패한 조회를 빈 메시지 성공으로 바꾸거나 AI 실행을 자동 재시도하지 않는다.
