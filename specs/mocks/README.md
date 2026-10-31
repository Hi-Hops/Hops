# 공용 Mock 데이터·시나리오

> **PRD 1.44 전환:** 아래 `hops-mock-1` JSON은 현재 부서 기반 코드·테스트의 호환 데이터다. 새 3개 시스템·주담당자·확인·사례 공개 예제가 아니며 실제 기획의 검증 근거로 쓰지 않는다. 새 계약 합의 후 fixture·시나리오·소비 코드·테스트를 함께 전환한다.

상태: `hops-mock-1` · 2026-10-27 REST·SSE 기준 문서 반영 · 전부 가상 데이터 · **실제 인증·업무 저장·AI 품질 평가가 아님**

추가 프로필의 `message-history-empty`(MessageHistoryPage)·`run-retry-request`(RunRetryRequest) 예시를 fixtures.json에 포함한다. 최신 30개·이전 커서, 토큰 갱신, 분석 재시도는 WIRE_CONTRACT 10절을 따른다. 기존 messages/MessagePage 예제는 전방 조회 호환용이다. 실행 Mock의 시간 초과·모델 오류는 다시 분석 시 정상 진행을 재현하며 실제 체크포인트 저장이나 서버 쿠키 구현을 뜻하지 않는다.

언어별로 예시를 다시 타이핑하지 않고 같은 JSON을 읽는다. 프론트의 테스트 fixture는 이 파일을 import/복사 변환하는 얇은 어댑터로 작성하고 중복 데이터 원본으로 관리하지 않는다. 서버 코드·실제 Mock HTTP/SSE 서버는 이번 제출물에 포함하지 않는다.

## 읽는 순서와 실행

1. [공통 계약](../contracts/README.md), [필드 표](../contracts/DATA_TYPES.md), [wire 규칙](../contracts/WIRE_CONTRACT.md)을 읽는다.
2. [accounts.json](accounts.json)에서 환경 변수와 CurrentUser/login fixture의 역할 매핑을 확인한다. 로그인 아이디·비밀번호는 frontend/.env에 직접 입력하며 공용 파일에는 저장하지 않는다.
3. [fixtures.json](fixtures.json)의 cases에서 id로 예제를 찾는다. 각 항목의 type은 공통 데이터 타입 문서의 타입 이름, value는 실제 전송 본문이다. wrapper(id/type/value)를 HTTP 본문으로 보내지 않는다.
4. [scenarios.json](scenarios.json)의 fixtureSequence 순서로 REST request/result·SSE·AI 결과·조회 결과를 테스트 어댑터에 공급한다. expected와 acceptanceCriteria를 테스트 이름·검증에 사용한다. fixtureSequence 자체가 실행 서버는 아니다.
5. [타입 문서](../contracts/DATA_TYPES.md)와 [입출력 규칙](../contracts/WIRE_CONTRACT.md)을 읽고 각 서비스의 테스트에서 공용 예시를 사용한다. 언어별 DTO·공통 코드 생성·별도 언어 왕복 검사는 제공하지 않는다.

프론트는 Mock REST/SSE 또는 테스트용 HTTP SSE 서버에서 응답을 공급한다. Spring은 JSON 역직렬화·REST 멱등성·SSE 이벤트 테스트, Python은 입출력 어댑터 테스트에 같은 파일을 사용한다. 언어별 서비스가 아직 없으면 fixture 검사까지만 완료로 기록한다.

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

미정 계약은 Mock에서 재현 가능한 형태를 제공한 것이다. 질문이 팀에서 합의되면 데이터 타입·입출력 문서·fixture·시나리오·각 서비스 구현·관련 스펙을 함께 수정한다.

`login-request`의 아이디·비밀번호 문자열은 JSON 형식 설명용 자리표시자이며 로그인 가능한 값이 아니다. 프론트 실행 값은 `.env`, 자동 테스트 값은 테스트 실행 시 생성한 임시 값을 사용한다.

## 확정 정책과 예시의 차이 (2026-10-25)

계약 정책은 WIRE_CONTRACT 10절, 실제 담당 구현·미검증은 11절을 따른다. fixtures는 모든 상태/오류·제어 이벤트를 포괄하지 않는다. 기존 실행 Mock의 Access 15분·메모리 Refresh는 확정한 실제 Access 15분·7일 절대 만료/회전 쿠키를 검증하지 않는다.

프론트의 `auth.invalidated`·`resync.required` 수신·복구 분기와 429 대기 안내는 구현하고 Mock/HTTP·SSE 대역 테스트로 검사했다. 실제 Spring의 부서 SSE와 eventId 단일 필드 `resync.required` 수신도 검사했다. 이는 업무 REST 복구·서버 auth.invalidated 발행·쿠키 회전, 실제 업무 DB 유일성/트랜잭션·AI 예산 강제·품질/비용 검증을 대신하지 않는다.

fixtures의 저장 응답은 MutationResult, 오류는 ApiErrorBody, 연결 대상은 SubscriptionTarget이다. 응답 객체 자체를 사용하며 별도 성공 외피나 구독 응답은 요구하지 않는다. MutationResult는 프론트에서 구현한 REST 연동 프로필이며 백엔드의 동일 DTO·저장 API 구현은 Q-INF-6으로 추적한다. 스냅샷 커서·replay는 미구현이다.

## hops-system-1 검토 예시

[hops-system-1.examples.json](hops-system-1.examples.json)은 PRD 1.44 구체 계약안을 검토하는 신규 가상 예시다. meta.runtime=false이며 현재 프론트·AI가 import하지 않는다. 가상의 확인·승인을 실제 사용자 동작·실제 운영 결과로 해석하지 않는다. 타입은 [신규 DATA_TYPES](../contracts/DATA_TYPES.md#신규-계약-hops-system-1)를 따른다.

각 case는 독립 상태 스냅샷이다. 같은 ID의 PENDING/RESOLVED나 PRIVATE/SEARCHABLE 예시를 하나의 동시 DB 상태로 합치지 않는다. 순차 실행·지식 품질·실제 승인 검증 자료는 아니다.

| 자료 | 처리 |
|---|---|
| 기존 contracts/mock-profile.json | hops-mock-1 enum·타입·공백 규칙 보존 |
| 기존 accounts.json | 현재 부서 기반 실행 Mock 계정 보존 |
| 기존 fixtures.json / scenarios.json | 현재 테스트 소비 데이터 보존 |
| hops-system-1.examples.json | 세 시스템·업무 상태와 Spring↔AI 요청/결과/이벤트를 함께 담은 단일 검토 예시 |

이전 파일을 새 필드로 덮으면 현행 소비 코드가 깨지므로 신규 예시는 분리한다. 새 실행 Mock으로 전환할 때에는 profile 선택·신규 DTO·권한·시나리오 소비 어댑터와 함께 적용하고 기존 프로필의 테스트를 별도로 유지한다. 전체 기능의 설계/검증 범위를 이번 계약에서 미루지 않는다.

필수 전환 시나리오는 현재 PRIMARY 접근/회수, 5회 질문 중복 방지·대기 재진입, 복합 일부 성공, 저장 후 응답 유실·조회 불가·동시 재시도, 처리/구현 검증·문제별 확인·미해결/무응답, Curator 중복·초안 실패, 승인 버전·반영 부분 실패/미확인/불일치·양쪽 비공개·성공 뒤 권한 검색이다. 각 spec의 Fxxx-CONTRACT 번호와 연결한다.

AI 타입의 예시는 [wire JSON Schema](../../contracts/agents/hops-system-1.schema.json)로 검사한다. 업무 DTO 예시와 같은 cases 목록에 두어 별도 예시 파일을 중복 유지하지 않는다. 모든 예시는 독립 상태이며 실제 선택 모델·실행 예산·확인/승인 증거가 아니다.
