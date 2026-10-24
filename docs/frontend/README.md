# 프론트엔드 개발 기준

상태: 팀 리뷰 전 초안. 이 폴더는 프론트 코드를 함께 작성하기 위한 기준이다. 실행 방법·환경 변수는 [프론트 실행 안내](../../frontend/README.md), 기능과 데이터 계약은 [스펙](../../specs/README.md)에서 관리한다.

## 읽는 순서

1. [프로젝트 공통 지침](../../AGENTS.md), [PRD](../PRD.md), [Git 컨벤션](../../GIT_CONVENTION.md).
2. 맡은 기능의 스펙과 [공통 데이터 타입](../../specs/contracts/DATA_TYPES.md)·[전송 계약](../../specs/contracts/WIRE_CONTRACT.md).
3. [구조](STRUCTURE.md): 코드 위치·레이어 의존·상태 보관·실제 공통 UI.
4. [컨벤션](CONVENTIONS.md): 코드·스타일·접근성·테스트·기능 완료 조건.
5. [화면 경로](ROUTES.md): 구현된 주소·접근 범위·URL 조회 조건.

문서만으로 미정 정책이나 구현 제안을 팀 승인으로 바꾸지 않는다. 운영 미정 사항은 [합의 질문](../../specs/QUESTIONS.md)을 따른다.

## 원본을 두는 위치

- 설치·Docker·`.env`·화면 확인 방법: `frontend/README.md`.
- 패키지와 버전: `frontend/package.json`, `package-lock.json`, `.nvmrc`. 문서에 설정 전문을 복제하지 않는다.
- 타입·빌드·테스트·코드 검사 설정: `frontend/tsconfig*.json`, `vite.config.ts`, `eslint.config.js`, `.prettierrc.json`.
- 제품 동작·필드·상태·오류·권한 의미: PRD·API와 `specs/`의 기능·계약 문서.
- 기능별 변경 내용·검사 결과·미실행 항목·화면 증거: 해당 PR 설명. 누적 작업 보고서를 개발 기준으로 유지하지 않는다.

React·TypeScript·Vite를 사용한다. 조회 데이터는 TanStack Query, 로그인·미전송 입력 같은 공용 클라이언트 상태는 Zustand, 모달의 키보드·포커스 처리는 Radix를 사용한다. 스타일은 CSS Modules와 공통 토큰을 따른다. 필요한 경우에만 의존성을 추가하고 이유와 영향은 PR에서 리뷰한다.

기준을 변경할 때는 영향받는 코드·스펙·설정을 함께 갱신한다. 공통 Git 절차와 승인 조건은 루트 문서에서 관리한다.
