# 기능 구현 절차

상태: 초안 · 대상: 프론트엔드 기능을 구현하는 사람과 AI

[기능 스펙](../../specs/README.md)의 기능 하나(`Fnnn`)를 **한 브랜치·한 PR**로 구현한다. 아래 순서와 완료 체크리스트를 매번 같은 방식으로 따른다. 순서를 건너뛰었으면 PR에 이유를 적는다.

## 1. 전체 진행 순서

| 단계 | 작업 | 이슈 | 선행 조건 |
|---|---|---|---|
| 0 | `frontend/` 초기 세팅: TOOLING 설정, STRUCTURE 폴더 골격, 라우터·Provider·`AppLayout` 빈 틀, 빈 페이지, 오류 화면 | #6 | 이 문서들 리뷰 |
| 1 | 공통 기반: `shared/api`(타입·client), `shared/realtime/socket.ts`, `shared/ui` 1차(상태 표시·Button·Modal·TextField) | #30 | 0 |
| 2 | F001 로그인·권한 → F002 실시간 연결 | #30 | 1, Q-BE-1·Q-BE-2·Q-INF-1·Q-INF-3 답(없으면 목으로 화면까지) |
| 3 | F003 채팅 → F005 결과 카드 → F004 정정 | #30 | 2 |
| 4 | F006 내 문의 → F007 관리자 조회 → F009 부서 티켓 → F010 생성 실패 | #31 | 1·F001 |
| 5 | F008 새 문의 동의 | #30 | Q-ALL-7·Q-BE-13 답 |

PRD 8.2 완료 흐름 순서(로그인 → 연결 → 문의 → 분석 → 정정 → 티켓 → 관리자 조회)를 따른다. 4단계는 3단계와 다른 사람이 동시에 진행할 수 있다.

## 2. 기능과 폴더의 대응

| 스펙 | 기능 폴더(`src/features/`) | 페이지(`src/pages/`) | 경로(ROUTES) |
|---|---|---|---|
| F001 | `auth` | `login`, `errors` | `/login`, `/403`, `*` |
| F002 | `realtime` (+ `shared/realtime`) | — (AppLayout) | — |
| F003 | `chat` | `home`, `inquiry-chat` | `/`, `/inquiries/:inquiryId/chat` |
| F004 | `chat` | `inquiry-chat` | 위와 같음 |
| F005 | `inquiry` | `inquiry-chat`, `inquiries` | 위와 같음 |
| F006 | `inquiry` | `inquiries` | `/inquiries`, `/inquiries/:inquiryId` |
| F007 | `ticket` | `admin-tickets` | `/admin/tickets`, `/admin/tickets/:ticketId` |
| F008 | `chat` | `inquiry-chat` | `/inquiries/:inquiryId/chat` |
| F009 | `ticket` | `tickets` | `/tickets`, `/tickets/:ticketId` |
| F010 | `creation-failure` | `admin-tickets`, `operator-recovery` | `/admin/tickets`, `/operator/recovery-cases` |

## 3. 기능 하나를 구현하는 순서

1. **읽기**: [README](README.md)의 진입점 순서대로 읽는다. 스펙의 "막히는 정도"에서 "결정 전 구현 보류" 항목과 관련 질문의 현재 답을 확인한다.
2. **브랜치**: 최신 `develop`에서 `feature/<이슈번호>-<설명>`을 만든다. 예: `feature/30-f003-chat`. (Git 컨벤션 4.1)
3. **인수 조건 목록 만들기**: 스펙의 `Fnnn-ACnn`을 테스트 이름 목록으로 먼저 적는다(빈 `it.todo`로 시작해도 된다).
4. **타입 확인**: 필요한 통신 타입이 [CONTRACT_TYPES](CONTRACT_TYPES.md)에 있는지 본다. 없으면 원본 근거를 찾아 `@contract` 표기와 함께 추가하고, 근거가 없으면 질문을 [QUESTIONS](../../specs/QUESTIONS.md)에 추가한다.
5. **목 데이터**: [공용 Mock JSON](../../specs/mocks/README.md)을 원본으로 읽고 `src/test/fixtures/`에는 얇은 어댑터만 둔다. 정상·빈 결과·실패·부분 실패·재전송 시나리오를 재사용한다.
6. **통신**: `features/<기능>/api/`에 REST 함수·쿼리 키, 필요하면 `realtime/`에 명령·이벤트 처리를 만든다(CONVENTIONS 3·4절).
7. **순수 로직**: `model/`에 표시명·매핑·검증을 만들고 단위 테스트를 쓴다.
8. **훅**: `hooks/`에서 통신과 로직을 묶어 화면이 쓸 값과 동작을 내보낸다.
9. **컴포넌트**: `components/`를 만들고 인수 조건 테스트를 채운다. 공통 컴포넌트([COMPONENTS](COMPONENTS.md))를 먼저 찾아 쓴다.
10. **페이지·경로**: `pages/`에서 조립하고, [ROUTES](ROUTES.md) 표대로 `app/router.tsx`·메뉴에 등록한다. 공개 진입점(`index.ts`)에 필요한 것만 내보낸다.
11. **검증**: `npm run check`와 `npm run build`를 실행한다. 화면은 개발 서버에서 직접 확인한다.
12. **PR**: 5절대로 작성한다.

## 4. 미정 계약을 만났을 때

1. 목 데이터로 화면·테스트까지 만든다. 서버 연결 코드는 타입이 `@contract 미정`이면 연결하지 않거나, 연결하더라도 PR에 "미정 계약 사용"으로 적는다.
2. 코드에 질문 ID 주석을 단다: `// Q-INF-5 확정 전 임시값`.
3. 기본값을 확정한 것처럼 원본 문서·타입 표기를 바꾸지 않는다(#30·#31 "미정인 필드·경로·예산은 기본값으로 확정하지 않는다").
4. 질문 답이 나오면 CONTRACT_TYPES 3절 절차로 타입을 고치고, 같은 PR에서 스펙 계약 표 판정을 바꾼다.

## 5. 커밋·PR

- 커밋: `feat(<기능>): <사용자가 체감하는 변경>` 한국어 제목. 본문에 `명세: F003-AC04, F003-AC06`, `검증: <실제 실행한 명령과 결과>`를 쓴다(Git 컨벤션 3절). 실행하지 않은 검사를 적지 않는다.
- PR: [PR 템플릿](../../.github/PULL_REQUEST_TEMPLATE.md) 순서를 따른다. "변경 사항"에 구현한 인수 조건 목록과 사용한 미정 계약(질문 ID)을 적는다. UI 변경은 스크린샷을 붙인다.
- 이슈 연결: 이슈의 완료 조건을 모두 채우면 `Closes #nn`, 일부면 `Refs #nn`(Git 컨벤션 5절).
- commit·push·PR 생성은 사용자가 요청할 때만 한다(AGENTS.md).

## 6. 완료 체크리스트

PR 전에 모두 확인한다. 확인하지 않은 항목은 체크하지 않고 이유를 적는다.

- [ ] 스펙의 인수 조건마다 테스트가 있고, 테스트 이름이 `Fnnn-ACnn`으로 시작한다
- [ ] 예외 인수 조건(실패·빈 결과·권한 없음·충돌·시간 초과·중복 이벤트·재접속)을 테스트했다
- [ ] 폴더·이름·레이어 규칙(STRUCTURE)을 지켰고 린트 예외를 추가하지 않았다
- [ ] 서버 데이터는 계약 타입만 썼고, 미정 계약 사용 부분에 질문 ID를 달았다
- [ ] 공통 컴포넌트를 재사용했고, 새 공통 컴포넌트는 COMPONENTS 표에 추가했다
- [ ] 저장 확인 전 성공·해결 표시가 없고, 실패 시 입력이 보존된다
- [ ] 동일 요청 재시도는 같은 `Idempotency-Key`·`requestId`, 내용·대상 버전을 수정한 새 요청은 새 키를 쓴다
- [ ] ROUTES 표와 `router.tsx`·메뉴가 일치한다
- [ ] `npm run check`, `npm run build` 통과(실제 실행 결과를 PR에 기록)
- [ ] 승인 없는 패키지 추가·버전 범위 사용이 없다
- [ ] 스펙과 다르게 구현한 부분이 있으면 스펙 또는 QUESTIONS를 같은 PR에서 고쳤다

## 7. AI가 작업할 때 추가 규칙

1. 한 번에 기능 하나만 구현한다. 다른 기능의 내부 파일이나 `shared/`를 바꿔야 하면 먼저 사용자에게 알리고, PR에 따로 적는다.
2. 스펙·원본 문서(PRD·API·DATA·ERD)를 근거 없이 고치지 않는다. 스펙이 모호하면 추측하지 말고 질문한다(AGENTS.md "모호한 요구사항은 질문").
3. 이 폴더의 규칙과 다르게 써야 할 이유가 생기면 코드를 먼저 바꾸지 말고 규칙 변경을 제안한다.
4. 작업을 마치면 인수 조건별 구현·테스트 여부 표, 실행한 검증과 결과, 미실행 항목과 이유, 사용한 미정 계약 목록을 보고한다.
