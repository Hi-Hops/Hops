# 언어 간 타입·직렬화 대응

상태: `hops-mock-1` Mock 초안. wire 구조는 [Schema](contract.schema.json) 하나에서 생성한다.

## 전송 타입

| JSON | TypeScript(프론트·Node) | Java 21(Spring) | Python(AI) | 경계 규칙 |
|---|---|---|---|---|
| UUID 문자열 | `Uuid = string` | `String` | `Uuid = str` | 소문자 하이픈 UUID. Q-/TK- 표시 번호와 구분 |
| UTC 시각 문자열 | `IsoDateTime = string` | `String` | `IsoDateTime = str` | UTC Z, 소수초 최대 6자리. epoch 숫자·로컬 시각 금지 |
| integer | `number` | `Integer` | `int` | 필드별 정수 범위 검사. 상한 2,147,483,647. 문자열/boolean 강제 변환 금지 |
| boolean | `boolean` | `Boolean` | `bool` | `true/false`만. 0/1, 문자열 허용 안 함 |
| nullable T | `T \| null` | 참조 타입 | `Optional[T]` | 필수 키 + null. Java 참조 타입만으로 필수/nullable을 구분할 수 없어 Schema 검증 필수 |
| 배열 | `Array<T>` | `List<T>` | `list[T]` | 빈 배열은 []. null 대신 사용 |
| 고정 코드 | 문자열 리터럴 union | `String` | `Literal[...]` | Java는 Schema enum 검증. 알 수 없는 코드 정책은 타입별 적용 |
| 객체 variant | union | sealed interface + record | `Union[...]` | `ok`·`type`·`status`로 구분. 지정된 variant만 허용 |

정의하지 않은 객체 필드는 거부한다. 입력 JSON에서 누락/null/[]를 서로 바꾸지 않는다. JSON Schema의 integer는 수학적으로 정수인 JSON number다. 직렬화할 때 정수 표기를 사용하며, 소수 1.5·NaN·Infinity는 보내지 않는다. Python에서 bool은 int의 하위 타입이지만 버전·순번에는 허용하지 않는다.

Java DTO는 record를 지원하는 Java 21 대상이다. Jackson에서 문자열→숫자, 비정수 소수의 정수 절삭, 숫자→boolean 같은 손실/타입 coercion을 금지하고 누락 필수값·추가 필드를 검증한다. JSON Schema에서는 1.0도 정수값이므로, 검증을 통과한 정수값 숫자만 손실 없이 Integer/int로 정규화할 수 있다. Python의 float 1.0도 같은 규칙이며 단순 int(1.5) 절삭은 금지한다. 전역 snake_case 설정을 적용하지 않는다. Python dataclass는 타입 힌트일 뿐 입력 검증기가 아니므로 `**payload` 생성 전에 JSON Schema로 검증한다. TypeScript 타입 단언도 런타임 검증이 아니다. Node와 프론트는 수신 경계에서 같은 Schema를 검증해야 한다. 라이브러리/설정 코드는 각 실제 서비스 구현에서 선정한다.

## 필드 이름과 버전

| 의미 | 공통 wire 필드 | 내부·DB 변환 |
|---|---|---|
| 로그인 사용자 | `CurrentUser` | `/api/auth/me` 경로는 유지. 기존 문서 `Me` 타입명을 변경 |
| 현재 문의 버전 | 문의 상세 `version`, 명령 `expectedInquiryVersion`, 이벤트/AI `inquiryVersion` | 의미는 같은 문의 버전. 어댑터에서 이름만 대응 |
| 메시지 수정 차수 | `revision` | DB `revision_no` 등은 서버 어댑터가 매핑 |
| 저장 메시지 발신자 | `USER / AI` | 내부 agent-1은 `USER / ASSISTANT`이므로 AI 어댑터에서 변환 |
| 티켓 처리 상태 | `processingStatus` | DB `ticket_status` 후보 |
| 티켓 배정 상태 | `routingStatus` | DB `routing_status` 후보 |
| 질문 횟수 | `questionCount` | DB `clarify_count` 후보. 서버 집계값 유지 |
| 문제·작업 임시 참조 | AI `ref`, `problemRef` | Spring이 `new:*`를 UUID로 매핑. 내부 `Problem.ref`는 저장 문제의 id 또는 임시 참조 |

## 서비스 AI와 내부 agent-1

`AiRequest/AiResult`는 이번 Mock의 외부 서비스 DTO다. `contracts/agents/README.md`의 `AgentRequest/AgentResult`를 교체하지 않는다. 외부에서 내부로 들어갈 때 Python 어댑터가 `invocationId`와 역할별 요청을 생성한다.

- `AiRequest.messages`는 저장 `ChatMessage`와 같은 타입을 재사용한다. 내부에서는 `id → messageId`, `AI → ASSISTANT`로 바꾸고 현재 revision/content만 넘긴다. 불필요한 수정 이력을 모델에 복사하지 않는다.
- `existingProblems[].id → Problem.ref`, `questionCount` 유지. `departments`는 공통 Department 필드에 responsibilities를 더한 `AiDepartment`이며 서버가 허용한 배정 대상만 보낸다.
- Evidence의 식별자·버전·source·observedAt 의미와 TicketProposal의 `ref/problemRef/workScope/departmentCode/purpose/evidenceIds`는 agent-1과 맞춘다.
- 내부 실행은 SEARCH/DIAGNOSE 등 여러 action을 사용할 수 있다. 외부 Mock은 최종 `ASK_USER / PROPOSE_TICKETS / OUT_OF_SCOPE`만 표현한다. 내부 중간 결과를 무조건 이 세 값으로 치환하지 않고 실행을 완료하거나 안전한 오류를 반환한다.
- AI는 `questionCount`를 증가시키지 않는다. Spring이 검증된 추가 질문을 전달할 때 한 번 집계한다. 3회 이후에는 ASK_USER를 허용하지 않고 미분류 IT 문의를 CS_REVIEW 후보로 검증한다.
- TOOL 근거는 observedAt 필수, MESSAGE 근거 sourceId는 실제 전달 메시지 ID, sourceVersion은 revision 문자열이어야 한다. 근거를 모델이 임의 생성하면 거부한다.
- 모든 proposal의 problemRef가 유효하고, evidenceIds가 제공 근거에 속하며, departmentCode가 서버 카탈로그에 있어야 한다. CS_REVIEW의 부서는 CS여야 한다.
- Spring은 runId·inquiryVersion·현재 상태를 재검증한 뒤 실제 UUID·작업↔티켓 연결을 저장한다. `AiSuccess.status=OK`만으로 티켓 카드·해결을 확정하지 않는다.

## 변경·호환성

필드명/타입/필수/nullable/enum 의미 변경 시 Schema·operation·fixture·시나리오·생성 파일을 함께 바꾸고 검사한다. Mock 프로필의 미정 상태는 `string`으로 유지해 새 값의 표시 fallback을 검사한다. 브레이킹 변경은 버전을 올리고 소비자별 반영 여부를 확인한다. 이 저장소에 실제 프론트·Node·AI 서비스가 아직 없으므로 생성 DTO를 제공한 것이며, 각 서비스에서의 실제 직렬화·컴파일 성공을 뜻하지 않는다.
