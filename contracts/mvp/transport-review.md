# MVP 서비스 연결 검토안

상태: #22 팀 리뷰 후보. 경로·서비스 인증·재연결 방식은 미승인 제안이며 구현 전에 관련 담당자가 확인한다. JWT와 Node.js/Socket.io 채택은 PRD에 반영하되 아래의 세부 전송 정책과 구분한다.

## 책임과 흐름

1. React는 Spring REST로 문의·정정을 접수하고 저장된 문의·실행·티켓을 조회한다.
2. Spring은 사용자 JWT와 업무 권한을 검사하고 문의 버전·runId·작업 ID를 영속화한다.
3. Spring은 Python에 실행을 요청하고 SSE를 구독한다. Python은 분석 결과를 제안한다.
4. Spring은 결과·최신 버전을 검증하고 작업별로 티켓과 부서를 저장한다.
5. Spring은 권한 범위의 진행·답변·저장 상태를 Node.js로 전달하고 Node.js가 Socket.io로 중계한다.
6. Socket.io 재접속 시 React는 Spring REST로 원본 상태를 복구한다. 중계 서버 메모리를 업무 상태 원본으로 사용하지 않는다.

Node.js가 연결을 받았다는 이유만으로 문의 room 구독을 허용하지 않는다. 문의자·처리 부서·관리자의 구독 범위는 Spring의 현재 권한 판단과 일치해야 한다. 검증·전달 방식, 권한 변경/토큰 만료 시 구독 해제와 Spring–Node.js 인증은 인증·중계 담당이 함께 정한다.

## Spring–Python 전송 제안

기존 접수 경로는 유지하고 결과도 SSE의 종료 이벤트로 전달하는 방식을 제안한다. 별도 callback과 SSE를 동시에 구현하지 않는다. [OpenAPI 초안](openapi.json)은 아래 세 경로만 기술하며 전체 업무 API나 서버 구현이 아니다.

| 경로 후보 | 의미 |
| --- | --- |
| `POST /internal/agent-runs` | 요청 접수 후 202와 runId·QUEUED 반환. 같은 runId·같은 정규화 요청은 기존 접수 결과, 다른 요청은 409 |
| `GET /internal/agent-runs/{runId}/events` | Spring 전용 SSE 구독. progress·result·failed 전달 |
| `GET /internal/agent-runs/{runId}/result` | SSE 종료 결과를 못 받은 Spring이 같은 실행의 보존 결과를 복구. 미완료는 202, 완료는 마지막 result/failed 외피 |

서비스 간 Bearer 자격 증명을 쓰는 안을 OpenAPI에 표현했지만, 이는 사용자 JWT의 재사용을 뜻하지 않는다. 자격 증명 종류·발급·교체·검증 방식은 통합 전에 정한다. 인증 실패는 401, 허용 범위 밖은 403이며 내부 오류 원문을 응답하지 않는다.

- 이벤트 외피는 기존 eventId·runId·inquiryId·inquiryVersion·schemaVersion·occurredAt·type·payload를 재사용한다. progress에는 공개 가능한 stage/message, result에는 분석 결과, failed에는 code/retryable을 담는 안이다.
- Python은 같은 실행의 종료 결과와 종료 eventId를 재시작 후에도 복구할 수 있게 보존해야 한다. 실행·중복 접수·결과 저장소의 구체 기술과 기간은 미정이다. 업무 DB 직접 쓰기를 허용하는 의미가 아니다.
- SSE 단절 시 새 runId로 다시 실행하지 않는다. 같은 runId의 결과 조회를 우선하고 미완료면 같은 실행에 재구독한다. MVP에서 진행 이벤트 전체 재생은 요구하지 않는 안이다. 보존 기간 경과 응답은 저장 정책과 함께 확정한다.
- 재구독의 종료 이벤트는 기존 eventId를 유지한다. Spring은 eventId 중복, runId·문의 소유 관계·최신 inquiryVersion을 검사하고 종료 결과를 한 번만 적용한다. 이전 문의 버전은 무시하고 기존 성공 티켓은 유지한다.
- Python 접수·진행·분석 성공과 Spring 저장 성공을 구분한다. 저장 결과만 실제 티켓 카드에 반영한다. 전체 deadline·취소·형식 복구와 모델 재시도는 역할 변경이나 재접속으로 초기화하지 않는다.

## 사용자 API에서 아직 고정하지 않은 부분

- JWT: #20의 헤더 전달·응답안을 검토하고 액세스/갱신 토큰·TTL·로그아웃·유휴 만료·CSRF를 합의한다. 기존 세션 60분/8시간을 임의로 JWT에 이식하지 않는다.
- 저장 전용 입력: 메시지 응답의 runId를 null로 둘지 실행 여부 필드를 추가할지 합의한다. 이관 완료 후에는 실행을 만들지 않으며, 미분석 문제는 계속 분석한다.
- 새 문의 분리: 사용자 동의 전에는 새 문의를 만들지 않는다. 동의 대상과 버전·멱등키·문맥 출처·거절/무응답 응답을 고정한다.
- 권한: 기술 복구 재시도는 운영 관리자, 부서·업무 판단 변경은 업무 관리자라는 경계를 유지한다. 목록 조회 범위와 재시도 권한은 별도로 정의한다.

합의 전 항목은 기본값으로 숨기지 않는다. 이 검토안과 OpenAPI의 구조 검사를 통과해도 팀 승인·실제 서버 인증·스트리밍·업무 저장 E2E 완료로 간주하지 않는다.
