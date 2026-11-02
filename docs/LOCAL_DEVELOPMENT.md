# Docker Compose 로컬 개발 환경

저장소 루트에서 Frontend·Spring·AI·PostgreSQL을 함께 실행한다. Docker Engine과 Compose 2.20 이상을 사용하며, Windows/macOS는 Docker Desktop의 Linux 컨테이너를 실행한다.

## 처음 실행

설정 예시는 루트 [.env.example](../.env.example) 하나다. 저장소 루트에서 `.env`가 없을 때 한 번만 복사한다.

```powershell
Copy-Item .env.example .env
```

macOS/Linux는 `cp .env.example .env`를 사용한다. 기존 `.env`는 유지하고 새 항목만 추가한다. 복사한 파일에서 필수 값인 `POSTGRES_PASSWORD`와 ASCII 32자 이상의 `HOPS_JWT_SECRET`을 채우고, 나머지는 필요한 항목만 변경한다. 프론트·백엔드·AI·DB의 Docker 설정은 모두 이 파일에 입력한다. 서비스 폴더에 예시 파일을 따로 복사할 필요가 없다. 각 컨테이너에는 필요한 변수만 전달하며 `.env`와 개인 DB 설정은 Git·이미지에서 제외한다.

```sh
docker compose up --build -d
docker compose ps
```

기본 주소는 프론트 `http://localhost:5173`, Spring Swagger `http://localhost:8080/swagger-ui/index.html`, AI `http://localhost:8000/healthz`다. DB는 `localhost:5432`이며 이름과 계정은 루트 `.env`의 `POSTGRES_DB`, `POSTGRES_USER`를 사용한다. 모든 포트는 로컬 PC에만 공개한다.

포트가 겹치면 루트 `.env`의 `FRONTEND_PORT`, `BACKEND_PORT`, `AI_PORT`, `POSTGRES_PORT`를 바꾸고 `docker compose up -d`를 실행한다. `VITE_API_BASE_URL`과 `HOPS_CORS_ALLOWEDORIGINS`를 비워두면 포트에 맞춰 로컬 주소를 만들며, 별도 주소가 필요하면 직접 입력한다. AI 모드와 실행 예산도 `HOPS_AI_*` 항목으로 설정한다.

[OpenAI](https://developers.openai.com/api/docs/quickstart)의 `OPENAI_API_KEY`와 [Langfuse](https://langfuse.com/docs/observability/sdk/troubleshooting-and-faq)의 `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, `LANGFUSE_BASE_URL`도 루트 `.env`에 미리 입력할 수 있다. Langfuse 주소는 사용하는 프로젝트의 리전 또는 자체 호스팅 주소로 설정한다. 이 값들은 AI 컨테이너에만 전달하며 선택 항목이다. 현재 OpenAI 호출과 Langfuse 추적 연동은 미구현이므로 키 입력만으로 외부 호출·추적 전송이 시작되지는 않는다.

DB는 [pgvector 프로젝트 이미지](https://github.com/pgvector/pgvector#docker)의 PostgreSQL 18·pgvector 0.8.7을 사용한다. Spring Flyway가 확장 활성화, 테이블과 개발용 가상 데이터를 적용하므로 Docker 실행 시 pgvector를 따로 설치하지 않는다. 기존 PostgreSQL 18 볼륨도 유지한다. 테스트 DB가 필요하면 [Spring 안내](../backend/spring/hops/README.md#처음-설치-mac--windows)에 따라 별도로 준비한다.

루트 Compose는 현재 develop 프론트를 `live` 모드로 실행해 Spring의 기존 `/api`에 연결한다. 신규 `/api/v2` 전환과 해당 업무 API 구현은 별도 작업이다. AI는 골격 상태이며 `/healthz`는 200, `/readyz`는 503이다. 기동 확인과 업무 연동 완료는 구분한다. 자세한 구현 범위는 [Frontend](../frontend/README.md)·[Spring](../backend/spring/hops/README.md)·[AI](../ai/README.md)를 따른다.

## 수정·로그·종료

코드를 바꾼 서비스만 다시 빌드한다. 아래 `backend`를 `frontend` 또는 `ai`로 바꿔 사용할 수 있다.

```sh
docker compose up --build -d backend
docker compose logs -f backend ai
docker compose down
```

프론트 단독 Compose도 루트 설정을 사용한다. 저장소 루트에서 `docker compose --env-file .env -f frontend/compose.yaml up --build -d`로 실행한다. 전체 환경과 단독 환경의 같은 호스트 포트를 동시에 사용하지 않는다.

일반 종료·재빌드 후에도 DB 볼륨은 유지된다. `docker compose down --volumes`는 DB 데이터를 삭제한다. `.env`의 DB 이름·계정·비밀번호는 새 볼륨의 초기 설정이며, 기존 DB의 값은 자동 변경되지 않는다.
