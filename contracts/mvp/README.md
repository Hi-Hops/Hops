# MVP 실행 데이터 계약

상태: #22 팀 리뷰용 초안. 팀 합의·서비스 연결·MVP 완료를 뜻하지 않는다.

현재 병합된 PRD와 MVP API의 단일 에이전트 실행 요청 및 분석 결과를 JSON Schema Draft 2020-12로 옮긴다. 후속 멀티 에이전트의 `agent-1` 내부 역할 계약과 구분한다. 문서 이름이 바뀌면 [프로젝트 개발 안내](../../README.md#첫-작업)에서 연결하는 최신 API 설계와 대조한다. PR #15의 미병합 설계가 현재 기준을 자동 대체하지 않는다.

## 범위와 완료 조건

| 파일 | 범위 |
| --- | --- |
| `schemas/run-request.schema.json` | `mvp-1` 실행 데이터, ID·버전·사용자 문맥·카탈로그·메시지·기존 문제/작업·예산 |
| `schemas/analysis-result.schema.json` | 단일 에이전트 답변·문제·추가 질문·복수 티켓 제안·메시지 근거 |
| `fixtures/` | 형식 검사용 가상 예시. 실제 계정·공통 기업 seed·평가 정답이 아님 |
| `openapi.json` | OpenAPI 3.1 형식의 접수·SSE·종료 결과 조회 경로 제안. JSON은 OpenAPI의 지원 문서 형식이며 경로 승인과 구분 |
| [서비스 연결 검토안](transport-review.md) | JWT·Socket.io 책임 경계와 서비스 인증·최종 결과·복구에 대한 리뷰 후보 |

이 변경의 검증 조건은 정상 예시 수용과 잘못된 ID·버전·enum·추가 필드·질문 누락·입력 길이·CS 검토 부서 오류 거부다. 두 JSON Schema는 데이터 형식을, OpenAPI는 미승인 전송 후보를 정의한다. `deadlineAt` fixture는 고정 시각이며 실제 실행에는 서버의 현재 제한 시각을 사용한다.

스키마는 기존 필드·분류·문제/작업 상태·90초 실행 예산을 전사한 검토안이다. `question`은 ASK_USER일 때 필수이며 그 외의 포함 금지는 새로 정하지 않는다. 메시지의 선택적 과거 `revisions`는 원문 문서에 상세 형식이 없어 이번 스키마에서 지원하지 않는다. 역할 문자열과 부서 코드를 임의의 공통 enum으로 확정하지 않는다. 임시 ref는 `new:` 접두어, 기존 ref는 UUID 형식을 검사한다.

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

## 통합 전 결정할 사항

아래는 미정 항목이다. 팀 결정 후 이 문서와 기계 판독 계약에 반영하고 리뷰한다.

| 항목 | 현재 문서 / 관련 작업 | 필요한 결정 |
| --- | --- | --- |
| 사용자 인증 | JWT 채택, #20 구현 담당 | 전달·만료·로그아웃·무효화·CSRF·기존 세션 정책 매핑 |
| 실시간 중계 | Node.js/Socket.io 채택, #17 문서 담당 | 중계 주체·인증 주체·조회 권한 검사·REST 복구 책임 |
| Spring–Python | HTTP 실행+SSE 진행, 최종 결과는 미정 | 접수·스트림·최종 결과 경로와 외피, 서비스 인증, 종료·재연결·중복/늦은 결과 처리 |
| 저장 전용 입력 | 이관 완료 후 자동 재분석 금지 | 실행 없음 응답, 관련 티켓 메시지 연결·권한, 해결 확인 응답 분리 |
| 새 문의 분리 | 별개 문의는 동의 후 생성 | 동의 대상 버전·중복 방지·문맥 출처·거절/무응답 처리와 MVP 적용 계약 |
| 공유 타입 | 역할/부서 문자열, 기존 상태 후보 | Spring·Python 소비자가 사용할 코드와 마이그레이션 호환성 |

서비스 간 인증이나 미정 정책을 기본값으로 숨기지 않는다. 이 표가 남아 있는 동안 #22 전체 완료나 통합 계약 승인을 선언하지 않는다.
