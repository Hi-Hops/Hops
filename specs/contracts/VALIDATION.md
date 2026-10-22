# 공통 Mock 계약 검증 기록

실행일: 2026-10-23 · 대상: `hops-mock-1` 자료와 생성 코드

환경: Python 3.14.7, Node.js 24.20.0. 팀 목표 Python 3.13·Node.js 24.22.0에서의 재검증은 미실행이다. 현재 PATH의 Java는 11이므로 Java 21용 DTO 컴파일은 실행하지 않았다.

| 실행 | 결과 | 확인 범위 |
|---|---|---|
| `python specs/contracts/generate.py --check` | 통과 | 공통 정의에서 생성한 TS·Java·Python·필드 표 4개가 원본과 일치 |
| `python specs/contracts/validate.py` | 통과 | 정의 66개, fixture 94개, 작업 연결 21개, 시나리오 참조 16개, 잘못된 입력 거부 18개 |
| `python specs/mocks/replay.py` | 통과 | 메모리 대역의 멱등성·버전·정정·조각 순서·부서 권한·계정 전환 |
| `node specs/contracts/check-wire.mjs` | 통과 | Node·Python의 fixture 94개 JSON 왕복 SHA-256 일치 |
| Python 생성 DTO import | 통과 | dataclass/Union/Literal 선언 로드 |
| Node의 `--experimental-strip-types`로 types.ts import | 통과 | TypeScript 구문을 제거한 모듈 로드. tsc 타입 검사 아님 |
| Markdown 상대 링크 검사 | 통과 | specs·프론트 기준 문서의 상대 링크 대상 존재 |

다음은 미실행이다: 공식 Draft 2020-12 구현체의 전체 스키마 적합성, TypeScript tsc 검사, Java 21 컴파일·Jackson 직렬화, 실제 Spring·Node(Socket.IO)·Python 서비스 계약 테스트, 브라우저 화면·인증·DB·SSE·실제 모델·E2E.

`validate.py`는 저장소에서 사용한 Schema 키워드에 한정된 검사기다. 시나리오 16개 수치는 선언과 fixture 참조 검사이며 16개의 실제 네트워크 실행 테스트를 뜻하지 않는다. 의미 재현은 replay.py에 구현된 경우만 통과한 것으로 기록한다. 실제 코드에 이 DTO를 적용하면 담당별 빌드/직렬화/런타임 검증을 추가해야 한다.
