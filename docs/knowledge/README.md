# HOPS 데이터·Knowledge 작업 안내

[PRD](../PRD.md)를 기준으로, 임직원의 IT 문의를 대화로 표준화하고 시스템 담당자의 처리 결과를 검증된 지식으로 재사용하기 위한 준비 문서다. 사용법·권한·현상 확인·기능 추가·개선을 함께 다룬다.

## 읽는 순서

1. [가상 기업·시스템 정본](VIRTUAL_COMPANY.md): 조직·직원·시스템·기능·담당의 정본, 데이터 계층(무엇을 DB에 넣고 무엇을 파일로 두는지), 기능별 정상 동작, 지식 문서·시드 사례·시나리오 카탈로그. seed·프론트·문서의 값이 다르면 이 문서를 따른다.
2. [데이터 구축 계획](DATASETS.md): 상담 대화·매뉴얼·해결 사례·관계·평가 데이터를 어떤 용도로 만들고 검수할지 확인한다.
3. [문의 양식](INQUIRY_FORMS.md): 문의 유형별 필수 정보, 모름 처리, 질문 한도 등 AI 서버가 따르는 규칙을 데이터 작성자 관점으로 정리한다.
4. [지식 데이터 형식](DATA_FORMAT.md): 가이드·정책·런북·기준 시나리오·시드 사례·그래프 시드를 만들 때 채울 값을 정리한다.
5. [평가 방식](EVALUATION.md): 성질 검사와 시나리오 반복 통과율, 개발/테스트 분할, 비교 실험 조건을 정리한다.
6. [선순환 E2E 실행 절차](FLYWHEEL_E2E.md): 문의 A → 사례 A 승인 → 문의 B를 로컬에서 한 번 끝까지 재현하는 방법과 알려진 공백을 정리한다.

제품 정책은 PRD, 역할과 서비스 경계는 [에이전트 계약](../../contracts/agents/README.md)과 [목표 공통 계약](../../specs/contracts/TARGET_CONTRACT.md)을 참조한다. 이 문서 묶음에서 별도 계약이나 제품 정책 사본을 만들지 않는다.

## 현재 상태와 다음 작업

현재는 데이터 구축을 다시 시작하는 설계 단계다. 가상 기업 정본이 조직·직원·담당(8명), 기능 10개의 합성 명세, 데이터 계층(회사 설정·지식 문서·시드 사례·평가 시나리오), 지식 문서 16건·시드 사례 6건·시나리오 21개의 카탈로그를 정했다. 이 문서들의 값은 PR 리뷰를 거쳐 병합되기 전까지 검토안이며 팀이 선정한 공통 데이터나 구현 완료를 뜻하지 않는다. 지식 문서 16건의 본문은 `corpus/`에, 시드 해결 사례 6건은 `cases/`의 카드에 작성했고 개발용 DB seed로 적재한다([갱신 절차](#코퍼스-갱신-절차)). 관계 데이터(증상·원인·확인 항목·질문 문구)는 코퍼스 선언과 `relations/`의 질문 카드로 만들어 적재한다. 상담 시나리오와 평가 정답은 아직 구축하지 않았다. 기존 외부 원문은 보존했지만 새 검색 대상으로 채택하지 않았다.

문의 양식·데이터 형식·평가 방식 문서(3~5번)는 데이터셋을 만들기 전에 팀이 기준을 먼저 합의하기 위한 검토안이며, 이 문서들이 PR 리뷰를 거쳐 병합된 뒤에 팀 기준으로 본다. 형식을 바꾸는 경우 해당 문서와 데이터를 같은 PR에서 갱신한다.

다음에는 카탈로그에 따라 지식 문서 원본과 DB 적재(#191), 상담 시나리오와 평가 정답(#174)을 만들고 실제로 쓸 수 있는지 확인한다. 그 결과로 데이터 형식과 수량을 조정한다. 각 작성·실행 작업의 범위는 착수 전에 설명하고 승인받는다.

자료 내용 검수, 팀 공통 데이터 선정, 제품 내 해결 사례 승인, DB 반영, 실제 모델 평가와 서비스 통합 검증은 각각 구분한다. 현재 코드의 구현 상태는 [AI 서버 안내](../../ai/README.md)에서 확인한다.

## 코퍼스 갱신 절차

지식 문서의 원본은 `corpus/`의 Markdown이고, 개발용 DB에는 이 원본에서 **자동 생성한** SQL(`backend/spring/hops/src/main/resources/db/seed/R__seed_dev_knowledge.sql`)로 들어간다. SQL을 직접 고치지 않는다.

문서를 고치거나 추가할 때:

1. `corpus/{guides,policies,runbooks}/{documentKey}.md`를 고친다. 새 문서는 [정본 6절](VIRTUAL_COMPANY.md#6-지식-문서-카탈로그) 카탈로그에 먼저 추가한다. 런북의 증상·원인·확인 항목은 [정본 6.1절](VIRTUAL_COMPANY.md#61-관계-카탈로그-증상원인확인-항목)에 있는 ID만 쓴다.
2. 이미 팀원 DB에 적재된 문서의 **내용을 바꾸면 front matter의 `version`을 올린다.** 올리지 않으면 이미 적재된 DB에는 반영되지 않는다(seed는 이미 있는 행을 바꾸지 않는다).
   - 모든 문서는 front matter의 `applicability`(이 문서를 어디에 쓰고 어디에 쓰지 않는지 한두 문장)가 필수다. 본문의 "적용 조건과 한계"·"적용 대상"에서 가져오고 없는 사실을 만들지 않는다. 직원 문서의 적용 조건에도 담당자 전용 금지어 검사를 한다.
   - `applicability`처럼 **메타데이터만** 바뀌어 문서 버전은 그대로인 변경은 이미 적재된 DB에 반영되지 않는다. 지식 검색 API는 이 값이 없는 기존 행에는 구조화된 값으로 대체 문구를 만들어 쓰므로 동작에는 문제가 없고, 코퍼스의 값을 받으려면 DB를 초기화해 다시 적재한다.
3. SQL을 다시 만든다. 코퍼스와 정본 카탈로그가 어긋나면 이 단계에서 오류로 알려 준다.

   ```bash
   uv run scripts/knowledge/build_knowledge_seed.py
   ```

4. 문서 파일과 다시 만든 SQL을 **같은 커밋**에 넣는다.
5. 어긋났는지만 확인하려면 `--check`를 쓴다. 파일은 바꾸지 않는다.

   ```bash
   uv run scripts/knowledge/build_knowledge_seed.py --check
   ```

6. 스크립트 자체의 테스트는 다음과 같다.

   ```bash
   uv run --no-project --with pytest --with pyyaml pytest scripts/knowledge
   ```

규칙:

- 문서 ID는 `uuid5(NAMESPACE_URL, "hops-knowledge.example/" + documentKey)`이고 버전 ID는 뒤에 `/v{version}`을 붙인다. documentKey를 바꾸면 ID가 바뀌므로 바꾸지 않는다.
- 저장하는 본문은 front matter를 뺀 Markdown이다. 줄바꿈은 LF로, 앞뒤 빈 줄은 없애고 끝은 개행 한 개로 맞춘 뒤 UTF-8 SHA-256을 `content_digest`로 쓴다. front matter만 바꾸면 digest는 바뀌지 않는다.
- 자동 검사는 필수 필드, 값, 본문 제목 구조, 참조 무결성, 정본 카탈로그와의 일치, 직원 문서의 담당자 전용 문구(금지어)까지만 본다. 통과가 내용 검수를 대신하지 않는다.
- 이미 팀원 DB에 있는 행을 바꿔야 하면 [Spring README의 "처음 상태로 되돌리기"](../../backend/spring/hops/README.md#처음-상태로-되돌리기)로 DB를 새로 만든다.

### 시드 해결 사례 카드

시드 해결 사례(과거에 해결됐다고 가정한 처리 이력과 승인된 사례)의 원본은 `cases/{caseKey}.yml` 카드이고, 카드에서 생성한 SQL(`R__seed_dev_knowledge_cases.sql`)이 DB에 들어간다. 이 SQL도 직접 고치지 않는다. 카드 형식은 [데이터 형식](DATA_FORMAT.md#시드-해결-사례-카드-docsknowledgecasesyml)에 있다.

1. 카드를 고친다. 새 사례는 [정본 7.2절](VIRTUAL_COMPANY.md#72-사례-6건)과 6.1절의 사례 연결에 먼저 추가한다.
2. SQL을 다시 만들고 카드와 함께 커밋한다.

   ```bash
   uv run scripts/knowledge/build_case_seed.py
   ```

3. 어긋났는지만 확인하려면 `--check`를 쓴다. 코퍼스가 바뀌었다면 위 문서 SQL의 `--check`도 함께 돌린다.

   ```bash
   uv run scripts/knowledge/build_case_seed.py --check
   ```

- 카드는 코퍼스의 근거 문서와 증상·원인·확인 항목 선언을 참조하므로, 코퍼스를 먼저 고친 뒤 카드를 고친다.
- 이미 적재된 사례를 고치면 팀원 DB에는 반영되지 않는다(seed는 이미 있는 행을 바꾸지 않는다). [Spring README의 "처음 상태로 되돌리기"](../../backend/spring/hops/README.md#처음-상태로-되돌리기)로 DB를 새로 만든다.
- 시드 사례는 승인 상태이고 저장소별 반영 요청을 보존했지만 AI에 전달하지 않았다. Vector·Neo4j에 색인하지 않았으므로 두 반영 상태가 `PENDING`이고, 현재 공개 규칙에서는 검색에 나오지 않는다([정본 10절 열린 문제 1](VIRTUAL_COMPANY.md#10-미정과-후속-반영)).
- 문의·티켓·사례 번호는 `SEED-` 접두사를 쓴다. 실제 번호 형식과 시퀀스를 건드리지 않기 위해서다.

### 관계 데이터

증상·원인·확인 항목과 그 연결은 코퍼스 런북·정책 front matter의 선언이 원본이고, 직원용 질문 문구는 `relations/questions.yml`, 사례 연결은 사례 카드의 `relation`이 원본이다. 이 셋에서 생성한 SQL(`R__seed_dev_relations.sql`)이 `relation_*` 테이블에 들어간다. 형식은 [데이터 형식](DATA_FORMAT.md#관계-카드-docsknowledgerelationsquestionsyml)에 있다.

1. 원본을 고친다. 증상·원인·확인 항목을 추가하면 [정본 6.1절](VIRTUAL_COMPANY.md#61-관계-카탈로그-증상원인확인-항목)에 먼저 쓴다. 직원 공개 확인 항목을 추가하면 질문 카드에 문구를 함께 쓴다.
2. 문서·사례·관계 SQL을 순서대로 다시 만들고 원본과 함께 커밋한다. 코퍼스를 바꾸면 세 SQL이 모두 바뀔 수 있다.

   ```bash
   uv run scripts/knowledge/build_knowledge_seed.py
   uv run scripts/knowledge/build_case_seed.py
   uv run scripts/knowledge/build_relation_seed.py
   ```

3. 어긋났는지만 확인하려면 각 스크립트에 `--check`를 쓴다.
- 질문의 근거는 확인 항목을 선언한 문서의 버전이다. 문서의 `version`을 올리면 관계 행의 근거가 새 버전을 가리키도록 SQL을 다시 만든다(이미 적재된 DB는 새로 만들어 적재한다).
- 연결은 후보다. 연결만으로 현재 문의의 원인을 확정하지 않는다.
- 새 마이그레이션(V25)이 필요하다. EC2 개발 서버에는 자동 적용되지 않으므로 배포 담당자와 적용 시점을 맞춘다.
