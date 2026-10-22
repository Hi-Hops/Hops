# 프론트 Mock 구현 및 검증 기록

범위는 로그인·채팅·결과 카드·메시지 정정·부서 티켓 조회다. 공통 계약은 여전히 **Mock 제안 / 팀 리뷰 전**이며 이 구현을 운영 계약 승인이나 제품 전체 MVP 완료로 해석하지 않는다. 사용자의 요청에 따라 한 작업 브랜치에서 기능별 커밋을 남겼으며, push·PR·병합·배포는 수행하지 않았다.

## 기능별 인수 조건

| 기능 | 구현·테스트한 인수 조건 | 근거 테스트 |
|---|---|---|
| 초기 설정 | React/TypeScript/Vite, 라우팅·Provider, 공통 버튼·입력·오류·로딩·배지 | `Controls.test.tsx`, `Feedback.test.tsx`, `ErrorPage.test.tsx` |
| F001 | AC01~AC09: 로그인·실패·me·접근 거부·인증 만료·동일/다른 계정·로그아웃·비밀값 비노출 | `LoginForm.test.tsx`, `RequireAuth.test.tsx`, `LoginPage.test.tsx`, `ForbiddenPage.test.tsx`, `MessageComposer.test.tsx` |
| F002 | AC01~AC11: 구독 권한·중복·버전/실행·순서·끊김·재접속·저장 이벤트·인증·해제 | `mockTransport.test.ts`, `TicketBoard.test.tsx`, `ChatView.test.tsx` |
| F003 | AC01~AC14: 입력·생성/전송·실패/미확인·목록·분석·질문·범위 밖·실행 오류·보완·충돌·변경 재조회 | `MessageComposer.test.tsx`, `ChatView.test.tsx` |
| F005 | AC01~AC09: 문제 구분·저장 티켓·처리/배정 분리·부분 실패·재시도/승인 없음·중복·재조회·실행 중 | `ResultPanel.test.tsx`, `ChatView.test.tsx` |
| F004 | AC01~AC07: 정정 중·원문 이력·버전·보완·충돌·미확인 재전송·본인 메시지 | `MessageRevision.test.tsx`, `mockTransport.test.ts` |
| F009 | AC01~AC06: 서버 범위·상태·URL 필터/페이지·빈/실패·읽기 상세·타 부서/재이관 후 비노출 | `TicketBoard.test.tsx` |

테스트 파일은 `frontend/src/` 내 해당 기능 옆에 있다. 테스트명은 인수 조건 ID를 포함한다. F006은 목록과 채팅 재진입만 보조 구현했으며 전체 기능 완료로 표시하지 않는다. F007 관리자 상세는 동일 티켓 화면을 재사용하는 최소 조회다. F009-AC07~AC09 관련 대화, 티켓 처리·재이관 조작, 장애 그룹·통계, F008 동의, F010 복구는 이번 범위에 없다.

## 디자인 적용

[Figma 작업본](https://www.figma.com/design/5W6UYTaYAuLnQNt2jRiqgc/Hops?node-id=6-2)의 로그인 `24:1101`, 채팅 `24:1125`, 정정 `35:2526`, 부서 목록 `22:565`, 상세 `23:727`을 확인했다. IBM Plex Sans KR을 로컬 자산으로 넣고 주황색 브랜드, 56px 헤더, 248px 사이드바, 사용자 말풍선·문제별 카드·표·두 열 상세 모달을 CSS Modules와 토큰으로 구현했다. 원본 SVG 상태 아이콘을 재사용한다.

홈 `50:2715`의 상세 design context 요청은 Figma 도구 사용량 제한으로 실패해 이미 확인한 페이지 이미지와 공통 레이아웃을 기준으로 구현했다. 원본 화면에 보이는 후속 기능 버튼·통계·관련 대화는 이번 공통 계약 범위에서 제외했고, 계약이 요구하는 처리/배정 상태·생성 시각은 표시한다. 따라서 원본 전체 화면의 모든 동작을 복제하거나 픽셀 완전 일치를 검증한 것은 아니다.

## 검증 결과

- Node.js 24.22.0에서 `npm run check`: TypeScript, ESLint, Prettier, Vitest **79개 테스트** 통과.
- `npm run build`: 배포용 정적 번들 생성 통과. 티켓·채팅 페이지를 지연 로드한다.
- `python specs/contracts/generate.py --check`: 생성 DTO·필드 표 일치.
- `python specs/contracts/validate.py`: Schema·생성물·공용 fixture·operation·시나리오 검사 통과.
- `python specs/mocks/replay.py`: 메모리 계약 시나리오 통과.
- `node specs/contracts/check-wire.mjs`: Node/Python 공용 fixture JSON 왕복 일치.
- 개발 브라우저에서 로그인 → 문의 생성 → 임시 분석 → 저장 답변/결과, 정정 편집, 부서 계정 목록/상세, Esc 닫기를 확인했다.

테스트는 메모리 서버/소켓 대역을 쓰는 단위·컴포넌트 통합 검사다. 실제 REST/Socket.IO 서버·DB·AI 모델·다른 브라우저/화면 크기 전체·자동 브라우저 E2E는 검사하지 않았다. Java 직렬화 검사는 실행하지 않았다. 계약 검사 통과를 실제 서비스 통합 통과로 보고하지 않는다.

## 서비스 연결 전 남은 것

`shared/api/client.ts`와 `shared/realtime/mockTransport.ts`는 Mock 전용이다. 로그인 세션·서버 데이터는 새로고침 시 초기화한다. 실제 서버 연결 전 인증 보관/갱신(Q-BE-2), 오류 표시(Q-ALL-4), 문의 버전·멱등성(Q-BE-3/10), 문제·티켓 관계(Q-BE-4), 상태 코드(Q-ALL-2), 소켓 인증/구독/이벤트(Q-INF 계열), 상세/관련 대화 권한(Q-BE-6/14)을 팀 리뷰로 확정해야 한다. 원본 질문 목록은 [QUESTIONS](../../specs/QUESTIONS.md)를 따른다.

Mock 명령은 공통 타입으로 보내며 전송 경계에서만 응답을 변환한다. 운영용 런타임 JSON Schema 검증기·실제 네트워크 어댑터는 이번 구현에 포함하지 않았다. 사용자 입력·미확인 요청은 동일 계정 재로그인 시 유지하고 로그아웃/다른 계정 전환 시 정리한다. 토큰을 localStorage에 저장하지 않는다. 부서 목록과 상세는 변경 이벤트·재접속 및 5초 주기 재조회로 갱신하며, 업무 관리자 전체 목록은 주기 재조회로 다른 부서 변경을 보완한다.
