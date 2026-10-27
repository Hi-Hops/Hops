# Hops Knowledge 데이터셋 v0 후보

팀원이 Agent·검색·읽기 Tool을 구현하기 전에 함께 검토할 **다중 서비스 지식 후보**다. 첫 실제 재현 범위는 PORTAL이며, IDENTITY·REMOTE_ACCESS·FILE_SHARE는 지식 답변·이관 범위다. 전체 범위는 [`docs/knowledge/DATASETS.md`](../../../docs/knowledge/DATASETS.md)를 따른다.

## 현재 상태

- STAFF_RUNBOOK 11건: PORTAL 5건·일반 영역 6건 담당자 확정, 팀 리뷰 대기
- EMPLOYEE_GUIDE 13건: 데이터 담당자 확정, 팀 리뷰 대기
- POLICY 5건: 데이터 담당자 확정, 팀 리뷰 대기
- 일반 공개 문서 62건: include 12·hold 4·reject 46, 데이터 담당자 판정·팀 리뷰 대기
- include 문서 섹션 82건: 기본 `STAFF`, 직원 공개 후보 12건·`NEVER_EXPOSE` 4건
- IT 용어 50건: 데이터 담당자 확정, 팀 리뷰 대기
- 진단 신호 레지스트리 55건: PORTAL 27건·비PORTAL 28건 데이터 담당자 확정, 팀 리뷰 대기·RAG 적재 금지
- 시나리오 카탈로그 34건: Knowledge 12·Demo Portal 10·업무 흐름 12,
  담당자 확정·팀 리뷰 대기·RAG 적재 금지
- RAG 청크 163건: 구조·해시 자동 검증 완료, 전부 적재 대기
- 실제 해결 CASE 0건: Demo Portal 재현·복구 검증 후 생성

계정·인증, 원격 접속, 공유 드라이브의 담당자 런북과 직원 가이드를 작성했다.
Wi-Fi·802.1X 런북·가이드는 공개 근거가 hold라 작성하지 않았다. 직원 가이드 13건은
담당자가 확인했지만 팀 리뷰 전이며, 정책 5건도 담당자 확정·팀 리뷰 전이다. 따라서 이 폴더만으로
전체 v0가 승인됐다고 판단하지 않는다.

`content_status`, `team_review_status`, `ingest_status`, `tool_binding.status`,
`scenario_validation.status`는 서로 다른 상태다. 현재 데이터는 검토용이며, 팀 승인이나
Vector DB 적재가 완료됐다는 뜻이 아니다.

## 폴더

- `catalogs/`: 가상 기업, IT 용어, 진단 신호 레지스트리, 시나리오 평가 계약
- `documents/employee_guides/`: 직원 공개용 안내 초안
- `documents/policies/`: 문의·권한·이관·CASE·지식 운영 정책 초안
- `documents/staff_runbooks/`: IT 담당자용 진단·복구 런북
- `chunks/`: 정본에서 로컬 생성하는 향후 RAG 적재 단위, Git 제외
- `manifests/`: 일반·PORTAL 공개 근거 검수표, 출처·라이선스, 섹션 권한, 청크 검수 요약
- `schemas/`: 문서·청크 JSON Schema
- `scripts/`: 청크 재생성 및 불변식 검증

사람이 읽는 설명과 검수 순서는 [`docs/knowledge/README.md`](../../../docs/knowledge/README.md)를 참고한다.

## 검증

저장소 루트에서 실행한다.

```bash
python3 datasets/knowledge/v0/scripts/build_and_validate.py
```

성공하면 Git에서 제외된 파생 청크, `manifests/chunk_review_summary.json`,
`manifest.json`이 로컬에서 갱신되고
`KNOWLEDGE DATASET V0 OK`가 출력된다.

팀 공유 범위와 리뷰 순서는
[`docs/knowledge/TEAM_HANDOFF.md`](../../../docs/knowledge/TEAM_HANDOFF.md)를 따른다.

## 변경 규칙

1. 사람이 직접 수정하는 정본은 `catalogs/`와 `documents/`다.
2. `chunks/`와 `manifest.json`은 검증 스크립트로 다시 만든다.
3. 팀 승인 전 `ingest_status=PENDING`을 바꾸지 않는다.
4. Tool 연결 전 `tool_binding.status=PENDING`, 재현 전
   `scenario_validation.status=NOT_RUN`을 유지한다.
5. 실제 해결 CASE는 재현·관측·사람의 복구·재시험·해결 확인을 모두 마친 뒤 추가한다.
6. `incident_scenarios.json`의 숨겨진 원인·평가 정답은 Vector DB와 Agent 입력에 넣지 않는다.
7. `error_signals.json`은 런북·Tool·평가 연결용 정본이며 독립 청크나 임베딩을 만들지 않는다.
   대신 런북의 `causes` 청크를 만들 때 참조한 신호 정의와 레지스트리 해시를 자동 결합한다.
