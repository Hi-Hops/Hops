# 코드 구조

범위: `frontend/src`. 레이어 의존은 [ESLint 설정](../../frontend/eslint.config.js)의 import 제한으로 검사한다.

## 1. 코드 위치와 의존 방향

```text
src/
├─ app/       라우터·Provider·인증/역할 가드·앱 레이아웃
├─ pages/     경로별 화면 조립
├─ features/  auth·realtime·chat·inquiry·ticket의 동작과 도메인 UI
├─ shared/    api·realtime·ui·lib·config·styles
└─ test/      공통 테스트 설정과 렌더 도우미
```

의존 방향은 **app → pages → features → shared**다. `shared`는 특정 기능을 가져오지 않는다. `features`는 다른 기능의 공개 진입점(`@/features/<기능>`)으로만 참조하며 순환 의존을 만들지 않는다. `pages`는 기능을 조립하고 공용 API를 사용하며 기능 내부 파일을 직접 가져오지 않는다.

기능 내부는 필요한 `api/`, `hooks/`, `model/`, `components/`와 공개 `index.ts`를 둔다. 앞으로 쓸 수 있다는 이유만으로 폴더·컴포넌트를 미리 만들지 않는다. 같은 기능 내부는 한 단계 상대 경로를 사용할 수 있고 다른 레이어·기능은 `@/` 별칭을 사용한다. `../../` 경로는 쓰지 않는다.

기능 대응은 auth=F001, realtime=F002, chat=F003·F004, inquiry=F005·내 문의 최소 목록, ticket=F009·관리자 최소 조회다. 후속 기능은 해당 스펙을 구현할 때 구조를 추가한다.

## 2. 이름과 공개 진입점

- 폴더는 kebab-case, 컴포넌트·페이지는 PascalCase, 훅은 `useXxx`, 일반 TS 파일은 camelCase다.
- 컴포넌트·스타일·테스트는 같은 위치에 `Name.tsx`, `Name.module.css`, `Name.test.tsx`로 둔다.
- 공개 `index.ts`에는 외부에서 실제로 사용하는 것만 내보낸다. 기능 구현을 통째로 재노출하지 않는다.
- 서버 필드·이벤트 이름은 계약 문서의 이름을 유지한다. 표시명은 소유 기능의 `model/`에 둔다.
- 실제 경로 등록은 `app/router.tsx`, 공용 경로 생성기는 `shared/config/paths.ts`에서 관리한다.

## 3. 상태를 두는 위치

- 조회한 문의·메시지·티켓: TanStack Query 캐시. 별도 Zustand/useState 사본을 만들지 않는다.
- 목록 필터·페이지·상세 대상: URL 경로와 쿼리. 상세를 닫아도 목록 조건을 유지한다.
- 한 화면의 열림·편집 상태: 컴포넌트 로컬 상태.
- 로그인·연결·여러 화면을 거치는 미전송 입력: 소유 기능의 상태 저장소.
- 분석 진행·답변 조각: 임시 상태. 저장 결과처럼 Query 캐시에 확정하지 않는다.

저장 이벤트 후 조회 캐시를 무효화하고 서버 결과로 맞춘다. 재접속·계정 전환과 입력 보존 규칙은 [전송 계약](../../specs/contracts/WIRE_CONTRACT.md)을 따른다.

## 4. 현재 공통 UI

공통 구현은 [shared/ui](../../frontend/src/shared/ui)에 있다. 정확한 props는 각 소스의 타입을 확인한다.

- `Button`: primary/secondary/ghost/danger. 로딩 시 비활성화하고 버튼 이름을 유지한다. 기본 type은 button이다.
- `TextField`: 보이는 label과 error/hint를 연결한다. 비밀번호는 input의 password 타입을 사용한다.
- `LoadingState`: 조회 중 status 안내.
- `ErrorState`: 제목·설명·선택적 다시 시도. 빈 결과와 구분한다.
- `StatusBadge`: label과 neutral/progress/attention/success/danger tone. 색 외에 문구를 제공한다.
- `RouteModal`: Radix Dialog 기반. 제목·설명·닫기·포커스 복귀를 처리한다. URL 변경과 목록 조건 유지는 호출 기능이 담당한다.

헤더·메뉴는 `app/layouts/AppLayout`, 연결 상태는 `features/realtime`에서 조립한다. 기능 지식이 들어간 결과 카드·정정·티켓 UI는 소유 기능 안에 둔다. 공통 UI는 데이터를 조회하지 않고 props로 받는다. 실제 재사용이 필요할 때 공통으로 올리고, 추가하면 이 목록과 접근성 테스트를 함께 갱신한다.

`AppLayout`의 사이드바는 상단 업무 메뉴와 하단 개인 채팅 세션으로 구성한다. `features/inquiry/components/RecentInquiries`가 세션 목록을 표시하고 같은 기능의 API 훅이 InquiryPage를 순차 조회한다. 서버 응답은 Query 캐시에서 관리하며 별도 클라이언트 목록 사본을 만들지 않는다. 새 문의 버튼은 기존 draft 저장소의 `new` 입력을 유지한 채 메인 입력창에 포커스를 옮긴다.

## 5. 타입·Mock·스타일

- 프론트 전송 타입은 `shared/api/types`에서 [공통 타입 문서](../../specs/contracts/DATA_TYPES.md)에 맞춰 직접 관리한다. 백엔드·AI용 타입을 프론트에 미리 추가하지 않는다.
- 공용 예시는 `specs/mocks/`, 읽기 어댑터는 `shared/api/mockData.ts`다. 테스트 전용 인증 값은 `test/mockCredentials.ts`에서 생성하며 개인 `.env`를 재사용하지 않는다.
- 현재 `shared/api/mockBackend.ts`, `mockWorkspace.ts`, `shared/realtime/mockTransport.ts`는 실제 서버와 분리한 메모리 대역이다.
- 화면의 조회·변경 REST는 `shared/api/client.ts`, 실시간 SSE는 `shared/realtime/index.ts`를 사용한다. `shared/config/connection.ts`의 모드에 따라 Mock 또는 fetch 기반 SSE 어댑터를 선택하며 기능에서 Mock 구현을 직접 import하지 않는다. 문의 생성·메시지 전송·정정은 기능 API에서 REST로 호출하고 실시간 계층에 명령을 보내지 않는다. 설정·연동 방법은 [실행 안내](../../frontend/README.md), 요청 URL·이벤트 이름은 [전송 계약](../../specs/contracts/WIRE_CONTRACT.md)에 둔다.
- CSS Modules를 사용하고 색·간격·폰트·모서리는 `shared/styles/tokens.css`의 변수를 재사용한다. 전역 스타일은 `shared/styles/`에서 관리한다. 디자인 자산은 `frontend/public/assets/`에 있다.
