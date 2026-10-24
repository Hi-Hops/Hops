# Hops 프론트엔드 실행

공통 계약·Mock 데이터로 로그인·채팅·메시지 정정·결과 카드·부서 티켓 조회를 확인하는 앱이다. Docker로 실행하면 PC에 Node·npm·Python을 따로 설치하지 않아도 된다. `frontend/`와 상위 `specs/contracts/`, `specs/mocks/`가 함께 있어야 한다.

## 1. 준비

Docker Engine과 Docker Compose **2.20 이상**이 필요하다. Windows/macOS에서는 Docker Desktop을 실행한다. Windows는 Linux 컨테이너 모드를 사용한다. 최초 실행 시 Node 이미지와 npm 패키지 다운로드를 위한 네트워크가 필요하다.

이 문서의 명령은 모두 **`frontend/`에서** 실행한다.

## 2. .env 작성

`.env.example`을 **같은 폴더의 `.env`**로 복사한다.

PowerShell:

```powershell
Copy-Item .env.example .env
```

macOS/Linux:

```sh
cp .env.example .env
```

생성한 `.env`를 편집해 아래 다섯 항목을 직접 채운다. 예제 파일은 로그인 값을 비워 두며 기본 계정은 제공하지 않는다.

- `VITE_MOCK_REQUESTER_ID`: 문의자로 로그인할 아이디.
- `VITE_MOCK_NETWORK_ID`: 네트워크 부서 담당자로 로그인할 아이디.
- `VITE_MOCK_BACKEND_ID`: backend 부서 담당자로 로그인할 아이디.
- `VITE_MOCK_ADMIN_ID`: 업무 관리자로 로그인할 아이디.
- `VITE_MOCK_PASSWORD`: 위 네 계정에 공통으로 적용할 가상 비밀번호.

네 아이디는 서로 다르게 입력한다. 다섯 항목 중 하나라도 비어 있거나 아이디가 중복되면 로그인 시 설정 오류를 안내한다. 화면에는 `.env`에 입력한 아이디와 비밀번호를 사용한다. 입력한 값은 문서나 화면의 계정 안내에 표시하지 않는다.

`FRONTEND_PORT`는 접속 포트이며 기본값은 **5173**이다. 다른 서버가 사용 중이면 이 값만 비어 있는 포트 번호로 바꾼다.

**현재 인증은 브라우저 안의 Mock이다. `VITE_` 환경 값은 브라우저 코드에 전달되므로 실제 계정·실제 비밀번호·API 키를 입력하지 않는다.** `.env`는 Git에서 제외하며 Docker 이미지에도 복사하지 않는다. Docker Compose가 실행 시 필요한 항목만 컨테이너 환경으로 전달한다.

## 3. Docker 실행·종료

```sh
docker compose up --build -d --wait
```

기본 접속 주소는 **http://127.0.0.1:5173/** 이다. `.env`에서 포트를 바꿨다면 해당 포트로 접속한다. 포트는 로컬 PC에서만 접근하도록 연결했다.

```sh
docker compose ps
docker compose logs -f frontend
docker compose down
```

`ps`로 정상 상태를 확인하고 `logs`로 실행 로그를 본다. 로그 확인은 `Ctrl+C`, 서버 종료는 `down`을 사용한다.

`.env` 값을 수정한 뒤에는 컨테이너를 다시 생성한다.

```sh
docker compose up -d --force-recreate --wait
```

소스를 변경했다면 `docker compose up --build -d --wait`로 이미지를 다시 만든다. 이 이미지는 로컬 Mock 개발 서버용이며 실제 백엔드·채팅 서버·DB·AI 연결은 포함하지 않는다.

## 4. 화면 확인 순서

1. `.env`에서 정한 문의자 아이디와 공통 비밀번호로 로그인한다.
2. 최근 문의에서 초기 복합 문의의 복수 티켓·부분 실패·추가 질문 상태를 확인한다.
3. 새 문의에 `VPN 연결이 안 돼요`를 보내고 추가 질문에 `809 오류예요`라고 답해 티켓 생성을 확인한다.
4. 사용자 메시지를 정정하고 정정됨·이전 원문 이력·기존 티켓 유지를 확인한다.
5. **같은 탭에서 새로고침하지 않고** 로그아웃 후 네트워크 담당자 아이디로 로그인해 생성된 티켓을 조회한다.
6. 티켓 필터와 상세 모달을 확인한다. `Esc` 또는 닫기 버튼으로 닫아도 조회 조건은 유지된다.
7. backend 담당자는 자기 부서 티켓만, 업무 관리자는 전체 티켓을 조회하는지 확인한다.

새 문의에 `권한`을 포함하면 복합 문제·부분 실패, `날씨`는 지원 범위 밖, `시간 초과`·`모델 오류`는 실행 오류, `CS 검토`는 CS 배정 시나리오다. 실제 AI 분석이 아닌 고정 Mock 흐름이다.

업무 데이터는 같은 탭에서 계정을 바꿔도 유지되지만 **새로고침하면 로그인과 데이터가 초기화**된다. 다른 탭·PC와 데이터가 공유되지 않는다.

## 5. 테스트·빌드

```sh
docker compose run --rm --no-deps frontend npm run check
docker compose run --rm --no-deps frontend npm run build
```

`check`는 타입·린트·포맷·Vitest 검사다. 자동 테스트는 개인 `.env`와 무관한 임시 로그인 값을 생성한다. 개인 로그인 설정이 없어도 자동 테스트는 실행할 수 있다. 실제 서버 통합·자동 브라우저 E2E·Figma 이미지 비교 검사를 대신하지 않는다. `build`의 결과물은 임시 컨테이너 안에 생성되며 명령 종료 시 제거된다.

Node.js **24.22.0**을 PC에 설치한 경우 Docker 대신 `.env` 작성 후 아래처럼 직접 실행할 수도 있다.

```sh
npm ci
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

직접 실행 시 `.env` 수정 후 개발 서버를 재시작한다. `FRONTEND_PORT`는 Compose용이므로 직접 실행의 포트는 `--port`로 지정한다.

## 6. 서버 담당자 참고

- [공통 계약](../specs/contracts/README.md): 데이터 구조와 필드·타입 문서.
- [요청·응답 연결표](../specs/contracts/WIRE_CONTRACT.md#8-작업별-데이터-타입-연결): REST·Socket 입출력.
- [전송 계약](../specs/contracts/WIRE_CONTRACT.md): ACK·요청 ID·버전·오류.
- [공용 Mock](../specs/mocks/README.md): 데이터·시나리오·역할 매핑.

현재 HTTP 어댑터와 소켓은 메모리 Mock이다. Network 탭에 실제 `/api` 요청이나 `/socket.io` 연결이 없어도 정상이며, 서버 주소 환경 변수를 추가하는 것만으로 실제 서버에 연결되지 않는다. 실제 연동은 공통 계약 확정 후 별도 작업이다.

문제가 생기면 Docker 실행 여부, `.env` 다섯 항목의 누락·중복, 포트 충돌을 확인한다. 개발 기준은 [프론트 개발 안내](../docs/frontend/README.md)를 참고한다.

## 7. 현재 지원 범위와 제한

- 로그인(F001), 실시간 연결의 Mock(F002), 채팅(F003), 메시지 정정(F004), 문제별 결과·복수 티켓·부분 실패(F005), 부서 티켓 목록·읽기 전용 상세(F009)를 구현했다.
- 내 문의는 채팅 재진입용 최소 목록이며, 관리자 화면은 같은 티켓 화면을 사용하는 전체 조회다. F006·F007 전체 기능 완료를 뜻하지 않는다.
- 티켓 가져오기·처리 완료·재이관, 관련 대화, 장애 그룹·통계, 새 문의 분리 동의(F008), 기술 복구(F010)는 제공하지 않는다. 원본 스펙의 전체 MVP 완료와 구분한다.
- 실제 REST·Socket.IO·DB·AI는 연결하지 않았다. 부서·티켓 조회는 변경 알림·재접속 및 주기 재조회로 갱신하는 메모리 Mock이다. 실제 서버 연결 전 인증·권한·상태·버전 정책은 공통 계약의 미정 질문을 합의해야 한다.
- [Figma 작업본](https://www.figma.com/design/5W6UYTaYAuLnQNt2jRiqgc/Hops?node-id=6-2)의 로그인·채팅·정정·티켓 목록/상세를 참고했다. 홈은 상세 디자인 정보 조회 제한으로 확보한 이미지와 공통 스타일을 기준으로 구현했다. 모든 화면의 픽셀 일치·반응형 전체 검증은 완료하지 않았다.

검사별 결과·실행 환경·화면 증거는 각 변경의 PR에 기록한다. 이 README에는 오래된 테스트 개수나 특정 작업의 완료 보고를 누적하지 않는다.
