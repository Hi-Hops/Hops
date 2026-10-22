# 폴더 구조와 레이어 규칙

상태: 초안 · 범위: `frontend/src` · 강제 수단: ESLint `no-restricted-imports`([TOOLING 6절](TOOLING.md#6-eslint))

## 1. 전체 구조

```text
frontend/src/
├─ main.tsx                 # 진입점. app/만 가져온다
├─ vite-env.d.ts
├─ app/                     # 앱 조립: 라우터·Provider·레이아웃·권한 가드
│  ├─ router.tsx            # ROUTES.md의 표를 코드로 옮긴 곳(경로 정의는 여기 한 곳)
│  ├─ providers.tsx         # QueryClient·인증·소켓 Provider
│  ├─ layouts/              # AppLayout(헤더+내비게이션), AuthLayout(로그인)
│  ├─ guards/               # RequireAuth, RequireRole
│  └─ navigation.ts         # 역할별 메뉴 정의(COMPONENTS 2절)
├─ pages/                   # 경로 하나 = 폴더 하나. 기능을 조립만 한다
│  ├─ login/LoginPage.tsx
│  ├─ home/HomePage.tsx
│  ├─ inquiry-chat/InquiryChatPage.tsx
│  ├─ inquiries/InquiriesPage.tsx
│  ├─ tickets/TicketsPage.tsx
│  ├─ admin-tickets/AdminTicketsPage.tsx
│  ├─ operator-recovery/OperatorRecoveryPage.tsx
│  └─ errors/ForbiddenPage.tsx, NotFoundPage.tsx
├─ features/                # 스펙 기능 단위. 화면 로직·데이터 통신·도메인 컴포넌트
│  ├─ auth/                 # F001
│  ├─ realtime/             # F002 (연결 상태·구독 훅)
│  ├─ chat/                 # F003, F004, F008
│  ├─ inquiry/              # F005, F006
│  ├─ ticket/               # F007, F009
│  └─ creation-failure/     # F010
├─ shared/                  # 기능을 모르는 공통 코드
│  ├─ api/                  # client.ts, queryClient.ts, types/(CONTRACT_TYPES.md)
│  ├─ realtime/             # socket.ts(연결 생성·명령 전송·ACK 처리)
│  ├─ ui/                   # 공통 컴포넌트(COMPONENTS.md)
│  ├─ lib/                  # 순수 함수(글자 수, 날짜 표시, requestId 생성 등)
│  ├─ config/               # env.ts(환경 변수 읽기)
│  └─ styles/               # tokens.css, global.css
└─ test/                    # setup.ts, fixtures/, renderWithProviders.tsx
```

기능 폴더와 스펙의 대응은 [WORKFLOW 2절](WORKFLOW.md#2-기능과-폴더의-대응)에 있다. 새 기능 폴더는 스펙이 생길 때만 만든다.

## 2. 기능 폴더 내부

```text
features/<기능>/
├─ index.ts                 # 공개 진입점. 밖에서 쓰는 것만 export
├─ api/<기능>Api.ts          # REST 함수와 쿼리 키
├─ realtime/                # 이 기능이 쓰는 소켓 명령·이벤트 처리(필요할 때만)
├─ hooks/use<이름>.ts        # 화면이 쓰는 훅(useQuery·useMutation 감싸기)
├─ components/<이름>.tsx     # 이 기능 전용 컴포넌트
│  ├─ <이름>.module.css
│  └─ <이름>.test.tsx
└─ model/                   # 순수 로직: 상태 표시명, 매핑, 검증, zustand 스토어
```

- 하위 폴더는 필요할 때만 만든다. 빈 폴더를 미리 만들지 않는다.
- `index.ts`에는 다른 레이어가 실제로 쓰는 것만 내보낸다. `export *`는 쓰지 않는다(타입 모음 `shared/api/types/index.ts`의 `export type *`만 예외).

## 3. 레이어 의존 방향

```text
app  →  pages  →  features  →  shared
```

| 레이어 | 가져와도 되는 것 | 가져오면 안 되는 것 |
|---|---|---|
| `app` | pages, features(공개 진입점), shared | — |
| `pages` | features(공개 진입점), shared | app, 기능 내부 파일(`@/features/x/components/...`) |
| `features` | shared, 다른 기능의 공개 진입점 | app, pages, 다른 기능의 내부 파일 |
| `shared` | shared 안의 다른 폴더, 외부 패키지 | app, pages, features |

- 기능끼리 서로 가져오는 순환을 만들지 않는다. 두 기능이 같은 것을 필요로 하면 `shared`로 내린다(단, 기능 지식이 들어가면 내리지 않고 한쪽 기능의 공개 진입점으로 연다).
- 같은 폴더 안은 상대 경로(`./`, `../`), 그 밖은 `@/` 별칭을 쓴다. 두 단계 이상 상위 상대 경로(`../../`)는 린트 오류다.
- `pages`는 데이터를 직접 가져오지 않는다. 기능의 훅을 부르고 컴포넌트를 배치한다.

## 4. 이름 규칙

| 대상 | 형식 | 예 |
|---|---|---|
| 폴더 | kebab-case | `creation-failure/`, `inquiry-chat/` |
| 컴포넌트 파일·컴포넌트 | PascalCase, 파일 하나에 공개 컴포넌트 하나 | `TicketCard.tsx` → `TicketCard` |
| 페이지 | `<이름>Page` | `InquiriesPage` |
| 훅 | `use` + camelCase | `useInquiryResult.ts` |
| 그 밖의 TS 파일 | camelCase | `inquiryApi.ts`, `statusLabels.ts` |
| 타입 | PascalCase, `type`으로 선언 | `TicketCard`, `InquiryStatus` |
| 컴포넌트 props 타입 | `<컴포넌트>Props` | `TicketCardProps` |
| 상수 | UPPER_SNAKE_CASE | `ACK_TIMEOUT_MS`, `MAX_MESSAGE_CODE_POINTS` |
| 쿼리 키 | `<기능>Keys` 객체 | `inquiryKeys.detail(id)` |
| 이벤트 처리 함수 / props | `handle<동작>` / `on<동작>` | `handleSubmit`, `onRetry` |
| 불리언 | `is`·`has`·`can` 접두 | `isPending`, `canRetry` |
| 테스트 파일 | 대상 파일 옆 `<이름>.test.tsx` | `TicketCard.test.tsx` |
| CSS Module | `<컴포넌트>.module.css`, 클래스는 camelCase | `styles.cardHeader` |

서버 계약의 이름(필드·상태 코드·이벤트 이름)은 바꾸지 않고 그대로 쓴다. 화면용 이름이 필요하면 `model/`의 매핑 함수에서 바꾼다.

## 5. 상태를 두는 위치

| 상태 | 두는 곳 | 하지 말 것 |
|---|---|---|
| 서버 데이터(문의·티켓·목록·상세·실행) | TanStack Query 캐시 | zustand·useState로 복사해 따로 관리 |
| 주소로 공유돼야 하는 상태(필터·페이지·열린 모달의 대상 ID) | URL 경로·쿼리 | 컴포넌트 상태에만 보관(모달 닫기 후 목록 유지 규칙 위반, PRD 4.11) |
| 입력 중인 값, 열림·닫힘 같은 화면 한 곳의 상태 | `useState` / `useReducer` | 전역 스토어 |
| 여러 화면이 함께 쓰는 클라이언트 상태(로그인 사용자, 소켓 연결 상태, 보내지 못한 입력) | 해당 기능의 `model/` zustand 스토어 | 서버 데이터를 섞어 넣기 |
| 실시간 임시 표시(진행 단계, 답변 조각) | 해당 기능 훅의 로컬 상태 또는 스토어 | 저장된 데이터처럼 Query 캐시에 써 넣기 |

실시간 이벤트를 받으면 저장 결과는 REST를 다시 조회해 반영한다(`queryClient.invalidateQueries`). 이벤트 내용으로 캐시를 직접 고치는 것은 `message.stored`처럼 저장 확정 이벤트에 한하며, 그 뒤에도 REST 결과가 최종이다(API 2.4 · PRD 5.1).

## 6. 스타일 파일

- 컴포넌트 스타일은 CSS Modules(`*.module.css`, Vite 기본 기능)로 쓴다. 전역 CSS는 `shared/styles/`의 두 파일만 둔다.
- 색·간격·글꼴·반경 값은 `shared/styles/tokens.css`의 CSS 변수로만 쓴다. 컴포넌트 CSS에 색 값을 직접 쓰지 않는다.
- 디자인 원본(Figma 등)과 토큰 값은 아직 팀이 정하지 않았다. 정해지기 전에는 토큰 이름만 쓰고 값은 임시로 둔다(COMPONENTS 1절).
