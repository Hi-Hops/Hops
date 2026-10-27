# 서비스·지식 정책 검토 목록

JSON 정본은 `datasets/knowledge/v0/documents/policies/`, RAG 청크는
`datasets/knowledge/v0/chunks/`에 있다. 이 폴더의 개별 Markdown은 팀 검토를 위한
자동 생성본이므로 직접 수정하지 않는다.

| 정책 | 핵심 경계 | 내용 상태 | RAG 적재 |
|---|---|---|---|
| [문의 처리 흐름](POLICY-INQUIRY-FLOW.v1.md) | 답변·추가 질문·진단 전환 | 담당자 확정 | 대기 |
| [티켓·이관·CS](POLICY-HANDOFF-CS.v1.md) | Agent 제안과 Spring 확정 분리 | 담당자 확정 | 대기 |
| [지식 접근과 노출](POLICY-KNOWLEDGE-ACCESS.v1.md) | 기본 거부·권한별 검색·문서 지시 무시 | 담당자 확정 | 대기 |
| [해결·CASE 수명주기](POLICY-CASE-LIFECYCLE.v1.md) | 해결 확인·재오픈·CASE 승격 | 담당자 확정 | 대기 |
| [지식 운영](POLICY-KNOWLEDGE-GOVERNANCE.v1.md) | 버전·폐기·재임베딩·평가 분리 | 담당자 확정 | 대기 |

공통 원칙은 다음과 같다.

- Agent는 근거 기반 답변과 판단 초안만 제안한다.
- 티켓 생성·부서 배정·재이관·해결 완료는 Spring 또는 사람이 확정한다.
- Agent는 코드·설정·배포·재시작·DB 쓰기를 수행하지 않는다.
- 정책 5건은 데이터 담당자의 내용 확인을 마쳤다. 팀 리뷰 후에만 RAG 적재 상태를 변경한다.
