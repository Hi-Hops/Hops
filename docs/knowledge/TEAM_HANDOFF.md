# Knowledge 데이터셋 v0 팀 공유 안내

상태: 데이터 담당자 확정 · 팀 리뷰 전 · 2026-10-27
확인 역할: 데이터 담당자 (`DATA_OWNER`)

이 문서는 팀원이 개인 대화와 `.local` 작업 자료를 보지 않고도 Knowledge 데이터셋
v0의 범위, 사용 가능 상태, 리뷰 순서와 개발 경계를 확인하기 위한 진입점이다.

## 1. 이번 공유의 의미

이번 공유는 **팀 리뷰가 가능한 정본 후보를 제공하는 것**이다. 다음 완료를 의미하지 않는다.

- RAG 적재 또는 임베딩 완료
- Knowledge Agent 검색 품질 검증
- 읽기 MCP Tool 연결
- Demo Portal 장애 재현
- 실제 해결 CASE 생성
- dev/test 평가셋 완성

담당자 확정 자료도 작성자 외 팀원 1명 이상의 PR 리뷰를 받은 뒤에만 공통 정본으로
채택한다. 현재 모든 RAG 후보의 `ingest_status`는 `PENDING`이다.

## 2. 공유 데이터 현황

| 데이터 | 수량 | 현재 상태 | v0 사용 목적 |
|---|---:|---|---|
| 가상 기업 | 서비스 4·부서 8 | 담당자 확정·팀 리뷰 대기 | 서비스·부서·권한 경계 |
| 공개 근거 검수 | 일반 62·PORTAL 31 | 담당자 확정·팀 리뷰 대기 | 파생 문서의 기술 근거 |
| 직원 가이드 | 13 | 담당자 확정·팀 리뷰 대기 | 직원에게 인용 가능한 안전한 안내 |
| 담당자 런북 | 11 | 담당자 확정·팀 리뷰 대기 | 증상·원인 후보·신호·이관 근거 |
| 서비스 정책 | 5 | 담당자 확정·팀 리뷰 대기 | 문의·권한·이관·CASE 운영 규칙 |
| IT 용어 | 50 | 담당자 확정·팀 리뷰 대기 | 직원·담당자 공통 용어 정의 |
| 진단 신호 | 55 | 담당자 확정·팀 리뷰 대기 | 런북·Tool·평가 연결용 정본 |
| 시나리오 계약 | 34 | 담당자 확정·팀 리뷰 대기 | Knowledge·Demo·업무 흐름 평가 준비 |
| 실제 해결 CASE | 0 | 정상 | 재현·복구·재시험 후 누적 |
| 평가 질의 | 0 | 미작성 | 이후 dev/test 각 30건 목표 |

로컬 생성 결과는 RAG 청크 163건이다. 청크와 통합 manifest는 정본 JSON에서 다시 만들
수 있으므로 Git에 올리지 않는다.

## 3. 먼저 읽는 순서

1. [가상 기업 환경](VIRTUAL_COMPANY.md): 직원 환경, 서비스, 부서, Agent 금지 작업
2. [데이터셋 범위](DATASETS.md): Knowledge 범위와 실제 재현 범위
3. [장애·문의 시나리오](INCIDENT_SCENARIOS.md): 같은 증상·다른 원인과 평가 정답 경계
4. [Knowledge–Diagnosis 계약](KNOWLEDGE_DIAGNOSIS_CONTRACT.md): Agent 단일 책임과 Evidence Bundle
5. [지식 수명주기](KNOWLEDGE_LIFECYCLE.md): 런북·CASE·평가 데이터 개선 규칙
6. [팀 검수 체크리스트](REVIEW_CHECKLIST.md): 승인 전 사람이 확인할 항목

개별 데이터는 [직원 가이드](guides/), [담당자 런북](runbooks/),
[서비스 정책](policies/), [IT 용어집](references/IT_GLOSSARY.md),
[진단 신호](references/ERROR_SIGNALS.md)에서 확인한다.

## 4. 개발 파트별 사용 경계

### Knowledge Agent

- 직원 답변은 `EMPLOYEE/QUOTABLE` 청크만 인용한다.
- 진단 지원은 권한 검증 후 `STAFF/INTERNAL_ONLY` 런북과 향후 CASE를 검색한다.
- 신호 레지스트리를 별도로 검색하지 않는다. 런북의 `causes` 청크에 결합된 정의를 사용한다.
- 검색 결과를 `KnowledgeEvidenceBundle`로 구조화하며 해결·부서·티켓을 확정하지 않는다.

### Diagnosis Agent

- Vector DB와 신호 레지스트리를 직접 검색하지 않는다.
- Knowledge가 반환한 Evidence Bundle과 읽기 MCP 관측 결과만 비교한다.
- 근거가 부족하거나 안내 후 미해결이면 담당 부서 이관을 제안한다.
- 코드·설정·배포·재시작·DB 쓰기와 자동 복구를 수행하지 않는다.

### 읽기 MCP Tool

- 런북 진단 단계와 신호 레지스트리를 참고해 필요한 읽기 필드를 설계한다.
- 원시 로그·개인정보·비밀값 대신 마스킹된 관측값을 반환한다.
- Tool 결과가 없거나 불명확한 상태도 명시적으로 반환한다.
- 수정·재시작·세션 종료·권한 변경 Tool은 v0 범위가 아니다.

### Spring Backend·Ticket

- 인증·문서 접근 범위·Tool 허용 목록을 실행 전에 검증한다.
- Agent의 부서·티켓 초안을 업무 규칙으로 다시 검증한다.
- 티켓 생성·배정·재이관·해결 완료와 CASE 승격을 최종 확정한다.
- 숨겨진 시나리오 정답을 Agent 입력이나 RAG에 전달하지 않는다.

### Demo Portal

- Hops와 분리된 Docker Compose 서비스로 구성한다.
- 첫 실제 재현은 `PORTAL_API`·`PORTAL_DB` 장애로 제한한다.
- IDENTITY·VPN·FILE_SHARE는 지식 답변과 Mock 진단 범위이며 모두 실재현하지 않는다.
- 장애 트리거는 사용자 증상과 관측값만 노출하고 숨겨진 원인 정답은 노출하지 않는다.

## 5. 팀 리뷰 방법

네 명이 동시에 검토할 때 다음과 같이 나누면 중복을 줄일 수 있다. 담당 배정이 아니라
권장 분담이며, 최종 승인은 PR 리뷰로 기록한다.

| 검토 묶음 | 확인 대상 | 핵심 질문 |
|---|---|---|
| 환경·정책 | 가상 기업, 정책 5건, 수명주기 | 실제 서비스 흐름과 Spring 확정 책임이 맞는가 |
| 직원 경험·권한 | 직원 가이드 13건, 용어 50건 | 직원에게 안전하고 이해 가능한가 |
| 진단 지식 | 런북 11건, 신호 55건 | 같은 증상의 원인을 구분하고 단정하지 않는가 |
| 시나리오·계약 | 시나리오 34건, Agent 계약, Schema | 정답 누수 없이 Agent·Tool 구현에 사용할 수 있는가 |

검토자는 [팀 검수 체크리스트](REVIEW_CHECKLIST.md)에 따라 다음 중 하나를 남긴다.

- 승인: 현재 내용으로 팀 공통 후보 사용 가능
- 변경 요청: 문서·필드·표현과 수정 이유 명시
- 보류: 해제 조건과 담당자 명시

## 6. 로컬 검증

저장소 루트에서 실행한다.

```bash
python3 -m pip install -r datasets/knowledge/v0/requirements.txt
python3 datasets/knowledge/v0/scripts/build_and_validate.py
```

성공 기준:

```text
KNOWLEDGE DATASET V0 OK: {'runbooks': 11, 'guides': 13, 'policies': 5, 'glossary_terms': 50, 'error_signals': 55, 'general_source_reviews': 62, 'general_source_sections': 82, 'scenarios': 34, 'chunks': 163}
```

명령은 로컬 `chunks/`, `manifest.json`, `manifests/chunk_review_summary.json`을 다시
생성한다. 이 세 경로는 검증 산출물이며 PR에 포함하지 않는다.

## 7. PR에 포함하는 것

- `docs/knowledge/`: 설계, 검토용 Markdown, 체크리스트
- `datasets/knowledge/v0/catalogs/`: 가상 기업·용어·신호·시나리오 정본
- `datasets/knowledge/v0/documents/`: 가이드·런북·정책 정본
- `datasets/knowledge/v0/manifests/`: 공개 근거·사람 판정·섹션 접근 자료
- `datasets/knowledge/v0/schemas/`: 구현 계약용 JSON Schema
- `datasets/knowledge/v0/scripts/`: 파생 데이터 생성·불변식 검증 코드

다음은 포함하지 않는다.

- `.local/`의 원문 수집 캐시·개인 보고서·실험 결과
- 공개 문서 원문 전체 복사본
- 재생성 가능한 `chunks/`와 통합 manifest
- 임베딩 벡터·Vector DB 파일
- 비밀값·실제 개인정보·실제 운영 로그와 티켓

검수자 필드는 실명 대신 역할 코드 `DATA_OWNER`를 사용한다. 실제 승인자는 GitHub PR
리뷰 이력으로 확인한다. `sha256`은 공개 원문과 파생 데이터의 변경 여부를 확인하는
무결성 값이며 비밀값이나 개인정보를 익명화하는 용도로 사용하지 않는다.

## 8. 팀 리뷰 후 처리

1. 변경 요청을 정본 JSON에 반영한다.
2. 생성·검증 명령을 다시 실행한다.
3. 승인된 문서만 `team_review_status=APPROVED`로 변경한다.
4. 실제 임베딩 대상만 `ingest_status=APPROVED`로 변경한다.
5. Knowledge Agent용 인덱싱·검색 평가를 별도 단계로 시작한다.

Tool 연결, Demo Portal 재현, CASE 생성, 평가셋 구축은 팀 문서 승인과 별개의 후속 작업이다.
