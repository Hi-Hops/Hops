# 라우팅 명세

상태: 초안 · 이슈 #6 작업 목록 "프론트엔드 라우팅 명세" · 경로 이름은 [ERD 설명](../ERD_설명.md)의 테이블 이름을 따른 **프론트 제안값**(Q-FE-1)

경로 정의는 코드에서 `src/app/router.tsx` 한 곳에만 둔다. 이 표와 코드가 다르면 PR에서 둘을 함께 고친다.

## 1. 경로 표

| 경로 | 페이지(`src/pages/`) | 레이아웃 | 접근 | 스펙 | 범위 | 메뉴 |
|---|---|---|---|---|---|---|
| `/login` | `login/LoginPage` | AuthLayout | 비로그인. 로그인 상태면 첫 화면으로 보냄 | F001 | MVP | — |
| `/` | `home/HomePage` | AppLayout | 로그인 사용자 | F003 | MVP | 새 문의 |
| `/inquiries/:inquiryId/chat` | `inquiry-chat/InquiryChatPage` | AppLayout | 문의 소유자(서버 403·404) | F003, F004, F005, F008 | MVP(F008은 Q-ALL-7) | — |
| `/inquiries` | `inquiries/InquiriesPage` | AppLayout | 로그인 사용자(본인 문의) | F006 | MVP(Q-ALL-1) | 내 문의 |
| `/inquiries/:inquiryId` | `inquiries/InquiriesPage` + 상세 모달 | AppLayout | 문의 소유자 | F006, F005 | MVP(Q-ALL-1) | — |
| `/tickets` | `tickets/TicketsPage` | AppLayout | 현재 처리 부서 구성원 | F009 | 목록 MVP | 부서 티켓 |
| `/tickets/:ticketId` | `tickets/TicketsPage` + 상세 | AppLayout | 현재 처리 부서 구성원(관련 대화 권한 Q-BE-14) | F009 | 상세 MVP, 관련 대화 Q-ALL-7 | — |
| `/admin/tickets` | `admin-tickets/AdminTicketsPage` | AppLayout | `BIZ_ADMIN` | F007, F010 | MVP | 관리자 조회 |
| `/admin/tickets/:ticketId` | `admin-tickets/AdminTicketsPage` + 상세 | AppLayout | `BIZ_ADMIN` | F007 | MVP | — |
| `/operator/recovery-cases` | `operator-recovery/OperatorRecoveryPage` | AppLayout | `AGENT_OPS_ADMIN` | F010 | 재시도 Q-ALL-7 | 기술 복구 |
| `/403` | `errors/ForbiddenPage` | AppLayout(로그인) / AuthLayout | 전체 | F001 | MVP | — |
| `*` (`/404` 포함) | `errors/NotFoundPage` | 위와 같음 | 전체 | F001 | MVP | — |

- `:inquiryId`, `:ticketId`는 서버 UUID다. 화면 표시 번호(`inquiry_number` Q-0142, `ticket_number` TK-0001)를 경로에 쓰지 않는다(ERD 2 공통 규칙).
- 상세 모달(`/inquiries/:inquiryId`, `/tickets/:ticketId`, `/admin/tickets/:ticketId`)은 목록의 자식 경로로 두어, 주소로 직접 열 수 있고 닫으면 목록의 쿼리(필터·페이지)를 유지한다(PRD 4.11 · F006-AC08).
- 부서 티켓 상세와 관리자 티켓 상세를 하나로 합칠지는 Q-FE-1에서 정한다. 합치기 전까지 상세 컴포넌트는 `features/ticket`에 하나만 두고 두 페이지가 재사용한다.

## 2. 쿼리 문자열

목록의 필터·페이지는 URL 쿼리에 둔다(STRUCTURE 5절). 이름은 API 필드 이름(camelCase)을 따른다.

| 경로 | 쿼리 | 상태 |
|---|---|---|
| `/inquiries` | `page` | 필터·정렬은 Q-BE-5 |
| `/tickets` | `page`, `processingStatus` | 초안, Q-BE-6 |
| `/admin/tickets` | `page`, `processingStatus`, `routingStatus` | 초안, Q-BE-6 |

`page`는 0부터 센다(API 1). 화면에 보이는 페이지 번호는 1부터 보여 준다.

## 3. 권한 판단과 첫 화면

화면 접근은 `/api/auth/me`의 `CurrentUser`로 판단한다(F001-AC03). 서버도 403으로 막으므로 화면 가드는 **보여 줄지 정하는 것**이지 보안 수단이 아니다.

| 사용자 | 판단 근거 | 첫 화면(제안) |
|---|---|---|
| 업무 관리자 | `roles`에 `BIZ_ADMIN` | `/admin/tickets` |
| 에이전트 운영 관리자 | `roles`에 `AGENT_OPS_ADMIN` | `/operator/recovery-cases` |
| IT 처리 부서 구성원 | 소속 부서가 배정 대상 부서(ERD `department.is_routable`) | `/tickets` |
| 그 밖의 사용자(문의자) | — | `/` |

- 역할이 여러 개면 위 표의 위쪽이 우선한다(제안).
- Mock 공통 계약에서는 `CurrentUser.department.isRoutable`로 부서 티켓 메뉴를 표시한다. 운영 필드·권한 정책은 Q-ALL-5 팀 합의 전이며 서버가 최종 접근 권한을 검사한다.
- 첫 화면과 우선순위는 Q-FE-1에서 확정한다.

## 4. 코드 형태

```tsx
// src/app/router.tsx — 형태 예시. 실제 페이지는 기능 작업에서 채운다.
export const router = createBrowserRouter([
  { path: '/login', element: <AuthLayout />, children: [{ index: true, element: <LoginPage /> }] },
  {
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { path: '/', element: <HomePage /> },
      { path: '/inquiries/:inquiryId/chat', element: <InquiryChatPage /> },
      {
        path: '/inquiries',
        element: <InquiriesPage />,
        children: [{ path: ':inquiryId', element: <InquiryDetailRoute /> }],
      },
      {
        path: '/admin/tickets',
        element: (
          <RequireRole role="BIZ_ADMIN">
            <AdminTicketsPage />
          </RequireRole>
        ),
      },
      { path: '/403', element: <ForbiddenPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
```

- `RequireAuth`: `/api/auth/me` 조회 중에는 로딩, 401이면 `/login`으로 보내고 돌아올 경로를 기억한다(F001-AC05).
- `RequireRole`: 역할이 없으면 `/403`으로 보낸다. 내용을 먼저 그리지 않는다(F001-AC04).
- 메뉴는 `src/app/navigation.ts`의 역할별 정의를 읽어 그린다(COMPONENTS 2절). 경로 문자열을 메뉴·링크에 직접 흩어 쓰지 않고 `src/app/paths.ts`의 함수(`paths.inquiryChat(id)`)로 만든다.
