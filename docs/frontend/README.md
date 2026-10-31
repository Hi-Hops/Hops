# 프론트엔드 개발 기준

> **PRD 1.44 전환:** 제품 목표는 [PRD](../PRD.md)와 기능 명세의 목표 인수 조건을 따른다. 기존 WIRE_CONTRACT 10절과 Mock은 부서 기반 현재 구현 프로필이며 새 시스템 배정·해결 확인·사례 승인 계약이 아니다. 실제 코드 구조와 목표 화면은 구분한다.

상태: 팀 리뷰 전 초안. 이 폴더는 프론트 코드를 함께 작성하기 위한 기준이다. 실행 방법·환경 변수는 [프론트 실행 안내](../../frontend/README.md), 기능과 데이터 계약은 [스펙](../../specs/README.md)에서 관리한다.

## 읽는 순서

1. [프로젝트 공통 지침](../../AGENTS.md), [PRD](../PRD.md), [Git 컨벤션](../../GIT_CONVENTION.md).
2. 맡은 기능의 스펙과 [공통 데이터 타입](../../specs/contracts/DATA_TYPES.md)·[전송 계약](../../specs/contracts/WIRE_CONTRACT.md).
3. [구조](STRUCTURE.md): 코드 위치·레이어 의존·상태 보관·실제 공통 UI.
4. [컨벤션](CONVENTIONS.md): 코드·스타일·접근성·테스트·기능 완료 조건.
5. [화면 경로](ROUTES.md): 구현된 주소·접근 범위·URL 조회 조건.

기존 구현의 개발 프로필은 전송 계약 10절을 참고하며 새 제품 정책은 PRD 1.44를 따른다. 담당 구현·미검증은 11절, 실제 환경 선정·후속 상세 설계는 [QUESTIONS](../../specs/QUESTIONS.md)를 따른다. 개발 기준 선택을 팀 리뷰/병합 또는 실제 서버 구현 완료와 구분한다.

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
