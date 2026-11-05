# 지식 데이터 형식

가이드·정책·런북·기준 대화 시나리오·시드 해결 사례·그래프 시드·평가 정답을 만들 때 쓸 **형식 검토안**이다. 실제 레코드를 만들기 전에 팀이 형식에 먼저 합의하려는 문서이며, 수량과 내용은 [데이터 구축 계획](DATASETS.md)과 [가상 기업 명세](VIRTUAL_COMPANY.md)를 따른다. 업무 정본의 테이블·컬럼은 [물리 ERD](../ERD.md), 검색 근거의 전송 형식은 [공통 계약](../../specs/contracts/README.md)이 정한다. 이 문서는 둘을 대체하지 않고, 작성 단계에서 어떤 값을 채워야 두 곳에 그대로 옮길 수 있는지만 정리한다.

지식 문서 원본은 `docs/knowledge/corpus/`에 두고 파일명을 `documentKey`로 한다. 시나리오와 평가 정답은 `ai/evaluation/`에 둔다. 문서·시나리오 목록은 [가상 기업 정본](VIRTUAL_COMPANY.md)의 카탈로그를 따른다.

## 공통 원칙

- 모든 레코드는 합성인지 표시한다(`origin`). 사람이 승인한 합성 사례도 운영 사례로 표시하지 않는다.
- 검색 자료와 평가 정답은 파일을 분리한다. 평가 정답과 미래 처리 결과는 모델 입력과 검색 자료에 들어가지 않는다.
- 직원 공개와 담당자 전용을 문서 단위로 분리한다. 직원 가이드 본문에 담당자 런북 내용을 섞지 않는다.
- 외부 자료에서 가져온 표현·사실은 출처·라이선스·변경 내용을 기록한다. 외부의 라벨과 답변은 정답으로 쓰지 않는다. CC BY는 표기하고, CC BY-NC는 쓰지 않는다.
- 개인정보·실제 조직도·비밀값을 넣지 않는다.
- 한 레코드의 식별자는 바뀌지 않는다. 내용이 바뀌면 버전을 올린다.

## 값 대응

Spring 저장값, AI 계약 값, 작성 단계 값이 이름이 다르다. 데이터에는 **계약(AI) 값**을 쓰고, Spring 적재 때 아래 대응으로 옮긴다.

| 항목 | 작성·AI 계약 값 | Spring 저장 값 | 비고 |
|---|---|---|---|
| 문서 종류 | `GUIDE`, `POLICY`, `RUNBOOK`, `CASE` | `EMPLOYEE_GUIDE`, `POLICY`, `STAFF_RUNBOOK`, `CASE` | 이름만 다름 |
| 공개 범위 | `EMPLOYEE`, `SYSTEM_STAFF` | `EMPLOYEE`, `STAFF` | `TicketKnowledgeReader`가 변환 |
| 출처 구분 | `SYNTHETIC`, `DEMO_VERIFIED`, `OPERATIONAL` | `SYNTHETIC`, `DEMO_VERIFIED`, `PRODUCTION` (사례) | 대응이 같은 의미인지 3b에서 확인 |
| 용도 분할 | 문서: `RAG`, `DEV`, `EVAL` | 같음 | 시나리오 분할은 별도(아래) |

## 직원 가이드·업무 정책·담당자 런북

본문은 Markdown, 메타데이터는 맨 위 front matter에 둔다. Spring `knowledge_document`와 문서 버전에 옮길 수 있는 값이다.

```markdown
---
documentKey: guide-ea-document-search     # 안정 식별자. 규칙: {종류}-{시스템}-{주제}
kind: GUIDE                                # GUIDE | POLICY | RUNBOOK
title: 전자결재에서 지난 문서 찾기
systemCode: ELECTRONIC_APPROVAL            # ELECTRONIC_APPROVAL | COMMON_LOGIN | FILE_SHARING | 공통이면 생략
accessScope: EMPLOYEE                      # RUNBOOK은 SYSTEM_STAFF만
version: 1
appliesToSystemVersion: null               # 기능 버전을 구분할 때만
dataSplit: RAG
origin: SYNTHETIC
reviewStatus: PENDING                      # 사람이 검수하면 APPROVED
source:
  type: AUTHORED                           # AUTHORED | ADAPTED
  reference: null                          # ADAPTED이면 출처·라이선스·변경 내용
---

## 목적
## 전제
## 절차
## 확인할 수 있는 결과
## 적용 조건과 한계
```

종류별 본문 요구는 다음과 같다.

| 종류 | 본문에 반드시 있는 것 | 넣지 않는 것 |
|---|---|---|
| 가이드 | 사용자 목적, 전제(필요한 권한 등), 번호 있는 절차, 관찰 가능한 결과, 적용 조건 | 담당자만 아는 원인·조치·내부 시스템 상태 |
| 정책 | 규칙, 적용 대상, 예외, 신청 절차(있으면), 근거 | 절차를 임의로 만들어 규칙처럼 서술 |
| 런북 | 증상, 확인 항목(순서), 가능 원인 후보, 각 확인 항목의 결과 해석, 적용 조건 | 확인되지 않은 원인을 확정 표현. 직원에게 보여 줄 문장 |

### 증상·원인·확인 항목 선언 (`symptoms`, `checks`)

런북은 front matter에 증상, 가능 원인, 원인을 가르는 확인 항목을 **ID와 함께 선언**한다. 이 선언이 관계 데이터(증상 → 원인 → 확인 항목)의 단일 원본이다. 범위와 ID는 [가상 기업 정본 6.1절](VIRTUAL_COMPANY.md#61-관계-카탈로그-증상원인확인-항목)이 정한다. 처리 지연처럼 오류가 아닌 승인 단계가 증상인 경우에 한해, 근거가 되는 정책이 같은 선언을 가질 수 있다.

```yaml
symptoms:
  - id: sym-ea-doc-not-listed              # sym-{시스템}-{주제}. 직원이 말하는 현상이며 원인을 담지 않는다
    label: 문서가 문서함에 보이지 않음
    featureCode: DOCUMENT_VIEW             # 증상이 나타나는 기능. systemCode의 기능이어야 한다
    causes:
      - id: cause-ea-period-out-of-range   # cause-{시스템}-{주제}
        label: 기안일이 조회 기간 밖
        checks: [check-ea-draft-date, check-ea-search-period]   # 원인을 가르는 확인 항목 ID
checks:                                    # 이 문서가 정의하는 확인 항목
  - id: check-ea-draft-date                # check-{시스템}-{주제}
    label: 문서를 기안한 시기
    employeeDisclosable: true              # 직원이 화면·설정에서 스스로 알 수 있는 사실이면 true
```

- 확인 항목은 **한 문서에서만 정의**하고, 다른 문서의 원인이 같은 항목을 쓰려면 `checks` 목록에서 ID로 참조한다. 정의되지 않은 ID를 참조하거나 정의만 하고 어느 원인도 쓰지 않으면 오류다.
- 질문 문구(`QuestionTemplate`)는 여기에 쓰지 않는다. 직원 공개 확인 항목마다 관계 카드가 질문 문구를 둔다.
- 선언은 메타데이터라서 본문 digest와 검색 본문에 들어가지 않는다. 본문에는 같은 내용을 사람이 읽는 문장으로 쓰고, 선언한 모든 ID를 한 번 이상 적어 두 곳이 어긋나는 것을 막는다.
- 증상 label은 직원이 쓰는 표현으로 쓰고 원인을 암시하지 않는다.

화면 경로·버튼명·기본 조회 기간처럼 [가상 기업 명세](VIRTUAL_COMPANY.md)에서 아직 정하지 않은 값은 가이드를 쓰기 전에 먼저 정하고 Mock과 맞춘다. 정하지 않은 사실을 절차에 지어 넣지 않는다.

## 기준 대화 시나리오

한 시나리오는 한 사건 계열의 대화 입력이다. 표현을 바꾼 변형은 같은 사건 계열로 묶으며 독립 시나리오 수에 더하지 않는다.

```json
{
  "scenarioId": "S-ea-search-time-unknown-001",
  "caseFamily": "ea-search-time-unknown",
  "split": "DEV",
  "origin": "SYNTHETIC",
  "actor": { "role": "EMPLOYEE", "primarySystemCodes": [] },
  "firstMessage": "지난달 결재 문서가 안 보여요",
  "userKnowledge": {
    "systemCode": "ELECTRONIC_APPROVAL",
    "taskOrScreen": "전자결재 문서 조회 화면에서 지난달 문서를 찾으려 함",
    "occurredAt": null,
    "expectedBehavior": "지난달에 상신한 문서가 목록에 보임"
  },
  "unknownFields": ["occurredAt"],
  "revealRule": "ASKED_FIELD_ONLY"
}
```

| 필드 | 설명 |
|---|---|
| `split` | `DEV`(조정에 쓰는 개발용) 또는 `TEST`(동결). `TEST`는 코드·프롬프트 조정에 쓰지 않는다. |
| `caseFamily` | 같은 사건에서 나온 변형을 묶는 식별자. 분할은 사건 계열 단위로 한다. |
| `userKnowledge` | 사용자가 아는 사실. 질문을 받기 전에는 공개하지 않는다. |
| `unknownFields` | 사용자가 모른다고 답할 필드. 원문에 모름 표현을 둔다. |
| `revealRule` | 사용자 모사 규칙. `ASKED_FIELD_ONLY`는 AI가 물은 필드에 해당하는 값만 답한다. |
| `corrections` | (선택) `{afterTurn, text}` 목록. AI가 `afterTurn`번째 질문을 한 뒤의 응답 끝에 `text`(정정 문장)를 덧붙인다. 한 turn에 하나. |
| `followUp` | (선택) `{text, resolution}`. AI가 질문 없이 안내로 끝낸 뒤 사용자가 보내는 응답이다. `resolution`은 현재 `UNRESOLVED`만 쓴다. 재생기 지원은 멀티턴 작업(#189)에서 하며, 그 전에는 파일에 두고 첫 실행만 재생한다. |
| `faults` | (선택) 환경에 주입할 결함 목록. 현재 `RETRIEVAL_ERROR`(검색 서비스 오류)만 있다. 재생기는 역할을 만들지 않으므로 호출자가 이 값을 보고 검색 역할을 구성한다. |

`userKnowledge`의 필드 이름은 `systemCode`와 질문 필드(`taskOrScreen` 등)다. 시스템을 모른다는 선언은 `unknownFields`에 `systemCode`로 쓰고, 사용자 모사는 시스템 질문(`system`)에 모름으로 답한다. 값은 사용자가 그대로 말할 문장이다. 복합 문의는 같은 필드에 문제마다 다른 답을 줄 수 없으므로, 필드별 답이 두 문제에 모두 맞도록 쓴다.

사용자 답변은 모델이 아니라 정해진 규칙으로 만든다. 같은 질문에는 같은 답이 나가야 반복 실행의 차이가 AI 쪽에서만 생긴다.

시나리오에는 다음 상황이 빠지지 않게 분포를 확인한다. [데이터 구축 계획](DATASETS.md)의 필수 상황과 같다: 모호한 최초 문의, 모름, 정정, 안내 후 미해결, 사용법과 처리 요청 구분, 보고 시스템과 책임 시스템 차이, 복합 문의, 시스템 미확인·범위 밖, 권한 거부·검색 0건·검색 오류. 질문 한도는 현재 규칙에서 시나리오로 도달시킬 수 없어 검사기 단위 테스트로만 확인한다([가상 기업 정본](VIRTUAL_COMPANY.md) 8절).

## 시드 해결 사례

승인된 재사용 지식의 초기 값이다. 처리 기록에서 Curator가 만든 초안이 아니라 **검수해서 승인 상태로 시작하는 합성 사례**다. 이 사실을 `origin`으로 남긴다.

```json
{
  "caseId": "case-ea-search-period",
  "caseVersion": 1,
  "caseType": "RESOLUTION",
  "origin": "SYNTHETIC",
  "status": "APPROVED",
  "systemCode": "ELECTRONIC_APPROVAL",
  "accessScope": "SYSTEM_STAFF",
  "symptom": "과거 문서가 조회 목록에 나오지 않음",
  "confirmedCause": "조회 기간 조건이 문서 작성일을 포함하지 않음",
  "action": "조회 기간을 문서 작성일이 포함되도록 조정",
  "verification": "조정 후 목록에 문서가 표시됨",
  "applicability": "조회 기간이 원인으로 확인된 경우에 한함. 기간이 맞는데 문서가 없으면 적용하지 않음",
  "searchText": "…",
  "contentDigest": "…",
  "sourceRef": "synthetic-authored"
}
```

- `searchText`(검색용 문장)와 원문 digest를 분리한다. 승인 본문 전체를 그대로 임베딩하지 않는다. 개인 실험에서 전체 JSON을 검색 입력으로 쓰면 원문보다 1위 적중이 낮았다. 팀 채택 전 참고이므로 4b 단계에서 다시 측정한다.
- `applicability`는 반드시 쓴다. 같은 증상·다른 원인의 사례가 방해 사례로 같은 묶음에 있어야 한다.
- 승인 사례는 `caseId + caseVersion`으로 고정하고 승인 후 수정하지 않는다. 바꾸려면 버전을 올린다.
- 검색 공개 조건(문의자 확인, 주담당자 승인, Vector·Neo4j 양쪽 반영)은 [데이터 구축 계획](DATASETS.md)을 따른다.

### 지식 선순환 검증 묶음

문서만 늘리지 않고 한 묶음으로 만든다: 첫 문의 A → 검수한 사례 A → 표현이 다른 후속 문의 B → 같은 증상·다른 원인의 방해 문의 C. 초안·승인·반영 상태가 다른 자료도 포함한다. B와 C의 정답과 미래 처리 기록은 평가 파일에만 둔다. 이 묶음의 평가 방식은 [평가](EVALUATION.md)에서 다룬다.

## 그래프 시드

Cypher 파일로 저장소에 두고 반복 적재해도 같은 결과가 나오게 한다(`MERGE`와 유일성 제약).

| 노드 | 의미 |
|---|---|
| `System`, `Feature` | 가상 기업 명세의 시스템·기능 |
| `Symptom`, `Cause`, `CheckItem` | 증상, 가능 원인, 확인 항목 |
| `QuestionTemplate` | 확인 항목을 묻는 질문 문구 |
| `Resolution` | 승인 사례(`caseId + caseVersion`) |

관계 유형은 고정한다. 이름은 4c 단계(`feature/142-neo4j-retrieval`)에서 스키마와 함께 확정하며, 이 문서는 필요한 의미만 정한다: 기능의 소속 시스템, 시스템 간 의존, 증상이 나타나는 기능, 증상의 가능 원인, 원인을 가르는 확인 항목, 승인 사례가 해결한 증상.

- 모든 노드와 관계는 근거(출처 문서 ID·버전)와 공개 범위를 가진다. 근거가 없는 연결은 만들지 않는다.
- 연결은 후보다. 연결만으로 현재 문의의 원인을 확정하지 않는다.
- 새 증상·원인·확인 항목은 가상 시스템에 실제 정의된 기능에 근거가 있을 때만 추가한다. 모델이 그럴듯하게 만들었다는 이유로 저장하지 않는다.

## 평가 정답

모델 입력, 검색 자료, 시드 사례와 **별도 파일**로 관리하고 AI 런타임이 읽지 못하는 위치(`ai/evaluation/golds/`)에 둔다. 시나리오는 `ai/evaluation/scenarios/`에 한 파일씩 둔다. 항목과 판정 방식은 [평가](EVALUATION.md)에서 정한다.

## 검수 항목

사람이 레코드마다 확인한다: 원문과 사실 일치, 허구의 절차·관계·원인 여부, 공개 범위, 합성 표시, 적용 버전, 참조 ID 존재, 출처·라이선스. 자동 검사는 참조 무결성(문서·시스템·사례 ID), 필수 필드, 직원 문서에 담당자 전용 문구 포함 여부(금지어 목록)까지만 한다. 자동 검사 통과가 내용 검수를 대신하지 않는다.
