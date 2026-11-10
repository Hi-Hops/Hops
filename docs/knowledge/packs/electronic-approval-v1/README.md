# 전자결재 담당자 지원 데이터팩 v1

기존 가상 회사 명세의 기능·제약에 근거한 **합성 사례 80건과 보강 문서 8건**이다. 실제 운영 처리 실적이 아니며 문의자 확인은 `SYNTHETIC_PRESET`이다. 기본 Flyway 시드 6건과 분리해 로컬 시연 환경에 선택적으로 적재한다.

## 구성

| 분류 | 사례 수 |
|---|---:|
| 조회 상태·목록 조건 | 14 |
| 기안일·기간 | 10 |
| 열람 권한·화면별 접근 | 12 |
| 상신 필수값·제목 길이 | 12 |
| 결재 단계·회수·수정 제약 | 10 |
| 첨부 용량·개수·형식 | 8 |
| 미리보기·검사 중·일시 오류 | 10 |
| 공통 로그인·세션 | 4 |

80건은 80개의 서로 다른 근본 원인을 의미하지 않는다. 같은 원인 계열에서도 문서 상태·사용자 관계·화면·입력값·형식이 다른 처리 시나리오다. 같은 조치 계열은 추천 화면에서 묶는다. 전자결재 책임 76건과 전자결재에서 발생했지만 공통 로그인 담당자가 처리한 4건을 구분한다.

- `cases.json`: 문의·추가 답변·확인 사실·처리 기록·문의자 확인·승인 사례의 입력 카드. 각 `caseKey`가 항목을 식별한다.
- `documents.json`: 4개 매뉴얼과 4개 런북. 기존 가이드·정책·런북을 대체하지 않고 상세 점검을 보강한다.
- 근거 규칙: [가상 회사 명세](../../VIRTUAL_COMPANY.md)와 각 카드의 `basis`가 가리키는 기존 지식 문서.
- 독립 평가 질의: `ai/evaluation/recommendations/electronic-approval-v1.json`. 평가 정답은 검색 코퍼스에 적재하지 않는다.

## 조건과 근거

승인되는 `applicability`에 `조회 상태: 승인`과 같이 이름·값이 있는 조건을 기록한다. 조치·검증과 조건은 구분한다. 현재 문의는 권한 검사를 통과한 사용자 출처 사실·허용된 문제별 원문과 기록된 기능 분류를 비교하며 추정을 사실로 추가하지 않는다. 지원하지 않는 자유서술은 미확인으로 남긴다.

검색 관련성과 적용 가능성은 별개다. 화면의 조건 일치 표시는 알려진 조건의 대조 결과이며 해결 확률이 아니다. 높은 일치는 2개 이상 조건 일치, 미확인·불일치 없음일 때만 표시한다. 불일치는 항상 별도로 표시한다. 원문 강조에는 문서 ID·버전과 실제 UTF-16 범위가 연결되고 클라이언트에서도 원문을 대조한다.

## 검증과 적재

저장소 루트에서 프로젝트 Python 환경으로 실행한다.

```sh
ai/.venv/bin/python scripts/knowledge/assistance_pack.py
ai/.venv/bin/python -m pytest scripts/knowledge/test_assistance_pack.py
```

적재에는 `HOPS_SEARCH_DSN`, `HOPS_SEARCH_EMBEDDING_API_KEY`, `HOPS_SEARCH_NEO4J_URL`, `HOPS_NEO4J_PASSWORD`가 필요하다. 비밀값은 서버 환경에서 주입한다.

```sh
ai/.venv/bin/python scripts/knowledge/assistance_pack.py --apply --isolated-demo
```

허용된 별도 로컬 저장소만 대상으로 한다. 기존 문의·사례·그래프를 초기화하지 않는다. 고정 ID를 사용하며 기본 시드와 데이터팩의 번호는 적재 트랜잭션 안에서 기존 서비스 규칙으로 통일한다. 문의는 `inquiry_number_seq`의 `Q-00000001`, 티켓은 `ticket_number_seq`를 사용해 화면에 `T-0007`처럼 표시한다. 해결 사례는 큐레이터 실행 UUID의 하위 16자리에서 생성한 `C-…` 형식이다. 이미 통일한 번호는 반복 적재해도 보존한다. 사례 문서의 출처 번호와 기존 Neo4j 사례 노드의 번호도 동기화한다. 합성 출처는 번호 접두사 대신 기존 `origin_type`, `is_synthetic`, `SYNTHETIC_PRESET` 메타데이터로 구분한다. 승인 본문이 기존 버전과 다르면 적재를 거부한다. 실제 Vector 적재와 Neo4j MERGE의 성공 건수를 확인한 후에만 검색 공개 상태를 갱신한다. 반복 실행은 이미 있는 임베딩을 재사용한다. 문서 출처 포인터는 실제 JSON 파일 경로와 항목 키로 기록한다.

## 검색 평가 재현

현재 제목 검색과 문맥·조건 기반 추천을 동일한 80건 코퍼스에서 비교한다. 평가 질의는 원본 사례 제목을 복사하지 않은 새 표현이며 DEV 40건·TEST 40건이다. 각 분할에 일치·조건 대비·정보 부족·적절한 사례 없음이 포함된다.

```sh
ai/.venv/bin/python scripts/knowledge/prepare_assistance_evaluation.py --out /tmp/hops-assistance-eval
```

이 명령은 실제 임베딩을 사용한다. 기존 캐시를 포함해 코퍼스와 질의 임베딩 입력 상한은 500개이며 설명용 추가 LLM 호출은 없다. 준비된 파일은 별도 PostgreSQL 18 테스트 DB에서 다음 환경으로 실행한다.

```sh
# backend/spring/hops에서 실행. 테스트 전용 DB 자격 증명은 환경으로 주입한다.
HOPS_ASSISTANCE_EVAL_DIR=/tmp/hops-assistance-eval ./gradlew test --tests '*AssistanceRetrievalEvaluationTest'
```

결과는 지정한 출력 폴더의 `retrieval-report.json`에 저장된다. 정답 사례가 있는 질의의 포함률과 조건 위반·인용 검증을 별도로 본다. 캐시 임베딩을 사용하는 API 지연 측정에는 외부 임베딩 호출 지연이 포함되지 않는다. 합성 데이터 성능을 실제 운영 품질로 일반화하지 않는다.
