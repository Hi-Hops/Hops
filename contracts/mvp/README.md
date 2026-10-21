# MVP 실행 데이터 계약

상태: #22 팀 리뷰용 초안. 팀 합의·서비스 연결·MVP 완료를 뜻하지 않는다.

병합된 [PRD v1.41](../../docs/PRD.md), [API 설계 3절](../../docs/API.md), [데이터 설계](../../docs/DATA.md)를 기준으로 Spring–Python 단일 에이전트 실행 계약을 구체화한다. 후속 멀티 에이전트의 `agent-1` 내부 역할 계약과 구분한다. 사용자 JWT·Socket.IO·제품 정책은 API 설계를 단일 기준으로 삼고 여기서 중복 정의하지 않는다.

## 범위와 완료 조건

| 파일 | 범위 |
| --- | --- |
| `schemas/run-request.schema.json` | `mvp-1` 실행 데이터, ID·버전·사용자 문맥·카탈로그·메시지·기존 문제/작업·예산 |
| `schemas/analysis-result.schema.json` | 단일 에이전트 답변·문제·추가 질문·복수 티켓 제안·메시지 근거 |
| `fixtures/` | 형식 검사용 가상 예시. 실제 계정·공통 기업 seed·평가 정답이 아님 |
| `openapi.json` | OpenAPI 3.1 형식의 접수·SSE·종료 결과 조회 경로 제안. JSON은 OpenAPI의 지원 문서 형식이며 경로 승인과 구분 |
| [서비스 연결 검토안](transport-review.md) | Spring–Python 서비스 인증·최종 결과·복구에 대한 리뷰 후보 |

이 변경의 검증 조건은 정상 예시 수용과 잘못된 ID·버전·enum·추가 필드·질문 누락·입력 길이·CS 검토 부서 오류 거부다. 두 JSON Schema는 데이터 형식을, OpenAPI는 미승인 전송 후보를 정의한다. `deadlineAt` fixture는 고정 시각이며 실제 실행에는 서버의 현재 제한 시각을 사용한다.

기존 `mvp-1`을 출발점으로 한 필드명·필수성·추가 필드 거부 규칙은 모두 리뷰 후보다. 최신 API는 특정 필드와 enum을 구현 전에 확정하도록 하므로, 이번 스키마를 승인된 공통 계약으로 취급하지 않는다. 문제·작업 status는 Spring 제공 문자열로 받고 과거 enum을 강제하지 않는다. 실행·모델 시간은 양의 정수, 재시도는 0 이상 정수로 검증하되 구체 상한은 미정이다. fixture의 90/30초·재시도 1회는 가상 검증값이며 공통 기본값이 아니다. 문제당 최대 질문 3회는 PRD 정책으로 유지한다.

`question`은 ASK_USER일 때 필수이며 그 외의 포함 금지는 새로 정하지 않는다. 메시지의 과거 `revisions`는 상세 형식이 정해지지 않아 이번 스키마에서 지원하지 않는다. 이를 전달하려면 형태·필수성·권한을 합의하고 스키마를 확장한다. 역할 문자열과 부서 코드는 임의 enum으로 제한하지 않는다. 임시 ref는 `new:` 접두어, 기존 ref는 UUID 형식을 검사한다.

JSON Schema만으로 다음을 검증할 수 없으므로 #24의 Python 검증 및 Spring의 서버 검증에서 확인해야 한다.

- 기존 문제·작업의 소유 문의, ref 중복과 실제 동일 작업 여부, problemRef 참조.
- 근거 메시지 ID/수정 버전의 실재, 제안 부서의 활성 카탈로그 포함 여부.
- 문제별 누적 질문 3회, 새 ref로 기존 문제를 복제해 한도를 초기화하는 행위.
- 현재 문의 버전·deadline·중복 이벤트·권한·취소·이미 저장된 티켓 여부.
- 모델/통신 오류를 CS 검토나 범위 밖으로 바꾸지 않는 실행 동작.

## 검증 실행

Python 3.13과 검증 전용 `jsonschema[format-nongpl]==4.25.1`을 사용한다. 날짜 형식 검사에 필요한 추가 의존성을 포함하며, 검사 스크립트도 잘못된 날짜의 거부 여부를 검증한다. AI 서버의 패키지 관리 도구·런타임 의존성 선택과는 별개다. uv가 설치된 환경의 예시는 다음과 같다.

```bash
uv run --no-project --python 3.13 --with 'jsonschema[format-nongpl]==4.25.1' python scripts/check_mvp_contracts.py
```

OpenAPI와 로컬 참조 검증은 다음 명령으로 실행한다.

```bash
uv run --no-project --python 3.13 --with 'jsonschema[format-nongpl]==4.25.1' --with openapi-spec-validator==0.7.2 python -c 'import json; from pathlib import Path; from openapi_spec_validator import validate_spec; p=Path("contracts/mvp/openapi.json").resolve(); validate_spec(json.loads(p.read_text()), spec_url=p.as_uri())'
```

형식 검증은 실제 모델 호출·Spring 저장·SSE·브라우저 E2E 검증을 대신하지 않는다.

## 계약 리뷰 범위와 담당 경계

아래는 미정 항목이다. 팀 결정 후 이 문서와 기계 판독 계약에 반영하고 리뷰한다.

| 항목 | 현재 문서 / 관련 작업 | 필요한 결정 |
| --- | --- | --- |
| Spring–Python | HTTP 실행+SSE 진행, 최종 결과는 미정 | 접수·스트림·최종 결과 경로와 외피, 서비스 인증, 종료·재연결·중복/늦은 결과 처리 |
| 요청·결과 타입 | 최신 API 3절의 최소 의미를 구체화한 후보 | 필드명·필수성·nullable·과거 메시지 형식·오류·서비스 카탈로그 전달 |
| 공유 타입·예산 | 역할/부서/상태는 서버 제공 문자열 | Spring·Python 소비자가 사용할 코드, 실행 상한·재시도·취소 |

JWT 수명·로그아웃·Socket.IO 명령/ACK·문의 분리 동의·저장 전용 응답은 [API 설계](../../docs/API.md)의 해당 담당자와 조율한다. #22의 이 변경에서는 이를 다시 설계하지 않는다. 기존 업무 문서가 다루는 기능 정책과 Spring–Python 타입을 연결하는 데 필요한 부분만 리뷰한다.

서비스 간 인증이나 미정 정책을 기본값으로 숨기지 않는다. 이 표가 남아 있는 동안 #22 전체 완료나 통합 계약 승인을 선언하지 않는다.
