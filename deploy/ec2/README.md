# EC2 개발 환경의 CI/CD 준비

상태: 배포 절차와 필요한 설정을 설명한다. 각 환경의 GitHub Actions, AWS 권한, ECR, SSM 연결과 실제 배포 결과는 별도로 확인한다. 설정 전에는 `HOPS_AUTO_DEPLOY_ENABLED`를 켜지 않는다.

## 실행 경로

1. `develop` 대상 PR에서는 변경된 서비스와 영향을 받는 공통 계약·CI 영역을 검사한다. 변경 파일을 알 수 없거나 분류하지 못하면 전체를 검사한다. `develop` push에서는 항상 네 영역을 모두 검사한다.
2. 항상 실행되는 `CI result`가 선택된 검사의 성공을 확인한다. `develop` push에서 전체 검사가 성공하고 자동 배포 변수가 켜진 경우에만 배포한다. PR 코드는 EC2에서 실행하지 않는다.
3. GitHub OIDC로 제한된 AWS 역할을 사용한다. 테스트한 커밋에서 세 이미지를 빌드해 각각 ECR의 `hops-spring`, `hops-ai`, `hops-frontend` 저장소에 올린다. 배포에는 태그 대신 이미지 digest를 전달한다.
4. Systems Manager의 고정된 `HopsDeploy` 문서가 해당 커밋의 배포 스크립트를 내려받아 SHA-256을 확인하고 EC2에서 실행한다.
5. 프론트 이미지에 포함된 Nginx 템플릿을 공개 호스트에 맞춰 렌더링하고 설정을 검사한다. 기존 컨테이너를 잠시 보관한 뒤 Spring·AI·Nginx를 교체하고 HTTPS 프론트·API·Swagger와 AI 생존 상태를 검사한다. 실패 시 이전 컨테이너 복원을 시도한다. Redis와 DB, 인증서 볼륨은 삭제하지 않는다.

서비스 디렉터리 변경만으로 다른 서비스의 호출 관계를 자동 추론하지는 못한다. API·계약 변경 시 작성자와 리뷰어가 관련 소비자 검사가 필요한지 확인한다. 공통 계약 디렉터리(`contracts/`, `specs/`) 변경과 분류되지 않은 파일은 보수적으로 전체 검사를 실행한다. `CI result`는 통합 상태 검사 이름이며 브랜치 규칙에 자동 등록하지 않는다.

현재 단일 EC2라 교체 중 일시 중단이 있다. 로컬 HTTPS 응답은 전체 사용자 로그인·SSE·AI 모델 E2E가 아니다. AI `/readyz`는 실행 파이프라인과 RunLedger가 준비된 real 모드에서만 200이므로, 배포 스크립트는 모드와 관계없이 `/healthz`로 생존만 확인한다. 이전 이미지와 DB 백업은 별도로 보관해야 하며, 복원 시도 자체가 성공을 보장하지 않는다.

`README.md`와 `docs/` 아래 Markdown만 변경한 push는 직전 커밋의 실제 배포 성공 로그가 확인된 경우에만 배포를 생략한다. 직전 배포가 진행 중이거나 실패·생략됐거나 로그를 조회할 수 없으면 기존과 같이 배포한다. 따라서 배포 이력 만료나 연속된 문서 변경 등에서는 불필요한 배포가 발생할 수 있다. GitHub Actions 외부의 수동 배포·복원은 이 기록에 포함되지 않으므로 별도 운영 절차가 필요하다.

## Nginx 설정과 Swagger

Nginx 설정은 [`frontend/nginx/default.conf.template`](../../frontend/nginx/default.conf.template)에서 관리하며 배포용 프론트 이미지에 포함한다. 배포 시 검증된 공개 호스트만 치환한다. Nginx의 `$host` 등 요청 변수는 치환하지 않는다. 인증서와 개인키는 Git에 넣지 않고 기존 Docker 볼륨에서 읽는다.

HTTP는 인증서 갱신 경로를 제외하고 HTTPS로 전환한다. HTTPS의 `/api/v2/`·SSE·`/swagger-ui/`·`/v3/api-docs`는 Spring으로, 나머지 경로는 프론트로 전달한다. Swagger 문서와 UI는 공개되고 API의 인증·인가 검사는 Spring이 담당한다. 설정을 비공개로 바꿀 때는 접근 정책을 별도로 정한다.

첫 전환 때 EC2의 기존 `hops-deploy/nginx/https.conf` 파일은 삭제하거나 덮어쓰지 않는다. 새 이미지가 만들어질 때까지 기존 배포는 그 파일을 사용한다. 새 배포에서는 이미지의 템플릿을 검사한 뒤 이전 컨테이너를 보존하면서 교체한다. 실패하면 기존 컨테이너와 기존에 연결된 설정의 복원을 시도한다. 새 이미지에서는 EC2의 수동 설정을 읽지 않으므로 설정 변경은 저장소에서 리뷰한 뒤 배포한다. 새 EC2에도 네트워크, 서비스 env와 유효한 인증서 볼륨은 별도 준비해야 한다.

## 마이그레이션 경계

배포는 DB 마이그레이션을 실행하거나 되돌리지 않는다. EC2의 `/var/lib/hops-deploy/spring-migrations.tree`, `ai-migrations.tree`에 검증된 Git tree ID가 있어야 하며, 배포 대상 커밋의 해당 디렉터리 tree가 다르면 컨테이너를 변경하기 전에 실패한다. 새 마이그레이션은 DB 백업·호환성·기존 앱과의 순서·앱 계정 권한을 검토한 뒤 별도 절차로 적용하고 실제 결과를 확인한 후에만 기준을 갱신한다. 마이그레이션만 포함된 변경도 이 확인 전에는 자동 배포되지 않는다.

AI 런타임 DB의 계정·소유자·권한 SQL은 배포 기준에 포함되지 않도록 `ai/migrations` 밖의 [`ai/db`](../../ai/db/README.md)에 두며, RDS 적용 절차와 점검 명령도 그 문서에 있다.

첫 기준 설정 전, 기존 DB의 Flyway 이력과 AI 실행 기록 스키마를 실제로 확인하고 현재 실행 이미지와 적용된 마이그레이션에 해당하는 커밋을 식별해야 한다. 예를 들어 EC2의 Git 체크아웃에서 확인한 커밋을 `VERIFIED_COMMIT`에 입력한 다음 아래를 실행한다. 이 파일들은 **관리자의 검증 기록**이며, 파일을 쓰는 행위 자체가 DB 검증을 뜻하지 않는다.

```bash
VERIFIED_COMMIT='<실제 적용·검증한 40자리 커밋>'
git -C ~/Hops cat-file -e "$VERIFIED_COMMIT^{commit}"
sudo install -d -m 0700 /var/lib/hops-deploy
git -C ~/Hops rev-parse "$VERIFIED_COMMIT:backend/spring/hops/src/main/resources/db/migration" | sudo tee /var/lib/hops-deploy/spring-migrations.tree >/dev/null
git -C ~/Hops rev-parse "$VERIFIED_COMMIT:ai/migrations" | sudo tee /var/lib/hops-deploy/ai-migrations.tree >/dev/null
```

기준 파일 갱신은 배포를 허용하는 보안상 중요한 작업이다. 실패한 마이그레이션을 성공한 것으로 기록하거나 임의의 최신 tree로 덮어쓰지 않는다. 동시 수동 변경은 배포 잠금 정책을 우회할 수 있으므로 팀이 운영 절차를 합의한다.

### 새 Spring 마이그레이션 적용

EC2의 Spring 컨테이너는 Flyway를 실행하지 않는다(`SPRING_FLYWAY_ENABLED=false`). 새 `V*.sql`이 develop에 병합되면 배포는 tree 불일치로 멈추고, 배포 담당자가 아래 순서로 적용한 뒤에만 다시 진행된다.

1. DB를 백업하고, 새 마이그레이션이 현재 실행 중인 이미지와 함께 동작하는지(컬럼 삭제·이름 변경 여부) 확인한다.
2. Flyway CLI는 앱과 같은 버전을 쓴다. 앱 버전은 `backend/spring/hops`에서 `./gradlew dependencyInsight --dependency org.flywaydb:flyway-core --configuration runtimeClasspath`로 확인한다(현재 12.4.0).
3. 대상 위치는 `db/migration`만 지정한다. `db/seed`는 로컬 가상 데이터라 개발 서버 DB에 넣지 않는다.
4. 앱이 쓰는 DB 계정으로 적용한다. 다른 계정으로 적용했다면 새 테이블·시퀀스의 소유자와 앱 계정 권한을 확인하고 필요한 권한을 부여한다.
5. `flyway_schema_history`에서 새 버전이 `success`인지 확인한 뒤, 적용한 커밋으로 위 명령을 실행해 `spring-migrations.tree`를 갱신한다.
6. 실패했던 이전 배포 run이 아니라 **기준을 갱신한 커밋의 배포 run(보통 최신 develop)** 을 다시 실행한다. 다른 커밋의 run은 tree가 달라 다시 실패한다.

## P0 검색을 배포하는 환경

1. 대상 업무 DB에 V27을 별도로 적용하고 위 절차대로 Flyway 이력과 배포 기준 tree를 확인한다. 격리 시연 DB의 승인 가상 지식과 Neo4j 투영은 `scripts/knowledge/load_search_demo.py --isolated-demo`로 적재한다. 이 스크립트는 카탈로그와 일치하는 격리 시연 DB용이다. 공유 DB의 승인 문서는 해당 DB의 데이터를 사용한 별도 색인 이관이 필요하다.
2. Neo4j는 Spring이 사용하는 `hops-internal` 네트워크에 연결한다. Spring env 파일에 `HOPS_SEARCH_NEO4J_URL`, `HOPS_NEO4J_PASSWORD`, `HOPS_SEARCH_EMBEDDING_API_KEY`를 주입한다. 기본 Neo4j 사용자와 데이터베이스는 `neo4j`이며 다른 값을 쓰는 경우 `HOPS_NEO4J_USERNAME`, `HOPS_SEARCH_NEO4J_DATABASE`도 맞춘다. 임베딩 키는 `text-embedding-3-small`을 호출할 수 있어야 한다.
3. AI를 real·spring·내부 API·판정 모드로 실행한다. 기본 모델 호출 상한은 양쪽 8회다. 조정할 때 AI의 `HOPS_AI_MAX_LLM_CALLS`와 Spring의 `HOPS_AGENT_RUN_LIMITS_MAXMODELCALLS`, `HOPS_AGENT_RUN_LIMITS_MAXOUTPUTTOKENS`를 함께 확인한다. Spring 출력 한도는 실행당 총량이다.
4. env 파일을 적용해 컨테이너를 다시 만든 뒤 Spring에서 임베딩과 Neo4j 조회를 확인하고 AI `/readyz` 및 실제 판정 옵션을 확인한다. V27과 컨테이너 생존 확인만으로는 검색이 준비됐는지 알 수 없다. pgvector의 현재 모델 청크와 Neo4j의 가상 카탈로그 데이터가 존재하는지 확인하고 브라우저에서 승인 가이드·승인 질문·답변 재반영을 실행한다.
5. 사례 공개 기준은 `INDEXED`다. 같은 승인 버전의 두 색인 상태가 모두 성공이어야 사례 검색 및 그래프 이웃에 나타난다. 새 승인 사례 자동 색인은 아래 절차로 켠다.

## 승인 사례 자동 색인을 켜는 환경 (#244 P1)

Spring이 사례 승인 뒤 `POST /internal/v2/index-jobs`로 VECTOR·NEO4J 반영을 요청하면 AI가 pgvector 조각과 Neo4j 사례 노드·연결을 넣는다. AI는 업무 원본을 읽지 않고 사례 본문을 Spring 내부 API로 받는다. 아래 1~3이 끝나기 전에는 AI가 `503 INDEX_UNAVAILABLE`로 응답하고 사례는 `PENDING`에 머문다(승인 자체는 유지된다).

1. **AI 마이그레이션 `003_index_jobs.sql`:** `hops_ai_migrator`로 `hops_ai_runtime`에 적용하고 [`ai/db/03_app_privileges.sql`](../../ai/db/03_app_privileges.sql)을 다시 실행한다. 확인 뒤 위 마이그레이션 경계 절차대로 `ai-migrations.tree`를 갱신한다. 갱신 전에는 이 커밋의 자동 배포가 마이그레이션 변경으로 멈춘다.
2. **업무 DB 권한:** 업무 DB `hops`의 `knowledge_chunk` 소유자(Spring Flyway 계정)로 [`ai/db/04_search_chunk_privileges.sql`](../../ai/db/04_search_chunk_privileges.sql)을 실행한다. `hops_ai_app`에 조각 DML과 문서 버전 `id`의 REFERENCES만 준다.
3. **AI env 파일:** `HOPS_AI_SEARCH_DSN`(`hops_ai_app`으로 `hops` DB 접속, RDS는 `sslmode=verify-full&sslrootcert=/etc/hops/rds-global-bundle.pem`), `HOPS_AI_NEO4J_URL`(`http://hops-neo4j:7474`), `HOPS_AI_NEO4J_PASSWORD`를 넣는다. 사용자·DB가 `neo4j`가 아니면 `HOPS_AI_NEO4J_USERNAME`, `HOPS_AI_NEO4J_DATABASE`도 맞춘다. 임베딩은 AI의 `OPENAI_API_KEY`로 `text-embedding-3-small`을 부른다. Spring 주소·`SPRING_SERVICE_TOKEN`은 기존 값을 쓴다.
4. **점검:** AI 컨테이너에서 `python -m hops_ai.persistence.check_runtime_db --business-dbname hops`를 실행해 `ai_index_job`·조각 권한 항목을 확인한다.
5. **검증:** 담당자로 사례를 승인하면 사례 화면의 Vector·Neo4j가 `반영 완료`가 된다. 이미 승인돼 `반영 대기`인 사례는 담당자의 `현재 반영 상태 확인`이 같은 요청을 다시 전달해 반영한다. `hops_knowledge.knowledge_chunk`의 해당 문서 버전 조각 1건과 Neo4j `ApprovedCase` 노드·연결을 확인한다.

## AWS 일회성 준비

AWS 계정에 장기 액세스 키를 만들거나 저장소에 비밀값을 넣지 않는다. 실제 생성 전 대상 계정·리전·비용과 기존 리소스를 확인한다.

1. 서울 리전에 프라이빗 ECR 저장소 `hops-spring`, `hops-ai`, `hops-frontend`를 만든다. 이미지 저장에는 비용이 발생할 수 있다. 이전 버전 복원에 필요한 digest를 보존한 뒤 보관 정책을 정하고, 환경 종료 시 이미지·저장소 보존 여부를 확인한다.
2. EC2에 SSM Agent와 인스턴스 프로파일을 구성해 Systems Manager의 관리형 노드로 등록한다. 역할에 `AmazonSSMManagedInstanceCore`와 세 ECR 저장소에서 이미지를 받는 최소 권한을 부여한다. EC2의 AWS CLI·Docker·curl·flock과 AWS 및 GitHub HTTPS 접근도 필요하다.
3. `ssm-document.yml`을 같은 계정·리전의 **명령 문서 `HopsDeploy`**로 생성한다. 기본 문서 버전을 관리자가 검토하고, GitHub 역할에는 임의의 `AWS-RunShellScript`가 아니라 이 문서와 대상 인스턴스에 대한 `ssm:SendCommand`만 허용한다. 문서 수정 권한은 별도 관리한다. SSM 명령은 EC2에서 root로 실행되므로 대상·문서·호출 권한 제한이 중요하다.
4. GitHub OIDC 공급자와 배포용 IAM 역할을 준비한다. 신뢰 정책은 대상 저장소와 `refs/heads/develop`, `sts.amazonaws.com`으로 제한하고 조직의 실제 브랜치 보호·리뷰 권한을 확인한다. GitHub 역할에 ECR 세 저장소의 push·이미지 조회와 SSM 명령 전송·결과 조회에 필요한 권한만 부여한다.
5. 현재 EC2의 `hops-spring`, `hops-ai`, `hops-nginx`, `hops-redis`, Docker 네트워크 `hops-internal`, RDS CA·서비스 env 파일과 인증서 볼륨을 확인한다. `hops-letsencrypt`에는 공개 호스트에 해당하는 인증서와 개인키가 있어야 한다. 배포 스크립트는 현재 수동 구성과 같은 컨테이너·볼륨 이름을 사용한다.

IAM 정책의 출발점은 [iam 디렉터리](iam/)에 있다. `github-trust.json`은 GitHub 역할의 신뢰 정책이고, `github-deploy-policy.json`은 해당 역할의 권한 정책이며, `ec2-ecr-pull-policy.json`은 EC2 역할의 ECR 읽기 권한 정책이다. AWS 콘솔에 입력할 때 각 `<...>` 자리에 실제 계정 ID, 리전과 인스턴스 ID를 넣고 범위를 확인한다. 이 템플릿을 저장소에 추가해도 AWS 권한은 생성되지 않는다.

이 저장소는 GitHub OIDC의 변경 불가능한 subject 형식을 사용하므로 신뢰 정책의 `sub`에는 조직 ID와 저장소 ID가 포함되어야 한다. 이름만 포함한 이전 형식은 일치하지 않는다. 템플릿의 ID와 브랜치가 실제 저장소 설정과 일치하는지 확인한다. [GitHub OIDC subject 형식](https://docs.github.com/en/actions/reference/security/oidc#immutable-subject-claims).

ECR 저장소 생성, IAM 역할·정책, SSM 문서와 GitHub 설정은 이 파일을 추가하는 것만으로 만들어지지 않는다. ECR 요금과 GitHub 저장소 설정 권한은 실제 계정에서 확인한다. 참고: [GitHub AWS OIDC](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws), [SSM 관리형 노드](https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-instance-permissions.html), [ECR 요금](https://aws.amazon.com/ecr/pricing/).

## GitHub Actions 변수

저장소 관리자가 Actions 변수에 설정한다. 실제 값은 계정 설정에만 보관하고 예시나 로그에 비밀값을 넣지 않는다.

| 변수 | 목적 |
| --- | --- |
| `HOPS_AWS_REGION` | EC2와 ECR이 있는 리전 |
| `HOPS_AWS_DEPLOY_ROLE_ARN` | GitHub OIDC로 맡을 배포 역할 ARN |
| `HOPS_EC2_INSTANCE_ID` | 대상 EC2 인스턴스 ID |
| `HOPS_PUBLIC_ORIGIN` | 브라우저가 접근하는 HTTPS origin. Spring 허용 origin과 일치해야 한다. |
| `HOPS_AUTO_DEPLOY_ENABLED` | 모든 준비와 팀 리뷰 후 `true`로 설정해야 자동 배포 실행 |

변수가 없거나 `HOPS_AUTO_DEPLOY_ENABLED`가 `true`가 아니면 배포 작업을 실행하지 않는다. 준비 완료 후 테스트 PR과 develop push의 결과, ECR 이미지 digest, SSM 명령 및 실제 서비스 검증을 따로 확인한다. 첫 연결은 안전하게 실패·복구할 수 있는 시간에 수행한다.

## 검증과 복구

CI는 격리된 PostgreSQL·Redis와 가상 자격 증명으로 실행한다. 배포 스크립트 회귀 검사는 모의 Docker·HTTP를 사용하므로 실제 AWS 통합 검증을 대신하지 않는다. 배포 이력·SSM 결과·실제 컨테이너 상태와 HTTPS 응답을 확인하고 실패하면 수동 조작 전 스크립트의 복원 결과와 기존 이미지를 확인한다.

인증서와 API 토큰, DB 비밀번호는 SSM 파라미터나 워크플로 로그로 전달하지 않는다. CI가 외부 실제 모델을 호출하거나 PR에서 배포를 수행하지 않는다. 컨테이너에 사용되는 기존 비밀 파일과 DB는 EC2에 남으며, 이 안내는 AWS 리소스 삭제를 수행하지 않는다.
