# 프론트엔드 개발 기준

> **PRD 1.44 전환:** 제품 목표는 [PRD](../PRD.md)와 기능 명세의 목표 인수 조건을 따른다. 기존 WIRE_CONTRACT 10절과 Mock은 부서 기반 현재 구현 프로필이며 새 시스템 배정·해결 확인·사례 승인 계약이 아니다. 실제 코드 구조와 목표 화면은 구분한다.

상태: 팀 리뷰 전 초안. 이 폴더는 프론트 코드를 함께 작성하기 위한 기준이다. 실행 방법·환경 변수는 [프론트 실행 안내](../../frontend/README.md), 기능과 데이터 계약은 [스펙](../../specs/README.md)에서 관리한다.

## UI 기준과 이번 작업 범위

UI 기준은 develop `d896faeb2a0b132da2c3212cba408bf880d797cf`의 현재 frontend 구현이다. 기존에 새로 작성한 Figma 화면은 이번 구현의 기준에서 제외한다. 화면 기능·상태·권한은 PRD와 공통 계약/spec을 따르고 화면 배치·스타일·상호작용은 아래 기존 구현을 확장한다.

- 공통 헤더·사이드바·최근 채팅·로그아웃: [AppLayout](../../frontend/src/app/layouts/AppLayout.tsx).
- 새 문의·채팅·입력·원문 이력: [HomePage](../../frontend/src/pages/home/HomePage.tsx)·[ChatView](../../frontend/src/features/chat/components/ChatView.tsx)·[MessageComposer](../../frontend/src/features/chat/components/MessageComposer.tsx).
- 담당 업무 보드·목록·상세: [TicketBoard](../../frontend/src/features/ticket/components/TicketBoard.tsx)·[TicketKanban](../../frontend/src/features/ticket/components/TicketKanban.tsx)·[TicketDetail](../../frontend/src/features/ticket/components/TicketDetail.tsx).
- 버튼·입력·상태·모달·색/간격: [공통 UI](../../frontend/src/shared/ui)·[디자인 토큰](../../frontend/src/shared/styles/tokens.css).

기존 말풍선·결과 카드·상세/모달의 표현을 재사용하고, 시스템 선택·해결 확인·처리 기록·CASE 검토에 필요한 영역과 입력만 같은 구성 안에 추가한다. 목표 경로는 ROUTES, 기능 책임과 데이터 소유는 STRUCTURE를 정본으로 삼는다. 새 Figma와의 일치 작업이나 전체 레이아웃 재설계는 구현 선행 조건으로 두지 않는다.

이번 단계의 산출물은 계약·spec·목표 프론트 구조의 결정 문서와 검토 예시다. 현재 `frontend/` 소스·실행 Mock·router가 신규 기획에 맞게 구현됐다는 뜻이 아니다. 문서 PR 승인 뒤 신규 계약용 어댑터/Mock과 기능을 의존 순서대로 구현하고 실제 서버 준비 후 서비스 연결을 검증한다.

## 읽는 순서

1. [프로젝트 공통 지침](../../AGENTS.md), [PRD](../PRD.md), [Git 컨벤션](../../GIT_CONVENTION.md).
2. 맡은 기능의 스펙과 [공통 데이터 타입](../../specs/contracts/DATA_TYPES.md)·[전송 계약](../../specs/contracts/WIRE_CONTRACT.md).
3. [구조](STRUCTURE.md): 코드 위치·레이어 의존·상태 보관·실제 공통 UI.
4. [컨벤션](CONVENTIONS.md): 코드·스타일·접근성·테스트·기능 완료 조건.
5. [화면 경로](ROUTES.md): 구현된 주소·접근 범위·URL 조회 조건.

기존 구현의 개발 프로필은 전송 계약 10절을 참고하며 새 제품 정책은 PRD 1.44를 따른다. 담당 구현·미검증은 11절, 신규 실행·저장·환경·검증 기본값은 [계약 결정 대장](../../specs/QUESTIONS.md)을 따른다. 결정값 작성 완료를 팀 리뷰/병합 또는 실제 서버 구현 완료와 구분한다.

## 원본을 두는 위치

- 설치·Docker·`.env`·화면 확인 방법: `frontend/README.md`.
- 패키지와 버전: `frontend/package.json`, `package-lock.json`, `.nvmrc`. 문서에 설정 전문을 복제하지 않는다.
- 타입·빌드·테스트·코드 검사 설정: `frontend/tsconfig*.json`, `vite.config.ts`, `eslint.config.js`, `.prettierrc.json`.
- 제품 동작·필드·상태·오류·권한 의미: PRD·API와 `specs/`의 기능·계약 문서.
- 기능별 변경 내용·검사 결과·미실행 항목·화면 증거: 해당 PR 설명. 누적 작업 보고서를 개발 기준으로 유지하지 않는다.

React·TypeScript·Vite를 사용한다. 조회 데이터는 TanStack Query, 로그인·미전송 입력 같은 공용 클라이언트 상태는 Zustand, 모달의 키보드·포커스 처리는 Radix를 사용한다. 스타일은 CSS Modules와 공통 토큰을 따른다. 필요한 경우에만 의존성을 추가하고 이유와 영향은 PR에서 리뷰한다.

기준을 변경할 때는 영향받는 코드·스펙·설정을 함께 갱신한다. 공통 Git 절차와 승인 조건은 루트 문서에서 관리한다.

## hops-system-1 구현 준비

PRD 1.44의 전체 MVP에 대한 구체 계약·화면안은 [공통 계약](../../specs/contracts/README.md)·[목표 경로](ROUTES.md#hops-system-1-목표-경로안)·각 기능의 CONTRACT 시나리오를 따른다. 이는 팀 리뷰안이며 현재 부서 기반 코드가 전환됐다는 뜻이 아니다.

필드/상태는 DATA_TYPES 신규 절, 경로/오류/SSE는 WIRE_CONTRACT 12절, 업무 전이는 TARGET_CONTRACT를 정본으로 삼는다. docs/frontend에는 동일 표를 복제하지 않는다. 새 DTO·API adapter를 연결할 때 기존 /api·hops-mock-1과 신규 /api/v2·hops-system-1을 명시적으로 분리한다. 현재 폴더 레이어·공통 UI·상태 도구를 재사용하며 새 프레임워크는 추가하지 않는다.

기존 프론트 테스트·Mock 통과를 신규 계약 통과로 기록하지 않는다. 문서/예시 검사와 실제 화면·서버 저장/권한·AI/검색·E2E를 구분한다. 실제 router·구현 현황은 코드 전환 뒤 갱신한다.


## ERD 대조 후 프론트 구현 범위

아래는 새 hops-system-1 구현 작업의 범위·순서다. 현재 프론트 코드를 문서만으로 전환하지 않는다. 저장 설계는 [병합 ERD와 보완](../ERD.md#6-hops-system-1-공통-계약-보완), wire 변환은 [DATA11절](../DATA.md#11-완성-erd-대조-결과와-병합-기준)을 따른다.

| 순서·기존 위치 | 구현할 내용 | 완료 조건 |
|---|---|---|
| 1 shared/api/types·client 및 기능 API | 신규 DTO/프로필 어댑터로 /api/v2 명령 receipt·후속 GET·오류를 연결. 기존 /api/Mock 타입을 직접 덮어쓰지 않음 | PrimaryAssignment2필드, inquiryNumber, WorkRecord 새 필드/종류, CaseVersion 새 필드·POLICY null systemId 검증 |
| 2 auth·ticket/model/permissions·메뉴 | department.isRoutable/AGENT_OPS_ADMIN 기반을 신규 CurrentUser.primarySystemIds/BIZ_ADMIN으로 전환 | 서버의 현재 PRIMARY와 관리자 제한 DTO 사용. 복수 시스템 담당·권한 회수 검사 |
| 3 inquiry/model/status·채팅·결과 카드 | wire TicketState/ProblemState 표시, 서버 전달 ticketRevision 그대로 왕복, Q 표시 번호/UUID 경로 구분 | DB OPEN/WAITING_CONFIRM/version0을 직접 해석하지 않음. UNKNOWN과 확정 미생성을 구분 |
| 4 정정·관련 대화·담당 상세 | message revision1/원문 이력·보완 대상 연결, 허용 구간 조회, 새 run 뒤 최신 표준 요청 스냅샷 | 입력 보존·409 재조회·다른 문제 원문 제외·기존 성공 티켓 유지 |
| 5 ticket/components/TicketDetail 처리 영역 | 공통 입력/모달로 PROGRESS·FAILED_ATTEMPT·검증 완료·구현 검증을 기록. confirmedCause/appliesToSystemVersion은 모르면 null | 실패 시도는 이력만, 완료 두 종류는 검증 필수·문의자 확인 요청 생성. 같은 티켓의 처리 주기 구분 |
| 6 기존 상세의 사례 탭·목표 /cases | caseType/문제 설명/원 기록의 확인된 원인, 새 불변 버전 편집·승인·같은 버전 양쪽 반영 상태 | 원 기록의 확인 요청 RESOLVED 검사·부분 실패/UNKNOWN 확인·실패 저장소만 수동 재실행 |
| 7 realtime·실제 서비스 검증 | 진행/답변 조각과 저장 완료를 분리하고 현재 GET으로 재동기화. 기존 문의/티켓/사례 화면에서 상태 전달 | 신규 replay 없음·권한 회수·문서/Schema/Mock/실제 저장/AI/검색/E2E를 별도로 검증 |

기존 Button/TextField/StatusBadge/RouteModal·디자인 토큰·목표 경로를 재사용한다. notification 테이블은 기존 화면 상태와 진입 링크에 연결하며 별도 알림함·통계·복구 전용 화면을 이번 작업에 추가하지 않는다. 공통 POLICY는 시스템 배지에 '공통 정책'으로 표시하고 임의 시스템명을 채우지 않는다. 무응답 종료·CS 검토·선점 UI는 신규 프로필에 포함하지 않는다.

담당 상세에는 기존 정정 모달을 재사용하는 조사/장애 처리 유형 정정 입력을 추가한다. ProcessingTypeCorrectionCommand로 근거·사유·expectedTicketRevision을 보내고 현재 담당자만 조작한다. 해당 변경은 상태를 자동 완료하지 않는다.
