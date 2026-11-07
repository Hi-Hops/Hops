# Hops 프론트엔드 실행·서버 연동

채팅 이미지 첨부는 작업 중이다. 이미지의 지정 영역을 브라우저에서 불투명하게 그려 새 PNG로 만드는 함수가 있지만 선택·가림 화면과 전송에는 아직 연결되지 않았다. 자동 개인정보 탐지 기능이 아니다. 합성 이미지로 Chrome의 단일 가림 영역과 주변 픽셀을 확인했으며, 다른 브라우저·다중 이미지·전체 화면 흐름의 검증은 남아 있다.

프론트엔드는 PRD 1.44의 **hops-system-1 · `/api/v2` 단일 계약**을 사용한다. 이전 부서 기반 화면·API·계약 선택 설정은 제공하지 않는다. 기존 로그인·채팅·티켓 보드/목록의 스타일과 공통 UI를 재사용한다. 데이터 소스만 `VITE_DATA_MODE=live|mock`으로 선택한다.

## 시스템 기반 화면 실행

기본 모드는 `live`다. 이 폴더의 [.env.example](.env.example)을 `frontend/.env`로 복사해 Vite 직접 실행과 Docker의 프론트 설정을 관리한다. 기존 파일은 덮어쓰지 않는다. 공개 포트도 서비스별 파일에서 관리하며 루트 Compose가 세 파일을 자동으로 읽는다.

다음 두 값은 `frontend/.env`에서 설정한다. Vite 직접 실행 시 `VITE_API_BASE_URL`에 실제 Spring origin을 입력한다. Vite는 이 파일을 자동으로 읽으며 프로세스 환경변수와 모드별 `.env.development`·개인 `.env.local` 등의 우선순위도 적용한다. 저장소 루트 `.env`는 앱 설정으로 읽지 않는다.

```dotenv
VITE_DATA_MODE=live
VITE_API_BASE_URL=http://localhost:8080
```

`VITE_CONTRACT_PROFILE`은 사용하지 않는다. 설정 변경 후 Vite 또는 프론트 컨테이너를 재시작한다. 브라우저는 `http://localhost:5173`으로 열고 백엔드에 등록된 계정으로 로그인한다. 실제 계정·비밀번호는 로그인 화면에서만 입력하며 `VITE_` 환경 변수에 넣지 않는다.

Spring은 [WIRE_CONTRACT 12절](../specs/contracts/WIRE_CONTRACT.md#12-신규-계약-hops-system-1)의 인증·조회·명령·SSE를 구현해야 한다. 기존 `/api` 서버로 자동 대체하지 않는다. API origin에는 `/api/v2`, 인증 정보, 쿼리를 붙이지 않는다. 브라우저에서 접근 가능한 origin을 사용하며 Docker 서비스 이름을 주소로 입력하지 않는다.

`localhost` API를 사용할 때 화면도 `localhost`로 열어 Refresh 쿠키의 사이트 조건을 맞춘다. Spring은 정확한 프론트 origin·Allow-Credentials, 계약의 GET/POST/OPTIONS와 Authorization·Content-Type·Idempotency-Key·Last-Event-ID 및 Retry-After 노출을 설정해야 한다. 로그인·갱신·로그아웃만 쿠키를 전달하고 업무 REST·fetch SSE는 쿠키 없이 Bearer를 사용한다. Secure·SameSite·Origin/CSRF 정책은 계약에 따라 서버에서 구현한다.

## 현재 구현한 페이지·기능

- `/login`: 로그인·현재 사용자 조회, 내부 복귀 주소, 시스템 담당·관리 역할에 따른 첫 화면, 갱신·로그아웃·권한 변경 처리.
- `/`: 자연어 새 문의와 선택적 보고 시스템. 저장 receipt 확인 후 본인 채팅으로 이동하며 미전송 초안을 보존한다.
- `/inquiries`·사이드바 최근 채팅: 본인 문의 목록·페이지 이동·상태·대화 재진입·문제별 해결 확인 진입.
- `/inquiries/:inquiryId/chat`: 서버 메시지·이전 대화·원문 정정 이력, 문제별 질문 수·시스템 확인·보완 대상 연결, 접수 결과·저장 여부 재확인·허용된 실패 재시도. Run 조회와 SSE 임시 답변은 최종 저장 메시지 GET과 구분한다.
- `/tickets`: 현재 PRIMARY의 시스템 티켓 보드/목록, 책임 시스템·상태 필터·페이지, 저장된 배정 담당자, 메타데이터 구독·보조 폴링.
- `/tickets/:ticketId`: 보드/목록 위 상세 모달. 표준 요청·내부 지식·허용 관련 대화·배정 이력과 책임 시스템/조사·장애 정정 모달. 필터를 유지하고 닫기·Escape로 목록에 복귀한다.
- `/tickets/:ticketId?tab=work`: 상세 모달의 처리·검증 입력 탭. 진행·실패 이력과 검증 완료/구현 검증, 원인·적용 버전, 해결 확인 대기와 초안 생성 상태·실패 재시도. `tab=case`는 상세 모달의 사례 진입 탭이다.
- `/admin/tickets`·`/admin/tickets/:ticketId`: BIZ_ADMIN의 관리 목록·제한 상세 모달·책임 시스템 정정. 관리 DTO만 읽고 담당자 내부 원문·표준 요청·관련 대화를 조회하지 않는다.
- `/cases`·`/cases/:caseId`: 현재 PRIMARY의 해결 사례 검토 목록·현재 초안 편집·버전 충돌 비교·승인. 원 기록의 문의자 해결 확인을 검사하고 승인 후 읽기 전용으로 표시한다. 같은 승인 버전의 Vector·Neo4j 반영/공개 상태와 저장소별 수동 확인·실패 재시도를 제공한다.
- `/403`·미등록 경로: 권한 없음·찾을 수 없음. 목록→상세→복귀 시 보기·필터·페이지를 유지한다.

본인 채팅의 문제별 해결 확인에서 해결/미해결을 응답한다. GUIDE는 티켓·사례를 만들지 않으며 업무 미해결은 같은 티켓의 처리를 이어간다. 무응답은 확인 대기를 유지한다. 최종 저장·배정·권한·상태 전이는 서버가 확정한다. UI는 Figma의 `Hops · 구현 UI와 후속 설계 (개선안)`을 기준으로 기존 공통 컴포넌트와 디자인 토큰을 확장한다.

## 저장·실시간 동기화

기능별 `system*Api.ts`는 같은 `/api/v2` DTO와 경로를 사용한다. `shared/api/systemWire.ts`가 응답 필수 필드·null·형식을 검사하고 `systemCommands.ts`가 requestId·resource 참조와 receipt를 검증한다. 화면은 후속 GET으로 최신 저장 결과를 읽는다. 응답 유실·PENDING/UNKNOWN에서는 입력·원 요청 키·본문을 보존하고 수동 확인을 제공하며 자동 업무 재전송을 하지 않는다. 400·409·429와 만료·권한 거부를 구분한다.

`shared/realtime`은 flat SSE를 검증해 현재 대상에만 전달한다. 진행/답변 조각을 저장 성공으로 취급하지 않는다. 종료·실행 교체·저장 이벤트·재접속에는 문의와 메시지를 재조회하며 현재 실행 이외의 조각을 섞지 않는다. 알림 유실은 REST 폴링으로 보완한다. Access는 메모리에만 보관한다. 새로고침하면 보호 화면 진입 전 `POST /auth/refresh`를 한 번 호출해 Refresh 쿠키로 세션을 복원한다. 쿠키 없음·만료·폐기는 원래 경로를 보존해 로그인으로 보내고, 네트워크 오류·429·5xx는 인증 만료로 확정하지 않고 다시 확인 또는 로그인 이동을 안내한다. 로그인 화면에서 새로고침해도 같은 방식으로 복원해 첫 화면으로 이동한다. 탭 사이 refresh는 Web Locks로 한 번에 하나씩 보내고, 회전 경합으로 `REFRESH_TOKEN_REVOKED`를 받으면 한 번만 다시 요청한다. Mock은 메모리 refresh라 새로고침하면 로그인으로 돌아간다.

## Mock으로 화면 개발

로그인 직후 주요 화면의 예시를 확인하려면 `frontend/`에서 다음 명령을 실행하고 [시연 로그인](http://localhost:5178/login)을 연다. 개인 `.env`를 수정하지 않으며 해당 실행에서만 Mock·예시 데이터를 사용한다.

```sh
npm run dev:demo
```

공통 가상 비밀번호는 `hops-demo-only`다. 실제 인증 계정이 아니며 로컬 Mock에서만 동작한다.

- `demo.requester`: 새 문의·내 문의·추가 질문·시스템 선택·복합 문의·문제별 해결 확인.
- `demo.staff`: 전자결재·공통 로그인 업무 보드/목록·상세 모달·처리 기록·정정·해결 사례 검토/승인.
- `demo.file`: 파일 공유 업무와 해당 시스템의 해결 사례 검토.
- `demo.admin`: 업무 관리 목록·제한 상세·책임 시스템 정정.

담당자는 `/tickets`에서 네 처리 상태의 예시를 확인한다. 티켓 선택 → 상세 모달의 `처리·검증` 탭에서 입력한다. `/cases`의 제목에서 `사례 초안`, `색인 부분 실패`, `승인 완료`, `구현 검증` 예시를 선택한다. `초안 생성 실패`와 `재사용 사례 없음`은 해당 티켓 상세 모달의 문의 처리 기록 탭에서 확인한다. 문의자는 `/inquiries`에서 `[예시]` 문의를 선택한다.

미리 응답된 해결 확인은 **가상 확인 시나리오(SYNTHETIC_PRESET)**로 표시한다. 확인 대기 예시에서 로그인 후 직접 저장한 응답은 Hops 문의자 확인으로 구분된다. 같은 탭에서 로그아웃·역할 전환하면 변경 상태를 이어서 볼 수 있고 새로고침하면 예시가 초기화된다. `dev:demo`는 실제 Spring·AI·검색 서버를 호출하지 않는다.

`VITE_DATA_MODE=mock`을 명시하면 서버를 호출하지 않고 같은 신규 계약의 메모리 대역을 사용한다. `VITE_MOCK_PREVIEW_ID`와 `VITE_MOCK_PASSWORD`에 가상 값만 입력하면 시스템 담당+관리자 체험 계정을 사용한다. 역할별 검사는 PREVIEW_ID를 비우고 REQUESTER/NETWORK/BACKEND/ADMIN 아이디와 공통 가상 비밀번호를 설정한다. 네 아이디는 모두 존재하며 서로 달라야 한다. NETWORK/BACKEND는 기존 환경 변수 이름을 유지한 것으로 현재는 각각 전자결재·공통 로그인 담당 / 파일 공유 담당을 뜻한다.

가상 카탈로그는 `systemMockData.ts`의 hops-demo-v1이다. 초기 두 문의는 REQUESTER 소유이며 체험 계정은 본인 새 문의를 생성할 수 있다. 시스템 선택 후 보완 응답은 접수 예시, 시스템 미선택은 최대 5회 질문 뒤 확인 대기다. `동시에`·`생성 실패`·`저장 미확인`·`시간 초과`·`모델 오류`는 고정 시나리오다. 시스템을 지정한 `사용법`·`안내만` 문의는 GUIDE 확인 예시다. Mock은 실제 모델·서버 저장·검색 검증이 아니다.

후속 흐름은 담당자로 초기 티켓에서 검증 완료 기록 저장 → REQUESTER로 해당 문의의 해결 응답 → 담당자로 사례 목록의 초안 검토·저장·승인 순으로 확인한다. 역할 전환은 로그아웃 후 로그인하며 같은 탭을 유지해야 메모리 데이터가 이어진다. 새로고침 시 Mock 데이터는 초기화된다. 초안 생성·색인 실패와 유실·충돌 검사는 `systemWorkflow.test.tsx`에 별도로 정의한다.

## 실행·종료

Frontend·Spring·AI·PostgreSQL을 함께 실행하려면 [루트 Docker Compose 안내](../README.md#docker-compose로-실행)를 따른다. 프론트 단독 실행도 아래 루트 Compose의 서비스 선택 명령을 사용할 수 있다. Docker Engine·Compose 2.20 이상과 첫 빌드의 의존성 다운로드가 필요하다. 먼저 루트 안내대로 서비스별 `.env` 세 개를 준비하고 다음 명령을 저장소 루트에서 실행한다.

```sh
docker compose up --build --no-deps -d frontend
docker compose ps frontend
docker compose logs -f frontend
docker compose stop frontend
```

프론트 단독 Compose는 Spring·DB·AI를 실행하지 않는다. 통합 Compose로 기동해도 각 서버의 신규 `/api/v2` 구현과 실제 연동 검증은 별도다. 포트는 loopback에만 바인딩하고 `FRONTEND_PORT`로 변경한다. `.env` 변경은 컨테이너 재생성, 소스 변경은 재빌드 후 반영된다. 기존 `.env`가 있으면 덮어쓰지 않는다.

Node.js 24.22.0을 사용해 `frontend/`에서 직접 실행할 수도 있다. 이 경우 `frontend/.env`만 준비하면 된다.

```sh
npm ci
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

브라우저에서 `http://localhost:5173`으로 접속한다. live 요청은 지정한 origin으로 직접 나가며 정적 빌드에서는 `VITE_` 설정이 빌드 시 포함된다. URL을 변경하면 다시 빌드한다.

## 검증

```sh
npm run check
npm run build
```

타입·린트·서식·컴포넌트·Mock·실제 loopback HTTP/SSE 대역을 검사한다. UI 검사는 저장/권한 계약, 초안/원 요청 보존, 목록/상세 모달·처리 입력·해결 응답·사례 승인/부분 실패 복구와 메시지 정정·재접속을 구분한다. 대역 통과는 실제 Spring·DB·AI·브라우저 쿠키/CORS·E2E 통과가 아니다.

`springBackend.integration.test.ts`는 신규 `/api/v2` 개발 서버의 읽기 통합 검사다. `HOPS_TEST_SPRING_URL`, `HOPS_TEST_LOGIN_ID`, `HOPS_TEST_PASSWORD`를 명시했을 때만 실행하며 현재 PRIMARY 개발 계정을 사용한다. Node 테스트에서 보낼 Origin은 기본 `http://localhost:5173`이며, 다른 환경에서는 Spring이 허용한 프론트 주소를 `HOPS_TEST_FRONTEND_ORIGIN`으로 지정한다. 이 값에는 `VITE_` 접두사를 사용하지 않는다. 문의 생성·모델 호출을 수행하지 않는다. 실제 AI·검색·업무 저장 통합은 별도 검증한다.

Spring은 `CurrentUser.primarySystemIds`와 시스템·본인 문의 목록을 제공한다. 실제 서버 연동에는 시스템 기반 업무 API와 `SYSTEM_TICKETS`·`CASE` 등의 SSE 구현·검증이 남아 있다. 서버 실행·Refresh 쿠키/Redis 설정은 [Spring 실행 안내](../backend/spring/hops/README.md)를 따른다.

정본은 [PRD](../docs/PRD.md), [DATA_TYPES](../specs/contracts/DATA_TYPES.md#신규-계약-hops-system-1), [WIRE_CONTRACT 12절](../specs/contracts/WIRE_CONTRACT.md#12-신규-계약-hops-system-1), 개발 구조·경로·규칙은 [프론트 개발 기준](../docs/frontend/README.md)이다.
