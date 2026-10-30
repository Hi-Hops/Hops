# Hops 프론트엔드 실행·서버 연동

> **PRD 1.44 전환:** 아래 부서 티켓 화면·Mock·기존 계약은 현재 프론트 구현 설명이다. 목표는 시스템 주담당자 업무·사용자 해결 확인·사례 승인과 검색 반영이며 아직 화면·서버 연결이 완료된 것은 아니다. [PRD](../docs/PRD.md)와 [새 기능 목표](../specs/README.md)를 따른다.

로그인·문의 채팅·메시지 정정·결과 카드·부서 티켓 조회의 프론트엔드와 Mock·fetch SSE Live Adapter를 제공한다. React→Spring REST로 변경 요청을 보내고 Spring→React SSE를 소비한다. 실제 팀원 서버의 업무/AI 연결 완료와는 구분하며 [연동 프로필](../specs/contracts/WIRE_CONTRACT.md#9-프론트-live-연동-프로필)을 따른다.

## 1. 실행 환경

Docker Engine과 Docker Compose **2.20 이상**을 준비한다. Windows/macOS는 Docker Desktop을 실행하고 Windows는 Linux 컨테이너 모드를 사용한다. 최초 실행은 이미지·npm 패키지를 내려받을 네트워크가 필요하다. 저장소의 `frontend/`와 `specs/`가 함께 있어야 한다.

아래 명령은 모두 **`frontend/`에서** 실행한다. `.env.example`을 같은 폴더의 `.env`로 복사한다.

```powershell
Copy-Item .env.example .env
```

macOS/Linux는 `cp .env.example .env`를 사용한다. 기존 `.env`가 있으면 덮어쓰지 말고 필요한 항목만 추가한다. `.env`는 Git·Docker 이미지에서 제외된다.

## 2. 실행 모드와 .env

### 서버 없이 Mock으로 실행

`VITE_DATA_MODE=mock`으로 설정한다. 생략해도 기본은 `mock`이다. **한 계정으로 전체 화면을 체험하려면 아래 두 값만 직접 채운다.** `.env.example`의 옆 주석은 가상 값 예시이며 자동으로 적용되지 않는다.

- `VITE_MOCK_PREVIEW_ID`: 한 계정 체험용 아이디.
- `VITE_MOCK_PASSWORD`: 체험용 가상 비밀번호.

이 계정은 기존 예시 문의의 소유자이며 `BIZ_ADMIN` 역할을 가진 Mock 프로필이다. 채팅·내 문의·새 문의와 전체 부서 티켓 목록·상세를 계정 전환 없이 확인한다. 사이드바 상단의 **부서 티켓 현황**(`/tickets`)과 **관리자 페이지**(`/admin/tickets`)로 이동한다. 관리자여도 다른 사용자의 비공개 문의 소유권 검사는 유지한다.

상단 **+ 새 문의**는 메인 입력창을 열고 포커스를 옮긴다. 첫 메시지 저장 전에는 빈 문의를 만들지 않으며 작성 중인 입력은 화면 이동 후에도 보존한다. 하단 **채팅 세션**에서 본인 문의를 선택해 이어가고, 20건을 넘으면 **더 보기**로 다음 목록을 불러온다. 실제 서버도 기존 `GET /api/inquiries?page=0&size=20`의 권한·정렬·페이지 계약을 그대로 사용한다.

**역할별 권한을 따로 검사하려면** `VITE_MOCK_PREVIEW_ID`를 비우고 아래 네 아이디와 위 공통 비밀번호를 입력한다.

- `VITE_MOCK_REQUESTER_ID`: 문의자 아이디.
- `VITE_MOCK_NETWORK_ID`: 네트워크 부서 담당자 아이디.
- `VITE_MOCK_BACKEND_ID`: backend 부서 담당자 아이디.
- `VITE_MOCK_ADMIN_ID`: 업무 관리자 아이디.

역할별 모드에서는 네 아이디가 서로 달라야 하며 누락·중복이면 오류를 안내한다. 체험 아이디가 있으면 역할별 계정 설정은 사용하지 않는다. **`VITE_` 값은 브라우저에 전달되므로 실제 계정·비밀번호·API 키를 넣지 않는다.** Mock 모드에서는 서버 주소가 있어도 서버에 요청하지 않는다. live에서는 체험 프로필을 사용하지 않고 Spring 응답의 권한을 따른다.

### 팀원 서버와 실제로 연동

`.env`에서 다음 항목을 설정한다. 아래는 같은 PC에서 서버를 실행하는 예시이며 각 담당자가 실행한 주소로 바꾼다.

```dotenv
VITE_DATA_MODE=live
VITE_API_BASE_URL=http://localhost:8080
FRONTEND_PORT=5173
```

- `VITE_API_BASE_URL`은 브라우저에서 접근 가능한 Spring origin이다. `/api`나 SSE 쿼리·인증 정보를 값에 붙이지 않는다. REST와 `/api/realtime/events` SSE가 같은 origin을 사용한다.
- 프론트가 Docker에서 실행돼도 요청은 사용자의 브라우저에서 나간다. 같은 PC의 서버에는 `localhost`, 다른 팀원 PC에는 접근 가능한 IP/도메인을 사용한다. Docker 서비스 이름은 브라우저에서 일반적으로 해석되지 않는다.
- `VITE_MOCK_*`는 live에서 사용하지 않는다. 실제 서버 계정은 로그인 화면에 입력한다. `.env`에 실제 계정·비밀번호·JWT·서버 비밀키를 적지 않는다.
- 서버 주소가 없거나 잘못되면 설정 오류를 안내한다. 통신 실패를 Mock 데이터로 대체하지 않는다. 모드는 실행 중 바꾸지 않고 변경 후 서버/컨테이너를 재시작한다.
- Access Token은 메모리에 보관하고 REST와 fetch 기반 SSE의 Bearer 헤더로 전달한다. 만료 시 `POST /api/auth/refresh`를 한 번 공유해 갱신하고 SSE를 새 토큰으로 다시 연다. Refresh Token 무효·부서/역할 변경은 입력을 보존한 재로그인으로 처리한다. 일반 403은 권한 안내다. 갱신 네트워크 장애는 인증 만료로 단정하지 않는다. 새로고침 시 자동 로그인 복원은 제공하지 않으며 live 저장 데이터는 서버가 관리한다.
- 로그인·refresh·로그아웃 요청은 `credentials: include`를 사용한다. Spring은 HttpOnly Refresh 쿠키 발급/회전/삭제와 허용 Origin·CSRF 검증, 정확한 CORS origin 및 Allow-Credentials를 구현해야 한다. 업무 REST는 쿠키 없이 Bearer를 사용한다. HTTPS Secure·SameSite 정책 등 서버 설정은 [추가 계약](../specs/contracts/WIRE_CONTRACT.md#10-인증재접속최신-대화분석-재시도-추가-프로필)을 함께 검토한다. 프론트의 호출 구현만으로 실제 쿠키 인증 검증 완료를 뜻하지 않는다.

## 3. Docker 실행·종료

```sh
docker compose up --build -d --wait
docker compose ps
docker compose logs -f frontend
docker compose down
```

기본 주소는 **http://127.0.0.1:5173/** 이다. 포트가 사용 중이면 `.env`의 `FRONTEND_PORT`를 바꾼다. 포트는 로컬 PC에만 바인딩한다. 로그 확인은 `Ctrl+C`, 서버 종료는 `down`이다.

`.env`만 바꿨다면 `docker compose up -d --force-recreate --wait`, 소스가 바뀌었다면 `docker compose up --build -d --wait`를 실행한다. 이 이미지는 프론트 Vite 개발 서버만 실행한다. Spring·DB·AI 서버는 각 담당자가 별도로 실행해야 한다.

Node.js **24.22.0**을 PC에 설치했다면 `.env` 작성 후 직접 실행할 수도 있다.

```sh
npm ci
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

직접 실행은 `.env` 변경 후 개발 서버를 재시작한다. `FRONTEND_PORT`는 Compose용이고 직접 실행 포트는 `--port`로 지정한다. live 요청은 지정한 origin으로 직접 나가며 Vite 개발 프록시를 사용하지 않는다. 정적 빌드는 `VITE_` 설정이 빌드 시 포함되므로 주소를 바꾸면 다시 빌드해야 한다.

## 4. Spring REST·SSE 연동 협의 기준

목표 요청 URL·메서드·성공 코드·SSE 이벤트는 [작업별 연결표](../specs/contracts/WIRE_CONTRACT.md#8-작업별-데이터-타입-연결)에 있다. 아래 내용은 이 개발 프로필을 맞춰 볼 때의 연결 조건이며 확정한 정책과 실제 서비스 구현·팀 리뷰·검증 완료는 구분한다. 접속·CORS·연결 오류는 [연동 프로필](../specs/contracts/WIRE_CONTRACT.md#9-프론트-live-연동-프로필)을 함께 검토한다.

- **Spring REST:** 로그인·현재 사용자·로그아웃, 문의 생성·메시지 전송·정정, 문의·메시지·실행 조회와 부서 티켓 목록·상세를 구현한다. 변경 요청은 `Idempotency-Key`를 받고 DB 저장 뒤 `MutationResult`를 반환한다. 프론트는 별도 `data` wrapper 없이 응답 객체 자체를 읽고 로그아웃은 204 본문 없음으로 처리한다.
- **Spring SSE:** `GET /api/realtime/events?targetType={type}&targetId={id}`에서 Bearer JWT와 현재 대상 권한을 검사한다. 한 연결은 한 대상을 수신하며 `agent.progress`, `answer.delta`, `message.stored`, `state.changed`, `auth.invalidated`, `resync.required`를 `id/event/data` 형식으로 전달한다.
- **복구:** 프론트는 eventId를 중복 제거하고 마지막 ID를 `Last-Event-ID`로 보낸다. replay가 불가능하면 REST 원본으로 복구한다. SSE 수신이나 연결 성공은 저장 성공이 아니며 저장 결과는 REST 응답과 조회로 확인한다.
- **CORS:** Spring은 브라우저 origin(예: `http://127.0.0.1:5173`)에 GET/POST/OPTIONS와 `Authorization`·`Content-Type`·`Idempotency-Key`를 허용한다. `localhost`와 `127.0.0.1`은 다른 origin이다. HTTPS 화면에서는 Spring도 HTTPS를 제공한다.
- 필드·타입·nullable·상태·권한은 [DATA_TYPES](../specs/contracts/DATA_TYPES.md), REST 멱등성과 SSE 복구는 [WIRE_CONTRACT](../specs/contracts/WIRE_CONTRACT.md), 예시 JSON은 [공용 Mock](../specs/mocks/README.md)을 따른다.

SSE가 연결되지 않아도 로그인·조회와 채팅 REST 저장은 수행할 수 있다. 재연결 때 현재 토큰과 대상 권한을 다시 확인하고 저장 데이터는 REST로 재조회한다. 변경 REST가 10초 안에 응답하지 않거나 연결이 끊기면 결과 미확인으로 표시하고 같은 멱등 키·본문으로 다시 확인할 수 있다. 변경 요청은 자동 반복하지 않는다.

문의 생성·추가 메시지·메시지 정정의 성공 응답은 [MutationResult](../specs/contracts/DATA_TYPES.md#mutationresult--rest-저장-결과의-프론트-연동-프로필)다. 프론트는 UUID·버전 범위·요청 대상 일치와 `effect`/`runId` 조합을 검사한다. `ANALYSIS_STARTED`는 실행 접수, `SUPPLEMENT_SAVED`는 실행 없는 보완 저장으로 구분한다. 잘못된 성공 응답도 저장 결과 미확인으로 처리하며 입력과 재확인 키를 보존한다. 분석 재시도 응답은 별도 `RunSummary`다.

### PR #59와 연결할 때

PR #59는 develop에 병합됐다(dc44f2c). 현재 replay·Redis·스냅샷 커서는 제공하지 않는다. Last-Event-ID를 재생할 수 없으면 eventId만 포함한 resync.required를 보낸 뒤 종료하며 프론트는 REST 복구 후 커서를 비워 새 연결을 연다. 새 스트림 수신을 시작한 뒤 다시 조회하고 comment 문구에는 의존하지 않는다. state.changed.target은 type·id 형식이다. INQUIRY·TICKET의 현재 503은 서버 도메인 권한·생산자 연결 전 상태이며 프론트의 Mock 성공으로 대체하지 않는다.

## 5. 연동 확인 순서

1. Spring을 실행하고 live 설정으로 프론트를 재시작한다.
2. 로그인 후 Network에서 `/api/auth/login` → Bearer `/api/auth/me`와 `text/event-stream` 연결을 확인한다. 토큰·비밀번호가 담긴 캡처는 공유하지 않는다.
3. 새 문의를 REST로 전송해 저장 결과의 문의 ID로 화면이 이동하는지 확인한다. 문의 REST 조회·대상 SSE·진행/답변·저장 메시지가 이어지는지 본다.
4. 추가 메시지·정정을 REST로 보내 저장 결과, 버전 충돌, 이력·결과 재조회를 확인한다. SSE 단절 시 원문을 보존하고 재연결·REST 조회 후 최신 상태로 복구하는지 본다.
5. 부서 담당자로 로그인해 허용된 티켓 목록·필터·페이지·읽기 전용 상세를 확인한다. 다른 부서 상세는 서버가 거부해야 한다. `state.changed` 후 갱신도 확인한다.

새 문의는 실시간 이벤트 없이 REST 저장 결과만 받아도 개인 세션 목록을 갱신한다. 채팅은 최신 30개를 오래된 순서로 표시하고 처음에는 맨 아래로 이동한다. 위로 스크롤하면 beforeSequenceNo로 이전 30개를 추가하며 위치를 유지한다. 과거를 읽는 중 새 답변은 새 메시지 버튼으로 이동한다. 최초 최신 조회 전·최신 재조회 실패 중에는 전송·정정을 막지만 이전 대화 조회 실패는 최신 대화와 입력을 유지한다. 정정 충돌은 최신 원문과 초안을 비교하도록 안내한다. 티켓 권한 없는 상세 URL은 모달·상세 API·SSE를 시작하지 않으며 최종 권한은 서버가 검사한다.

Mock에서는 직접 정한 가상 로그인 값을 사용한다. `VPN 연결이 안 돼요` → `809 오류예요`로 기본 흐름, `권한`으로 복수 티켓·부분 실패, `날씨`로 범위 밖, `시간 초과`·`모델 오류`로 실행 오류, `CS 검토`로 CS 배정을 확인한다. 같은 탭의 계정 전환은 데이터를 유지하고 새로고침하면 Mock 데이터는 초기화된다. 이 키워드는 live 서버 동작을 강제하지 않는다.

문제가 생기면 모드·Spring 주소·REST/SSE 응답 상태·CORS·프록시 buffering/timeout·브라우저 origin·포트를 확인한다. 다른 필드·이벤트 이름은 자동 변환하지 않으므로 계약과 함께 맞춘다.

## 6. 자동 검사

```sh
docker compose run --rm --no-deps frontend npm run check
docker compose run --rm --no-deps frontend npm run build
```

`check`는 타입·린트·포맷·Vitest 검사다. 목표 테스트는 Mock·화면 테스트와 임시 HTTP/SSE 서버를 실제로 띄우는 어댑터 통신 테스트를 포함한다. HTTP/SSE 대역 서버 테스트는 Bearer·대상별 연결·중복·Last-Event-ID·PR #59의 replay 미지원 종료·REST 복구·제어 이벤트를 검사한다. 개인 `.env`나 팀원 서버·실제 계정 없이 실행한다.

통신 테스트는 계약용 대역 서버와의 연결 검사다. 팀원 Spring·DB·AI 통합, 실제 JWT 검증, 브라우저 CORS, 자동 E2E·모델 품질 평가를 대신하지 않는다. `build` 산출물은 임시 컨테이너 종료 시 제거된다.

실제 Spring 연결 검사는 `src/test/springBackend.integration.test.ts`로 별도 실행한다. `HOPS_TEST_SPRING_URL`·`HOPS_TEST_LOGIN_ID`·`HOPS_TEST_PASSWORD` 환경변수를 개발 서버 주소와 개발용 처리 부서 계정으로 설정한 뒤 `npx vitest run src/test/springBackend.integration.test.ts`를 실행한다. 값이 없으면 이 검사는 생략하며 운영 계정을 사용하지 않는다. 로그인·Bearer 내 정보 조회·현재 부서 SSE 연결/해제·resync.required의 실제 응답을 검사한다. 화면 조작·브라우저 CORS·미구현 업무 API 성공 검증은 포함하지 않는다.

현재 Spring 공개 구현은 `POST /api/auth/login`, `GET /api/auth/me`, `GET /api/realtime/events`다. SSE는 `DEPARTMENT_TICKETS`만 허용하고 `INQUIRY`·`TICKET`은 503을 반환한다. 문의 저장·티켓 조회·refresh·로그아웃의 프론트 호출이 있어도 해당 백엔드 기능의 연결 완료로 보지 않는다.

## 7. 지원 범위

- F001 로그인, F002 SSE 연결·권한·재접속, F003 채팅, F004 정정, F005 문제별 결과·복수 티켓·부분 실패, F009 부서 티켓 목록·읽기 전용 상세를 Mock/live 공통 화면으로 제공한다.
- 내 문의는 재진입용 최소 목록, 관리자는 같은 티켓 화면의 전체 조회다. F006·F007 전체 완료를 뜻하지 않는다. live의 AI 동작·저장·배정·권한은 서버 구현에 달려 있다.
- 부서 티켓의 읽기 전용 MVP 화면은 목록(번호·제목·부서·처리/배정 상태·생성 시각), 처리 상태 필터·페이지 이동, 상세의 문의 요약·AI 진단·배정 이력과 조회/권한 오류를 제공하며 Mock/대역 테스트로 검사한다. 현재 live 서버에서는 로그인 사용자·소속 부서와 부서 SSE를 연결할 수 있지만 티켓 목록·상세 REST가 없어 실제 티켓 데이터를 표시하지 못한다. 조회 실패는 해당 영역에 표시하고 정상 SSE는 유지한다. 관련 대화·가져오기·처리 완료·재이관은 후속이다.
- 부서 화면은 기본 칸반이며 `view=board|list`로 목록과 전환한다. 카드는 처리 대기·처리 중·사용자 확인 대기·종료 컬럼으로 나누고 종료의 해결 완료/미확인 종료를 구분한다. 컬럼 건수는 현재 페이지 기준이며 필터·페이지·상세 복귀를 유지한다. view는 서버 요청에 보내지 않는다. Figma의 그룹·우선순위·요청자/개인 담당자·검색·컬럼별 전체 집계는 기존 계약에 없어 후속 검토로 둔다.
- 관리자 페이지는 소속 부서와 무관한 서버 허용 전체 목록·상세를 표시하며 기본은 목록이다. 부서 필터로 응답을 줄이지 않는다. 기존 부서 SSE와 5초 전체 REST 재조회·수동 새로고침을 유지하며 관리자 전용 SSE 대상은 추가하지 않는다.
- 채팅·정정·분석 재시도는 429의 Retry-After 동안 재전송 버튼과 제출을 막고 입력·요청 키를 보존한다. 대기 종료 후 자동 전송하지 않는다. 분석 대기/최신 입력 대체/미등록 상태를 구분하며 실행이 바뀌면 이전 임시 답변·진행 문구를 새 실행에 섞지 않는다.
- F003-AC10은 retryable=true인 실패/시간 초과에 다시 분석 버튼과 `POST /api/runs/{id}/retries`를 연결한다. 본문은 requestId·expectedInquiryVersion이며 응답은 RunSummary다. 접수 중 중복 클릭 차단, 통신 오류 시 같은 키 재확인, 충돌 시 최신 조회를 적용한다. 실제 서버의 소유권·중복 방지·기존 성공 작업 보존이 필요하며 LLM 중간 체크포인트 재개는 미구현이다.
- 티켓 가져오기·처리·재이관·관련 대화·장애 그룹·통계, F008 새 문의 분리 동의, F010 기술 복구는 제공하지 않는다. 실제 Refresh 쿠키/회전·실행 중 열린 SSE 회수·replay·DB 제약은 서버 담당자의 구현 및 통합 검증이 필요하다. REST 응답 대기 10초·SSE 자동 재접속 30초 기준은 추가 계약을 따른다.
- [Figma 작업본](https://www.figma.com/design/5W6UYTaYAuLnQNt2jRiqgc/Hops?node-id=6-2)과 [칸반 v1.41](https://www.figma.com/design/5W6UYTaYAuLnQNt2jRiqgc/Hops?node-id=247-2855)을 참고했다. 칸반은 기존 데이터 계약의 조회 전용 범위에 맞춰 구현하며 전체 Figma 기능·픽셀·반응형 검증 완료를 뜻하지 않는다.

개발 기준은 [docs/frontend](../docs/frontend/README.md)를 참고한다. 검사 결과·미실행 항목은 변경 PR에 기록한다.

## 확정 계약과 아직 구현·검증하지 않은 부분 (2026-10-27)

정책 원본은 [WIRE_CONTRACT 10절](../specs/contracts/WIRE_CONTRACT.md#10-인증재접속최신-대화분석-재시도-추가-프로필), 담당별 작업/미검증은 [11절](../specs/contracts/WIRE_CONTRACT.md#11-담당별-구현과-검증-상태)이다. 프론트 구현 상태와 실제 서비스 통합 상태를 구분한다.

- 실제 인증 기준은 Access 15분·Refresh 최초 로그인부터 절대 7일·매 갱신 회전이다. 기존 실행 Mock은 Access 15분·메모리 Refresh를 사용하므로 서버 쿠키/회전/폐기 검증을 대신하지 않는다.
- Socket.IO 의존성·환경 설정·transport·명령/구독 타입을 제거하고 REST mutation·fetch SSE·제어 이벤트·대상별 연결 공유로 전환했다. ResyncRequiredEvent는 develop 확정 형식을 사용하고 MutationResult에 맞춘 백엔드 저장 DTO·API 구현은 Q-INF-6으로 추적한다.
- 실제 Spring/DB/AI의 토큰 회전·권한 회수·SSE replay·DDL/중복 방지·AI 실행 예산·종료 후 정정·E2E·품질/비용은 아직 검증하지 않았다. Mock/HTTP·SSE 대역 서버 테스트와 구분한다.
