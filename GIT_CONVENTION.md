# Git Convention — Git Flow

상태: Git Flow 전환을 위한 팀 PR 리뷰 후보 · 2026-10-15. 문서 수정은 팀 승인, 브랜치 생성, 보호 규칙 설정 또는 배포 완료를 뜻하지 않는다. 작성자 외 팀원 최소 1명의 승인과 병합 후 공통 기준으로 적용한다.

## 1. 기본 원칙

- `main`은 출시 기준, `develop`은 기본(default) 브랜치이자 다음 버전의 개발 통합 기준으로 유지한다. `dev`라는 별도 통합 브랜치는 두지 않는다.
- 기능 개발과 문서 작업은 `develop`에서 만든 작업 브랜치에서 수행하고 PR로 `develop`에 통합한다.
- 출시는 `develop`에서 `release/*`를 분기하여 검증한 뒤 `main`과 `develop` 양쪽에 반영한다.
- 운영 긴급 수정은 `main`에서 `hotfix/*`를 만들고 운영 반영과 개발 계열 반영을 모두 완료한다.
- `main`, `develop`, `release/*`의 기존 브랜치에는 직접 push하지 않는다. 모든 변경은 PR 리뷰와 검증을 거친다. 승인된 기준 커밋에서 최초 브랜치를 만드는 작업은 별도 초기화 권한으로 수행한다.
- 개인별 영구 브랜치를 만들지 않는다. 하나의 작업 브랜치와 PR은 하나의 목적을 다루며, 작은 변경 단위로 나눈다.
- `develop`도 빌드·테스트가 통과하는 상태를 유지한다. 미완성 기능은 작업 브랜치에 남기거나 기본 비활성화된 기능 플래그로 격리한다.
- 문서·AI 지침·스킬도 팀원 리뷰를 거친다. 개인 AI 산출물은 `.local/`에 보관하고 공유하지 않는다.

## 2. 브랜치 역할과 경로

### 상시 브랜치

- `main`: 검증된 출시 기준. 운영 배포 이력은 태그와 배포 기록으로 식별한다.
- `develop`: 저장소의 기본(default) 브랜치이자 다음 출시를 위한 통합 브랜치. 일반 개발·문서 작업 PR의 base다.

### 작업 브랜치

영문 소문자와 하이픈을 사용한다. 기본 형식은 `<type>/<issue-number>-<description>`이며 단순 작업은 이슈 번호를 생략할 수 있다.

- `feature/12-add-login`: 기능 개발. `develop`에서 생성 → `develop`으로 PR.
- `fix/24-handle-empty-input`: 출시 전 버그 수정. `develop`에서 생성 → `develop`으로 PR.
- `refactor/18-extract-validator`, `test/22-add-login-tests`, `chore/update-ci`: 일반 작업. `develop`에서 생성 → `develop`으로 PR.
- `docs/update-guide`: 일반 문서·PRD·Spec·Contract·팀 규칙 변경. `develop`에서 생성 → `develop`으로 PR.
- `release/1.0.0`: 출시 준비. `develop`에서 생성 → 검증 후 `main` 및 `develop`으로 각각 PR.
- `hotfix/1.0.1-login-error`: 운영 긴급 수정. `main`에서 생성 → `main` 및 `develop`으로 각각 PR. 진행 중인 `release/*`에도 반영한다.
- `fix/1.0.0-release-validation`: 출시 후보 수정. 해당 `release/1.0.0`에서 생성 → 같은 `release/1.0.0`으로 PR.
- `sync/1.0.0-to-develop`: 역병합 충돌 해결. 대상 `develop`에서 생성 → 원본 release/hotfix를 merge하고 충돌 해결·검증 → `develop`으로 PR.

`feature/`는 브랜치 접두사이고 `feat`는 커밋 타입이다. 둘을 구분한다. 병합된 작업 브랜치는 재사용하지 않는다.

### 공통 문서 작업

PRD·Spec·Contract·팀 규칙도 `develop`에서 `docs/*`를 만들어 `develop`으로 PR을 연다. 구현 기준의 승인이 필요한 문서는 작성자 외 팀원 최소 1명의 리뷰 승인과 `develop` 병합 후 관련 구현에 적용한다. 문서 승인만을 위해 `main`에 먼저 병합하거나 같은 문서를 `develop`에 추가 동기화하는 예외 경로는 두지 않는다.

문서만 변경하는 PR에는 관련 없는 코드·런타임 설정·DB 변경을 섞지 않는다. 코드와 함께 수정하는 설명 문서는 해당 구현 PR에 포함한다. `main` 반영은 출시 절차를 따르며, 진행 중인 release나 운영 긴급 수정에 필요한 문서는 해당 release/hotfix 경로를 따른다.

## 3. 커밋 규칙

```text
<type>(<scope>): <summary>

<선택: 변경 이유와 주요 설명>

<선택: 관련 이슈 또는 호환성 변경 설명>
```

- `type`은 영문 소문자, `scope`는 선택 사항이다.
- 제목은 72자 이내를 권장하며, 끝에 마침표를 붙이지 않는다.
- 설명은 한국어를 기본으로 한다. 코드 식별자와 고유명사는 원문을 유지한다.
- Conventional Commits의 `type(scope)`는 검색·자동화를 위한 분류 표기이며, 제목의 설명과 본문은 사람이 이해할 수 있는 한국어 문장으로 쓴다. 타입만 보고 의미를 추측하게 하지 않는다.
- 동작·계약이 바뀌는 커밋 본문에는 변경 이유, 바뀐 동작, 관련 Spec/AC와 검증을 적는다. 사소한 오탈자처럼 제목만으로 충분한 경우 본문은 생략할 수 있다.
- `수정`, `작업 중`, `update`처럼 내용을 알 수 없는 제목은 사용하지 않는다.
- 하나의 커밋은 설명 가능한 하나의 논리적 변경을 담는다.

사용 가능한 타입:

- `feat`: 사용자에게 제공하는 기능 추가
- `fix`: 버그 수정
- `docs`: 문서 변경
- `style`: 동작에 영향 없는 포맷·공백 변경
- `refactor`: 기능 변경 없는 코드 구조 개선
- `perf`: 성능 개선
- `test`: 테스트 추가·수정
- `build`: 빌드 시스템 또는 의존성 변경
- `ci`: CI/CD 구성 변경
- `chore`: 위 항목에 해당하지 않는 유지보수
- `revert`: 기존 변경 되돌리기

예시:

```text
feat(auth): 이메일 로그인 기능 추가
fix(validation): 빈 입력값 처리 오류 수정
docs: Git Flow 개발 규칙 작성
ci: PR 빌드 검사 추가
```

이 프로젝트의 예시:

```text
feat(ticket): 같은 문의를 다시 보내도 티켓이 중복 생성되지 않도록 처리

네트워크 오류 후 재전송하면 동일한 문의가 여러 티켓으로 접수되는 문제를 해결한다.
같은 요청 키와 입력으로 다시 접수하면 최초 티켓의 결과를 반환한다.
같은 키에 다른 입력이 들어오면 충돌 오류를 반환한다.

명세: F001-R07, F001-AC04
검증: 동일 요청 재전송과 다른 입력 충돌 테스트 통과
```

위 검증 문구는 형식 예시다. 실제 커밋에는 실제 실행한 검사와 결과만 작성한다. `feat`, `fix` 같은 타입 뒤에 짧은 명사만 나열하기보다 사용자가 체감하는 변경을 설명한다.

호환성이 깨지는 변경은 타입 또는 scope 뒤에 `!`를 붙이고, 본문에 영향과 전환 방법을 설명한다.

```text
feat(api)!: 사용자 응답의 name 필드를 displayName으로 변경

BREAKING CHANGE: 클라이언트는 name 대신 displayName을 사용해야 한다.
```

## 4. 일반 개발 절차

아래 명령은 `origin`, `main`, `develop`이 이미 있는 저장소의 실행 예시다. 문서 작성만으로 초기화·원격 생성·push를 수행하지 않는다.

### 4.1. 최신 develop에서 시작

미완료 변경이 없는지 확인한 뒤 실행한다.

```bash
git switch develop
git pull --ff-only origin develop
git switch -c feature/12-add-login
```

### 4.2. 개발·검증·공유

로컬에서 관련 서비스를 실행하고 린트·테스트·빌드를 수행한다. 파일을 명시적으로 stage하고 diff를 확인한다. 아래 경로는 실제 파일로 바꾼다.

```bash
git status
git diff
git add <변경한-파일-경로>
git diff --cached
git commit -m "feat(auth): 이메일 로그인 기능 추가"
git push -u origin feature/12-add-login
```

비밀값·실제 개인정보·`.env`를 커밋하지 않는다. 설정 예시는 실제 비밀값이 없는 `.env.example` 등으로 관리한다.

### 4.3. 최신 변경 반영과 PR

- PR의 base는 `develop`, compare는 작업 브랜치다. 진행 중이면 Draft PR로 공유한다.
- 리뷰 수정은 동일 브랜치에 추가 커밋으로 push한다.
- 다른 작업이 먼저 통합되면 내 작업 브랜치에서 최신 `develop`을 가져온다.

```bash
git fetch origin
git merge origin/develop
```

충돌은 작업 브랜치에서 해결하고 관련 검증을 다시 수행한다. 공유 이력 재작성과 force push는 금지한다. release에는 이후 개발 기능이 섞이지 않도록 `develop`을 다시 merge하지 않는다.

## 5. PR 작성·리뷰·병합

### PR 내용

PR 제목은 커밋 제목과 같은 형식을 사용하고, 최종 merge 커밋 제목·본문도 한국어 설명 중심으로 정리한다.

PR 본문은 [PR 템플릿](.github/PULL_REQUEST_TEMPLATE.md)의 항목과 순서를 따른다. 템플릿을 기준으로 작성하며 아래 안내는 각 항목에 담을 내용을 설명한다.

| 템플릿 항목 | 작성 내용 |
| --- | --- |
| 📌 작업 내용 요약 (Summary) | 해결할 문제와 변경 후 동작을 간결하게 설명한다. |
| 📝 변경 사항 (Key Changes) | 주요 변경·영향 범위·출발 브랜치 → 대상 브랜치를 적는다. 관련 PRD/Spec·인수 조건과 Contract 변경 여부가 있으면 함께 적는다. |
| 📸 스크린샷 (Screenshots / Demos) | UI 변경의 전후 화면이나 데모를 첨부한다. 해당하지 않으면 사유와 함께 '해당 없음'으로 표시한다. |
| 🔗 연관된 이슈 (Related Issues) | 완료할 이슈는 `Close #12`, 참조만 할 이슈는 `Refs #12`로 연결한다. 자동 종료 조건은 아래 규칙을 따른다. |
| 🧐 주요 리뷰 포인트 (Review Point) | 리뷰어가 확인할 동작·설계 판단·호환성·위험을 적는다. 필요한 설정·마이그레이션·배포·복구 방법과 release/hotfix의 다른 대상 반영 PR·상태도 이 항목에 적는다. |
| ✅ 최종 PR전 체크 리스트 (Checklist) | 컨벤션·불필요한 주석/로그·검증·문서 갱신 여부를 실제 결과에 맞게 표시한다. 실행한 명령·환경·결과와 미실행 항목·사유를 함께 적는다. |

체크리스트는 템플릿에서 미리 체크된 항목도 실제로 확인한 뒤 표시한다. 문서 변경처럼 Checkstyle이나 앱 테스트가 적용되지 않는 경우에는 '해당 없음'과 사유를 적고, 수행하지 않은 검사를 통과한 것으로 표시하지 않는다.

모델·프롬프트·정책 변경은 평가 데이터 버전과 결과를 적는다. UI 변경은 필요한 스크린샷을 첨부한다. 개인 채팅·세션 ID·로컬 절대 경로·원시 로그를 공유 문서의 필수 근거로 사용하지 않는다.

기본 브랜치는 `develop`이다. 이슈의 완료 조건을 충족하는 `develop` 대상 PR에는 `Closes #12`를 적어 병합 시 이슈를 자동 종료한다. 후속 작업이 남아 있는 PR은 `Refs #12`로 참조하고, 완료 조건을 확인한 뒤 담당자가 이슈를 닫는다. `main`·`release/*` 등 기본 브랜치가 아닌 대상을 향한 PR에서는 종료 키워드에 의한 연결·자동 종료를 가정하지 않는다. 이슈 종료는 개발 작업의 완료를 뜻하며 운영 배포 완료와 구분한다.

### 병합 조건

- Draft 해제, 작성자 외 팀원 최소 1명 승인, 수정 요청과 리뷰 대화 해결.
- 해당 대상 브랜치의 필수 CI 통과 및 충돌 없음. 마지막 코드 변경 이후 검증·승인이 유효해야 한다.
- CI가 없다면 실행한 로컬 검증과 결과를 PR에 기록한다. CI 도입 후 필수 검사로 전환한다.
- AI 검토나 로컬 대화의 동의는 팀원 PR 리뷰 승인을 대신하지 않는다.

### Merge commit으로 통일

**Create a merge commit**을 사용한다. Git의 `--no-ff` 방식처럼 분기·병합 이력을 유지하고, 여러 대상에 반영되는 release/hotfix의 공통 커밋을 보존한다. Squash merge와 Rebase merge는 사용하지 않는다. 보호 브랜치에 로컬 merge 결과를 직접 push하지 않고 GitHub PR로 병합한다.

동일 release/hotfix를 여러 대상으로 보내는 PR에서는 대상 브랜치 변경을 원본 브랜치에 섞지 않는다. 특히 `develop`을 release/hotfix에 merge하면 미출시 기능이 `main`으로 유입될 수 있다. 역병합 충돌은 대상 브랜치 기반 `sync/*`에서 원본을 merge하여 해결한다.

## 6. 출시 절차

1. 출시할 기능을 `develop`에 통합하고 범위를 확정한다. 출시하지 않을 기능은 release 분기 전 통합하지 않거나 비활성화한다.
2. 최신 `develop`에서 `release/1.0.0`을 만든다. 이후 `develop`에서는 다음 버전 개발을 계속할 수 있다.
3. release에는 버그 수정·버전·출시 문서만 반영한다. 수정은 release 기반 `fix/*`와 PR로 처리한다.
4. 출시 후보의 통합/E2E·마이그레이션·스모크·복구 검증을 수행한다.
5. `release/1.0.0 → main` PR을 승인·병합한다. 원본 release를 동결하고, 최종 `main` 병합 커밋의 검사도 확인한다.
6. 그 `main` 커밋에 `v1.0.0` 태그를 지정한다. 태그와 빌드 산출물을 연결하여 승인된 배포 절차로 운영 반영한다. 태그 생성과 배포 성공은 별도로 기록한다.
7. `release/1.0.0 → develop` PR로 안정화 수정을 역병합한다. 충돌 시 `develop` 기반 `sync/1.0.0-to-develop`에서 release를 merge하여 해결한다.
8. 두 대상 반영과 필요한 기록 보존을 확인한 후 release와 보조 브랜치를 삭제한다.

```bash
git switch develop
git pull --ff-only origin develop
git switch -c release/1.0.0
git push -u origin release/1.0.0
```

위 명령은 출시 브랜치 생성 예시다. 병합·태그·배포를 자동 수행하지 않는다. 한 번에 활성 release는 하나로 운영한다. `main`이 변경되면 release에서 최신 `main`을 반영하고 다시 검증한 뒤 출시한다.

## 7. 운영 긴급 수정

1. 현재 운영 태그와 `main` 상태를 확인한다. 일반적으로 최신 `main`에서 `hotfix/1.0.1-login-error`를 만든다. 운영 버전이 다르면 해당 운영 태그를 기준으로 하고, 최신 main과의 호환성도 검증한다.
2. 최소 수정과 회귀 검증을 수행하고 `hotfix/* → main` PR을 승인·병합한다.
3. 최종 `main` 커밋을 검증하여 패치 태그 `v1.0.1`을 생성하고 승인된 절차로 배포한다.
4. 같은 hotfix를 `develop`에도 PR로 반영한다. 활성 `release/*`가 있다면 그 브랜치에도 별도 PR로 반영하여 다음 출시에서 수정이 사라지지 않게 한다.
5. 대상별 검증·병합을 모두 확인한 뒤 hotfix 브랜치를 삭제한다. 충돌은 해당 대상 기반 `sync/*`에서 해결한다.

긴급 상황에도 PR과 팀원 승인 조건을 유지한다. 운영 미배포 기능을 hotfix에 포함하지 않는다. release와 hotfix의 main 병합은 순서를 정해 처리하고, 먼저 병합된 변경을 나중 후보에 반영해 재검증한다. 이미 사용한 출시 태그는 이동·재사용하지 않는다.

## 8. 개발·검증·운영 환경

브랜치와 실행 환경은 구분한다. 아래는 적용할 배포 구성안이며 실제 파이프라인이 구축됐다는 의미가 아니다.

- 개인 로컬: 각 작업 브랜치에서 개발·테스트.
- 공용 개발 환경: `develop` 통합 결과를 배포하여 서비스 간 연동 확인.
- 출시 검증 환경: `release/*`의 고정 커밋을 배포하여 출시 후보 검증.
- 운영 환경: 검증한 `main`의 버전 태그에 연결된 산출물을 배포.

문서만 변경한 PR이 `develop`에 병합되어도 운영 배포나 버전 태그를 자동 생성하지 않는다. 운영 배포는 지정된 출시·패치 태그와 승인 절차를 기준으로 한다.

## 9. 정리·되돌리기

- `main`, `develop`은 삭제하지 않는다.
- 일반 개발·문서 작업 브랜치는 `develop` 대상 PR 병합 후 삭제한다. release/hotfix는 모든 대상 반영 완료 후 삭제한다.
- GitHub의 병합 즉시 브랜치 자동 삭제는 끈다. 첫 main PR 병합만으로 release/hotfix가 사라지지 않도록 한다.

```bash
git switch develop
git pull --ff-only origin develop
git fetch --prune
git branch -d feature/12-add-login
```

`-d`가 거절하면 미반영 커밋과 PR 상태를 먼저 확인한다. 무조건 `-D`로 삭제하지 않는다.

되돌리기는 이력 reset 대신 PR로 처리한다. 개발 변경 취소는 `develop` 기반 `fix/*`, 운영 변경 취소는 `main` 또는 운영 태그 기반 `hotfix/*`에서 수행한다.

```bash
# 일반 커밋 하나를 되돌릴 때
git revert <일반-커밋-SHA>

# PR merge 커밋 전체를 되돌릴 때: 부모 순서를 확인한 후 실행
git show --no-patch --pretty=raw <merge-커밋-SHA>
git revert -m 1 <merge-커밋-SHA>
```

`-m 1`은 첫 번째 부모가 유지할 대상 브랜치인 경우에만 사용한다. merge revert 후 같은 브랜치를 다시 merge해도 변경이 복원되지 않을 수 있으므로 재도입은 revert 취소 또는 새 수정 PR로 계획한다. 운영 되돌리기도 develop과 활성 release에 전파한다. 코드 revert와 배포 롤백은 별개이며 DB·외부 시스템 변경은 별도의 복구 절차가 필요하다.

## 10. 저장소 설정 체크리스트

아래는 관리자가 적용할 항목이며 완료 상태가 아니다.

- [ ] 최초 승인된 main에서 develop 생성, 저장소 기본(default) 브랜치는 develop으로 설정
- [ ] main·develop·release/*에 직접 push 금지 및 PR 필수
- [ ] 작성자 외 팀원 승인 최소 1명, 새 코드 변경 시 기존 승인 무효화
- [ ] 리뷰 대화 해결과 대상별 CI 상태 검사 필수
- [ ] 병합 전 대상 변경을 포함한 검증 수행; 역병합 충돌은 sync 브랜치에서 처리
- [ ] main·develop 삭제 및 force push 금지, 관리자 우회 제한
- [ ] Create a merge commit 허용, Squash/Rebase merge 비활성화
- [ ] merge commit과 충돌하는 선형 이력 필수 설정은 비활성화
- [ ] 병합 후 브랜치 자동 삭제 비활성화, 다중 대상 반영 후 담당자 정리
- [ ] 출시 태그 보호 및 운영 배포 승인 설정
- [ ] 일반 개발·문서 PR base는 develop인지 확인; PR/CI 대상에 develop·release/*·main 포함

## 참고

[Git Flow 원문: A successful Git branching model](https://nvie.com/posts/a-successful-git-branching-model/)을 참고한다. 이 프로젝트는 원문의 master를 main으로 부르며, PR 리뷰·문서 작업의 develop 통합·hotfix의 develop 및 활성 release 동시 반영은 팀 운영 규칙으로 명시한다.
