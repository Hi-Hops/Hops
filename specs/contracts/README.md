# 공통 데이터·전송 계약

기준: [PRD 1.44](../../docs/PRD.md). 신규 `hops-system-1`은 팀 리뷰용 구현 계약안이며 승인·구현·통합 완료와 구분한다. 현재 코드의 `hops-mock-1`과 Python `agent-1`을 보존하고 프로필을 섞지 않는다.

## 정본과 읽는 순서

| 문서 | 이곳에서 관리할 내용 |
|---|---|
| [TARGET_CONTRACT](TARGET_CONTRACT.md) | 업무 관계·권한·상태 전이·중복 방지·공개 불변식 |
| [DATA_TYPES 신규 절](DATA_TYPES.md#신규-계약-hops-system-1) | 데이터 타입·enum·필드·필수성·nullable·명령 본문 |
| [WIRE_CONTRACT 12절](WIRE_CONTRACT.md#12-신규-계약-hops-system-1) | /api/v2 REST·오류·SSE·멱등성·복구 |
| [에이전트 계약 신규 절](../../contracts/agents/README.md#hops-system-1-실행-계약안) | Spring↔AI wire·내부 역할·검색·실행 한도 |
| [AI State 계약](../../contracts/agents/README.md#내부-state와-wire-검증-계약)·[JSON Schema](../../contracts/agents/hops-system-1.schema.json) | 내부 State 작성/병합·실행 전이·외부 DTO의 기계 검증 |
| [F001~F011](../README.md) | 화면·조작·AC·계약 검증 시나리오 |
| [DATA](../../docs/DATA.md)·[ERD](../../docs/ERD_설명.md) | 업무 저장 관계·유일성·잠금·outbox 제안 |
| [QUESTIONS](../QUESTIONS.md) | 계약 제안의 리뷰 상태와 실제 선정해야 할 모델·인프라·예산 |

공통 상태·오류·필드 표를 각 spec에 다시 정의하지 않는다. spec은 해당 정본을 참조하고 사용자에게 관찰되는 동작·완료 조건을 적는다. 제품 범위의 정본은 PRD다.

## 프로필과 실제 구현

| 프로필 | 용도 | 구현 상태 |
|---|---|---|
| hops-system-1 | PRD 1.44 전체 MVP의 /api/v2·신규 AI wire 계약안 | 이 문서 변경은 코드·DB·새 경로를 구현하지 않음 |
| hops-mock-1 | DATA_TYPES 전환 전 절·WIRE 1~11절·기존 mock-profile.json과 공용 Mock | 현재 프론트/테스트가 읽는 이전 부서 기반 데이터 |
| agent-1 | 현재 Python 내부 타입·Fake | Inquiry/Knowledge/Curator 신규 역할이 구현됐다는 의미가 아님 |

새 프로필은 시스템·주담당자, 5회 질문·대기, 무티켓 안내·문제별 해결 확인, 처리 기록, 사례 승인·동일 버전 두 저장소 반영을 모두 포함한다. 선점·수락·자동 재배정·무응답 종료·전용 복구 화면은 신규 필수 기능으로 추가하지 않는다.

## 예시와 전환

[신규 JSON 예시](../mocks/hops-system-1.examples.json)는 명령/상태 경계를 검토하는 가상 데이터다. 실행 Mock의 대체 파일·서버 응답·실제 승인 데이터가 아니다. 기존 mock-profile.json·accounts.json·fixtures.json·scenarios.json은 소비 코드와 함께 전환하기 전까지 그대로 둔다.

전환은 신규 API/DB/AI 구현 → 신규 어댑터·DTO → 프로필별 fixture/계약 검사 → 서비스 저장·권한 검사 → 실제 모델/검색 → 브라우저 E2E 순으로 검증한다. 전체 설계는 이번 계약에 포함하고 구현만 순서대로 진행한다. 통신 실패 시 다른 프로필이나 Fake로 자동 대체하지 않는다.
