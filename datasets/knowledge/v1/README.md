# PRD 1.44 정책 데이터 초안

상태: AI 작성 초안 · 담당자 검수 및 팀 PR 리뷰 전 · 검색 적재 금지. `documents/policies/` 5건은 새 문의·시스템 배정·사례·접근·공개 정책의 후보이며 모든 `ingest_status`는 `PENDING`이다. 새 시스템·시나리오·사례·관계의 기계 판독 데이터는 아직 준비되지 않았다. 직원 가이드 8건과 담당자 런북 2건은 문서 초안으로 작성됐지만 JSON·청크·manifest와 검수는 아직 없다.

형식은 기존 `../v0/schemas/knowledge_policy.schema.json`을 재사용한다. 내용 검수·참조 ID·출처·권한 검토 뒤 v1용 생성·검증 경로와 청크·manifest를 설계해야 한다. 기존 v0 생성기는 v0를 대상으로 하므로 이 폴더를 읽거나 v1의 해시·청크를 생성하지 않는다. 기존 v0 JSON과 UI fixture는 현재 구현과 테스트 호환을 위해 유지했다. 이 초안을 실제 운영 이력·팀 데이터 승인·검색 반영 완료로 해석하지 않는다.

제품 정책 설명은 [PRD](../../../docs/PRD.md), [정책 문서](../../../docs/knowledge/policies/POLICY-PRD-1.44.md), [지식 수명주기](../../../docs/knowledge/KNOWLEDGE_LIFECYCLE.md)를 따른다.
