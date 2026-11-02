# Docker Compose 로컬 개발 환경

저장소 루트에서 Frontend·Spring·AI·PostgreSQL을 함께 실행한다. Docker Engine과 Compose 2.20 이상을 사용하며, Windows/macOS는 Docker Desktop의 Linux 컨테이너를 실행한다.

## 처음 실행

아래 명령은 저장소 루트에서 실행한다.

```powershell
Copy-Item .env.example .env
```

macOS/Linux는 `cp .env.example .env`를 사용한다. 기존 `.env`는 유지한다. `POSTGRES_PASSWORD`와 ASCII 32자 이상의 `HOPS_JWT_SECRET`을 채운다. 루트 `.env`만 사용하며 각 서비스의 개인 `.env`나 DB 설정은 이미지에 포함하지 않는다.

```sh
docker compose up --build -d
docker compose ps
```

기본 주소는 프론트 `http://localhost:5173`, Spring Swagger `http://localhost:8080/swagger-ui/index.html`, AI `http://localhost:8000/healthz`다. DB는 `localhost:5432`, 사용자·DB 이름은 `hops`다. 모든 포트는 로컬 PC에만 공개한다.

포트가 겹치면 루트 `.env`의 `FRONTEND_PORT`, `BACKEND_PORT`, `AI_PORT`, `POSTGRES_PORT`를 바꾸고 `docker compose up -d`를 실행한다. 프론트 API 주소와 Spring CORS도 해당 포트에 맞춰 설정된다.

PostgreSQL 18의 `hops` DB에 Spring Flyway가 테이블과 개발용 가상 데이터를 적용한다. 테스트 DB가 필요하면 [Spring 안내](../backend/spring/hops/README.md#처음-설치-mac--windows)에 따라 별도로 준비한다.

루트 Compose는 현재 develop 프론트를 `live` 모드로 실행해 Spring의 기존 `/api`에 연결한다. 신규 `/api/v2` 전환과 해당 업무 API 구현은 별도 작업이다. AI는 골격 상태이며 `/healthz`는 200, `/readyz`는 503이다. 기동 확인과 업무 연동 완료는 구분한다. 자세한 구현 범위는 [Frontend](../frontend/README.md)·[Spring](../backend/spring/hops/README.md)·[AI](../ai/README.md)를 따른다.

## 수정·로그·종료

코드를 바꾼 서비스만 다시 빌드한다. 아래 `backend`를 `frontend` 또는 `ai`로 바꿔 사용할 수 있다.

```sh
docker compose up --build -d backend
docker compose logs -f backend ai
docker compose down
```

일반 종료·재빌드 후에도 DB 볼륨은 유지된다. `docker compose down --volumes`는 DB 데이터를 삭제한다. `.env`의 비밀번호를 바꿔도 기존 DB 계정의 비밀번호는 자동 변경되지 않는다.
