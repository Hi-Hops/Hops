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
