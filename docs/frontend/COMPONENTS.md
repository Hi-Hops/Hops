# 공통 컴포넌트

상태: 초안 · 범위: `src/shared/ui`, `src/app/layouts` · 디자인 원본: **미정**

화면마다 같은 역할을 하는 컴포넌트를 다시 만들지 않도록 목록과 계약(props·동작)을 정한다. 시각 디자인(색·간격·글꼴)은 팀이 디자인 원본을 정한 뒤 `tokens.css` 값만 바꾼다. 이 문서는 모양이 아니라 **역할·props·접근성·상태 규칙**을 정한다.

## 1. 디자인 토큰 이름

값은 디자인 원본이 정해질 때까지 임시로 둔다. 컴포넌트 CSS는 아래 변수 이름만 쓴다(CONVENTIONS 7절).

| 분류 | 변수 |
|---|---|
| 색(바탕·글자) | `--color-bg`, `--color-surface`, `--color-text`, `--color-text-muted`, `--color-border`, `--color-primary`, `--color-primary-text` |
| 상태 색 | `--color-status-<tone>-bg`, `--color-status-<tone>-fg` (`tone`: `neutral`, `progress`, `pending`, `attention`, `success`, `danger`) |
| 간격 | `--space-1` ~ `--space-8` |
| 모서리 | `--radius-sm`, `--radius-md`, `--radius-lg` |
| 글꼴 | `--font-sans`, `--font-size-sm`, `--font-size-md`, `--font-size-lg`, `--font-size-xl`, `--font-weight-regular`, `--font-weight-bold` |
| 레이아웃 | `--header-height`, `--sidenav-width`, `--content-max-width` |

## 2. 레이아웃·내비게이션 (`src/app/layouts`, `src/shared/ui`)

| 컴포넌트 | 위치 | 역할 | props(요지) | 스펙 |
|---|---|---|---|---|
| `AppLayout` | app/layouts | 로그인 화면 공통 틀: 헤더 + 사이드 내비게이션 + 연결 상태 띠 + 본문(`<Outlet />`) | 없음(Provider에서 사용자·연결 상태를 읽음) | F001, F002 |
| `AuthLayout` | app/layouts | 로그인 화면 틀 | 없음 | F001 |
| `AppHeader` | shared/ui | 서비스 이름, 사용자 이름·소속 부서명, 로그아웃 버튼 | `userName`, `departmentName`, `onLogout` | F001-AC01·AC08 |
| `SideNav` | shared/ui | 역할에 맞는 메뉴, 현재 위치 표시 | `items: NavItem[]` (`{ to, label }`) | ROUTES 1절 "메뉴" 열 |
| `PageHeader` | shared/ui | 화면 제목, 보조 설명, 오른쪽 동작 버튼 | `title`, `description?`, `actions?` | 전체 |
| `ConnectionBanner` | shared/ui | 실시간 연결이 정상이 아닐 때만 띠로 표시 | `state: 'connecting' \| 'reconnecting' \| 'disconnected' \| 'relayError'` | F002-AC06 |

메뉴 정의(`src/app/navigation.ts`, 제안):

| 메뉴 | 경로 | 보이는 사용자 |
|---|---|---|
| 새 문의 | `/` | 로그인 사용자 |
| 내 문의 | `/inquiries` | 로그인 사용자 |
| 부서 티켓 | `/tickets` | IT 처리 부서 구성원(판단 필드 Q-ALL-5) |
| 관리자 조회 | `/admin/tickets` | `BIZ_ADMIN` |
| 기술 복구 | `/operator/recovery-cases` | `AGENT_OPS_ADMIN` |

- 데스크톱 기준으로 사이드 내비게이션을 둔다. 좁은 화면에서 접는 방식은 디자인 원본이 정해진 뒤 정한다.
- `SideNav`는 react-router의 `NavLink`를 써서 현재 메뉴에 `aria-current="page"`를 단다.

## 3. 상태 표시 (`src/shared/ui`)

조회 화면은 **로딩 → 오류 → 빈 결과 → 내용** 순서를 지킨다(CONVENTIONS 2.8). 아래 컴포넌트로만 표현한다.

| 컴포넌트 | 역할 | props(요지) | 규칙 |
|---|---|---|---|
| `AsyncContent` | 쿼리 결과의 네 상태를 순서대로 그림 | `query`(TanStack Query 결과), `isEmpty(data)`, `empty`, `children(data)`, `errorTitle?` | 오류 시 `ErrorState`, 빈 결과 시 `empty`를 그림. 빈 결과와 실패를 섞지 않음(PRD 4.11) |
| `LoadingState` | 불러오는 중 | `label?` | `role="status"` |
| `EmptyState` | 결과 없음 | `title`, `description?`, `action?` | 다음에 할 일을 버튼으로 제시(예: 새 문의) |
| `ErrorState` | 조회 실패 | `title`, `description?`, `onRetry?`, `isRetrying?` | 다시 시도 제공. 내부 오류 문자열 비노출 |
| `StatusBadge` | 상태 문구 + 색 | `tone`, `label` | 색만으로 구분하지 않음. `pending`은 "전송 중"·"결과 확인 중"처럼 확정 전 표시 |
| `InlineNotice` | 화면 일부의 안내·경고(부분 실패, 정보 보완 저장) | `tone`, `title`, `children` | 비동기 변화는 `role="status"` |

상태 표시명(문의·처리·배정 상태)은 공통 컴포넌트가 아니라 각 기능의 `model/statusLabels.ts`에 둔다(`InquiryStatusBadge`, `ProcessingStatusBadge`, `RoutingStatusBadge`가 `StatusBadge`를 감쌈). 표시명은 Q-ALL-2 확정 전 임시값이다.

## 4. 입력·동작 (`src/shared/ui`)

| 컴포넌트 | 역할 | props(요지) | 규칙 |
|---|---|---|---|
| `Button` | 기본 버튼 | `variant: 'primary' \| 'secondary' \| 'ghost' \| 'danger'`, `isLoading?`, 기본 `button` 속성 | `isLoading` 중 비활성 + 문구 유지. 기본 `type="button"` |
| `IconButton` | 아이콘만 있는 버튼 | `label`(필수, `aria-label`), `icon` | 라벨 없이 만들 수 없게 타입으로 강제 |
| `TextField` | 한 줄 입력 | `label`, `error?`, `hint?`, 기본 `input` 속성 | 보이는 라벨 필수, 오류는 `aria-describedby`로 연결 |
| `TextArea` | 여러 줄 입력 | `TextField`와 같음 + `rows?` | |
| `Pagination` | 목록 페이지 이동 | `page`(0부터), `size`, `totalElements`, `onPageChange` | 화면 표시는 1부터(ROUTES 2절) |
| `Modal` | Radix Dialog 래퍼 | `open`, `onOpenChange`, `title`, `description?`, `children`, `footer?` | 제목 필수. 포커스 가두기·Esc·복귀는 Radix에 맡김 |
| `RouteModal` | 경로로 열리는 상세 모달 | `title`, `children`, `closeTo`(돌아갈 목록 경로+쿼리) | 닫으면 목록 쿼리 유지(F006-AC08) |
| `ConfirmDialog` | 확인·취소 | `open`, `title`, `description`, `confirmLabel`, `onConfirm`, `onCancel`, `isPending?` | 되돌릴 수 없는 동작에만 사용 |

## 5. 기능 전용 컴포넌트 (공통 아님)

기능 지식이 들어간 컴포넌트는 `shared/ui`에 두지 않고 해당 기능에 둔다. 두 기능이 함께 쓰면 소유 기능의 공개 진입점으로 연다.

| 컴포넌트 | 소유 기능 | 쓰는 곳 | 스펙 |
|---|---|---|---|
| `LoginForm` | auth | LoginPage | F001 |
| `MessageList`, `MessageBubble` | chat | InquiryChatPage | F003 |
| `MessageComposer`(글자 수·전송 상태·재전송) | chat | HomePage, InquiryChatPage | F003-AC01~AC04 |
| `ProgressSteps`(진행 단계·답변 조각 임시 표시) | chat | InquiryChatPage | F003-AC07 |
| `RevisionEditor`, `RevisionHistory` | chat | MessageBubble 안 | F004 |
| `SplitConsentCard` | chat | InquiryChatPage | F008 |
| `InquiryResultPanel`(문제 목록·티켓 카드·생성 실패) | inquiry | InquiryChatPage, 문의 상세 모달 | F005, F006 |
| `TicketCard`, `ProblemRow`, `CreationFailureNotice` | inquiry | InquiryResultPanel | F005 |
| `InquiryList`, `InquiryDetail` | inquiry | InquiriesPage | F006 |
| `TicketTable`, `TicketDetail`, `RelatedConversation` | ticket | TicketsPage, AdminTicketsPage | F007, F009 |
| `CreationFailureTable` | creation-failure | AdminTicketsPage, OperatorRecoveryPage | F010 |

## 6. 공통 컴포넌트 작성 규칙

1. `shared/ui`는 서버 계약·기능을 모른다. 데이터를 가져오지 않고 props로만 받는다(STRUCTURE 3절). `AppHeader`처럼 사용자 정보가 필요하면 문자열 props로 받는다.
2. 기본 HTML 속성을 받는 컴포넌트는 해당 요소의 속성 타입을 확장한다(`ComponentProps<'button'>`).
3. 새 공통 컴포넌트는 이 문서 표에 먼저 추가하고(같은 PR), 접근성 역할을 확인하는 테스트를 하나 이상 둔다.
4. 두 번째로 같은 모양이 필요해질 때 공통으로 올린다. 처음부터 범용으로 만들지 않는다.
5. 아이콘·이미지 자산과 아이콘 라이브러리는 디자인 원본이 정해질 때 함께 정한다. 그 전에는 텍스트 라벨을 쓴다.

## Mock 구현 반영 (2026-10-23)

`ErrorState(title, onRetry?)`, `LoadingState(label?)`를 `shared/ui`에서 재사용한다. 조회 실패는 alert, 조회 중은 status로 안내한다. 상태 배지의 tone은 neutral/progress/attention/success/danger다. `RouteModal(title, description, onClose, returnFocusId?, children)`은 Radix Dialog를 사용하며 URL 변경은 호출 기능이 담당한다. 상세 종료 시 목록 링크 또는 페이지 제목으로 포커스를 돌린다. 티켓 상세와 목록은 같은 검색 조건을 유지한다.

현재 공개 기능 컴포넌트는 `ResultPanel`, `TicketStatuses`(inquiry), `MessageRevision`(chat 내부), `TicketBoard`(ticket)다. 기획 목록의 이름을 그대로 파일로 미리 만들지 않고 구현된 공개 진입점만 노출한다.
