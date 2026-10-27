# Hops Knowledge 설계

Knowledge Agent와 Diagnosis·Ticket Agent 개발자가 공통으로 참고할 가상 기업, 장애 시나리오, 지식 데이터 구성과 개선 규칙이다.

## 먼저 읽을 문서

1. [팀 공유 안내](TEAM_HANDOFF.md)
2. [가상 기업 환경](VIRTUAL_COMPANY.md)
3. [데이터셋 인벤토리와 구축 범위](DATASETS.md)
4. [장애·문의 시나리오와 정답 경계](INCIDENT_SCENARIOS.md)
5. [지식 수명주기와 개선 규칙](KNOWLEDGE_LIFECYCLE.md)
6. [Knowledge–Diagnosis 책임과 Evidence Bundle 계약](KNOWLEDGE_DIAGNOSIS_CONTRACT.md)
7. [팀 검수 체크리스트](REVIEW_CHECKLIST.md)
8. [현재 작성된 직원 가이드](guides/)
9. [현재 작성된 담당자 런북](runbooks/)
10. [현재 작성된 서비스·지식 정책](policies/)
11. [IT 용어집](references/IT_GLOSSARY.md)
12. [IT 용어집 담당자 검수 결과](references/IT_GLOSSARY_REVIEW.md)
13. [진단 신호 레지스트리](references/ERROR_SIGNALS.md)
14. [진단 신호 담당자 검수 결과](references/ERROR_SIGNAL_REVIEW.md)

현재 기계 판독 후보 데이터는 [`datasets/knowledge/v0`](../../datasets/knowledge/v0)에 있다.
일반 공개 근거 62건과 PORTAL 핵심 슬라이스, 담당자 확정·팀 리뷰 대기 자료가 있으며
아직 Git 병합·RAG 적재가 승인된 정본은 아니다. `DATASETS.md`의 목표 범위와 팀
리뷰 조건을 충족한 뒤 공유 정본으로 채택한다.

## 상태 구분

- `OWNER_CONFIRMED`: 데이터 담당자가 내용 방향을 확인했다.
- `content_status=AI_DRAFT`: AI 초안이며 데이터 담당자 내용 검수가 남았다.
- `content_status=OWNER_CONFIRMED`: 데이터 담당자가 내용을 확인했지만 팀 승인 전이다.
- `TEAM_REVIEW_PENDING`: 공통 데이터 채택을 위한 팀 리뷰가 남았다.
- `ingest_status=PENDING`: RAG 인덱스에 넣지 않는다.
- `tool_binding_status=PENDING`: 실제 읽기 MCP Tool과 연결하지 않았다.
- `scenario_validation_status=NOT_RUN`: Demo Portal 재현 결과가 아니다.

문서 작성, 팀 승인, RAG 적재, Tool 연결, Demo 재현은 서로 다른 완료 조건이다. 한 상태를 통과했다고 다른 상태까지 완료된 것으로 간주하지 않는다.
