# F002 Socket.IO 연결·구독·재접속 복구

상태: 개발 기준 확정 / 구현·검증 별도 · 범위: MVP · 담당: frontend(클라이언트) · 협의: infra(Node.js Socket.IO 서버), backend(권한·확정 이벤트)
근거: PRD 4.11, 5.1, 5.4, 8.2 · API 2.4, 2.5 · DATA 5

## 확정 범위와 남은 작업 (2026-10-25)

내부 검증 3초·저장 ACK 10초·자동 재접속 최대 30초다. auth.invalidated(AuthInvalidatedEvent)·relay.status(RelayStatusEvent)를 추가한다. 서버는 통지 전에 접근/구독을 회수하고 복원 시 재인증·재구독·REST 동기화한다. 새 제어 이벤트의 Node/프론트 처리는 아직 구현하지 않았다.

정확한 타입·enum은 [DATA_TYPES](../contracts/DATA_TYPES.md), 정책은 [WIRE_CONTRACT 10절](../contracts/WIRE_CONTRACT.md#10-인증재접속최신-대화분석-재시도-추가-프로필), 담당 구현·미검증은 [11절](../contracts/WIRE_CONTRACT.md#11-담당별-구현과-검증-상태)을 따른다. 확정은 개발 기준 선택이며 팀 리뷰/병합·서비스 구현 완료가 아니다.

## 1. 목표

로그인한 사용자가 Node.js Socket.IO 서버에 연결해, 권한이 확인된 대상의 실시간 이벤트만 받는다. 연결이 끊겨도 입력과 저장 결과를 잃지 않고, 다시 연결되면 REST로 실제 상태를 맞춘다. (PRD 4.11, 5.1)

이 기능은 화면이 아니라 F003~F007이 함께 쓰는 공통 기반이다.

## 2. 사용자 시나리오

1. 로그인하면 채팅 서버에 연결된다.
2. 채팅 화면에 들어가면 그 문의의 이벤트만 받는다. 다른 사람의 문의는 주소를 알아도 받을 수 없다.
3. 네트워크가 끊기면 어느 구간이 끊겼는지 보이고, 입력 중인 내용은 남아 있다.
4. 다시 연결되면 놓친 내용이 REST 조회로 채워진다.

## 3. 인수 조건

- **F002-AC01 인증 연결**
  When 로그인 직후 · Then `auth.token`에 JWT를 담아 연결한다. 연결이 거부되면 채팅 기능만 "연결 안 됨"으로 표시하고, REST 화면(목록·상세)은 계속 쓴다. (API 2.4, 2.5 · PRD 4.11 부분 실패)
- **F002-AC02 구독 요청과 해제**
  When 채팅 화면(또는 관리자 조회 화면)에 들어가면 · Then `subscription.request`로 대상을 요청하고, 서버가 허용한 뒤에만 그 대상의 이벤트를 화면에 반영한다. 화면을 떠나면 `subscription.release`를 보낸다. (API 2.4)
- **F002-AC03 타인 문의 구독 차단**
  Given 다른 사용자의 문의 ID · When 구독을 요청하면 · Then 거부되고, 내용을 보여 주지 않고 권한 없음 안내를 한다. (PRD 8.2 검증 항목 · API 2.4)
- **F002-AC04 중복 이벤트**
  같은 `eventId`를 두 번 받으면 한 번만 반영한다. (API 2.4)
- **F002-AC05 뒤늦은 이벤트**
  현재 문의 버전과 활성 `runId`에 맞지 않는 진행·답변 조각은 무시한다. `(runId,messageId,seq)` 중복을 제거하고 미수신 조각은 순번대로 버퍼링한다. 1·3·2 순으로 와도 2를 버리지 않는다. 누락·내용 충돌은 최종 REST 저장 메시지로 복구하며 저장 이력·기존 티켓은 유지한다. (API 2.4 · PRD 4.4)
- **F002-AC06 끊김 표시**
  React–Node.js 연결이 끊기면 "연결이 끊겼어요"를 표시한다. Node.js–Spring 구간 오류를 알 수 있으면 구분해 표시한다. 입력과 이미 표시된 저장 결과는 지우지 않는다. (PRD 4.11 · 구간 구분 방법은 Q-INF-7)
- **F002-AC07 재접속 후 복구**
  When 다시 연결되면 · Then 인증과 구독을 다시 요청하고, REST로 메시지·문의·티켓·실행 상태를 다시 조회해 화면을 맞춘다. 재연결만으로 누락이 복구됐다고 보지 않는다. (API 2.4 · PRD 4.11)
- **F002-AC08 확정 이벤트 처리**
  `message.stored`, `state.changed`를 받으면 이벤트 내용으로 임시 갱신하거나 해당 대상을 REST로 다시 조회한다. 진행 이벤트(`agent.progress`, `answer.delta`)만으로 저장·해결 성공을 표시하지 않는다. (API 2.4 · PRD 5.1)
- **F002-AC09 연결 중 토큰 만료**
  연결 중 토큰이 만료되면 갱신하거나 다시 연결한다. 둘 다 안 되면 전송과 구독을 멈추고 F001-AC05의 재로그인 안내로 넘긴다. (API 2.5 · 처리 방식 Q-INF-4)
- **F002-AC10 로그아웃 시 종료**
  로그아웃하면 연결을 끊고 구독 상태를 비운다. (API 2.1)
- **F002-AC11 부서·역할 변경과 구독 회수**
  추가 개발 프로필은 서버가 AUTH_CONTEXT_CHANGED로 확인한 사용자 소속/역할 변경에 재로그인한다. 단순 업무 대상 403은 권한 안내이며 인증 갱신을 반복하지 않는다. 자동 재접속은 감지 후 30초, 기본 간격 1→2→4→최대 5초와 jitter, 실패 시 다시 연결/online으로 재개한다. 저장 ACK 10초와 별개이며 WIRE_CONTRACT 10절을 따른다.
  서버가 구독 해제나 권한 변경을 알리면 그 대상의 이벤트 반영을 멈추고 REST로 다시 조회한다. 결과가 403이면 내용을 지우고 권한 없음 화면으로 보낸다. 이전 부서의 티켓 내용을 화면에 남기지 않는다. (API 2.4 "재이관·소속 변경 시 이전 구독 권한을 회수", API 2.5 · 알림 방식 Q-INF-4)

## 4. 계약(화면이 기대하는 값)

공통 필드·필수/nullable·wire 표현의 원본은 [데이터 타입·필드 문서](../contracts/DATA_TYPES.md)다. 아래 MVP 연결표는 확정 개발 기준이다. 후속 기능 후보와 실제 담당 구현·검증은 구분한다.

| 용도 | 이름 | 공통 계약 타입 | 구현·검증 추적 |
|---|---|---|---|
| 연결 인증 | auth.token | Mock 가짜 토큰 / live Spring 발급 accessToken | Q-BE-2, Q-INF-4 |
| 구독/해제 | subscription.request / subscription.release | SubscriptionCommand → SubscriptionAck | Q-INF-3/6 |
| 진행 | agent.progress | AgentProgressEvent | Q-AI-1 |
| 답변 조각 | answer.delta | AnswerDeltaEvent | Q-AI-2 |
| 메시지 저장 | message.stored | MessageStoredEvent | Q-INF-6 |
| 업무 변경 | state.changed | StateChangedEvent | Q-INF-6 |
| 인증 회수 | auth.invalidated | AuthInvalidatedEvent | Q-INF-4, Node/프론트 구현 필요 |
| 중계 상태 | relay.status | RelayStatusEvent | Q-INF-7, Node/프론트 구현 필요 |

구독 ACK와 저장 CommandAck를 혼용하지 않는다. 서버·JS·Java 클라이언트 버전(Q-INF-1), 연결 주소/CORS(Q-INF-2), 권한 회수/제어 이벤트(Q-INF-4)는 확정 계약에 맞게 구현·검증한다. Mock 구독은 INQUIRY/DEPARTMENT_TICKETS/TICKET만 포함한다.

개발 서버 연결은 [live 프로필](../contracts/WIRE_CONTRACT.md#9-프론트-live-연동-프로필)의 접속 path·인증·오류·ACK 규칙을 따른다. 실제 모드도 같은 target을 사용한다. 브라우저 설정은 [프론트 실행 안내](../../frontend/README.md), 정책과 미검증 항목은 공통 계약 10·11절을 따른다.

- **F002-AC10 인증 회수 제어 이벤트(구현 필요)**
  서버가 접근/구독을 먼저 회수한 뒤 auth.invalidated를 수신하면 만료는 refresh·재연결, AUTH_CONTEXT_CHANGED/REFRESH_TOKEN_REVOKED는 재로그인한다. 통지 유실 시에도 서버는 기존 권한 요청을 차단해야 한다.
- **F002-AC11 중계 단절/복구 제어 이벤트(구현 필요)**
  relay.status=UNAVAILABLE이면 입력을 보존하고 전송을 보류한다. AVAILABLE이면 재인증·재구독·REST 복원 후 전송을 허용하며 저장 명령은 자동 재전송하지 않는다.

## 5. 연결 상태

```text
미연결 → 연결 중 → 연결됨 → (구독 요청 → 허용 / 거부)
연결됨 → 끊김(입력 보존, 표시) → 재연결 중 → 연결됨 → 재구독 → REST 재조회
연결됨 → 토큰 만료 → 갱신/재연결 → 실패 시 전송·구독 중지 → F001 재로그인
```

## 6. 범위 밖

Node.js 서버 내부 구현, Spring 여러 인스턴스 라우팅, 이벤트 보존 방식(API 2.4 "통합 전 합의").

## 7. 막히는 정도

- 목으로 진행 가능: 테스트용 Socket.IO 서버로 연결·ACK·끊김·재접속·중복 이벤트를 재현해 클라이언트를 먼저 만든다.
- 구현·검증 상태: 위 확정 범위와 공통 계약 11절을 따른다. 후속 상세 설계는 QUESTIONS의 해당 ID로 추적한다.

## 공통 Mock 계약 (2026-10-23)

이 기능의 Mock 전송 데이터는 [공통 데이터 타입 문서](../contracts/DATA_TYPES.md)와 [요청·응답 연결표](../contracts/WIRE_CONTRACT.md#8-작업별-데이터-타입-연결)을 따른다. 공용 입력·응답·이벤트 예시는 [Mock 시나리오](../mocks/README.md)를 사용한다. Mock은 계약 예시의 일부이며 모든 확정 정책을 구현한 서버가 아니다. 팀 리뷰·서버 저장/권한·AI 품질 검증 완료를 뜻하지 않는다.
