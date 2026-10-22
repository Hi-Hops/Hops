# 프론트엔드 개발 기준

상태: **초안 · 팀 리뷰 전** · 작성 2026-10-22 · 관련 이슈: #6(초기 세팅), #30·#31(MVP 화면)

React 화면을 누가(사람이든 AI든) 구현하더라도 **같은 환경·같은 구조·같은 형식**으로 코드를 쓰기 위한 기준이다. 무엇을 만들지는 [기능 스펙](../../specs/README.md)이 정하고, 이 폴더는 **어떻게 만들지**를 정한다.

제품 정책은 [PRD](../PRD.md), 통신 계약안은 [API](../API.md), 저장 규칙은 [DATA](../DATA.md)가 원본이다. 이 폴더는 원본 문장을 복제하지 않는다. 원본·스펙과 이 문서가 다르면 원본을 따르고 이 문서를 고친다. 공통 협업 규칙은 루트 [AGENTS.md](../../AGENTS.md), 브랜치·커밋·PR은 [Git 컨벤션](../../GIT_CONVENTION.md)을 따른다.

## 문서 지도

| 문서 | 정하는 것 | 언제 읽나 |
|---|---|---|
| [TOOLING.md](TOOLING.md) | 버전, 패키지, `package.json` 스크립트, TypeScript·Vite·ESLint·Prettier 설정 전문 | #6 초기 세팅, 의존성을 바꿀 때 |
| [STRUCTURE.md](STRUCTURE.md) | 폴더 구조, 레이어 의존 방향, 파일·이름 규칙, 상태를 두는 위치 | 파일을 새로 만들 때마다 |
| [CONVENTIONS.md](CONVENTIONS.md) | TypeScript·React 문법 규칙, 통신·오류·실시간 처리, 스타일, 접근성, 테스트, 주석 | 코드를 쓸 때마다 |
| [CONTRACT_TYPES.md](CONTRACT_TYPES.md) | 공통 Schema·생성 타입 참조와 계약 상태 표기 규칙 | 서버와 주고받는 데이터를 다룰 때 |
| [COMPONENTS.md](COMPONENTS.md) | 공통 컴포넌트(헤더·내비게이션·상태 표시·모달·입력) 목록과 계약 | 화면을 조립할 때 |
| [ROUTES.md](ROUTES.md) | 라우팅 명세: 경로·화면·권한·레이아웃·스펙 연결 (#6 작업 목록 3) | 화면을 추가할 때 |
| [WORKFLOW.md](WORKFLOW.md) | 스펙 기능 하나를 구현하는 순서와 완료 체크리스트 | 기능 작업을 시작할 때 |

## AI·개발자 작업 진입점

프론트엔드 코드를 쓰기 전에 아래 순서로 읽는다. 읽지 않은 문서를 읽었다고 가정하지 않는다(AGENTS.md).

1. [AGENTS.md](../../AGENTS.md) → [GIT_CONVENTION.md](../../GIT_CONVENTION.md)
2. 이 README
3. [WORKFLOW.md](WORKFLOW.md) — 작업 순서와 완료 조건
4. 맡은 기능의 스펙(`specs/Fnnn-*/spec.md`)과 [합의 질문](../../specs/QUESTIONS.md)
5. 작업에 필요한 기준 문서(STRUCTURE·CONVENTIONS·CONTRACT_TYPES·COMPONENTS·ROUTES)

## 확정된 것과 아닌 것

| 구분 | 내용 | 근거 |
|---|---|---|
| 확정 | Node.js 24.22.0, React·React DOM 19.3.0, Vite 8.3.4, @vitejs/plugin-react 6.1.2 | PRD 5.1 · AGENT_DEVELOPMENT 10 |
| 확정 | React는 로그인·조회·티켓 업무를 Spring REST로, 문의·메시지·정정·새 문의 동의를 Node.js Socket.IO로 보낸다 | PRD 5.1 · API 1, 2.4 |
| 제안(팀 승인 전) | 나머지 라이브러리·설정·구조·규칙 전체. TOOLING.md의 조합은 임시 프로젝트에서 설치·타입 검사·린트·Prettier·테스트·빌드를 통과했다(로컬 Node 24.20.0) | 이 문서 |
| 미정 | JWT 브라우저 보관·갱신·CSRF, Socket.IO 서버 버전, 이벤트 스키마, 상태 코드·오류 코드 | PRD 8.5 · [QUESTIONS](../../specs/QUESTIONS.md) |

미정 항목은 이 문서들이 기본값으로 확정하지 않는다. 코드에서는 [CONTRACT_TYPES.md](CONTRACT_TYPES.md)의 `@contract 미정` 표기와 [WORKFLOW.md](WORKFLOW.md) 4절의 처리 규칙을 따른다.

## 이 문서를 바꾸는 법

- 규칙 변경은 `develop`에서 `docs/*` 브랜치로 PR을 열고 작성자 외 1명 이상의 리뷰를 받는다(Git 컨벤션 2절).
- 설정 파일(TOOLING.md)을 바꾸면 실제 `frontend/`의 같은 파일도 같은 PR에서 바꾼다. `frontend/`가 만들어진 뒤에는 **코드의 설정 파일이 원본**이고 TOOLING.md는 그 이유와 변경 기록만 남긴다.
- 전송 타입의 원본은 [공통 JSON Schema](../../specs/contracts/README.md)다. 프론트·Node·Java·Python 타입을 함께 생성하고 직접 고치지 않는다. 프론트 코드가 생겨도 wire 계약 원본은 Schema로 유지하며 화면 전용 모델만 프론트 코드에서 관리한다.
