# Hops 프론트엔드 실행·서버 연동

로그인·문의 채팅·메시지 정정·결과 카드·부서 티켓 조회를 제공한다. 같은 화면을 `mock`(서버 없이 가상 데이터) 또는 `live`(Spring REST + Node Socket.IO)로 실행한다. 서버는 아래 공통 계약을 구현해야 한다.

## 1. 실행 환경

Docker Engine과 Docker Compose **2.20 이상**을 준비한다. Windows/macOS는 Docker Desktop을 실행하고 Windows는 Linux 컨테이너 모드를 사용한다. 최초 실행은 이미지·npm 패키지를 내려받을 네트워크가 필요하다. 저장소의 `frontend/`와 `specs/`가 함께 있어야 한다.

아래 명령은 모두 **`frontend/`에서** 실행한다. `.env.example`을 같은 폴더의 `.env`로 복사한다.

```powershell
Copy-Item .env.example .env
```

macOS/Linux는 `cp .env.example .env`를 사용한다. 기존 `.env`가 있으면 덮어쓰지 말고 필요한 항목만 추가한다. `.env`는 Git·Docker 이미지에서 제외된다.

## 2. 실행 모드와 .env

### 서버 없이 Mock으로 실행

`VITE_DATA_MODE=mock`으로 설정한다. 생략해도 기본은 `mock`이다. 아래 다섯 항목은 직접 정한 가상 값으로 채운다. 로그인 가능한 값이나 기본 계정은 문서에 제공하지 않는다.

- `VITE_MOCK_REQUESTER_ID`: 문의자 아이디.
- `VITE_MOCK_NETWORK_ID`: 네트워크 부서 담당자 아이디.
- `VITE_MOCK_BACKEND_ID`: backend 부서 담당자 아이디.
- `VITE_MOCK_ADMIN_ID`: 업무 관리자 아이디.
- `VITE_MOCK_PASSWORD`: 네 역할의 공통 가상 비밀번호.

네 아이디는 서로 달라야 한다. 누락·중복 설정이면 로그인 시 오류를 안내한다. **`VITE_` 값은 브라우저에 전달되므로 실제 계정·비밀번호·API 키를 넣지 않는다.** Mock 모드에서는 서버 주소가 있어도 서버에 요청하지 않는다.

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
- 토큰은 메모리에 보관하고 REST의 `Authorization: Bearer <accessToken>`, Socket.IO의 `auth.token`으로 전달한다. 쿠키 인증·자동 refresh는 구현하지 않았다. 만료 시 재로그인하며 같은 사용자 입력은 기존 규칙대로 보존한다. 새로고침하면 로그인은 초기화되지만 live 저장 데이터는 서버가 관리한다.

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

## 4. 백엔드·채팅 서버 담당자 구현 기준

요청 URL·메서드·성공 코드·쿼리는 [REST 목록](../specs/contracts/WIRE_CONTRACT.md#2-rest와-페이지), 소켓 이벤트별 입출력은 [작업별 연결표](../specs/contracts/WIRE_CONTRACT.md#8-작업별-데이터-타입-연결)를 따른다. 접속·CORS·연결 오류의 실제 조건은 [연동 프로필](../specs/contracts/WIRE_CONTRACT.md#9-프론트-live-연동-프로필)에 있다.

- **Spring:** 로그인·현재 사용자·로그아웃, 문의 목록·상세·메시지·실행 조회, 부서 티켓 목록·상세를 구현한다. 정확한 URL과 JSON 타입은 위 목록을 따른다. 프론트는 별도 `data` wrapper 없이 응답 객체 자체를 읽고 로그아웃은 204 본문 없음으로 처리한다.
- **Node Socket.IO:** 연결의 `auth.token`으로 Spring에 인증을 확인한다. `subscription.request` 대상 접근 권한도 Spring에 확인한 뒤 룸에 참여시키고 `SubscriptionAck`를 반환한다. 프론트는 임의 룸 이름이나 권한 판단용 userId를 보내지 않는다.
- 문의 생성 `inquiry.create`, 전송 `message.send`, 정정 `message.revise`를 Spring에 전달하고 **Spring 저장 결과를 받은 뒤** `CommandAck`를 응답한다. 접속이나 룸 참여만으로 메시지 저장 성공 ACK를 보내지 않는다.
- Node는 `agent.progress`, `answer.delta`, `message.stored`, `state.changed`를 허용된 연결에 중계한다. ACK는 별도 이벤트가 아닌 Socket.IO 명령 콜백이다. 토큰은 이벤트 payload·URL·로그에 넣지 않는다.
- **CORS:** Spring은 브라우저 origin(예: `http://127.0.0.1:5173`)에 GET/POST/OPTIONS와 `Authorization`·`Content-Type`을 허용한다. Node도 같은 origin의 polling·WebSocket 연결을 허용하고 Spring 연결을 준비한다. `localhost`와 `127.0.0.1`은 다른 origin이다. HTTPS 화면에서는 서버도 HTTPS/WSS를 제공한다.
- 필드·타입·nullable·상태·권한은 [DATA_TYPES](../specs/contracts/DATA_TYPES.md), ACK·멱등성·복구는 [WIRE_CONTRACT](../specs/contracts/WIRE_CONTRACT.md), 예시 JSON은 [공용 Mock](../specs/mocks/README.md)을 따른다. Spring↔Node 내부 검증·중계 API는 두 서버 담당자가 합의하며 프론트용 URL과 혼용하지 않는다. 미정 운영 정책의 팀 승인을 대신하지 않는다.

채팅 서버가 연결되지 않아도 로그인·REST 티켓 조회는 사용할 수 있다. 채팅은 연결 복구 후 전송한다. 재연결 때 현재 토큰으로 인증·구독을 다시 수행하고 저장 데이터는 REST로 재조회한다. ACK가 10초 안에 오지 않거나 연결이 끊기면 결과 미확인으로 표시하고 같은 요청 ID로 다시 확인할 수 있다. 저장 명령은 자동 반복하지 않는다.

## 5. 연동 확인 순서

1. Spring·Node를 실행하고 live 설정으로 프론트를 재시작한다.
2. 로그인 후 Network에서 `/api/auth/login` → Bearer `/api/auth/me`와 Socket.IO 연결을 확인한다. 토큰·비밀번호가 담긴 캡처는 공유하지 않는다.
3. 새 문의를 전송해 `inquiry.create` ACK의 문의 ID로 화면이 이동하는지 확인한다. 문의 REST 조회·구독·진행/답변·저장 메시지가 이어지는지 본다.
4. 추가 메시지·정정을 보내 저장 ACK, 버전 충돌, 이력·결과 재조회를 확인한다. 연결 단절 시 원문을 보존하고 재연결 후 최신 상태로 복구하는지 본다.
5. 부서 담당자로 로그인해 허용된 티켓 목록·필터·페이지·읽기 전용 상세를 확인한다. 다른 부서 상세는 서버가 거부해야 한다. `state.changed` 후 갱신도 확인한다.

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
- 티켓 가져오기·처리·재이관·관련 대화·장애 그룹·통계, F008 새 문의 분리 동의, F010 기술 복구는 제공하지 않는다. 자동 토큰 갱신과 실행 중 부서 변경에 따른 서버 구독 회수 정책도 별도 합의가 필요하다.
- [Figma 작업본](https://www.figma.com/design/5W6UYTaYAuLnQNt2jRiqgc/Hops?node-id=6-2)을 참고했다. 홈은 확보한 이미지와 공통 스타일로 구성했고 전체 픽셀·반응형 검증은 미완료다.

개발 기준은 [docs/frontend](../docs/frontend/README.md)를 참고한다. 검사 결과·미실행 항목은 변경 PR에 기록한다.
