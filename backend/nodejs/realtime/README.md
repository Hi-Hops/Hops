# Hops 실시간 채팅 중계 서버

React와 Spring 사이의 채팅 명령과 실시간 이벤트를 중계하는 **Node.js·TypeScript·Socket.IO 서버**입니다.

React는 화면과 입력을, Node는 연결·구독·중계를, Spring은 사용자 인증·업무 권한·메시지 저장·업무 상태 확정을 담당합니다. **성공 ACK는 Spring의 저장 결과를 확인한 뒤 반환합니다.**

> 현재 Node 서버는 테스트용 Spring 역할 클라이언트와 내부 API 대역으로 검증했습니다. 실제 Spring Java 클라이언트·JWT·DB·AI를 포함한 통합은 아직 검증하지 않았습니다.

## 1. 실행 환경

| 항목        | 사용 버전·도구              |
| ----------- | --------------------------- |
| 런타임      | Node.js 24.x                |
| 언어        | TypeScript 5.9.3            |
| 실시간 통신 | Socket.IO 4.8.4             |
| 패키지 설치 | npm, package-lock.json 기준 |
| 테스트      | Node 내장 테스트 러너       |

Socket.IO 서버와 테스트 클라이언트는 기존 프론트 클라이언트와 같은 버전을 사용합니다. Java 클라이언트 버전은 Spring 담당자가 호환성을 확인한 뒤 선정해야 합니다.

## 2. 설치 및 실행

저장소 루트에서 아래 디렉터리로 이동합니다. 이후 명령은 모두 **`backend/nodejs/realtime/`에서** 실행합니다.

```sh
cd backend/nodejs/realtime
npm ci
```

### 환경변수 파일 준비

macOS/Linux:

```sh
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

기존 `.env`가 있다면 필요한 항목만 추가합니다. 아래 표를 참고해 **두 서비스 자격증명을 서로 다른 값으로 설정**합니다.

### 빌드 및 실행

```sh
npm run build
npm start
```

기본 예제 설정의 서버 주소는 `http://localhost:3001`입니다. `Ctrl+C`로 종료합니다. 소스를 수정한 뒤에는 다시 빌드합니다.

- `npm run build`: 서버와 테스트를 `dist/`에 컴파일합니다.
- `npm start`: `.env`를 읽고 `dist/src/main.js`를 실행합니다. 배포 환경변수를 모두 제공하면 `.env` 없이 실행할 수 있습니다.
- `GET /healthz`: `{ "status": "UP" }`을 반환합니다. 프로세스 생존 확인용이며 Spring·DB의 준비 상태를 뜻하지 않습니다.

Node 프로세스는 Spring 없이도 시작할 수 있지만, 사용자 연결 인증과 실제 채팅에는 아래의 Spring 연동이 필요합니다.

## 3. Docker 실행

Docker Engine과 Docker Compose 2.20 이상이 필요합니다. Docker Desktop을 사용하는 경우 먼저 앱을 실행하고 `docker info`가 정상 응답하는지 확인합니다. Docker 방식은 호스트에 Node.js나 npm 패키지를 설치하지 않아도 됩니다.

### 환경변수 준비

위 설치 안내의 `.env` 복사 단계만 수행하고 두 서비스 자격증명을 설정합니다. Spring을 호스트 PC에서 실행한다면 `.env`의 주소를 다음과 같이 바꿉니다.

```dotenv
SPRING_BASE_URL=http://host.docker.internal:8080
```

컨테이너의 `localhost`는 컨테이너 자신입니다. `host.docker.internal`은 호스트 PC의 Spring에 접근하기 위한 주소이며, Compose에 Linux용 host-gateway 매핑도 포함했습니다. Spring이 외부에서 접근 가능한 주소에 바인딩되어 있는지 확인합니다.

- Spring이 다른 PC에 있으면 해당 PC의 접근 가능한 IP/도메인을 사용합니다.
- Spring도 컨테이너라면 공유 Docker 네트워크와 서비스 이름을 별도로 구성해야 합니다. 현재 Compose는 realtime 서버만 실행합니다.
- `ALLOWED_ORIGINS`는 Docker 서비스 이름이 아닌 브라우저의 실제 Origin입니다. `localhost`와 `127.0.0.1`을 구분합니다.
- 같은 `.env`로 직접 실행 방식으로 돌아갈 때는 Spring 주소도 직접 실행 환경에 맞춰 변경합니다.

### 빌드·시작·종료

아래 명령은 모두 `backend/nodejs/realtime/`에서 실행합니다.

```sh
docker compose up --build -d --wait
docker compose ps
docker compose logs -f realtime
```

기본 접속 주소는 `http://localhost:3001`이며 포트는 호스트의 `127.0.0.1`에만 공개합니다. `.env`의 `PORT`를 변경하면 호스트와 컨테이너 포트가 함께 바뀝니다. 로그 보기 종료는 `Ctrl+C`이며 서버 종료는 다음 명령입니다.

```sh
docker compose down
```

소스를 바꿨다면 `docker compose up --build -d --wait`로 다시 빌드합니다. `.env`만 바꿨다면 다음 명령으로 재생성합니다.

```sh
docker compose up -d --force-recreate --wait
```

`http://localhost:3001/healthz`의 응답과 `docker compose ps`의 healthy 상태는 Node 프로세스 생존을 뜻합니다. Spring 연결·JWT·DB·AI 통합 성공은 별도로 확인해야 합니다.

### 이미지 구성

[Dockerfile](Dockerfile)은 빌드·운영 의존성 설치·실행 단계를 분리합니다. 기존 프론트와 같은 `node:24.22.0-bookworm-slim` 이미지를 사용합니다.

| 단계                      | 내용                                                        |
| ------------------------- | ----------------------------------------------------------- |
| `build`                   | 개발 의존성을 포함해 설치하고 TypeScript 서버·테스트 컴파일 |
| `production-dependencies` | `npm ci --omit=dev`로 운영 의존성만 설치                    |
| `runtime`                 | 운영 의존성·컴파일된 서버만 복사, `node` 사용자로 실행      |

실행 이미지에는 테스트·TypeScript 컴파일러·Prettier·개인 `.env`를 넣지 않습니다. [.dockerignore](.dockerignore)는 로컬 의존성·빌드 결과·환경변수 파일을 빌드 컨텍스트에서 제외합니다. 서비스 자격증명은 [Compose](compose.yaml)가 실행 시 환경변수로 전달하며 빌드 인자로 사용하지 않습니다.

### 컨테이너에서 자동 테스트

개발 의존성이 있는 `build` 단계를 별도 이미지로 빌드하면 실제 Spring이나 `.env` 없이 자동 테스트를 실행할 수 있습니다.

```sh
docker build --target build -t hops-realtime-test .
docker run --rm --init hops-realtime-test npm test
```

기본 runtime 이미지 빌드는 TypeScript 컴파일을 수행하며 자동 테스트 실행은 위 명령으로 구분합니다. Docker 빌드·기동 검증과 실제 서비스 통합의 검증 범위는 아래 자동 검사 항목을 참고합니다.

## 4. 환경변수

설정 파일은 [.env.example](.env.example)을 참고합니다.

| 변수                   | 필수   | 설명                                                                 |
| ---------------------- | ------ | -------------------------------------------------------------------- |
| `PORT`                 | 예     | Node 서버 포트. 예제는 `3001`                                        |
| `SPRING_BASE_URL`      | 예     | 내부 검증 API를 제공하는 Spring 주소. 예제는 `http://localhost:8080` |
| `ALLOWED_ORIGINS`      | 예     | 브라우저 Origin 허용 목록. 여러 개는 쉼표로 구분                     |
| `SOCKET_PATH`          | 아니요 | Socket.IO 접속 path. 기본 `/socket.io`                               |
| `NODE_SERVICE_TOKEN`   | 예     | Node → Spring 내부 HTTP 요청의 Bearer 자격증명                       |
| `SPRING_SERVICE_TOKEN` | 예     | Spring → Node 서비스 연결의 자격증명                                 |

서비스 자격증명은 `.env` 또는 배포 환경에서 관리하며 프론트에 전달하지 않습니다. 필수 설정이 없거나 두 자격증명이 같으면 시작을 거부합니다.

`ALLOWED_ORIGINS`에는 브라우저가 실제로 사용하는 Origin을 설정합니다. `http://localhost:5173`과 `http://127.0.0.1:5173`은 서로 다른 Origin입니다. 프론트 설정은 [프론트 실행·서버 연동 안내](../../../frontend/README.md)를 참고합니다.

## 5. 디렉터리 구성

```text
realtime/
├── src/
│   ├── main.ts                     # 설정 로드·서버 시작·정상 종료
│   ├── server.ts                   # HTTP·Socket.IO 생성과 모듈 연결
│   ├── config/
│   │   └── env.ts                  # 환경변수 읽기·검증
│   ├── socket/
│   │   ├── auth.ts                 # 연결 인증·만료·인증 회수
│   │   ├── subscription.ts         # 구독 권한 확인·room 참여·해제
│   │   ├── commands.ts             # 명령 등록·전송 순서·Spring 저장 ACK 중계
│   │   ├── relay.ts                # Spring 이벤트 전달·중계 상태 알림
│   │   └── types.ts                # Socket 타입·공유 연결 상태·모듈 의존성
│   ├── clients/
│   │   └── spring.client.ts        # Spring 내부 검증 HTTP API 호출
│   └── contracts/
│       ├── types.ts                # 명령·ACK·이벤트·Spring 연동 타입
│       └── validation.ts           # 외부 입력·ACK·이벤트 런타임 검증
├── test/
│   ├── main.test.ts                # 프로세스 실행·종료 검증
│   └── server.test.ts              # Socket.IO·HTTP 통신 검증
├── Dockerfile
├── compose.yaml
├── .dockerignore
├── .env.example
├── package.json
└── tsconfig.json
```

[TypeScript 설정](tsconfig.json)은 `strict`와 `noUncheckedIndexedAccess`를 적용합니다. 외부 입력과 Spring 응답은 `unknown`으로 받고, [런타임 검증](src/contracts/validation.ts)을 거쳐 사용합니다. [서버 타입](src/contracts/types.ts)은 이 서비스의 구현용이며 공통 계약 원본은 [데이터 타입 문서](../../../specs/contracts/DATA_TYPES.md)입니다.

[server.ts](src/server.ts)는 서버마다 Spring 연결과 연결 세대(`epoch`)를 하나만 생성하고 각 처리자에 같은 참조를 전달합니다. 연결 세대는 Spring 재접속 전에 시작한 비동기 검증이 복구 후의 새 연결에 적용되지 않도록 구분하는 값입니다. 모듈별로 별도 연결을 만들거나 전역 연결 상태를 사용하지 않습니다.

[인증](src/socket/auth.ts)은 연결·만료·회수를, [구독](src/socket/subscription.ts)은 현재 권한 확인과 room 변경을 담당합니다. [명령 처리](src/socket/commands.ts)는 연결별 미완료 요청 제한과 구독 처리 순서를 유지하며, [이벤트 중계](src/socket/relay.ts)는 같은 Spring 연결 상태를 기준으로 이벤트를 전달합니다. [Spring HTTP 클라이언트](src/clients/spring.client.ts)는 Socket 상태를 직접 관리하지 않습니다.

## 6. React 연결 및 이벤트

### 연결·구독

React는 기본 namespace `/`에 `auth: { token: accessToken }`으로 연결합니다. 접속 path는 `/socket.io`이며 polling과 WebSocket을 지원합니다. namespace·path·room은 서로 다른 개념입니다.

Node는 Spring에 사용자 인증과 현재 접근 권한을 확인한 뒤 구독을 허용합니다. 클라이언트가 임의 room 이름이나 userId를 제출해 권한을 넓힐 수 없습니다.

| 명령                   | 역할                                                             |
| ---------------------- | ---------------------------------------------------------------- |
| `subscription.request` | `INQUIRY`·`DEPARTMENT_TICKETS`·`TICKET` 대상의 권한 확인 후 구독 |
| `subscription.release` | 대상 구독 해제. 반복 해제도 성공                                 |

이벤트 전달 때도 현재 인증·대상 권한을 확인합니다. 구독 해제·인증 회수·연결 종료 후 뒤늦게 완료된 검증으로 이전 접근이 복원되지 않도록 처리합니다.

### 채팅 명령·ACK

| React → Node → Spring | 역할                         |
| --------------------- | ---------------------------- |
| `inquiry.create`      | 문의와 첫 메시지 생성 요청   |
| `message.send`        | 추가 메시지 저장 요청        |
| `message.revise`      | 기존 사용자 메시지 정정 요청 |

Node는 requestId·문의 버전·원문을 보존하여 명령을 전달합니다. Spring이 반환한 CommandAck를 검증한 뒤 **ACK 콜백의 첫 번째 인자 하나로** 응답합니다. 별도 ACK 이벤트나 `(null, result)` 형식을 사용하지 않습니다.

- 내부 HTTP 검증 제한: 요청당 최대 **3초**.
- 저장 ACK 대기: 명령 수신부터 최대 **10초**.
- ACK 유실·잘못된 응답·중계 단절: `SERVICE_UNAVAILABLE`로 저장 상태 확인을 안내합니다. 저장 실패나 작업 취소로 확정하지 않습니다.
- 자동 저장 재전송은 하지 않습니다. 동일 요청의 멱등 결과 보관·버전 검사·DB 중복 방지는 Spring에서 구현해야 합니다.

입력·ACK의 상세 필드와 복구 정책은 [데이터 타입](../../../specs/contracts/DATA_TYPES.md)과 [전송 계약](../../../specs/contracts/WIRE_CONTRACT.md)을 따릅니다.

### Spring → React 이벤트

| 이벤트           | 의미                            |
| ---------------- | ------------------------------- |
| `agent.progress` | 공개 가능한 분석 진행 상태      |
| `answer.delta`   | 임시 답변 조각                  |
| `message.stored` | 메시지 저장 확인                |
| `state.changed`  | 문의·티켓 등의 상태 재조회 신호 |

Spring 서비스 연결만 업무 이벤트를 발행할 수 있습니다. Node는 허용된 구독자에게 전달하며, 진행·답변 조각으로 저장 성공이나 해결을 확정하지 않습니다. eventId 중복 제거·답변 조각 순서·활성 실행 검사는 프론트와 Spring의 담당 범위를 따릅니다.

### 입력·전송 제한

| 항목               | 제한                                                            |
| ------------------ | --------------------------------------------------------------- |
| 사용자 메시지      | 1~4,000 Unicode 코드 포인트. 공백 전용 입력·단독 surrogate 거부 |
| 명령 데이터        | UUID·정수·필수 필드 검사, 추가 필드 거부                        |
| 연결별 미완료 명령 | 최대 32개                                                       |
| Socket.IO payload  | 최대 64 KiB                                                     |

미완료 명령 수와 payload 크기는 현재 구현의 자원 보호값이며 운영 부하 검증 대상입니다.

## 7. Spring 연결 순서

**이 절의 `/spring` namespace·서비스 handshake·내부 명령 외피·인증 회수 통지 형식은 이번 서버의 연결 구현안입니다.** 팀 공통 계약 승인이나 Java 처리자 구현 완료를 뜻하지 않습니다. Node는 단일 인스턴스·단일 Spring 연결을 대상으로 하며 두 번째 Spring 연결은 거부합니다.

### 1단계: 내부 검증 API 구현

두 API는 `POST`이며 헤더는 `Authorization: Bearer <NODE_SERVICE_TOKEN>`입니다. 사용자 JWT는 헤더가 아닌 JSON의 `accessToken`으로 전달됩니다.

| 경로                                        | 요청 본문               | 성공 응답                        |
| ------------------------------------------- | ----------------------- | -------------------------------- |
| `/internal/realtime/authenticate`           | `{accessToken}`         | `{user: CurrentUser, expiresAt}` |
| `/internal/realtime/authorize-subscription` | `{accessToken, target}` | `{target}`                       |

- `CurrentUser`의 id·employeeNo·name·department·roles를 모두 반환합니다.
- `expiresAt`은 UTC Z 시각으로 반환합니다.
- 오류는 ApiErrorBody이며 인증 실패·권한 거부·서비스 장애를 구분합니다.
- Spring이 사용자 JWT와 소유자·현재 처리 부서·업무 관리자 권한을 검사합니다.

정확한 입출력과 시간 제한은 [전송 계약](../../../specs/contracts/WIRE_CONTRACT.md)의 Node → Spring 내부 검증 항목을 참고합니다.

### 2단계: Java Socket.IO 클라이언트 연결

| 설정      | 값                                             |
| --------- | ---------------------------------------------- |
| 서버 주소 | Node 서버 주소                                 |
| namespace | `/spring`                                      |
| path      | Node의 `SOCKET_PATH`와 같은 값                 |
| 연결 인증 | `auth: { serviceToken: SPRING_SERVICE_TOKEN }` |

일반 WebSocket/STOMP 클라이언트 대신 Socket.IO 호환 Java 클라이언트를 사용해야 합니다. Spring의 JWT 책임과 패키지 구성은 [Spring 백엔드 안내](../../spring/hops/README.md)를 참고합니다.

### 3단계: 명령 처리·저장 결과 반환

Spring은 `inquiry.create`, `message.send`, `message.revise`를 다음 내부 외피로 받습니다.

```ts
{
  (accessToken, command);
}
```

`command`는 React의 외부 명령 그대로입니다. Spring에서 JWT·현재 권한·문의 버전·멱등성을 검사하고, 업무 저장 결과에 맞춰 StoredAck 또는 ErrorAck 하나를 반환합니다. 접수만으로 `ok=true`를 반환하지 않습니다.

진행·저장 이벤트는 [공통 데이터 타입](../../../specs/contracts/DATA_TYPES.md)의 Event 객체 그대로 발행합니다. 임의 room 이름이나 수신 사용자 목록을 이벤트에 추가하지 않습니다.

### 4단계: 인증 회수 통지

Spring 서비스 연결에서 `auth.invalidated`를 다음 형식으로 발행합니다.

```ts
{
  (userId, code);
}
```

code는 `ACCESS_TOKEN_EXPIRED`, `AUTH_CONTEXT_CHANGED`, `REFRESH_TOKEN_REVOKED` 중 하나입니다. 현재 구현은 해당 사용자의 모든 연결을 회수합니다.

**현재 로그인 세션만 로그아웃하는 통지의 식별·수신 범위는 별도 계약이 필요합니다.** 사용자 전체 회수로 이를 대체하지 않습니다. 폐기된 세션은 다음 명령·이벤트의 내부 재검증으로 차단하지만, 유휴 연결의 세션별 즉시 종료는 아직 통합하지 않았습니다.

## 8. 인증 만료 및 연결 복구

| 상황                | Node 처리                                                     | 클라이언트 복구                                 |
| ------------------- | ------------------------------------------------------------- | ----------------------------------------------- |
| 연결 인증 실패      | `connect_error.data`에 ApiErrorBody 전달                      | 오류 코드에 따라 인증 갱신·재로그인·연결 재시도 |
| 토큰 만료·인증 회수 | 접근·구독을 먼저 회수한 뒤 `auth.invalidated` 전달, 연결 종료 | 새 인증으로 연결                                |
| Spring 연결 단절    | 기존 구독 제거, `relay.status=UNAVAILABLE` 전달               | 입력·저장 결과 보존, 전송 보류                  |
| Spring 연결 복구    | `relay.status=AVAILABLE` 전달                                 | 재인증·재구독·REST 재조회                       |

Node는 이전 room이나 누락 이벤트를 자동 복원하지 않습니다. 재연결만으로 저장 상태가 복구됐다고 판단하지 않습니다. 프론트의 제어 이벤트 수신·화면 처리는 별도 구현 대상이며, 요구 동작은 [실시간 연결 명세](../../../specs/F002-realtime/spec.md)를 참고합니다.

## 9. 검증 명령 및 범위

```sh
npm run typecheck
npm test
npm run format:check
```

`npm test`는 TypeScript 빌드 후 컴파일된 테스트를 실행합니다. 테스트용 Spring 역할 클라이언트와 HTTP API 대역을 사용하며, 개인 `.env`·실제 JWT·업무 DB 없이 실행합니다.

| 구분      | 검증 대상                                                    |
| --------- | ------------------------------------------------------------ |
| 실행      | 프로세스 기동·healthz·정상 종료·잘못된 자격증명 설정         |
| 연결·권한 | polling/WebSocket·인증 거부·타인 문의 구독·서비스 위장 차단  |
| 명령·ACK  | 입력 경계·원문/버전 보존·추가 메시지/정정·ACK 유실/형식 오류 |
| 회수·복구 | 토큰 만료·권한 회수·구독 경합·중계 단절/복구                 |
| 내부 API  | 서비스 Bearer 분리·오류 응답·3초 제한                        |

### 확인한 결과 (2026-10-26)

Docker Engine 29.8.0과 `node:24.22.0-bookworm-slim` 이미지를 사용해 다음 항목을 확인했습니다.

- 타입 검사와 Prettier 검사 통과.
- `build` 대상 이미지 및 Compose의 runtime 이미지 빌드 성공.
- 테스트 컨테이너에서 빌드 후 자동 테스트 19개 통과. 테스트는 실제 Spring·DB 없이 수행했습니다.
- 실행 중인 Compose 컨테이너의 healthy 상태와 `/healthz`의 `{ "status": "UP" }` 응답 확인.
- runtime 컨테이너의 `node` 사용자(uid 1000) 실행 확인.
- runtime 이미지에서 개인 `.env`·테스트 코드·TypeScript 컴파일러·Prettier가 제외된 것 확인.

실제 Spring Java 클라이언트·JWT 발급/폐기·DB 저장/24시간 멱등성·Python/LLM·브라우저 전체 E2E·다중 인스턴스·운영 부하·TLS와 자격증명 회전은 아직 검증하지 않았습니다. 테스트용 중계 검증과 구분합니다.

## 10. 참고 문서

| 문서                                                       | 확인할 내용                                       |
| ---------------------------------------------------------- | ------------------------------------------------- |
| [PRD](../../../docs/PRD.md)                                | 채팅 흐름·서버 책임·MVP 범위: 4.3·4.4·5.1·8.2     |
| [API 설계](../../../docs/API.md)                           | Socket.IO·인증: 2.4·2.5                           |
| [데이터 설계](../../../docs/DATA.md)                       | 업무 저장·멱등성·복구 책임: 5절                   |
| [공통 데이터 타입](../../../specs/contracts/DATA_TYPES.md) | 명령·ACK·이벤트·오류의 필수 필드와 값             |
| [전송 계약](../../../specs/contracts/WIRE_CONTRACT.md)     | live 연결·내부 검증·시간 제한·담당별 작업: 9~11절 |
| [실시간 연결 명세](../../../specs/F002-realtime/spec.md)   | 구독·권한 회수·재접속 인수 조건                   |
| [프론트 실행·연동 안내](../../../frontend/README.md)       | live 주소·path·Origin 설정과 화면 복구            |
| [Spring 백엔드 안내](../../spring/hops/README.md)          | Spring 인증 책임과 실행 기반                      |

Docker 구성은 [다단계 빌드 안내](https://docs.docker.com/build/building/multi-stage/)와 [Docker Desktop 네트워크 안내](https://docs.docker.com/desktop/features/networking/)를 참고했습니다.
