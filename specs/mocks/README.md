# 공용 Mock 데이터·시나리오

상태: `hops-mock-1` · 전부 가상 데이터 · **실제 인증·업무 저장·AI 품질 평가가 아님**

언어별로 예시를 다시 타이핑하지 않고 같은 JSON을 읽는다. 프론트의 테스트 fixture는 이 파일을 import/복사 변환하는 얇은 어댑터로 작성하고 중복 데이터 원본으로 관리하지 않는다. 서버 코드·실제 Mock HTTP/Socket 서버는 이번 제출물에 포함하지 않는다.

## 읽는 순서와 실행

1. [공통 계약](../contracts/README.md), [필드 표](../contracts/DATA_TYPES.md), [wire 규칙](../contracts/WIRE_CONTRACT.md)을 읽는다.
2. [accounts.json](accounts.json)에서 환경 변수와 CurrentUser/login fixture의 역할 매핑을 확인한다. 로그인 아이디·비밀번호는 frontend/.env에 직접 입력하며 공용 파일에는 저장하지 않는다.
3. [fixtures.json](fixtures.json)의 cases에서 id로 예제를 찾는다. 각 항목의 type은 공통 데이터 타입 문서의 타입 이름, value는 실제 전송 본문이다. wrapper(id/type/value)를 HTTP 본문으로 보내지 않는다.
4. [scenarios.json](scenarios.json)의 fixtureSequence 순서로 request/ACK/AI 결과/조회 결과를 테스트 어댑터에 공급한다. expected와 acceptanceCriteria를 테스트 이름·검증에 사용한다. fixtureSequence 자체가 실행 서버는 아니다.
5. [타입 문서](../contracts/DATA_TYPES.md)와 [입출력 규칙](../contracts/WIRE_CONTRACT.md)을 읽고 각 서비스의 테스트에서 공용 예시를 사용한다. 언어별 DTO·공통 코드 생성·별도 언어 왕복 검사는 제공하지 않는다.

프론트는 기존 개발 기준대로 fetch/가짜 소켓 또는 테스트용 Socket.IO 서버에서 응답을 공급한다. Spring은 JSON 역직렬화와 서비스 검증 테스트, Node는 이벤트·ACK 전달 테스트, Python은 입출력 어댑터 테스트에 같은 파일을 사용한다. 언어별 서비스가 아직 없으면 fixture 검사까지만 완료로 기록한다.

## 계정·관계

| 계정 | 역할/부서 | 확인할 내용 |
|---|---|---|
| `VITE_MOCK_REQUESTER_ID` | 가상 업무부서, roles=[] | 자기 문의·채팅·결과 조회 |
| `VITE_MOCK_NETWORK_ID` | 네트워크, roles=[] | 네트워크 티켓만 목록·상세 조회 |
| `VITE_MOCK_BACKEND_ID` | backend, roles=[] | backend 티켓만 조회, 네트워크 상세 거부 |
| `VITE_MOCK_ADMIN_ID` | 가상 업무부서, BIZ_ADMIN | 두 부서 티켓 조회 허용. 문의 전체 대화는 별도 권한 아님 |

처리 부서 8개+비처리 요청자 부서 1개다. ACCOUNT_PERMISSION 등 부서 code는 Mock 카탈로그 제안이며 운영 카탈로그 확정이 아니다. 사용자 정보의 소속 부서와 티켓의 처리 부서를 구분한다.

기본 흐름의 inquiry-after-question은 질문 후 version=1, inquiry-completed-routing은 응답 후 version=2다. 전자는 티켓 없음, 후자는 같은 문제에 네트워크·backend 티켓 2개가 있다. inquiry-result는 별도 복합 문의 스냅샷으로 문제 3개(성공/실패/질문 중)를 동시에 표현한다. 시나리오마다 초기 상태를 리셋하며 서로 다른 스냅샷을 하나의 연속 실행으로 오해하지 않는다.

정정 시나리오는 version=2에서 시작해 version=3/revision=2로 바뀐다. 보완 저장 시나리오는 모든 문제의 이관 완료 상태 version=3에서 시작한다. 부분 실패 상태가 남은 inquiry-result에 SUPPLEMENT_SAVED를 적용하지 않는다.
## 검증 범위

이 JSON 예시만으로 실제 JWT·서버 동시성·DB 트랜잭션·서비스 인증·SSE/Socket.IO 네트워크·브라우저·LLM 호출·모델 품질·E2E를 검증할 수 없다.

미정 계약은 Mock에서 재현 가능한 형태를 제공한 것이다. 질문이 팀에서 합의되면 데이터 타입·입출력 문서·fixture·시나리오·각 서비스 구현·관련 스펙을 함께 수정한다.

`login-request`의 아이디·비밀번호 문자열은 JSON 형식 설명용 자리표시자이며 로그인 가능한 값이 아니다. 프론트 실행 값은 `.env`, 자동 테스트 값은 테스트 실행 시 생성한 임시 값을 사용한다.
