# Knowledge–Diagnosis 책임과 Evidence Bundle 계약

상태: 데이터 담당자 확정 · 팀 리뷰 전
확정일: 2026-10-27

## 1. 결정

- Knowledge Agent만 가이드·런북·CASE·정책 RAG를 검색한다.
- Diagnosis Agent는 Vector DB와 지식 문서를 직접 검색하지 않는다.
- Diagnosis Agent는 Knowledge Agent가 State에 저장한 `Evidence Bundle`과 읽기 MCP Tool 관측만 비교한다.
- 추가 지식이 필요하면 Diagnosis Agent가 검색하지 않고 `NEED_MORE_KNOWLEDGE`를 반환한다.
- Supervisor가 해당 요청을 받아 Knowledge Agent를 다시 호출하고 State를 갱신한다.
- 진단 신호 레지스트리는 RAG 문서가 아니다. 신호 ID·의미·매칭 조건·확정 제한의 단일 정본이다.

## 2. 책임 경계

| 구성요소 | 책임 | 금지 |
|---|---|---|
| Knowledge Agent | RAG 검색, 관련 문서 선정, 신호 정의 조립, Evidence Bundle 생성 | Tool로 현재 상태 진단, 업무 상태 확정 |
| Diagnosis Agent | 읽기 Tool 호출, Evidence Bundle과 관측 비교, 원인·반대 근거·불확실성 제안 | Vector DB·신호 레지스트리·CASE 직접 검색, 쓰기 작업 |
| Supervisor | Agent 순서·재호출·실패 복구, State 전달 | 도메인 원인 임의 생성 |
| Read MCP Tool | 현재 로그·메트릭·상태를 구조화해 반환 | 원인·부서·해결 여부 확정 |
| Spring | 권한 검사, Evidence Bundle 검증, 티켓·부서·상태 최종 확정 | Agent 제안을 무검증 저장 |

## 3. 저장과 파생

```text
진단 신호 레지스트리(단일 정본, RAG 금지)
        ↓ signal_id 참조
STAFF_RUNBOOK 정본
        ↓ 청크 생성 시 신호 정의 결합
RAG 런북 청크
        ↓ Knowledge Agent 검색·조립
Evidence Bundle(State)
        ↓
Diagnosis Agent + Read MCP Tool 관측
```

- 런북 정본은 원인별 `required_signal_ids`와 `contradictory_signal_ids`를 참조한다.
- 신호 이름·의미·확정 제한을 런북마다 복사하지 않는다.
- 런북 RAG 청크에는 검색 결과 하나만으로 비교할 수 있도록 참조 신호 정의를 파생 포함한다.
- 신호 레지스트리 자체의 독립 청크와 임베딩은 만들지 않는다.

## 4. 최초 진단 흐름

1. Supervisor가 사용자 문의와 문제 단위를 Knowledge Agent에 전달한다.
2. Knowledge Agent가 권한에 맞는 가이드·런북·검증 CASE를 검색한다.
3. Knowledge Agent가 관련 신호 정의와 문서 근거를 `Evidence Bundle`로 조립한다.
4. Spring이 접근 범위와 근거 ID를 검증하고 State에 저장한다.
5. Diagnosis Agent가 런북에서 요구한 읽기 Tool만 호출한다.
6. Diagnosis Agent가 필요 신호·반대 신호와 실제 관측을 비교한다.
7. 근거가 충분하면 원인 후보·불확실성·후보 부서를 제안하고, 부족하면 이관 또는 추가 지식을 요청한다.

## 5. 조건부 Knowledge 재호출

Tool에서 현재 Evidence Bundle에 없는 코드·로그 패턴이 관측된 경우에만 다음 루프를 사용한다.

```text
Diagnosis Agent
→ NEED_MORE_KNOWLEDGE + 관측값 + 요청 사유
→ Supervisor
→ Knowledge Agent 재호출
→ 보강 Evidence Bundle로 State 갱신
→ Diagnosis Agent 재실행
```

- Diagnosis Agent는 검색어를 실행하지 않고 관측 사실과 부족한 지식만 요청한다.
- Supervisor는 동일 요청의 무한 반복을 막기 위해 재호출 횟수와 이미 검토한 문서 ID를 유지한다.
- Knowledge 재조회 후에도 근거가 부족하면 원인을 만들지 않고 담당 부서로 이관한다.

## 6. 기계 판독 계약

- Knowledge 출력: [`knowledge_evidence_bundle.schema.json`](../../datasets/knowledge/v0/schemas/knowledge_evidence_bundle.schema.json)
- Diagnosis 추가 지식 요청: [`diagnosis_knowledge_request.schema.json`](../../datasets/knowledge/v0/schemas/diagnosis_knowledge_request.schema.json)
- 진단 신호 정본: [`error_signals.json`](../../datasets/knowledge/v0/catalogs/error_signals.json)

`Evidence Bundle`은 다음 정보를 구조적으로 전달한다.

| 필드 | 역할 |
|---|---|
| `artifacts` | 검색된 문서·버전·청크 ID와 관련성 요약을 남기는 출처 목록 |
| `evidence_chunks` | 권한·해시 검증을 통과해 Diagnosis가 실제로 읽을 수 있는 청크 본문 |
| `diagnostic_steps` | 런북의 읽기 전용 진단 순서, 기대 관측, Tool 필요 상태·호출 계약 |
| `cause_hypotheses` | 원인 후보와 필요·반대 신호 ID, 담당 후보 부서, 근거 청크 연결 |
| `diagnostic_signals` | 신호의 매칭 조건·의미·확정 제한과 `runbook_id/step_id`, `runbook_id/cause_id` 연결 |
| `knowledge_gaps` | 검색 후에도 부족한 지식과 추가 조회·이관 판단 근거 |

- Knowledge Agent는 접근 권한과 `content_sha256` 검증을 통과한 청크만
  `evidence_chunks`에 넣는다.
- Tool이 아직 연결되지 않은 단계는 `tool_requirement.status=PENDING`, 읽기 Tool이 필요 없는
  단계는 `NOT_REQUIRED`, 호출 가능한 단계만 `READY`로 전달한다.
- Diagnosis Agent는 Bundle 안의 검증된 본문·단계·원인–신호 연결만 사용하며 문서나
  신호 레지스트리를 다시 검색하지 않는다.
- Spring은 Bundle 저장 전 접근 범위, 청크·문서 ID, 해시, Tool 허용 목록을 다시 검증한다.

현재 Schema는 데이터·Agent 구현 협의를 위한 v0다. 실제 State 모델과 API DTO에 적용할 때
필드명을 맞추되 책임 경계와 위 검증 조건은 변경하지 않는다.

## 7. 구현 전 남은 연결

- [x] STAFF_RUNBOOK 11건의 원인별 필요·반대 신호를 signal ID로 연결
- [x] 런북 청크 생성 시 신호 정의를 자동 결합
- [ ] Knowledge Agent가 Evidence Bundle을 생성하는 조립기 구현
- [ ] Diagnosis Agent가 `NEED_MORE_KNOWLEDGE`를 반환하는 출력 모델 구현
- [ ] Supervisor 조건부 재호출·횟수 제한·checkpoint 구현
- [ ] Spring의 접근 범위·근거 ID·업무 상태 검증 구현
