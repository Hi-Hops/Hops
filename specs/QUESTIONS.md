# PRD 1.44 계약 검토와 적용 전 결정

기준: [PRD 1.44](../docs/PRD.md). 제품의 시스템 배정·5회 질문·문의자 확인·담당자 승인·동일 버전 양쪽 반영 정책은 합의된 전제다. 아래 구체안은 팀 리뷰 후 적용하며 이 대화·문서 작성만으로 팀 승인되지 않는다.

## 구체 제안이 작성된 항목

| ID | 제안 | 정본·검토 담당 | 현재 상태 |
|---|---|---|---|
| C01 | 신규 hops-system-1·/api/v2와 기존 프로필 분리 | [WIRE 12절](contracts/WIRE_CONTRACT.md#12-신규-계약-hops-system-1) · Frontend/Spring | 리뷰 대기 |
| C02 | PRIMARY 기반 권한·관리자 제한 DTO·열린 SSE 회수 | [TARGET](contracts/TARGET_CONTRACT.md) · Frontend/Spring | 리뷰 대기 |
| C03 | 문의/문제/작업/티켓 관계·버전·질문 번들 중복 방지 | [DATA_TYPES](contracts/DATA_TYPES.md#신규-계약-hops-system-1)·[DATA](../docs/DATA.md) · Spring/AI | 리뷰 대기 |
| C04 | 생성 결과 PENDING/STORED/NOT_CREATED/UNKNOWN·동일 작업 복구 | [F005](F005-result-cards/spec.md)·[F010](F010-creation-failures/spec.md) · 전체 | 리뷰 대기 |
| C05 | 검증 기록·문제별 확인 요청·미해결 처리 주기 | [F006](F006-my-inquiries/spec.md)·[F009](F009-department-tickets/spec.md) · Frontend/Spring | 리뷰 대기 |
| C06 | 사례 불변 버전·승인·outbox·두 저장소 공개 gate·수동 재반영 | [F011](F011-case-lifecycle/spec.md)·[DATA](../docs/DATA.md) · 전체 | 리뷰 대기 |
| C07 | 역할별 출력·서비스 Bearer·기한/예산·검색/그래프 허용 범위 | [에이전트 계약](../contracts/agents/README.md#hops-system-1-실행-계약안) · AI/데이터/Spring | 리뷰 대기 |
| C08 | 전체 필수 화면·목표 경로·조작·충돌/미확인·CONTRACT 검증 | [기능 목록](README.md)·[ROUTES](../docs/frontend/ROUTES.md) · Frontend/전체 | 리뷰 대기 |
| C09 | 논리 업무 ID·필드와 물리 엔티티·FK·담당 관계의 매핑 | [DATA](../docs/DATA.md#9-hops-system-1-저장-제약안)·[ERD 이슈 #66](https://github.com/Hi-Hops/Hops/issues/66) · Spring/데이터 | 연계 검토 |
| C10 | 외부 wire JSON Schema·예시, 내부 State 작성/병합·종료·결과 적용 | [State 계약](../contracts/agents/README.md#내부-state와-wire-검증-계약) · AI/Spring | 리뷰 대기 |

각 행은 미작성 항목이 아니라 구체 제안의 승인 대기다. 검토자는 채택/수정과 이유를 PR에 남기고 병합 후 해당 문서의 상태를 갱신한다. 실제 코드 상태와 합의 상태를 같은 완료 칸으로 합치지 않는다.

## 적용 전 선정 항목

| ID | 선정할 값·산출물 | 담당 | 적용 전 조건 |
|---|---|---|---|
| S01 | OpenAI 모델 ID·임베딩 ID·차원·modelProfileId·가격 기준일 | AI/데이터 | 실제 호출 코드 연결 전 검수·비용 비교. 특정 개인 실험을 기본값으로 강제하지 않음 |
| S02 | maxModelCalls/maxInputTokens/maxOutputTokens/maxToolCalls/maxRetries/maxCostUsd와 전체 실행 기한 | AI/Spring | 누적 제한과 초과 검증을 포함한 실행 manifest. 미선정이면 real 실행 비활성 |
| S03 | Vector 제품/컬렉션·접속 환경, Neo4j 허용 그래프 카탈로그·검색 평가 | 데이터/AI | 같은 임베딩·출처·ACL·공개 gate 검증. 기존 데이터셋을 무검수 재사용하지 않음 |
| S04 | 시스템/기능 UUID·가상 사용자·PRIMARY seed, 카탈로그 노드/관계 출처 | 데이터/Spring | 세 시스템·단일 유효 PRIMARY·개인정보 없는 seed 검수 |
| S05 | 물리 DDL·잠금/outbox·journal 보존·인덱스, 부서 데이터 이관·롤백 계획 | Spring/데이터 | [저장 제약안](../docs/DATA.md#9-hops-system-1-저장-제약안)의 경합 테스트와 마이그레이션 리뷰 |
| S06 | API/AI origin·서비스 토큰 배포·CORS·쿠키/TLS·접근 회수 구현 | Spring/AI | 실제 환경 인증·권한·브라우저 통합 검증 |
| S07 | 대표 시나리오·평가 분할·데이터 동결·목표·담당·일정 | 전체 | 미래 해결/정답 유출 없는 평가와 E2E 시연 결과 |
| S08 | AI RunLedger의 재시작 보존·저장 방식·보존 범위와 운영 환경 | AI/Spring | 같은 runId 재접수로 모델 호출이 중복되지 않는지, 복원 불가 UNKNOWN·새 Run 복구 검사 |

선정은 기능 확장을 미루는 절차가 아니다. 모든 업무·화면·전송·엔티티·State 범위는 C01~C10에 포함한다. 실제 공급자/환경/수치가 필요한 항목은 담당자가 근거와 결과를 공유해 구현 연결 전에 선정한다. 기존 코드의 과거 모델·예산값은 신규 선정값이 아니다.

## 결정 기록 방법

계약 항목 ID, 채택한 값/방식, 생산자·소비자, 리뷰 PR, 적용 프로필·코드 전환 시점, 검사 결과를 해당 PR에 남긴다. 개인 대화 원문·세션 ID·비밀값은 공유 문서에 넣지 않는다. 계약 수정 후에는 정본·예시·기능 시나리오·서비스 소비자 영향을 함께 검토한다.
