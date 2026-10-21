# MVP 서비스 연결 검토안

상태: #22 팀 리뷰 후보. [API 설계](../../docs/API.md) 3절의 Spring–Python 경계를 구체화한다. 경로·서비스 인증·재연결 방식은 미승인 제안이며 구현 전에 Spring·Python 담당자가 확인한다.

## 책임과 흐름

1. 문의·메시지·정정·새 문의 동의는 React → Node.js Socket.IO → Spring으로 전달한다. 로그인·조회·티켓 업무 REST와 실시간 명령의 구분은 API 설계 2.1·2.4절을 따른다.
2. Spring은 사용자 JWT와 업무 권한을 검사하고 문의 버전·runId·작업 ID를 영속화한다.
3. Spring은 Python에 실행을 요청하고 SSE를 구독한다. Python은 분석 결과를 제안한다.
4. Spring은 결과·최신 버전을 검증하고 작업별로 티켓과 부서를 저장한다.
5. Spring은 권한 범위의 진행·답변·저장 상태를 Node.js로 전달하고 Node.js가 Socket.io로 중계한다.
6. Socket.io 재접속 시 React는 Spring REST로 원본 상태를 복구한다. 중계 서버 메모리를 업무 상태 원본으로 사용하지 않는다.

사용자 JWT·Socket.IO 연결/구독·저장 ACK는 API 설계 2.4·2.5절을 따른다. 이 검토안은 사용자 인증이나 채팅 명령 계약을 대체하지 않는다.

## Spring–Python 전송 제안

과거 접수 경로를 후보로 사용하고 결과도 SSE의 종료 이벤트로 전달하는 방식을 제안한다. 최신 API는 경로를 아직 확정하지 않았으므로 아래 세 경로 모두 리뷰 대상이다. 별도 callback과 SSE를 동시에 구현하지 않는다. [OpenAPI 초안](openapi.json)은 Spring–Python만 기술하며 전체 업무 API나 서버 구현이 아니다.

| 경로 후보 | 의미 |
| --- | --- |
| `POST /internal/agent-runs` | 요청 접수 후 202와 runId·QUEUED 반환. 같은 runId·같은 정규화 요청은 기존 접수 결과, 다른 요청은 409 |
| `GET /internal/agent-runs/{runId}/events` | Spring 전용 SSE 구독. progress·result·failed 전달 |
| `GET /internal/agent-runs/{runId}/result` | SSE 종료 결과를 못 받은 Spring이 같은 실행의 보존 결과를 복구. 미완료는 202, 완료는 마지막 result/failed 외피 |

서비스 간 Bearer 자격 증명을 쓰는 안을 OpenAPI에 표현했지만, 이는 사용자 JWT의 재사용을 뜻하지 않는다. 자격 증명 종류·발급·교체·검증 방식은 통합 전에 정한다. 인증 실패는 401, 허용 범위 밖은 403이며 내부 오류 원문을 응답하지 않는다.

- 이벤트 외피는 과거 필드를 참고해 eventId·runId·inquiryId·inquiryVersion·schemaVersion·occurredAt·type·payload로 제안한다. progress에는 공개 가능한 stage/message, result에는 분석 결과, failed에는 code/retryable을 담는 안이다. Node.js로 중계하는 외부 Socket.IO 이벤트와 같은 계약으로 간주하지 않는다.
- Python은 같은 실행의 종료 결과와 종료 eventId를 재시작 후에도 복구할 수 있게 보존해야 한다. 실행·중복 접수·결과 저장소의 구체 기술과 기간은 미정이다. 업무 DB 직접 쓰기를 허용하는 의미가 아니다.
- SSE 단절 시 새 runId로 다시 실행하지 않는다. 같은 runId의 결과 조회를 우선하고 미완료면 같은 실행에 재구독한다. MVP에서 진행 이벤트 전체 재생은 요구하지 않는 안이다. 보존 기간 경과 응답은 저장 정책과 함께 확정한다.
- 재구독의 종료 이벤트는 기존 eventId를 유지한다. Spring은 eventId 중복, runId·문의 소유 관계·최신 inquiryVersion을 검사하고 종료 결과를 한 번만 적용한다. 이전 문의 버전은 무시하고 기존 성공 티켓은 유지한다.
- Python 접수·진행·분석 성공과 Spring 저장 성공을 구분한다. 저장 결과만 실제 티켓 카드에 반영한다. 전체 deadline·취소·형식 복구와 모델 재시도는 역할 변경이나 재접속으로 초기화하지 않는다.

## Spring 소비자와 함께 확인할 항목

- Socket.IO 명령 ACK와 내부 실행 202 응답을 구분하고, Spring 저장 결과만 사용자용 확정 이벤트로 중계한다.
- 보완 정보 저장이나 미동의 새 문의 후보에 Python 실행을 만들지 않는다. Spring이 판정한 실제 분석 대상만 실행 요청에 담는다.
- 사용자 JWT는 Python으로 전달하지 않고 Spring이 검증한 최소 문맥만 전달한다. 서비스 인증과 사용자 권한을 혼동하지 않는다.
- 사용자 API의 경로·명령·인증 정책은 API 설계를 따르며 이 OpenAPI에 중복 추가하지 않는다.

합의 전 항목은 기본값으로 숨기지 않는다. 이 검토안과 OpenAPI의 구조 검사를 통과해도 팀 승인·실제 서버 인증·스트리밍·업무 저장 E2E 완료로 간주하지 않는다.
