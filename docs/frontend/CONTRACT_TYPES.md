# 통신 타입과 공통 계약 사용 기준

상태: 초안 · 갱신 2026-10-23 · 대상: 프론트·Node·Spring·AI의 Mock 개발

로그인·채팅·부서 티켓 조회의 전송 데이터 원본은 [공통 계약](../../specs/contracts/README.md)의 [JSON Schema](../../specs/contracts/contract.schema.json)다. TypeScript만을 서버 계약의 원본으로 삼지 않는다. 이번 Mock 초안은 팀 승인·운영 계약 확정을 뜻하지 않는다. 기존 질문은 팀 리뷰까지 유지한다.

## 1. 생성 타입과 실제 코드

- [types.ts](../../specs/contracts/types.ts)는 프론트·Node 공용 생성 타입이며, 전체 필드 표는 [DATA_TYPES.md](../../specs/contracts/DATA_TYPES.md)다. Me는 CurrentUser로 명명한다. `/api/auth/me` 경로는 유지한다.
- 실제 프론트가 생기면 `src/shared/api/types/`에서 생성 타입을 재사용하거나 생성 결과를 배치한다. 생성 파일을 직접 고치지 않는다. 경로 배치 방식은 초기 세팅에서 정하고 `generate.py --check`와 같은 동기화 검사를 적용한다.
- 화면 전용 모델·상태 표시명·가공 타입은 기존 STRUCTURE 규칙대로 각 기능의 model에 둔다. 전송 타입과 섞지 않는다.
- TypeScript의 type/assertion은 JSON 수신 검증이 아니다. 실제 서비스 연동 때 Schema 검증과 언어별 직렬화 테스트를 적용한다. 사용할 런타임 검증 패키지는 이번 문서 작업에서 임의로 설치하지 않는다.

## 2. 계약 상태와 필수값

| 상태 | 의미 | 사용 |
|---|---|---|
| 확정 | 팀이 승인한 원본 계약 | 승인 근거와 버전을 추적 |
| 초안 | 기존 원본의 후보 경로·필드 | 관련 질문을 추적 |
| 미정 / Mock 제안 | 이번 프로필의 상세 필드·상태·기본값 | Mock에서 사용하고 운영 연결 전 합의 |

`@contract`, `@source`, `@proposal Q-…` 표기는 유지한다. 공통 생성 파일의 상태는 Mock 초안이며 관련 질문은 공통 README에 모은다. 질문 답변 없이 생성 타입 존재만으로 상태를 확정으로 바꾸지 않는다.

모든 객체 필드는 필수다. nullable은 `T | null`, 빈 배열은 `[]`. optional 키를 자동으로 추가하지 않는다. 보완 저장 ACK의 runId도 null을 명시한다. 이 규칙은 이번 Mock의 일관된 표현이며 운영 API 합의 시 변경할 수 있다.

문의/처리 상태 등 기존 값 목록이 있는 코드는 리터럴 union, 문제·배정·실행·오류 등 미정 코드는 string으로 유지한다. Mock 대표값은 [mock-profile.json](../../specs/contracts/mock-profile.json)만 참조하며 미등록 값 표시를 테스트한다.

## 3. HTTP·이벤트 계약

[operations.json](../../specs/contracts/operations.json)이 요청/응답/방향을 연결한다. 상세 의미는 [WIRE_CONTRACT.md](../../specs/contracts/WIRE_CONTRACT.md)를 따른다.

- REST: `shared/api/client.ts`를 통한 기능별 API 함수. 204는 JSON 파싱하지 않는다. 서버 데이터는 TanStack Query에 둔다.
- Socket.IO: 앱 공용 연결과 sendCommand를 사용하되, 저장 명령은 CommandAck, 구독 명령은 SubscriptionAck로 타입을 분리한다. 모든 명령을 같은 저장 ACK로 단언하지 않는다.
- 시간 초과/끊김은 전송 계층의 결과 미확인 상태이며 서버 ErrorAck가 아니다. 구현에서 별도 클라이언트 결과 타입으로 표현한다.
- 이벤트 맵은 operations.json의 이벤트명과 타입을 일치시킨다. 일반 WebSocket으로 대체하지 않는다. 구독 target은 INQUIRY/DEPARTMENT_TICKETS/TICKET이다.
- 동일 요청 재전송은 같은 requestId. 내용·expectedInquiryVersion을 고친 새 요청은 새 requestId다.
- 낮은 문의 버전·비활성 runId의 진행/답변 조각은 무시하지만, 저장 메시지·정정 이력·기존 티켓은 버리지 않는다. 조각의 순서 누락은 버퍼링하고 최종 REST 저장 메시지로 복구한다.

## 4. 공용 fixture

원본은 [specs/mocks](../../specs/mocks/README.md)의 JSON이다. 프론트 `src/test/fixtures/`에는 이를 읽거나 변환하는 얇은 어댑터를 둔다. 프론트용 예제를 별도 수동 복제해 공통 데이터와 어긋나게 하지 않는다. 실제 개인정보·비밀값은 넣지 않는다.

부서 정보에 Mock 제안 `isRoutable`, 티켓 카드에 `problemId/workItemId/ticketNumber`가 추가됐다. 티켓 상세 타입도 공통 원본에서 제공한다. 이전 문서의 최소 필드 예시를 그대로 복사하지 않는다.

## 5. 변경과 검사

1. 공통 계약 Schema·operation을 수정하고 미정 질문/승인 근거를 기록한다.
2. 공용 fixture·시나리오와 관련 스펙을 함께 수정한다.
3. `python specs/contracts/generate.py` 후 `python specs/contracts/validate.py`, `python specs/mocks/replay.py`를 실행한다.
4. 실제 프론트/Node가 생성된 뒤에는 각 프로젝트의 typecheck·테스트·빌드도 수행한다. 이 자료 검사만으로 앱/E2E 통과를 보고하지 않는다.

이 문서에서 이전에 제공한 수동 타입 전문·공통 client/socket 코드 예시는 공통 계약과 충돌하지 않도록 제거했다. 실제 client/socket 구현은 CONVENTIONS·공통 wire 계약을 따라 초기 세팅에서 작성한다.
