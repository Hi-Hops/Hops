# Hops 프론트엔드 실행·서버 연동

로그인·문의 채팅·메시지 정정·결과 카드·부서 티켓 조회의 프론트엔드 기반과 Mock·임시 Live Adapter를 제공한다. 같은 화면을 `mock`(서버 없이 가상 데이터) 또는 `live`(Spring REST + Node Socket.IO)로 실행한다. 확정 개발 정책과 현재 어댑터 구현의 차이는 [확정 기준과 남은 담당 작업](../specs/contracts/README.md#확정-기준과-남은-담당-작업)을 확인한다. 개발 기준 확정은 팀 리뷰·병합이나 실제 팀원 서버 통합 완료를 뜻하지 않는다.

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
VITE_SOCKET_URL=http://localhost:3001
VITE_SOCKET_PATH=/socket.io
FRONTEND_PORT=5173
```

- 두 URL은 **브라우저에서 접근 가능한 서버 origin**이다. `/api`, `/socket.io`, 쿼리나 인증 정보를 URL에 붙이지 않는다. `/api`는 프론트 요청 경로에 포함되어 있다. Socket.IO path는 별도 `VITE_SOCKET_PATH`로 지정한다. namespace는 기본 `/`다.
- 프론트가 Docker에서 실행돼도 요청은 사용자의 브라우저에서 나간다. 같은 PC의 서버에는 `localhost`, 다른 팀원 PC에는 접근 가능한 IP/도메인을 사용한다. Docker 서비스 이름은 브라우저에서 일반적으로 해석되지 않는다.
- `VITE_MOCK_*`는 live에서 사용하지 않는다. 실제 서버 계정은 로그인 화면에 입력한다. `.env`에 실제 계정·비밀번호·JWT·서버 비밀키를 적지 않는다.
- 서버 주소가 없거나 잘못되면 설정 오류를 안내한다. 통신 실패를 Mock 데이터로 대체하지 않는다. 모드는 실행 중 바꾸지 않고 변경 후 서버/컨테이너를 재시작한다.
- Access Token은 메모리에 보관하고 REST Bearer·Socket.IO auth.token으로 전달한다. 만료 시 `POST /api/auth/refresh`를 한 번 공유해 갱신하고 Refresh Token 무효·부서/역할 변경은 입력을 보존한 재로그인으로 처리한다. 일반 403은 권한 안내다. 갱신 네트워크 장애는 인증 만료로 단정하지 않는다. 새로고침 시 자동 로그인 복원은 제공하지 않으며 live 저장 데이터는 서버가 관리한다.
- 로그인·refresh·로그아웃 요청은 `credentials: include`를 사용한다. Spring은 HttpOnly Refresh 쿠키 발급/회전/삭제와 허용 Origin·CSRF 검증, 정확한 CORS origin 및 Allow-Credentials를 구현해야 한다. 업무 REST는 쿠키 없이 Bearer를 사용한다. HTTPS Secure·SameSite 정책 등 서버 설정은 [추가 계약](../specs/contracts/WIRE_CONTRACT.md#10-인증재접속최신-대화분석-재시도-추가-프로필)을 함께 검토한다. 프론트의 호출 구현만으로 실제 쿠키 인증 검증 완료를 뜻하지 않는다.

## 3. Docker 실행·종료

```sh
docker compose up --build -d --wait
docker compose ps
docker compose logs -f frontend
docker compose down
```

기본 주소는 **http://127.0.0.1:5173/** 이다. 포트가 사용 중이면 `.env`의 `FRONTEND_PORT`를 바꾼다. 포트는 로컬 PC에만 바인딩한다. 로그 확인은 `Ctrl+C`, 서버 종료는 `down`이다.

`.env`만 바꿨다면 `docker compose up -d --force-recreate --wait`, 소스가 바뀌었다면 `docker compose up --build -d --wait`를 실행한다. 이 이미지는 프론트 Vite 개발 서버만 실행한다. Spring·Node·DB·AI 서버는 각 담당자가 별도로 실행해야 한다.

Node.js **24.22.0**을 PC에 설치했다면 `.env` 작성 후 직접 실행할 수도 있다.

```sh
npm ci
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

직접 실행은 `.env` 변경 후 개발 서버를 재시작한다. `FRONTEND_PORT`는 Compose용이고 직접 실행 포트는 `--port`로 지정한다. live 요청은 지정한 origin으로 직접 나가며 Vite 개발 프록시를 사용하지 않는다. 정적 빌드는 `VITE_` 설정이 빌드 시 포함되므로 주소를 바꾸면 다시 빌드해야 한다.

## 4. 백엔드·채팅 서버 담당자 연동 협의 기준

현재 어댑터가 기대하는 요청 URL·메서드·성공 코드·쿼리는 [REST 목록](../specs/contracts/WIRE_CONTRACT.md#2-rest와-페이지), 소켓 이벤트별 입출력은 [작업별 연결표](../specs/contracts/WIRE_CONTRACT.md#8-작업별-데이터-타입-연결)에 있다. 아래 내용은 이 개발 프로필을 맞춰 볼 때의 연결 조건이며 확정한 개발 정책과 실제 서비스 구현/팀 리뷰·검증 완료는 구분한다. 접속·CORS·연결 오류는 [연동 프로필](../specs/contracts/WIRE_CONTRACT.md#9-프론트-live-연동-프로필)을 함께 검토한다.

- **Spring:** 로그인·현재 사용자·로그아웃, 문의 목록·상세·메시지·실행 조회, 부서 티켓 목록·상세를 구현한다. 정확한 URL과 JSON 타입은 위 목록을 따른다. 프론트는 별도 `data` wrapper 없이 응답 객체 자체를 읽고 로그아웃은 204 본문 없음으로 처리한다.
- **Node Socket.IO:** 연결의 `auth.token`으로 Spring에 인증을 확인한다. `subscription.request` 대상 접근 권한도 Spring에 확인한 뒤 룸에 참여시키고 `SubscriptionAck`를 반환한다. 프론트는 임의 룸 이름이나 권한 판단용 userId를 보내지 않는다.
- 문의 생성 `inquiry.create`, 전송 `message.send`, 정정 `message.revise`를 Spring에 전달하고 **Spring 저장 결과를 받은 뒤** `CommandAck`를 응답한다. 접속이나 룸 참여만으로 메시지 저장 성공 ACK를 보내지 않는다.
- Node는 `agent.progress`, `answer.delta`, `message.stored`, `state.changed`를 허용된 연결에 중계한다. ACK는 별도 이벤트가 아닌 Socket.IO 명령 콜백이다. 토큰은 이벤트 payload·URL·로그에 넣지 않는다.
- **CORS:** Spring은 브라우저 origin(예: `http://127.0.0.1:5173`)에 GET/POST/OPTIONS와 `Authorization`·`Content-Type`을 허용한다. Node도 같은 origin의 polling·WebSocket 연결을 허용하고 Spring 연결을 준비한다. `localhost`와 `127.0.0.1`은 다른 origin이다. HTTPS 화면에서는 서버도 HTTPS/WSS를 제공한다.
- 필드·타입·nullable·상태·권한은 [DATA_TYPES](../specs/contracts/DATA_TYPES.md), ACK·멱등성·복구는 [WIRE_CONTRACT](../specs/contracts/WIRE_CONTRACT.md), 예시 JSON은 [공용 Mock](../specs/mocks/README.md)을 따른다. Spring↔Node 내부 검증·제어 이벤트의 입출력은 WIRE_CONTRACT 10절을 따르며 실제 서비스 인증/중계 구현은 두 서버 담당자가 맡는다. 이 내부 API를 프론트용 URL과 혼용하지 않는다. 미정 운영 정책의 팀 승인을 대신하지 않는다.

채팅 서버가 연결되지 않아도 로그인·REST 티켓 조회는 사용할 수 있다. 채팅은 연결 복구 후 전송한다. 재연결 때 현재 토큰으로 인증·구독을 다시 수행하고 저장 데이터는 REST로 재조회한다. ACK가 10초 안에 오지 않거나 연결이 끊기면 결과 미확인으로 표시하고 같은 요청 ID로 다시 확인할 수 있다. 저장 명령은 자동 반복하지 않는다.

## 5. 연동 확인 순서

1. Spring·Node를 실행하고 live 설정으로 프론트를 재시작한다.
2. 로그인 후 Network에서 `/api/auth/login` → Bearer `/api/auth/me`와 Socket.IO 연결을 확인한다. 토큰·비밀번호가 담긴 캡처는 공유하지 않는다.
3. 새 문의를 전송해 `inquiry.create` ACK의 문의 ID로 화면이 이동하는지 확인한다. 문의 REST 조회·구독·진행/답변·저장 메시지가 이어지는지 본다.
4. 추가 메시지·정정을 보내 저장 ACK, 버전 충돌, 이력·결과 재조회를 확인한다. 연결 단절 시 원문을 보존하고 재연결 후 최신 상태로 복구하는지 본다.
5. 부서 담당자로 로그인해 허용된 티켓 목록·필터·페이지·읽기 전용 상세를 확인한다. 다른 부서 상세는 서버가 거부해야 한다. `state.changed` 후 갱신도 확인한다.

새 문의는 실시간 이벤트 없이 저장 ACK만 받아도 개인 세션 목록을 갱신한다. 채팅은 최신 30개를 오래된 순서로 표시하고 처음에는 맨 아래로 이동한다. 위로 스크롤하면 beforeSequenceNo로 이전 30개를 추가하며 위치를 유지한다. 과거를 읽는 중 새 답변은 새 메시지 버튼으로 이동한다. 최초 최신 조회 전·최신 재조회 실패 중에는 전송·정정을 막지만 이전 대화 조회 실패는 최신 대화와 입력을 유지한다. 정정 충돌은 최신 원문과 초안을 비교하도록 안내한다. 티켓 권한 없는 상세 URL은 모달·상세 API·구독을 시작하지 않으며 최종 권한은 서버가 검사한다.

Mock에서는 직접 정한 가상 로그인 값을 사용한다. `VPN 연결이 안 돼요` → `809 오류예요`로 기본 흐름, `권한`으로 복수 티켓·부분 실패, `날씨`로 범위 밖, `시간 초과`·`모델 오류`로 실행 오류, `CS 검토`로 CS 배정을 확인한다. 같은 탭의 계정 전환은 데이터를 유지하고 새로고침하면 Mock 데이터는 초기화된다. 이 키워드는 live 서버 동작을 강제하지 않는다.

문제가 생기면 모드·두 주소·Socket.IO path·서버 실행 여부·CORS·브라우저 origin·포트를 확인한다. 다른 필드·이벤트 이름은 자동 변환하지 않으므로 계약과 함께 맞춘다.

## 6. 자동 검사

```sh
docker compose run --rm --no-deps frontend npm run check
docker compose run --rm --no-deps frontend npm run build
```

`check`는 타입·린트·포맷·Vitest 검사다. 기존 Mock·화면 테스트와 임시 HTTP·Socket.IO 서버를 실제로 띄우는 어댑터 통신 테스트를 포함한다. 개인 `.env`나 팀원 서버·실제 계정 없이 실행한다. Socket.IO 서버 패키지는 테스트용 개발 의존성이며 프론트 실행 시 서버를 제공하지 않는다.

통신 테스트는 계약용 대역 서버와의 연결 검사다. 팀원 Spring·Node·DB·AI 통합, 실제 JWT 검증, 브라우저 CORS, 자동 E2E·모델 품질 평가를 대신하지 않는다. `build` 산출물은 임시 컨테이너 종료 시 제거된다.

## 7. 지원 범위

- F001 로그인, F002 연결·구독·재접속, F003 채팅, F004 정정, F005 문제별 결과·복수 티켓·부분 실패, F009 부서 티켓 목록·읽기 전용 상세를 Mock/live 공통 화면으로 제공한다.
- 내 문의는 재진입용 최소 목록, 관리자는 같은 티켓 화면의 전체 조회다. F006·F007 전체 완료를 뜻하지 않는다. live의 AI 동작·저장·배정·권한은 서버 구현에 달려 있다.
- F003-AC10은 retryable=true인 실패/시간 초과에 다시 분석 버튼과 `POST /api/runs/{id}/retries`를 연결한다. 본문은 requestId·expectedInquiryVersion이며 응답은 RunSummary다. 접수 중 중복 클릭 차단, 통신 오류 시 같은 키 재확인, 충돌 시 최신 조회를 적용한다. 실제 서버의 소유권·중복 방지·기존 성공 작업 보존이 필요하며 LLM 중간 체크포인트 재개는 미구현이다.
- 티켓 가져오기·처리·재이관·관련 대화·장애 그룹·통계, F008 새 문의 분리 동의, F010 기술 복구는 제공하지 않는다. 실제 Refresh 쿠키/회전·실행 중 서버 구독 회수·Node 내부 검증 API·DB 제약은 서버 담당자의 구현 및 통합 검증이 필요하다. 10초 ACK·30초 자동 재접속·3초 내부 검증 확정 기준은 추가 계약을 따른다.
- [Figma 작업본](https://www.figma.com/design/5W6UYTaYAuLnQNt2jRiqgc/Hops?node-id=6-2)을 참고했다. 홈은 확보한 이미지와 공통 스타일로 구성했고 전체 픽셀·반응형 검증은 미완료다.

개발 기준은 [docs/frontend](../docs/frontend/README.md)를 참고한다. 검사 결과·미실행 항목은 변경 PR에 기록한다.

## 확정 계약과 아직 구현·검증하지 않은 부분 (2026-10-25)

정책 원본은 [WIRE_CONTRACT 10절](../specs/contracts/WIRE_CONTRACT.md#10-인증재접속최신-대화분석-재시도-추가-프로필), 담당별 작업/미검증은 [11절](../specs/contracts/WIRE_CONTRACT.md#11-담당별-구현과-검증-상태)이다. 문서 확정만으로 런타임을 변경하지 않았다.

- 실제 인증 기준은 Access 15분·Refresh 최초 로그인부터 절대 7일·매 갱신 회전이다. 기존 실행 Mock은 Access 30분·메모리 Refresh를 사용하므로 서버 쿠키/회전/폐기 검증을 대신하지 않는다.
- `auth.invalidated`·`relay.status` 수신 처리, QUEUED/SUPERSEDED의 전체 화면 처리, 429 대기시간의 일관된 안내/버튼 제한은 추가 구현이 필요하다.
- 실제 Spring/Node/DB/AI의 토큰 회전·권한 회수·내부 인증·DDL/중복 방지·AI 실행 예산·종료 후 정정·E2E·품질/비용은 아직 검증하지 않았다. 기존 Mock/대역 서버 테스트와 구분한다.
