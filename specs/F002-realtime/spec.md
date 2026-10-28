# F002 SSE 연결·권한·재접속 복구

상태: 개발 기준 확정 / 구현·검증 별도 · 범위: MVP · 담당: frontend(SSE 수신) · 협의: backend(인증·권한·스트림·확정 이벤트)
근거: PRD 4.11, 5.1, 5.4, 8.2 · API 2.4, 2.5 · DATA 5

## 확정 범위와 남은 작업 (2026-10-27)

React의 문의·메시지·정정은 Spring REST로 보내고, 분석 진행·답변 조각·저장 메시지·상태 변경은 Spring SSE로 받는다. 스트림 재연결만으로 누락 복구를 완료하지 않고 REST로 저장 상태를 동기화한다.

정확한 타입·enum은 [DATA_TYPES](../contracts/DATA_TYPES.md), 전송·인증·재접속 정책은 [WIRE_CONTRACT 10절](../contracts/WIRE_CONTRACT.md#10-인증재접속최신-대화분석-재시도-추가-프로필), 담당 구현·미검증은 [11절](../contracts/WIRE_CONTRACT.md#11-담당별-구현과-검증-상태)을 따른다. 확정은 개발 기준 선택이며 팀 리뷰/병합·서비스 구현 완료가 아니다.

## 1. 목표

로그인한 사용자가 Spring의 권한 검사를 거친 SSE로 현재 화면에 필요한 이벤트만 받는다. 연결이 끊겨도 입력과 저장 결과를 잃지 않고, 다시 연결되면 재생 가능한 이벤트를 받은 후 REST로 실제 상태를 맞춘다.

이 기능은 화면이 아니라 F003~F007이 함께 쓰는 공통 기반이다.

## 2. 사용자 시나리오

1. 로그인 후 화면에 진입하면 해당 문의·티켓·부서 대상의 SSE에 연결한다.
2. Spring은 연결 시 현재 사용자의 소유자·부서·역할 권한을 확인한다.
3. 네트워크가 끊기면 연결 오류를 표시하고 입력 중인 내용과 이미 표시한 저장 결과는 남긴다.
4. 다시 연결되면 `Last-Event-ID`로 재생을 요청하고 REST 조회로 누락된 저장 상태를 채운다.

## 3. 인수 조건

- **F002-AC01 인증 연결**
  When 실시간 화면에 진입하면 · Then fetch 기반 SSE 요청의 `Authorization: Bearer <accessToken>`으로 Spring에 연결한다. 토큰은 URL·쿼리·이벤트 본문에 넣지 않는다.
- **F002-AC02 대상 선택과 종료**
  When 화면에 진입하면 · Then `GET /api/realtime/events?targetType={type}&targetId={id}`로 한 대상에 연결하고, 화면을 떠나면 해당 요청을 취소한다. 같은 대상의 여러 소비자는 클라이언트 참조 횟수를 관리한다.
- **F002-AC03 타인 문의 접속 차단**
  Given 다른 사용자의 문의 ID · When SSE 연결을 요청하면 · Then Spring이 403 `ACCESS_DENIED`로 거부하고 내용을 전송하지 않는다.
- **F002-AC04 중복 이벤트**
  같은 `eventId`를 두 번 받으면 한 번만 반영한다.
- **F002-AC05 뒤늦은 이벤트**
  현재 문의 버전과 활성 `runId`에 맞지 않는 진행·답변 조각은 무시한다. `(runId,messageId,seq)` 중복을 제거하고 1·3·2 순서라면 3을 버퍼링했다가 2 후에 반영한다. 누락·내용 충돌은 REST 저장 메시지로 복구한다.
- **F002-AC06 끊김 표시**
  React–Spring SSE가 끊기면 "연결이 끊겼어요"를 표시하고 입력과 이미 표시한 저장 결과를 지우지 않는다.
- **F002-AC07 재접속 후 복구**
  When 다시 연결하면 · Then 최신 토큰과 현재 대상 권한을 다시 확인하고, 있으면 `Last-Event-ID`를 전달한다. 이후 REST로 메시지·문의·티켓·실행 상태를 다시 조회한다.
- **F002-AC08 확정 이벤트 처리**
  `message.stored`, `state.changed`를 받으면 임시 화면을 갱신하거나 REST로 해당 대상을 다시 조회한다. `agent.progress`, `answer.delta`만으로 저장·해결 성공을 표시하지 않는다.
- **F002-AC09 연결 중 토큰 만료**
  Access Token이 만료하면 기존 SSE를 종료하고 refresh 후 새 토큰으로 재연결한다. refresh를 할 수 없으면 입력을 보존한 재로그인 안내로 넘긴다.
- **F002-AC10 로그아웃 시 종료**
  로그아웃·계정 전환 시 모든 SSE를 즉시 종료하고 이전 사용자의 이벤트 커서·조회 캐시·대기 요청을 정리한다.
- **F002-AC11 부서·역할 변경과 접근 회수**
  Spring은 사용자의 소속·역할 변경이나 계정 차단을 확인하면 기존 스트림을 종료하고 이후 REST·SSE 요청을 차단한다. 전송 가능한 경우 `auth.invalidated`를 보낸 뒤 종료하며, 통지 유실이 있어도 서버 권한 검사가 접근을 막는다.
- **F002-AC12 재생 불가 복구**
  Spring이 `Last-Event-ID` 이후 이벤트를 재생할 수 없으면 `resync.required`를 전달한 뒤 스트림을 종료한다. React는 실시간 내용을 임의로 채우지 않고 REST 원본으로 복구한 후 기존 커서와 제어 이벤트 ID를 비워 `Last-Event-ID` 없이 새로 연결한다.

## 4. 전송 계약

| 용도 | 경로·이벤트 | 공통 계약 타입 |
|---|---|---|
| 실시간 연결 | `GET /api/realtime/events?targetType={type}&targetId={id}` | `text/event-stream` |
| 진행 | `agent.progress` | AgentProgressEvent |
| 답변 조각 | `answer.delta` | AnswerDeltaEvent |
| 메시지 저장 | `message.stored` | MessageStoredEvent |
| 업무 변경 | `state.changed` | StateChangedEvent |
| 인증 회수 | `auth.invalidated` | AuthInvalidatedEvent |
| REST 동기화 요구 | `resync.required` | ResyncRequiredEvent |

SSE 블록은 `id: <eventId>`, `event: <name>`, `data: <JSON>` 뒤 빈 줄로 종료한다. 유휴 시에는 주석 heartbeat를 보낸다. 브라우저는 마지막 `eventId`를 보존하고 재연결에 `Last-Event-ID`로 전달한다. 정확한 heartbeat 간격·SSE timeout·이벤트 보존 시간과 개수는 실제 프록시·부하 검증 후 고정한다.

## 5. 연결 상태

```text
미연결 → 연결 중 → 연결됨
연결됨 → 끊김(입력·저장 결과 보존) → 재연결 중 → 권한 재검증 → SSE 재개 → REST 재조회
연결됨 → 토큰 만료 → refresh → SSE 재연결 → 실패 시 입력 보존 재로그인
연결됨 → resync.required → REST 원본 동기화 → SSE 재개
```

## 6. 범위 밖

- Spring 다중 인스턴스의 이벤트 라우팅 구현.
- 정확한 heartbeat·timeout·재연결 수치와 이벤트 보존소 선정.
- 운영 프록시·TLS·buffering·최대 연결 수 설정.

## 7. 막히는 정도

- Mock으로 진행 가능: Spring SSE 형식의 테스트 스트림으로 연결·끊김·재접속·중복·재생 불가를 재현해 프론트 어댑터를 먼저 만든다.
- 구현·검증 상태는 공통 계약 11절을 따른다. 다중 인스턴스·보존 세부 설계는 실측 후 확정한다.

## 공통 Mock 계약 (2026-10-27)

이 기능의 Mock 전송 데이터는 [공통 데이터 타입 문서](../contracts/DATA_TYPES.md)와 [요청·응답 연결표](../contracts/WIRE_CONTRACT.md#8-작업별-데이터-타입-연결)를 따른다. 공용 입력·응답·이벤트 예시는 [Mock 시나리오](../mocks/README.md)를 사용한다. Mock은 계약 예시의 일부이며 서버 저장·권한·AI 품질 검증 완료를 뜻하지 않는다.
