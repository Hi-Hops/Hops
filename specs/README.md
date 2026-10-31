# 기능 명세와 공통 계약

기준: [PRD 1.44](../docs/PRD.md). F001~F011은 제품 목표와 `hops-system-1` 구체 계약·화면의 팀 리뷰안이다. 계약안 작성·승인은 구현이나 실제 서비스 통합 완료가 아니다. 현재 부서 기반 코드·Mock은 별도 전환 전 프로필이다.

## 기능과 계약 연결

| ID | 기능·화면 | 주요 선행 계약 |
|---|---|---|
| [F001](F001-auth/spec.md) | 로그인·현재 권한·공통 메뉴 | CurrentUser·현재 PRIMARY·auth·권한 회수 |
| [F002](F002-realtime/spec.md) | 공개 진행·상태 변화·복구 | 구독 대상·이벤트·REST 재조회·현재 권한 |
| [F003](F003-chat/spec.md) | 새 문의·채팅·질문·시스템 선택·가이드 | 문의/메시지·버전·표준 요청·GUIDE 확인 |
| [F004](F004-revision/spec.md) | 원문 정정·이력·기존 티켓 보완 | 메시지 revision·문의 버전·멱등성 |
| [F005](F005-result-cards/spec.md) | 문제별 결과·접수 안내 모달 | 생성 결과·저장 티켓·resultRevision |
| [F006](F006-my-inquiries/spec.md) | 내 문의·문제별 해결 확인 | 확인 요청·원 처리 기록·응답 경합 |
| [F007](F007-admin-view/spec.md) | 업무 관리·제한된 상세·정정 | 관리자 투영·공통 수동 정정 |
| [F008](F008-split-consent/spec.md) | 같은 문의 안의 복합 문제 | 안정적인 문제 ID·생성 작업·독립 진행 |
| [F009](F009-department-tickets/spec.md) | 담당 시스템 업무·상세·처리 | 시스템 조회·관련 대화·처리 기록·정정 |
| [F010](F010-creation-failures/spec.md) | 저장 확인·실패 작업 복구 | workItem 유일성·미확인/미생성·재시도 |
| [F011](F011-case-lifecycle/spec.md) | 사례 검토·승인·검색 반영 | Curator·버전·사용자 확인·양쪽 반영·ACL |

F008/F009 디렉터리 이름은 기존 참조 경로를 보존한다. 새 문의 분리 동의·부서 선점은 새 MVP 목표가 아니다. 새 기능 spec 파일을 추가하지 않고 위 명세 안에서 화면·계약·검증을 연결한다.

## 정본과 변경 순서

1. [TARGET_CONTRACT](contracts/TARGET_CONTRACT.md)의 관계·상태·권한을 검토한다.
2. [DATA_TYPES](contracts/DATA_TYPES.md#신규-계약-hops-system-1)와 [WIRE_CONTRACT 12절](contracts/WIRE_CONTRACT.md#12-신규-계약-hops-system-1)의 필드·REST·SSE·오류를 검토한다.
3. [에이전트 계약](../contracts/agents/README.md#hops-system-1-실행-계약안)·저장 제약과 각 기능의 CONTRACT 시나리오를 함께 리뷰한다.
4. 실제 선정·환경·이관 결정은 [QUESTIONS](QUESTIONS.md), 구현 작업 의존 관계는 [ISSUE_MAP](ISSUE_MAP.md)에 기록한다.

전체 MVP 계약을 먼저 설계하며 구현은 의존 순서대로 진행한다. 공통 규칙을 기능별로 중복 정의하지 않는다. 목표 경로는 ROUTES의 구현 경로와 구분한다. 기존 UI·토큰·메시지 이력·입력 보존·SSE 기반을 재사용한다.

## 구현·검증 구분

각 spec의 기존 TARGET/DETAIL 조건은 보존하고 신규 계약 검증에는 Fxxx-CONTRACT 번호를 사용한다. 이전 코드의 AC 테스트 통과를 신규 시나리오 통과로 옮겨 적지 않는다. 문서/예시·Mock·HTTP/SSE 대역·실제 Spring/DB·AI/검색·브라우저 E2E를 따로 기록한다. [신규 JSON 예시](mocks/hops-system-1.examples.json)는 가상 검토 자료이며 실행 Mock 대체물이 아니다.
