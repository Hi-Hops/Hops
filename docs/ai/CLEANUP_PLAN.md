# AI 서버 정리 계획

AI 서버 코드와 가상 기업 데이터를 정리하는 작업의 진단·결정·단계를 한곳에 남긴 **검토안**이다. 작업 이슈는 [#171](https://github.com/Hi-Hops/Hops/issues/171)이 추적한다. 이 문서는 PR 리뷰를 거쳐 병합된 뒤에 팀 기준이 되며, 병합 전까지는 AI 담당자의 제안이다. 제품 정책은 [PRD](../PRD.md), 역할 경계는 [에이전트 계약](../../contracts/agents/README.md), 공통 필드·전송은 [공통 계약](../../specs/contracts/README.md)이 정하며 이 문서가 대체하지 않는다.

진단 수치는 `develop` `17db1bd`(#168 병합 직후) 기준이다. 코드가 바뀌면 수치도 달라지므로 [재현 명령](#8-재현-명령)으로 다시 확인한다.

## 1. 목적과 범위

- 1차: 프론트 → 백엔드 → AI 서버 → 에이전트의 협업 판단과 백엔드 통제, 답변 표시와 과정 저장, 멀티턴, 해결 뒤 표준화한 지식의 재사용이 일반 문의에서 동작하게 한다.
- 2차: 에이전트 성능과 데이터 구조(pgvector 임베딩, Neo4j 관계)를 개선할 수 있는 바탕을 만든다. 이 문서의 작업은 2차 구현을 포함하지 않는다.
- 이 정리는 Supervisor가 모델 판단으로 다음 처리를 고르는 PRD 정책(`docs/PRD.md` 228행)을 바꾸지 않는다. 가드의 위치와 일반성이 대상이다.

## 2. 진단

검토 범위: `ai/src/hops_ai/`(약 9,150줄)와 테스트(약 14,650줄), Spring `inquiry/`·`agent/`·`knowledge/`의 AI 결과 처리 경로 일부, 프론트 Mock 데이터. 정리 전 기준선은 AI 테스트 1006 통과·24 건너뜀·1 예상 실패, `ruff` 통과(`uv run pytest`, `uv run ruff check .`)다. 이 기준선은 Fake 모델 테스트이며 실제 모델 품질이 아니다.

| ID | 진단 | 근거 |
|---|---|---|
| F1 | Supervisor 가드가 `supervisor/validation.py`에 모이고 `agents/`가 `supervisor`를 함수 안에서 늦게 import한다(7개 파일 23곳). 순환 의존을 import 시점 우회로만 풀고 있다. 최근 1주(10/29 이후) `ai/`를 바꾼 fix 커밋 39개 중 7개가 제목 기준으로 "이 상태에서 이 역할을 고르지 못하게" 제한하는 수정이다. | `agents/inquiry/agent.py` 4, `knowledge/agent.py` 5, `ticket/model.py` 5, `ticket/executing.py` 4, `ticket/agent.py` 2, `curator/agent.py` 2, `base.py` 1 |
| F2 | 사용법(`KNOWLEDGE_QA`) 편향이 있다. `KNOWLEDGE_QA` 분기가 8개 파일 14곳이다. `GUIDE_FIRST_FIELDS`·`HANDOFF_FIELDS`는 HOW_TO만 정의한다. Supervisor 프롬프트(`supervisor.decision.v6`)와 추출 프롬프트(`inquiry-extraction-v5`)에 사용법 전용 단락이 있다. | `agents/inquiry/forms.py`, `supervisor/decision.py`, `agents/inquiry/extraction.py` |
| F2-a | 시스템 코드 `Literal`이 3곳에 있어 새 시스템은 코드를 고쳐야 한다. 입력(`AiRunRequest.systems`)에 지원 시스템 목록이 이미 온다. | `contracts/wire.py:211`, `agents/inquiry/extraction.py:41`, `services/retrieval/fixture_documents.py:13` |
| F2-b | 질문 한도 5가 `forms.MAX_QUESTIONS_PER_PROBLEM`(28행)이 있는데도 숫자로 반복된다. | `supervisor/decision.py:196`, `supervisor/validation.py:264·274·525` |
| F3 | Knowledge는 사용법 문제 1건에 가이드 1개만 만들고 `CASE_DRAFT` 실행은 거부한다. 정책·런북·승인 사례를 질문이나 티켓 근거로 쓰는 경로가 없어 해결 사례 재사용이 AI에서 끊겨 있다. | `agents/knowledge/agent.py` |
| F4 | Ticket 구현이 3종(`RuleTicketAgent`, `ModelTicketAgent`, `ExecutingTicketAgent`)이고 처리 가능 문제 선택 로직이 중복된다. `ExecutingTicketAgent`는 호출마다 `ModelTicketAgent`를 새로 만든다. `SupervisorPipeline`은 `ticket_client`가 있으면 TICKET 등록을 묵시적으로 덮어쓴다. 레지스트리가 2개(`build_registry`, `build_runtime_registry`)이고, 호출하면 미구현 오류를 내는 자리 클래스(`services/retrieval/pgvector.py`, `neo4j.py`)와 빈 패키지(`evaluation/`, `indexing/`)가 있다. | `agents/ticket/`, `agents/registry.py`, `services/runs/supervisor.py` |
| F5 | 계약이 AI(`contracts/wire.py`, 683줄), Spring DTO, 프론트(`types/hopsSystem1.ts`)에 따로 있다. 세 구현을 같은 예시로 자동 대조하는 검사를 찾지 못했다(확인 범위: 계약 문서와 `mock-profile.json` 참조 검색이며 각 스택의 테스트를 전수 확인하지는 않았다). | 이번 라운드에서는 보류(6절) |
| F6 | 멀티턴의 서버 상태가 구현되지 않았다. `inquiry_problem`을 만드는 곳이 `GuideResultWriter` 하나뿐이고, `clarifyCount`는 읽는 메서드만 있어 증가시키는 곳이 없으며, 메시지 `payload.questionBundles`는 항상 빈 목록이고, `InquiryAnswerHandler`는 `problemProposals`·`questions`를 저장하지 않는다. AI도 `plan_next` 호출에 `asked`를 넘기지 않고 `resolutionContext`(UNRESOLVED)를 읽지 않는다. 문의 생성·정정·문제 연결·멱등성은 병합됐다(#127, #145, #153, #108). | Spring `GuideResultWriter.java:108`, `AiAnswerMessageWriter.java:54`, `UserMessagePayload.java:54`, `InquiryAnswerHandler.java`; AI `agents/inquiry/agent.py:193` |
| F7 | 유지할 것: "모델은 제안하고 코드는 검증한다"(원문 인용 대조, 검색 오류와 0건 구분), harness(예산·재시도·취소)와 역할 로직의 분리, 약 4초에 끝나는 테스트. | - |
| F8 | 가상 회사 정의가 여러 곳에 흩어져 있고, 지식 문서 테이블(`knowledge_document`, `knowledge_document_version`)은 있으나 행이 없다. Spring에는 지식을 읽어 주는 내부 API가 없다(내부 API는 `ticket-creations`뿐). | [가상 회사 정본](../knowledge/VIRTUAL_COMPANY.md) |

읽지 못했거나 훑기만 한 곳: `executor`, `RunLedger`, harness 내부, Curator 내부, `finalize`, Spring `agent/` 패키지 전체, 프론트의 AI 응답 처리. 위 진단에는 이 부분의 문제가 포함되지 않았다. 해당 부분을 바꾸는 이슈에서 읽고 판단한다.

## 3. 결정

AI 담당자가 정한 구현 방향이다. 이 문서의 리뷰에서 이의가 나오면 해당 항목을 다시 정한다.

| ID | 결정 | 이유 |
|---|---|---|
| D1 | Supervisor는 모델 판단 라우터를 유지하고, 역할 선택 가드는 각 역할의 `available_for`로 옮긴다. | PRD 정책 유지. 새 역할·의도를 추가할 때 Supervisor를 고치지 않게 한다. |
| D2 | Rule 기반 `RuleTicketAgent`를 삭제하고 테스트는 Fake 모델로 대체한다. | 같은 규칙이 Rule과 Model에 중복된다. |
| D3 | 의도별 정책(필수 필드, 가이드 우선, 인계 필드, 처리 유형, 티켓 가능 여부, 검색할 지식 종류)은 코드 안의 타입 있는 표로 둔다. | 타입 검사가 된다. 설정 파일화는 필요해질 때 2차에 한다. |
| D4 | 1차 검색은 Spring이 DB의 승인 문서를 읽는 SQL(키워드·FTS)이다. pgvector 임베딩과 Neo4j는 `KnowledgeSource` 뒤의 2차 교체다. | 인터페이스가 이미 있어 교체 비용이 작다. |
| D5 | 문제당 질문 한도의 정본은 `forms.py`의 상수 하나다. | 숫자 중복 제거. |
| D6 | 평가 하네스는 `ai/evaluation/`에 둔다. 성질 목록은 [평가 방식](../knowledge/EVALUATION.md)(P1~P9, B1~B6)을 따른다. | 정답 파일이 AI 런타임에서 읽히지 않는 위치. |
| D7 | 증상 문의에서 런북과 승인 사례는 "확인 질문 후보와 티켓 근거"까지만 쓴다. 원인 확정에는 쓰지 않는다. | 같은 증상·다른 원인의 방해 사례를 현재 원인으로 확정하지 않기 위해. |
| D8 | 동작을 바꾸지 않는 정리와 동작이 바뀌는 변경은 브랜치를 분리한다. | 리뷰어가 변경 성격을 구분하고 되돌리기 쉽다. |

## 4. 정리 단계

작업 이슈 9개를 한 브랜치·커밋 여러 개·PR 1개로 처리한다. 커밋마다 테스트가 통과하게 하고 PR 설명에 커밋별 요약을 쓴다.

| 단계 | 이슈 | 내용 | 동작 변경 |
|---|---|---|---|
| 1 | #172 | 이 문서와 가상 회사 정본 | 없음(문서) |
| 1 | #174 | 평가 하네스, 시나리오 20개(개발 12·테스트 8), 정리 전 실모델 기준선 | 없음 |
| 2 | #191 | seed 정리, 지식 문서 원본, 문서에서 seed 생성·적재 | 데이터 추가 |
| 2 | #176 | AI 구조 정리: Ticket, 레지스트리, 순환 import, 역할 가드 | **없음(불변)** |
| 3 | #194 | Spring 지식 읽기 API, AI `SpringKnowledgeSource` | 추가 |
| 3 | #181 | 의도별 정책 표, 프롬프트 일반화, 시스템 코드 동적화(AI), 호출 한도 | **있음** |
| 4 | #189 | 멀티턴: 문제·질문 저장, 재실행 입력, UNRESOLVED 처리 | 있음 |
| 4 | #198 | Knowledge 일반화, 증상 문의의 런북·사례 활용 | 있음 |
| 5 | #201 | 시드 사례와 선순환 E2E | 검증 |

처리 순서는 같은 단계 안에서 병렬로 진행할 수 있다. #176은 #174의 하네스 커밋이 병합된 뒤에, #181은 #176이 병합된 뒤에 시작한다. 동작이 바뀌는 #181은 정리 전 기준선과 같은 조건으로 재측정해 안전 성질 위반 0건을 확인하고, 실행하지 않은 검증을 PR에 구분해 쓴다.

## 5. 합의와 검토 원칙

- 계약이나 공유 코드를 바꾸는 변경은 사전 합의를 기다리지 않고 **하위 호환 PR**로 올려 해당 영역 담당자의 리뷰를 받는다. 이 문서의 결정(D)이나 AI 제안은 팀 승인이 아니다.
- 이미 계약에 정의돼 있는데 AI가 읽지 않는 필드(`resolutionContext`)는 AI 쪽 변경만으로 처리한다.
- #189처럼 Spring과 AI를 함께 바꾸는 작업은 착수 전에 관련 담당자에게 범위를 알리고 같은 파일을 건드리는 진행 중 작업을 조정한다.
- 개인 메모·실험 자료는 이 정리의 근거로 링크하지 않는다. 팀이 채택한 데이터와 병합된 문서만 근거로 쓴다.

## 6. 이번 라운드에서 하지 않는 것

선순환과 멀티턴 목표에 필수가 아니어서 미룬다. 필요해지면 새 이슈로 올린다.

- 계약 3중 복사의 자동 대조 테스트(F5)
- `AiResult.guide`를 문제별 안내 목록으로 확장
- Spring·프론트의 시스템 코드 제한 완화(AI 쪽은 #181에서 한다)
- 프롬프트의 파일 분리와 버전 관리 체계
- 그래프 시드(Cypher)와 Neo4j 검색 구현, pgvector 임베딩 검색(2차)
- Curator가 Knowledge를 사용하는 범위(`CASE_DRAFT` 실행의 Knowledge 사용은 계속 거부한다)

## 7. 한계

- 정리 전 기준선과 진단 수치는 Fake 모델 테스트와 정적 분석 기준이다. 실제 모델 동작은 #174의 실모델 기준선에서 따로 측정한다.
- F6은 코드 검색으로 확인한 결과다. Spring README와 아직 병합되지 않은 브랜치는 보지 않았다.
- 진단에서 제외한 읽지 못한 범위(2절)가 있다.

## 8. 재현 명령

저장소 루트에서 실행한다.

```bash
# F1: agents/가 supervisor를 함수 안에서 import하는 곳 (23곳, 7개 파일)
grep -rnE "^\s+from hops_ai.supervisor" ai/src/hops_ai/agents | wc -l

# F2: KNOWLEDGE_QA 분기 (14곳, 8개 파일)
grep -rn "KNOWLEDGE_QA" ai/src | wc -l

# F2-a: 시스템 코드 Literal (3곳)
grep -rn 'Literal\["ELECTRONIC_APPROVAL"' ai/src

# F2-b: 질문 한도 숫자 중복
grep -rnE "5 - |>= 5" ai/src/hops_ai/supervisor

# F4: Ticket 구현 3종, 레지스트리 2개
grep -rnE "^class (RuleTicketAgent|ModelTicketAgent|ExecutingTicketAgent)" ai/src
grep -rnE "^def build_(runtime_)?registry" ai/src

# F6: 문제를 만드는 곳 (1곳), 질문 묶음 저장 (항상 빈 목록)
grep -rn "new InquiryProblem(" backend/spring/hops/src/main/java
grep -rn "questionBundles" backend/spring/hops/src/main/java

# 기준선
cd ai && uv run pytest -q && uv run ruff check .
```
