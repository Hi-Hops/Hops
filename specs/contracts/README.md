# 로그인·채팅·부서 티켓 공통 계약

버전: `hops-mock-1` · 작성: 2026-10-23 · **Mock 개발용 초안 / 팀 리뷰 전**

프론트(TypeScript), Node.js Socket.IO(TypeScript), Spring(Java 21), AI(Python 3.13+)가 같은 wire JSON으로 개발·검사하기 위한 원본이다. 사용자 요청으로 작성한 Mock 계약이며 팀 승인·실제 서버 연결·MVP 완료를 뜻하지 않는다. 제품 정책은 [PRD](../../docs/PRD.md), 역할 경계는 [API](../../docs/API.md)·[DATA](../../docs/DATA.md)를 따른다. 운영 정책과 스키마 후보를 혼동하지 않는다.

## 완료 조건과 범위

- F001 로그인·현재 사용자·로그아웃·만료 안내, F002 연결·구독·복구, F003 문의 생성·채팅, F004 정정, F005 문제·티켓·실패 결과, F009 목록·읽기 전용 상세를 Mock으로 연결할 자료를 제공한다.
- 완료 조건: 모든 JSON 예제가 같은 스키마로 검증되고, 네 언어 영역의 DTO가 원본에서 재생성 가능하며, 같은 문의·문제·작업·티켓 ID가 조회 예제에 연결되어야 한다.
- 부서 조회는 이번 Mock 개발 범위다. PRD 8.3과 API 2.1 사이의 출시 범위 확인(Q-ALL-7)을 팀 대신 확정하지 않는다.
- 내 문의 목록은 채팅 재진입용 최소 조회 예제만 포함한다. F006 상세 모달의 요약·부분 조회 계약, F007 관리자 화면, F008 새 문의 분리, F010 기술 복구는 이번 계약으로 완료하지 않는다.
- 가져오기·완료·재이관·전체/관련 대화·그룹·통계·운영 복구·실행 재시도 API는 제외한다. 시간 초과 때 상태 안내는 제공하지만 이번 Mock에서 다시 분석 버튼은 연결하지 않는다.

## 파일과 원본

| 파일 | 역할 |
|---|---|
| [contract.schema.json](contract.schema.json) | JSON Schema Draft 2020-12의 공통 데이터 원본. `$defs`에서 대상 타입을 선택 |
| [operations.json](operations.json) | 경로·이벤트·방향·요청/응답 타입 연결 |
| [WIRE_CONTRACT.md](WIRE_CONTRACT.md) | 필수/nullable, 권한, ACK, 버전·재전송·조각 순서·페이지 규칙 |
| [DATA_TYPES.md](DATA_TYPES.md) | 스키마에서 생성한 전체 필드 표 |
| [LANGUAGE_MAPPING.md](LANGUAGE_MAPPING.md) | 언어 타입과 내부 agent-1 매핑, 런타임 검증 책임 |
| [mock-profile.json](mock-profile.json) | Mock 전용 상태 코드·시계·기본값. 운영값 아님 |
| [types.ts](types.ts) | 프론트와 Node가 동일하게 사용하는 생성 타입 |
| [HopsContracts.java](HopsContracts.java) | Java 21용 생성 record·sealed interface |
| [contracts_dto.py](contracts_dto.py) | Python 생성 dataclass·Union·Literal |
| [../mocks/README.md](../mocks/README.md) | 공용 JSON·계정·시나리오와 재현 방법 |
| [VALIDATION.md](VALIDATION.md) | 실행 환경·검사 결과와 미실행 범위 |

생성 DTO를 직접 수정하지 않는다. Schema → `generate.py` → 타입·표를 함께 갱신한다. API의 외부 필드명은 모든 언어에서 camelCase로 유지한다. 각 서비스의 DB 모델·내부 클래스까지 동일하게 만들지는 않는다.

```text
python specs/contracts/generate.py
python specs/contracts/generate.py --check
python specs/contracts/validate.py
node specs/contracts/check-wire.mjs
```

검사기는 표준 라이브러리만 사용하며, 현재 스키마에서 사용한 키워드만 검증한다. 미지원 키워드는 실패한다. 공식 Draft 2020-12 전체 적합성 검증기나 언어별 실제 직렬화 테스트를 대신하지 않는다. 실제 통합 때 각 서비스의 JSON 직렬화/역직렬화와 이 fixture를 다시 검사한다.

check-wire.mjs는 Python·Node가 같은 fixture를 JSON으로 왕복했을 때 해시가 같은지 검사한다. Python 실행 경로가 다르면 HOPS_CONTRACT_PYTHON 환경 변수로 지정한다. 이는 TypeScript 타입 검사나 Java 직렬화 검증이 아니다.

## 담당별 사용

- 프론트: 생성 `types.ts`를 공통 API 타입의 원본으로 삼고, `src/test/fixtures/`에서 공용 JSON을 읽거나 변환한다. 미정 상태는 string이며 알 수 없는 상태를 안전하게 표시한다.
- Node: 프론트와 같은 타입을 사용하고 원본 payload를 임의로 변경하지 않는다. 사용자 연결·Spring 서비스 연결을 구분하며 현재 권한 확인 없이 room을 허용하지 않는다.
- Spring: 생성 Java DTO를 전송 경계에 적용한다. 권한·버전·멱등성·카탈로그·작업별 티켓 유일성·저장을 검증한다. AI 제안을 저장 결과로 변환한다.
- AI: 생성 Python DTO는 서비스 경계의 모양이다. 내부 `agent-1`은 어댑터에서 변환한다. DB 저장·실제 UUID 발급·업무 성공 확정은 하지 않는다.

## 미정 계약 처리

기존 API가 명시하지 않은 상세 필드·부서 표시·구독 응답·서비스 AI 경로는 이 Mock 프로필에서 테스트할 형태를 제안한 것이다. 스키마가 있다는 이유로 관련 질문을 확정 처리하지 않는다. Q-BE-1/2/3/4/5/6/7/10/12, Q-INF-3/5/6, Q-AI-1/2/3, Q-ALL-2/4/5/7을 팀 리뷰에서 추적한다. 역할·버전·키 책임의 제품 정책은 유지한다.

운영 전에는 JWT 갱신·폐기·CSRF, 서비스 인증·SSE 보존/재연결, 오류/상태 카탈로그, 처리 시간·예산, 물리 DB 모델과 실제 연결 검증을 별도로 합의한다.
