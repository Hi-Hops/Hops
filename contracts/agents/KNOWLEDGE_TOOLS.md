# 지식 검색 내부 REST 계약

Knowledge는 Spring의 내부 REST로 승인된 지식 문서를 읽는다. 업무 정본·접근 범위·공개 규칙은 Spring이 확정하고, AI는 접근 범위가 확정된 승인 문서만 받는다. 모델 출력이나 AI 쪽 필터를 권한 확인으로 해석하지 않는다. 1차 검색은 임베딩 없이 PostgreSQL SQL로 하며 `KnowledgeSource` 뒤에서 2차에 교체한다([AGENT_ARCHITECTURE](../../docs/ai/AGENT_ARCHITECTURE.md) 8절, [CLEANUP_PLAN](../../docs/ai/CLEANUP_PLAN.md) D4).

이 문서는 팀 리뷰를 거쳐 병합된 뒤 기준이 된다. 병합 전까지는 구현 제안이다. 문서 검색은 아래 본문, 증상→확인 항목→질문 문구를 찾는 **관계 조회**는 "관계 조회" 절이 정한다. 두 API는 같은 인증·실행 검사·접근 범위 규칙을 쓴다.

## 구현 상태

Spring의 지식 검색 API와 관계 조회 API, 내부 판단 경계(옵션, 기본 꺼짐)를 구현했다. AI 쪽 `SpringKnowledgeSource`·`SpringRelationSource`(`HOPS_AI_KNOWLEDGE_SOURCE=spring`)는 같은 PR의 커밋 ④에서 추가하므로 지금 AI는 이 API들을 호출하지 않는다. 관계 조회의 AI 쪽 사용(질문 생성·평가)은 #181 범위다.

## 인증

[Ticket 도구 계약](TICKET_TOOLS.md)의 인증을 그대로 쓴다. `/internal/v2/**`는 `SPRING_SERVICE_TOKEN` Bearer만 허용하며 사용자 JWT·쿠키와 분리한다. 요청 본문에는 사용자·권한·접근 범위 필드가 없다. **알 수 없는 필드가 있으면 400 INVALID_INPUT으로 거부**하므로 `accessScope`·`purpose`·`systemIds` 확대 같은 우회 시도는 받아들여지지 않는다.

## 접근 범위는 실행에서 확정한다

Spring은 `runId`로 저장된 실행 입력(`input_snapshot`)을 읽어 문의자·`policyRevision`·`purpose`·`accessContext`를 다시 확인한다. 검색에 쓰는 공개 범위와 시스템 범위는 이 값에서만 정해진다.

| 실행 입력 `allowedDocumentScopes` | 읽을 수 있는 공개 범위 | 읽을 수 있는 종류 |
|---|---|---|
| `["EMPLOYEE"]` (직원 상담의 기본) | `EMPLOYEE` | `GUIDE`, `POLICY` |
| `SYSTEM_STAFF` 포함 | `STAFF`도 포함 | `RUNBOOK`, `CASE`도 포함 |

- 시스템 범위는 실행 입력의 `systemIds`다. 요청의 `systemIds`는 이 범위를 **좁히기만** 한다.
- 직원 상담 실행이 담당자 전용 문서를 읽는 것은 [AGENT_ARCHITECTURE 9절](../../docs/ai/AGENT_ARCHITECTURE.md)의 옵션 프로필(아래 "내부 판단 경계")이며 서버 설정으로만 켠다. 이 API는 실행 입력에 없는 범위를 어떤 요청으로도 넓히지 않는다.
- 실행이 접수된 문의의 현재 실행이고 `QUEUED`·`RUNNING`이며 문의 버전이 같고 기한 전이어야 한다. 아니면 4절의 오류 응답이다.

## API

`POST /internal/v2/runs/{runId}/knowledge-searches`

요청 본문(알 수 없는 필드는 거부):

```text
inquiryVersion: 양의 정수. 실행의 문의 버전과 같아야 한다
problemRef: 실행 입력의 기존 문제 UUID 또는 new:[A-Za-z0-9_-]+ (최대 100자)
query: 검색어 1~4000자. 공백만 있으면 거부
topK: 1~5, 생략하면 5
kinds: 선택. GUIDE | POLICY | RUNBOOK | CASE 중 1개 이상. 생략하면 접근 범위 안의 모든 종류
systemIds: 선택. 실행의 접근 시스템 안에서 더 좁힌다 (최대 10개)
```

`problemRef`가 기존 문제 UUID이면 그 실행 입력의 문제여야 한다. 검색 결과는 이 문제의 질의에 대한 것이며 다른 문제에 재사용하지 않는다([Ticket 도구 계약](TICKET_TOOLS.md)의 문제별 근거 범위와 같은 원칙).

## 응답

정상 HTTP 200 본문:

```text
contractVersion: hops-system-1
runId: UUID
problemRef: 요청과 같음
outcome: FOUND | NO_RESULT | DENIED
deniedReason: KIND_NOT_ALLOWED | SYSTEM_NOT_ALLOWED | null
applied: { accessScopes: [...], kinds: [...], systemIds: [...], topK: n }
results: 점수가 높은 순, 같으면 documentKey 순. FOUND가 아니면 빈 배열
```

| outcome | 뜻 | AI의 처리 |
|---|---|---|
| `FOUND` | 선필터와 반환 전 재검증을 통과한 문서가 1건 이상 | 근거로 사용 |
| `NO_RESULT` | 범위 안에서 검색했지만 일치하는 문서가 없음 | 빈 목록 |
| `DENIED` | 요청이 실행의 접근 범위를 넘음(예: 직원 실행에서 `RUNBOOK` 요청, 접근 밖 시스템). **조용히 빼지 않는다** | 빈 목록이 아니라 검색 실패로 처리 |

- 검색 실패(DB 오류, 저장 원문 digest 불일치)는 **HTTP 503 `SEARCH_UNAVAILABLE`**이다. 오류를 `NO_RESULT`나 빈 `results`로 바꾸지 않는다(평가 성질 P2).
- 이 API는 결과가 없다는 이유로 접근 거부를 숨기거나, 접근 거부를 결과 없음으로 바꾸지 않는다. 직원 실행에서 담당자 문서는 `DENIED`가 아니라 접근 범위 밖이라 결과에 나타나지 않으며, 담당자 종류를 **직접 요청한 경우에만** `DENIED`다.

### 결과 항목

```text
evidence: KnowledgeEvidence와 같은 형태
  id: 문서 버전 ID (knowledge_document_version.id)
  documentId, documentVersion: 문서 종류에 따라 아래 규칙
  kind: GUIDE | POLICY | RUNBOOK | CASE
  title, excerpt(승인 원문), systemId, appliesToSystemVersion, applicability
  accessScope: EMPLOYEE | SYSTEM_STAFF
  origin: SYNTHETIC | DEMO_VERIFIED | OPERATIONAL
documentKey: 문서 키(CASE는 사례 번호)
content: 승인된 버전의 원문
contentDigest: 저장된 content_digest
digestBasis: CONTENT | APPROVED_SNAPSHOT
score: 0 초과 1 이하
caseIndexStatus: CASE만 { vector, graph }. 저장된 색인 상태 그대로
```

- `documentId`·`documentVersion`: GUIDE·POLICY·RUNBOOK은 `knowledge_document.id`와 버전 번호, **CASE는 `resolved_case.id`와 사례 버전**이다(기존 근거 재검증이 CASE를 이 키로 찾는다).
- `digestBasis=CONTENT`이면 `contentDigest`는 `content`의 UTF-8 SHA-256이다. AI가 모델 입력 전에 직접 대조한다. `APPROVED_SNAPSHOT`(CASE)이면 승인 거래가 만든 사례 스냅샷의 digest이며 AI는 다시 계산할 수 없다. Spring이 응답 전에 스냅샷에서 다시 계산해 대조한다.
- `applicability`: 문서 메타데이터에 있으면 그대로, CASE는 사례의 적용 조건을 쓴다. 메타데이터에 없는 가이드·정책·런북은 시스템·종류·시스템 버전 같은 **구조화된 값으로만** "전자결재 직원 가이드. 시스템 버전 미지정" 형태를 조립한다. 절차나 사실을 지어내지 않는다. 적용 조건이 없는 CASE는 공개하지 않는다.
- 같은 근거 조립 규칙을 저장된 근거의 현재 공개 검증(`TicketKnowledgeReader`)도 쓴다.

## 선필터와 반환 전 재검증

검색 전에 다음을 SQL 조건으로 건다. 하나라도 아니면 점수 계산 대상이 아니다.

- 문서·문서 버전 `review_status = APPROVED`
- `data_split = RAG` (`EVAL`·`DEV` 제외)
- 삭제되지 않은 문서와 활성 시스템
- 실행 입력의 공개 범위와 시스템 범위(공통 정책은 `it_system_id` 없이 허용)
- 요청한 종류

후보마다 반환 전에 **현재 DB 기준의 공개 판정 한 가지**(`isCurrentlyVisible`)를 다시 적용한다. 담당자 전용 문서는 해당 시스템의 주담당자인 실행 문의자만 통과한다. 캐시된 엔티티 상태로 판단하지 않는다.

### CASE 공개 규칙 (1차 SQL 검색의 제안)

계약은 승인 사례가 Vector와 Neo4j 양쪽에 반영(`SUCCEEDED`)돼야 검색에 쓴다고 정한다. 1차 SQL 검색에는 실제 벡터·그래프 반영이 없고 시드 사례(#191)의 두 상태는 `PENDING`이다. 그래서 **설정**으로 규칙을 고른다.

| `hops.knowledge.case-visibility` | CASE 공개 조건 |
|---|---|
| `APPROVED_SNAPSHOT` (기본, 1차 SQL 검색) | 승인(`APPROVED`)된 현재 사례 스냅샷, 같은 시스템·공개 범위, 승인자·승인 일시, 해결된 티켓과 해결·구현 기록. **색인 상태는 조건이 아니다** |
| `INDEXED` (2차, 계약 원안) | 위 조건에 더해 `vector_status`·`graph_status`가 모두 `SUCCEEDED` |

- 색인 상태를 `SUCCEEDED`로 바꾸거나 위장하지 않는다. 응답의 `caseIndexStatus`에 저장된 값이 그대로 나간다.
- 이 설정은 지식 검색과 저장된 근거의 현재 공개 검증이 함께 따른다. 한쪽만 바꾸면 검색에서 나온 근거를 티켓 생성·안내 저장 단계가 거부하기 때문이다.
- 사례 검토 API(`GET /api/v2/cases/{id}`)의 `publication.state`는 **색인 반영 상태**이며 이 설정과 무관하다. 1차 SQL 검색에서는 `publication.state = PRIVATE`인 승인 사례가 검색에 나올 수 있다. 이 차이와 표시 의미는 사례(#170) 담당자 리뷰에서 정한다.
- 2차에 실제 색인이 생기면 설정을 `INDEXED`로 바꿔 계약대로 되돌린다.

## 검색 방법과 한계 (1차)

- 질의에서 한글·영문·숫자 토큰을 만들고(2자 이상, 불용어 제외) 흔한 조사를 떼어 최대 20개까지 쓴다. 제목과 승인 원문에서 부분 문자열로 찾는다. 점수는 제목 일치에 가중하며 항상 같은 질의에 같은 순서를 낸다. 형태소 분석이 없어서 어미 변화·동의어는 찾지 못한다.
- PostgreSQL FTS(`simple`)와 비교한 결과와 한계는 아래 "한국어 검색 품질 확인"에 기록한다. 개선(임베딩·형태소·`pg_trgm`)은 2차로 넘긴다.
- Spring은 호출 횟수를 저장하지 않는다. 실행 전체의 도구 호출 한도는 AI의 `RunBudget`이 강제한다. 서비스 전체의 요청 빈도 제한은 이 계약의 범위 밖이다.

## 오류

Spring 공통 ApiError. 400 INVALID_INPUT(형식·알 수 없는 필드·빈 검색어·검색할 단어가 남지 않는 질의·실행 입력에 없는 problemRef), 401 UNAUTHENTICATED, 403 ACCESS_DENIED(문의자·`policyRevision`·목적 불일치), 404 NOT_FOUND(실행 없음), 409 INQUIRY_VERSION_CONFLICT·INVALID_STATE(버전·현재 실행·상태·기한), 503 SEARCH_UNAVAILABLE. 권한·입력·버전 오류는 자동 재시도하지 않는다.

## 검증

`InternalJudgementOptionTest`(옵션 켜짐)와 `AnalysisInputFactoryAccessTest`·AI의 `test_internal_judgement_boundary.py`가 위 경계를, `KnowledgeSearchApiTest`가 실제 PostgreSQL과 MockMvc로 다음을 확인한다: 직원 실행에서 담당자 문서 0건, 미승인·EVAL·삭제 제외, 시스템 범위와 좁히기, 종류·시스템 직접 요청의 `DENIED`, 결과 없음과 검색 실패(503)의 구분, 인증 실패, 실행·버전·기한 검증, `topK` 상한, 요청 본문으로 접근 범위를 넓히려는 시도 거부, CASE 공개 설정별 동작과 저장된 색인 상태 노출, 검색 근거가 현재 공개 검증을 통과함. 실제 AI 호출·모델 품질·프론트 E2E는 포함하지 않는다.

## 관계 조회

`POST /internal/v2/runs/{runId}/relation-searches`

`RelationSource`([AGENT_ARCHITECTURE](../../docs/ai/AGENT_ARCHITECTURE.md) 8절)의 1차 SQL 구현이다. 키워드·기능 후보·시스템으로 **증상 → 확인 항목 → 직원용 질문 문구 → 승인 사례 이웃**과 각 항목의 근거·공개 범위를 돌려준다. 연결은 후보일 뿐이며 현재 문의의 원인을 확정하는 필드는 없다. Neo4j는 같은 인터페이스 뒤로 2차에 교체한다. 인증·실행 검사·알 수 없는 필드 거부·오류 코드는 지식 검색과 같다.

요청 본문(알 수 없는 필드는 거부):

```text
inquiryVersion: 양의 정수. 실행의 문의 버전과 같아야 한다
problemRef: 실행 입력의 기존 문제 UUID 또는 new:[A-Za-z0-9_-]+
keywords: 선택. 핵심어 1~20개, 각 1~50자
featureIds: 선택. 시스템 기능 UUID 1~10개 (실행 입력 systems의 기능 후보)
systemIds: 선택. 실행의 접근 시스템 안에서 더 좁힌다 (최대 10개)
limit: 1~5, 생략하면 5. 돌려줄 증상 수
```

`keywords`와 `featureIds` 중 하나는 있어야 한다(둘 다 없으면 400). `featureIds`가 실행의 접근 시스템 기능이 아니거나 없는 기능이면 `FEATURE_NOT_ALLOWED`로 거부한다.

응답:

```text
contractVersion, runId, problemRef
outcome: FOUND | NO_RESULT | DENIED
deniedReason: SYSTEM_NOT_ALLOWED | FEATURE_NOT_ALLOWED | null
applied: { accessScopes, systemIds, limit }
symptoms: 점수 순(같으면 catalogKey 순)
  id, catalogKey, label, systemId, featureId, featureCode, accessScope, basis, score
  checkItems: 원인을 가르는 확인 항목. 후보 원인을 더 많이 가르는 순
    id, catalogKey, label(담당자 범위 응답만), employeeDisclosable, accessScope, basis
    questions: 직원용 질문 문구 id, catalogKey, text, basis
  causes: 가능 원인(담당자 범위 응답만). 직원 응답에는 키가 없다
  cases: 승인 사례 이웃 documentKey(사례 번호), evidence(KnowledgeEvidence 형태), caseIndexStatus
```

- 증상은 키워드 토큰이 증상 이름에 있거나 기능 후보가 증상의 기능과 같으면 후보다. 기능이 같으면 가산한다. 일치 신호가 없으면 결과에 넣지 않는다.
- `basis`는 그 항목을 선언한 근거 문서 버전이 **지금 이 실행에 공개 가능할 때만** `{visible: true, documentId, documentVersion, documentKey}`다. 담당자 전용 런북에서 나온 항목이라도 직원 응답에는 런북의 ID·키를 싣지 않고 `{visible: false}`로 둔다. 근거가 없는 항목은 DB 제약상 존재하지 않는다.

### 접근 범위: 지식 검색과 다른 점

관계 항목의 공개 범위는 항목 종류마다 정본 6.1절이 정한다. 증상은 `EMPLOYEE`, 원인·확인 항목·연결은 `SYSTEM_STAFF`이며 확인 항목 대부분이 "직원 공개"(`employeeDisclosable`)다. 그래서 지식 검색처럼 `access_level`만으로 거르면 직원 상담의 확인 항목과 질문이 모두 사라진다. 규칙은 다음과 같다.

- 모든 항목은 승인(`APPROVED`)이고 삭제되지 않았으며 활성 시스템이어야 한다. 승인되지 않은 항목·연결은 후보 계산에도 쓰지 않는다.
- **직원 실행**(`allowedDocumentScopes=["EMPLOYEE"]`): 응답에는 `EMPLOYEE` 증상, **직원 공개로 표시된** 확인 항목의 ID, 그 항목의 승인된 `EMPLOYEE` 질문 문구와 `EMPLOYEE` 사례만 나간다. 확인 항목은 후보 원인을 가르는 연결로 찾지만 응답에는 **원인의 이름·ID·수·연결, 담당자 전용 확인 항목의 ID·이름, 확인 항목의 이름(label)이 없다.** 직원 공개 표시가 없거나 질문 문구가 직원 공개로 승인되지 않은 확인 항목은 응답에 나타나지 않는다.
- **담당자 범위 실행**(옵션 프로필, 서버가 실행 입력으로만 켠다): 문서 검색과 같이 해당 시스템의 주담당자인 실행 문의자에게만 `STAFF` 증상·원인·모든 확인 항목·사례를 돌려준다. 확인 항목에는 `label`과 직원 공개 여부가 붙는다.
- 사례 이웃은 지식 검색과 같은 현재 공개 판정(`isCurrentlyVisible`)과 `hops.knowledge.case-visibility`를 따른다. 사례의 색인 상태는 저장된 값 그대로다.
- `DENIED`는 요청이 실행의 접근 범위(시스템·기능)를 넘을 때만이며, 담당자 전용 항목이 걸러진 것은 `DENIED`가 아니다.
- 검색 실패(DB 오류)는 HTTP 503 `SEARCH_UNAVAILABLE`이다. 오류를 `NO_RESULT`로 바꾸지 않는다.

### 검증

`RelationSearchApiTest`가 실제 PostgreSQL로 다음을 확인한다: 직원 실행에서 원인·담당자 전용 확인 항목·확인 항목 이름이 어디에도 없고 질문 문구만 나가는 것, 직원 공개 표시가 없거나 미승인인 항목·질문의 제외, 근거 런북 키의 비공개, 0건과 503의 구분, 시스템·기능 범위와 좁히기와 `DENIED`, 인증·실행 검증, 알 수 없는 필드와 범위 확대 시도의 거부, 담당자 범위 실행에서 주담당자에게만 원인·사례가 나가는 것과 사례 공개 규칙. 실제 AI 호출·질문 생성·모델 품질은 포함하지 않는다.

## 내부 판단 경계 (옵션, 기본 꺼짐)

[AGENT_ARCHITECTURE 9절](../../docs/ai/AGENT_ARCHITECTURE.md)의 옵션 프로필이다. **SECURITY.md SEC-09·LLM02 개정과 보안 리뷰 전에는 켜지 않는다.** 이 절은 켰을 때의 경계를 정하며 켜는 결정은 아니다.

**기본 프로필(꺼짐)**: 직원 상담 실행 입력은 `accessContext.allowedDocumentScopes=["EMPLOYEE"]`이고 `retrievalScopes` 키가 없다. 지식 검색·관계 조회는 직원 공개 자료와 직원 공개로 승인된 관계 항목만 돌려주고, AI의 `validate_evidence_scope`는 직원 상담에서 비직원 근거를 거부한다. 이 동작은 옵션 구현 전후로 같다.

**켜는 방법**: Spring 서버 설정 `hops.agent.run.internal-judgement.enabled=true`뿐이다(기본 `false`). 요청 본문·모델 출력으로는 켤 수 없고(알 수 없는 필드는 400), 켜진 채 시작하면 Spring이 경고 로그를 남긴다.

**켜졌을 때**:

- Spring이 접수하는 상담 실행 입력에 `accessContext.retrievalScopes=["EMPLOYEE","SYSTEM_STAFF"]`(가산 필드, 꺼져 있으면 키 없음)를 싣는다. `allowedDocumentScopes`는 그대로 `["EMPLOYEE"]`이며 사용자에게 닿을 수 있는 범위를 넓히지 않는다.
- 지식 검색·관계 조회는 `retrievalScopes`를 읽기 범위로 쓰고, 담당자 전용 자료를 **주담당자 조건 없이**(문의자가 해당 시스템 담당자가 아니어도) 돌려준다. 시스템 범위, 승인 상태, `RAG`, 삭제 제외, 사례 공개 규칙은 그대로 적용한다. 응답 `applied.internalJudgement=true`로 구분되고 Spring이 내부 판단 읽기 로그를 남긴다.
- **설정이 꺼진 서버는 실행 입력에 `retrievalScopes`가 남아 있어도 무시한다**(기본 프로필로 읽음). 읽기 범위가 `allowedDocumentScopes`를 줄이거나 알 수 없는 값을 가진 입력은 403이다.
- 사용자에게 닿는 경로는 열리지 않는다. 안내·티켓 근거의 현재 공개 검증(`TicketKnowledgeReader`)은 내부 판단 읽기를 모르고, 담당자 전용 근거는 주담당자가 아닌 문의자에게 통과하지 못한다. 직원에게 질문으로 나갈 수 있는 것은 직원 공개로 표시된 확인 항목과 승인된 `EMPLOYEE` 질문 문구뿐이며(관계 조회의 `employeeDisclosable`·`questions`) 기본 프로필과 같은 집합이다.
- AI wire는 `retrievalScopes`를 선택 필드로 받고(`allowedDocumentScopes`를 모두 포함, 중복·빈 값·알 수 없는 값 거부), 직원 상담이 아닌 실행에는 두지 못한다. AI는 이 값으로 `validate_evidence_scope`나 검색 질의의 공개 범위를 넓히지 않는다. 내부 판단 근거를 평가에 쓰는 것은 별도 기능(#181)이며 그 전에는 AI 쪽에서 쓰이지 않는다.

배포 순서: AI를 먼저 갱신해 선택 필드를 받게 한 뒤 Spring을 배포한다(AI는 알 수 없는 필드를 거부한다). 옵션이 꺼져 있으면 Spring은 새 필드를 보내지 않아 기존 AI와 호환된다.

## 한국어 검색 품질 확인

`KnowledgeSearchQualityTest`가 직원 상담 범위(가이드 8·정책 4)에서 질의 10개의 정답 문서 순위를 센다. 질의는 평가 DEV 시나리오의 문구와 직접 쓴 변형이고 동결한 TEST 시나리오의 문구는 쓰지 않았다. 정답은 카탈로그에서 사람이 정했다. 비교 대상은 PostgreSQL FTS(`simple` 설정, 접두 일치 OR, 조사 제거 없음)이며 순위는 상위 5건 안에서 센다.

| # | 질의 | 정답 | 현재 규칙 순위 | FTS(simple, 접두) 순위 | 시스템 미지정 순위 |
|---|---|---|---|---|---|
| 1 | 전자결재에서 지난 문서를 찾는 방법을 알려주세요. | guide-ea-document-search | 1 | 1 | 1 |
| 2 | 전자결재에서 임시 저장한 문서는 어디서 확인하나요? | guide-ea-document-create | 2 | 1 | 2 |
| 3 | 상신한 문서가 지금 어느 결재자 단계에서 멈췄는지 보고 싶어요. | guide-ea-approval-status | 3 | 2 | 3 |
| 4 | 첨부 파일을 눌러도 열리지 않는데 어떻게 열고 내려받나요? | guide-ea-attachment-open | 1 | 2 | 1 |
| 5 | 첨부할 수 있는 파일 크기와 개수가 얼마까지인가요? | policy-ea-attachment-limit | 1 | 1 | 1 |
| 6 | 공통 로그인 포털에서 다른 시스템 이용 권한을 신청하고 싶어요. | guide-login-permission-request | 1 | 2 | 1 |
| 7 | 비밀번호를 여러 번 틀렸더니 계정이 잠겼어요. | policy-login-account-lock | 1 | 1 | 1 |
| 8 | 사내 포털에 한 번 로그인하면 다른 시스템에서도 다시 로그인하지 않아도 되나요? | guide-login-sso-use | 1 | 1 | 1 |
| 9 | 파일 공유에서 동료에게 파일 링크를 만들어 보내고 싶어요. | guide-file-share-link | 1 | 1 | 1 |
| 10 | 공유 폴더에 접근 권한이 없다고 나오는데 누구에게 열려 있는 건가요? | policy-file-share-scope | 1 | 1 | 1 |

상위 5건 안: 현재 규칙 10/10, FTS(simple, 접두, 조사 제거 없음) 10/10, 시스템 미지정(12건 후보) 10/10
1위: 현재 규칙 8/10, FTS 7/10

해석과 한계:

- 현재 규칙과 FTS 모두 이 질의에서는 상위 5건에 정답을 넣었고 1위는 현재 규칙 8건, FTS 7건이다. 차이가 작아서 한쪽이 낫다고 말하기 어렵다. 그래서 확장(`pg_trgm`)·마이그레이션 없이 단순한 규칙으로 시작한다.
- **이 수치는 일반 검색 품질이 아니다.** 문서가 12건(시스템당 4~5건)이라 상위 5건은 후보 대부분을 포함하고, 질의와 문서를 같은 팀이 같은 어휘로 썼다. 어미 변화·동의어("안 열려요"와 "열기 실패")·오타·여러 문제가 섞인 질의는 확인하지 않았다.
- 2위·3위에 든 질의(2, 3번)는 정답이 아닌 가이드가 더 높은 점수를 받은 경우다. 점수는 단순 일치 수라 의미 순위를 보장하지 않는다. 의미 검색(임베딩)·형태소 분석·재순위는 2차에서 다룬다.
