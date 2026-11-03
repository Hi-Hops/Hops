# 전환 전 공용 Mock 데이터·시나리오

> **PRD 1.44 전환:** 아래 `hops-mock-1` JSON은 전환 전 부서 기반 계약의 보존 자료다. 현재 프론트·테스트는 이 JSON을 읽지 않으며 `hops-system-1`의 메모리 Mock을 사용한다. 가상 로그인·예시·실행법은 [Frontend 안내](../../frontend/README.md#mock으로-화면-개발)를 따른다. 아래 계정·시나리오·검사 설명은 이전 프로필의 기록이다.

상태: `hops-mock-1` · 2026-10-27 REST·SSE 기준 문서 반영 · 전부 가상 데이터 · **실제 인증·업무 저장·AI 품질 평가가 아님**

추가 프로필의 `message-history-empty`(MessageHistoryPage)·`run-retry-request`(RunRetryRequest) 예시를 fixtures.json에 포함한다. 최신 30개·이전 커서, 토큰 갱신, 분석 재시도는 WIRE_CONTRACT 10절을 따른다. 기존 messages/MessagePage 예제는 전방 조회 호환용이다. 실행 Mock의 시간 초과·모델 오류는 다시 분석 시 정상 진행을 재현하며 실제 체크포인트 저장이나 서버 쿠키 구현을 뜻하지 않는다.

이전 프로필에서는 같은 JSON을 얇은 테스트 어댑터로 읽는 방식으로 사용했다. 현재 프론트의 신규 DTO·카탈로그·시나리오는 `frontend/src/shared/api/systemMock*.ts`와 신규 계약 테스트에서 관리한다. 이 디렉터리의 JSON 자체는 Mock HTTP/SSE 서버가 아니다.

## 읽는 순서와 실행

1. [공통 계약](../contracts/README.md), [필드 표](../contracts/DATA_TYPES.md), [wire 규칙](../contracts/WIRE_CONTRACT.md)을 읽는다.
2. [accounts.json](accounts.json)에서 환경 변수와 CurrentUser/login fixture의 역할 매핑을 확인한다. 로그인 아이디·비밀번호는 frontend/.env에 직접 입력하며 공용 파일에는 저장하지 않는다.
3. [fixtures.json](fixtures.json)의 cases에서 id로 예제를 찾는다. 각 항목의 type은 공통 데이터 타입 문서의 타입 이름, value는 실제 전송 본문이다. wrapper(id/type/value)를 HTTP 본문으로 보내지 않는다.
4. [scenarios.json](scenarios.json)의 fixtureSequence 순서로 REST request/result·SSE·AI 결과·조회 결과를 테스트 어댑터에 공급한다. expected와 acceptanceCriteria를 테스트 이름·검증에 사용한다. fixtureSequence 자체가 실행 서버는 아니다.
5. [타입 문서](../contracts/DATA_TYPES.md)와 [입출력 규칙](../contracts/WIRE_CONTRACT.md)을 읽고 각 서비스의 테스트에서 공용 예시를 사용한다. 언어별 DTO·공통 코드 생성·별도 언어 왕복 검사는 제공하지 않는다.

프론트는 Mock REST/SSE 또는 테스트용 HTTP SSE 서버에서 응답을 공급한다. Spring은 기존 프로필의 JSON 역직렬화·REST 멱등성·SSE 이벤트 테스트에 사용할 수 있다. 현재 Python 골격은 이 fixture를 읽지 않으며 기존 부서 기반 실행·SSE 어댑터를 제공하지 않는다. 새 AI 계약을 합의할 때 소비 코드·예시·계약 테스트를 함께 연결한다.

## 계정·관계

| 계정 | 역할/부서 | 확인할 내용 |
|---|---|---|
| `VITE_MOCK_PREVIEW_ID` | 가상 업무부서, BIZ_ADMIN | 예시 문의의 소유자로 채팅·내 문의와 전체 부서 티켓을 한 계정으로 조회 |
| `VITE_MOCK_REQUESTER_ID` | 가상 업무부서, roles=[] | 자기 문의·채팅·결과 조회 |
| `VITE_MOCK_NETWORK_ID` | 네트워크, roles=[] | 네트워크 티켓만 목록·상세 조회 |
| `VITE_MOCK_BACKEND_ID` | backend, roles=[] | backend 티켓만 조회, 네트워크 상세 거부 |
| `VITE_MOCK_ADMIN_ID` | 가상 업무부서, BIZ_ADMIN | 두 부서 티켓 조회 허용. 문의 전체 대화는 별도 권한 아님 |

처리 부서 8개+비처리 요청자 부서 1개다. ACCOUNT_PERMISSION 등 부서 code는 Mock 카탈로그 제안이며 운영 카탈로그 확정이 아니다. 사용자 정보의 소속 부서와 티켓의 처리 부서를 구분한다.

`accounts.json.preview`는 단일 체험 계정 매핑이다. `user-preview`는 예시 문의 소유자인 `user-0`와 동일한 회원 ID에 BIZ_ADMIN을 부여한 Mock 전용 사용자 스냅샷이다. 체험 아이디와 공통 비밀번호만 설정하면 이 프로필로 로그인하고, 체험 아이디를 비우면 기존 네 역할의 계정을 사용한다. 두 로그인 모드는 혼용하지 않는다. 실제 서버 모드의 사용자·역할 응답은 변경하지 않는다.

기본 흐름의 inquiry-after-question은 질문 후 version=1, inquiry-completed-routing은 응답 후 version=2다. 전자는 티켓 없음, 후자는 같은 문제에 네트워크·backend 티켓 2개가 있다. inquiry-result는 별도 복합 문의 스냅샷으로 문제 3개(성공/실패/질문 중)를 동시에 표현한다. 시나리오마다 초기 상태를 리셋하며 서로 다른 스냅샷을 하나의 연속 실행으로 오해하지 않는다.

정정 시나리오는 version=2에서 시작해 version=3/revision=2로 바뀐다. 보완 저장 시나리오는 모든 문제의 이관 완료 상태 version=3에서 시작한다. 부분 실패 상태가 남은 inquiry-result에 SUPPLEMENT_SAVED를 적용하지 않는다.
## 검증 범위

이 JSON 예시만으로 실제 JWT·서버 동시성·DB 트랜잭션·서비스 인증·SSE 네트워크·브라우저·LLM 호출·모델 품질·E2E를 검증할 수 없다.

이 자료는 전환 전 계약의 재현 예시다. 신규 계약의 채택값은 [결정 대장](../QUESTIONS.md)을 따르며 이 JSON의 누락이나 이전 제안을 현재 미정 항목으로 해석하지 않는다.

`login-request`의 아이디·비밀번호 문자열은 JSON 형식 설명용 자리표시자이며 로그인 가능한 값이 아니다. 프론트 실행 값은 `.env`, 자동 테스트 값은 테스트 실행 시 생성한 임시 값을 사용한다.

## 확정 정책과 예시의 차이 (2026-10-25)

계약 정책은 WIRE_CONTRACT 10절, 실제 담당 구현·미검증은 11절을 따른다. fixtures는 모든 상태/오류·제어 이벤트를 포괄하지 않는다. 기존 실행 Mock의 Access 15분·메모리 Refresh는 확정한 실제 Access 15분·7일 절대 만료/회전 쿠키를 검증하지 않는다.

전환 전 프론트에서 `auth.invalidated`·`resync.required` 수신·복구와 429 대기 안내, 실제 Spring 부서 SSE의 eventId 단일 필드 수신을 검사한 이력이 있다. 현재 신규 계약의 UI·대역 검사와 구분하며 실제 업무 저장·권한·AI/검색 통합을 대신하지 않는다.

fixtures의 저장 응답은 전환 전 MutationResult, 오류는 ApiErrorBody, 연결 대상은 SubscriptionTarget이다. 현재 프론트의 저장 응답은 신규 CommandReceipt이며 이 자료의 MutationResult·당시 replay 목표를 사용하지 않는다.

## 신규 계약의 호출 예시와 검증

PRD 1.44 신규 기능은 [에이전트 계약의 호출과 진행 예시](../../contracts/agents/README.md#호출과-진행-예시)를 참고한다. 고정 가상 카탈로그는 [DATA 2절](../../docs/DATA.md#2-고정-카탈로그)에 seed 코드로 정의한다. 별도 예시 JSON 파일은 두지 않는다. 문서의 예시는 실제 확인·승인·서비스 통합 증거가 아니다.

기존 contracts/mock-profile.json·accounts.json·fixtures.json·scenarios.json은 참고용으로 보존한다. 현재 프론트는 신규 DTO·권한·시나리오와 hops-system-1 하나로 전환됐으며 계약 선택 설정을 제공하지 않는다. 실제 Spring·AI·검색 연동 검증은 별도다.

필수 전환 시나리오는 현재 PRIMARY 접근/회수, 5회 질문 중복 방지·대기 재진입, 복합 일부 성공, 저장 후 응답 유실·조회 불가·동시 재시도, 처리/구현 검증·문제별 확인·미해결/무응답, Curator 중복·초안 실패, 승인 버전·반영 부분 실패/미확인/불일치·양쪽 비공개·성공 뒤 권한 검색이다. 각 spec의 Fxxx-CONTRACT 번호와 연결한다.

AI 요청·결과·이벤트의 형식은 [에이전트 Markdown 계약](../../contracts/agents/README.md#s2-외부-wire-형식)을 기준으로 구현한다. 검증용 Schema는 공유 산출물에 포함하지 않으며 이 문서만 받아서 자동 검증이 실행되지는 않는다. 각 서비스는 Markdown의 호출 예시와 기능별 CONTRACT 시나리오를 바탕으로 구현에 맞는 검사를 작성하며 문서 코드만으로 서비스 검증을 대신하지 않는다.
