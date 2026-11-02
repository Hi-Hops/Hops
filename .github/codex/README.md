# develop PR 자동 리뷰

`develop` 대상의 같은 저장소 PR을 생성·재개하거나 커밋을 추가하고 Draft를 해제하면 [워크플로](../workflows/codex-review.yml)가 실행된다. 기존 PR의 base만 `develop`으로 바꾸면 실행되지 않으며 새 커밋 추가나 PR 재개가 필요하다. Draft·fork·봇 작성 PR과 `[bot]` 계정이 발생시킨 실행은 제외한다. Codex Action의 기본 호출자 제한에 따라 저장소 쓰기 권한이 있는 사용자의 실행을 대상으로 한다.

## 활성화

1. 이 변경을 팀원 리뷰 후 `develop`에 병합한다. 리뷰는 base 버전의 스킬과 게시 스크립트를 사용하므로 이 설정을 처음 도입하는 PR 자체에서는 안내 메시지를 남기고 모델 실행·댓글 게시를 건너뛴다.
2. 저장소 Settings → Secrets and variables → Actions에서 `OPENAI_API_KEY`를 Secret으로 등록한다. 키 값을 파일이나 PR에 남기지 않는다.
3. 팀에서 사용할 모델과 예산을 정하고 해당 모델 ID를 `CODEX_REVIEW_MODEL` 저장소 Variable로 등록한다. 키나 모델이 미설정이면 notice를 남기고 모델 실행을 건너뛴다. 실행 검증 실패와 모델 결과 없음은 리뷰 본문으로 표시한다. 해당 API 프로젝트에 사용량·예산 관리도 설정한다.
4. Actions와 `GITHUB_TOKEN`의 PR 리뷰 작성이 조직 정책상 허용되는지 확인한다.
5. 결함을 일부러 넣은 작은 `develop` 대상 PR로 `validation`, `review`, `post-review` 성공과 해당 줄의 리뷰 스레드 생성을 확인한다. 스레드를 해결하지 않고 새 커밋을 올리면 같은 지적이 중복 게시되지 않아야 한다.

## 동작과 제한

- [리뷰 스킬](../../.agents/skills/pr-review/SKILL.md)을 프롬프트에서 명시적으로 호출한다. 변경 동작을 호출자·소비자·테스트 assertion까지 추적하고 정상·경계·실패 시나리오를 검토한다. 지적 기준과 P0~P3 정의는 Codex 내장 리뷰 rubric을 Hops 문서(SEC ID·프론트엔드 규칙·공통 계약)에 맞춰 옮긴 것이다.
- 모델은 [출력 스키마](review-schema.json)에 맞는 JSON을 반환하고, 게시 스크립트가 지적마다 head 커밋의 해당 줄에 `COMMENT` 리뷰 스레드를 연다. 스레드에는 심각도·근거·수정 방향·회귀 테스트가 들어간다. diff hunk 밖 줄은 파일 단위 스레드로, PR 변경 파일 밖의 지적은 리뷰 본문으로 남긴다. 요약 댓글을 따로 만들거나 갱신하지 않는다.
- 스레드 해결은 팀원이 한다. 봇은 스레드를 resolve하지 않는다. 해결되지 않은 봇 스레드와 같은 지적(파일과 제목 기준)은 다시 게시하지 않고, 스레드가 해결된 뒤 같은 결함이 다시 발견되면 새 스레드를 연다.
- 리뷰는 새 줄 단위 스레드, 스레드로 달 수 없는 지적, 검토 미완료, CI 검사 실패 중 하나가 있을 때만 만든다. 새로 알릴 내용이 없으면 PR에 아무것도 게시하지 않는다. 신뢰도 50% 미만 지적과 확인한 동작은 Actions 실행 요약에만 남긴다. 일부 파일을 읽지 못한 리뷰는 본문에 미확인 파일 목록을 표시한다. 형식이 맞지 않는 출력은 원문과 안내를 리뷰 본문으로 게시한다. 스키마가 없는 base에서는 이전 Markdown 출력으로 실행한다.
- 해결되지 않은 봇 스레드를 데이터로 모델에 전달해 같은 결함에 같은 제목을 쓰게 한다. 스레드 조회 토큰은 이 step에만 전달하며 Codex 실행 사용자에게 넘기지 않는다. 최근 100개 스레드만 조회한다.
- 기본은 `fast` 모드(reasoning effort `medium`, 최대 15분)다. PR에 `deep-review` 라벨을 붙이면 즉시 다시 실행되며, 라벨이 있는 동안 모든 이벤트가 `deep` 모드(effort `high`, 최대 30분)로 실행된다. 다른 라벨 이벤트는 실행 중인 리뷰를 취소하지 않는다. deep 모드는 API 비용이 더 크다.
- 모델 job은 PR head의 Git 객체를 확보한 뒤 base로 전환한다. PR에서 바꾼 스킬·지침을 현재 리뷰 지침으로 실행하지 않는다. 모델은 `git diff`와 `git show`로 코드를 읽고 PR 코드를 실행하지 않는다.
- 별도 `validation` job은 API 키와 운영 Secret 없이 PR head의 코드·테스트를 실행한다. 같은 runner에서 모델을 실행하지 않으며 Git 자격 증명을 checkout에 보존하지 않는다. 검증 runner에는 프로젝트 설치·테스트를 위한 네트워크 접근이 있고 실행 후 폐기된다. PR은 자체 검증 코드도 변경할 수 있으므로 결과를 보안 증명으로 취급하지 않는다.
- 검증 보고서의 SHA·명령·성공/실패/skip과 제한된 출력 요약을 artifact로 3일 보관해 모델에 데이터로 전달한다. 실패·skip된 명령이 있거나 보고서가 유효하지 않을 때만 리뷰 본문에 통과한 명령 수, 실패·skip된 명령, Actions 링크를 붙인다. 모델은 검증 결과를 재요약하거나 결함과 무관한 테스트 제안을 쓰지 않는다. 테스트 실패·보고서 누락·모델 실패를 검토 통과로 표현하지 않으며, validation 실패 시에도 리뷰와 결과 게시를 시도한다.
- 정확한 공통 조상(merge-base)과 필요한 과거 파일을 오프라인으로 조회하기 위해 현재는 전체 Git 이력을 가져온다. 저장소가 커지면 base/head의 이력을 공통 조상까지 확보하는 방식으로 최적화할 수 있다.
- 분석은 `openai/codex-action` v1.13(커밋 SHA 고정)의 `permission-profile: ':read-only'`, `unprivileged-user`, `contents: read`·`pull-requests: read`로 실행한다. 액션 버전을 올릴 때는 고정 SHA가 해당 태그의 실제 커밋인지 확인한다. 공식 Action 예제를 따라 sudo 권한이 없는 별도 `codex-review` 사용자에게 저장소 읽기 권한을 주고, Git 저장소 신뢰 경로를 해당 사용자의 설정에 등록한다. 리뷰 쓰기 권한은 별도 runner에만 부여한다. 모델 출력은 명령으로 실행하지 않고 API의 문자열 인자로 전달한다. `pull_request_target`은 사용하지 않는다.
- 일부 Ubuntu runner에서 `drop-sudo`의 DBus socket 접근 제한이 실패해 모델 실행 전 종료되는 문제를 피한다. 리뷰 사용자에게 sudo가 허용되지 않는지와 head Git 객체를 읽을 수 있는지 실행 전 확인한다. 사용자 전환으로 환경 변수가 사라져도 검토할 커밋을 알 수 있도록 base 프롬프트 사본에 base/head SHA만 덧붙인다.
- 같은 PR의 이전 실행을 취소한다. 게시 직전에 head·대상 브랜치·열린 상태를 다시 확인하며 오래된 결과를 버린다. 대상 브랜치의 커밋만 전진했으면 실행 당시의 head 커밋에 리뷰를 게시한다. 리뷰는 `COMMENT`로만 게시하며 Approve·Request changes와 자동 병합은 수행하지 않는다.
- 모드별 분석 시간과 reasoning effort는 위와 같다. 모델은 기존 저장소 Variable을 사용한다. validation은 최대 25분이다. 시간 제한은 금액 상한이 아니며 모델 추론량·API 비용과 Actions 시간이 증가할 수 있다. 취소된 실행은 리뷰를 게시하지 않는다.
- 리뷰는 이벤트의 base 지침을 사용하므로 스킬·규칙 변경은 병합 후 새 이벤트의 리뷰부터 적용된다. 이전 이벤트를 재실행하면 이전 base SHA와 지침을 유지한다.
- 자동 리뷰는 팀원 승인과 CI 테스트를 대체하지 않는다. 정적 검토로 발견하지 못하는 실행 오류가 있을 수 있다.

## 검증

변경 경로에 따라 다음 검사를 선택한다. 공통 계약(`contracts/`, `specs/contracts/`) 변경은 세 소비자 영역을 모두 검증한다. 문서만 변경한 경우 실행 검사를 추가하지 않고 diff 검사만 했다고 표시한다.

- 공통: `git diff --check BASE...HEAD`.
- `.github/`, `.agents/`: Node 스크립트 회귀 테스트와 `actionlint` 문법·표현식 검사. ShellCheck는 실행하지 않는다.
- `frontend/`: `npm ci --ignore-scripts` 후 typecheck·lint·format:check·test·build.
- `ai/`: Python 3.13, 고정 uv와 lockfile로 의존성을 설치하고 Ruff lint·format·pytest를 실행한다. 별도 DSN이 필요한 DB 테스트는 skip될 수 있으며 pytest 출력에서 확인한다.
- `backend/`: Java 21, 임시 pgvector PostgreSQL의 `hops_test` DB와 Redis 8.2의 테스트 전용 15번 DB를 사용해 Gradle `test`를 실행한다. PostgreSQL은 검증 스크립트가 생성·폐기하며, Redis는 `validation` job의 서비스 컨테이너로 준비 상태(`redis-cli ping`)를 확인한 뒤 실행하고 job 종료 시 폐기한다. Spring 테스트는 runner에서 실행하므로 Redis의 6379 포트를 연결하고 `SPRING_DATA_REDIS_HOST=localhost`, `SPRING_DATA_REDIS_PORT=6379`, `SPRING_DATA_REDIS_DATABASE=15`를 주입한다. 운영 DB와 Secret은 사용하지 않는다.

의존성 설치나 DB 준비가 실패하면 해당 후속 검사는 skip으로 기록하고 다른 영역의 검증은 계속한다. 이미 base에 있던 실패인지 이번 변경의 회귀인지는 결과만으로 확정하지 않는다. 명령별 실행 제한은 4분이며 전체 job 제한과 함께 적용된다.

로컬 자동화 로직 검증: `node --test .github/scripts/post-codex-review.test.cjs .github/scripts/run-review-validation.test.cjs`

실제 OpenAI 호출·GitHub 리뷰 스레드 게시·권한 검증은 Secret/Variable 설정 후 PR에서 별도로 확인한다. 새 스킬·프롬프트는 base에 병합된 이후의 이벤트부터 적용되므로 도입 PR 자체의 모델 리뷰는 이전 스킬을 사용한다. 실제 검증 job의 실행 결과는 해당 PR에서도 확인할 수 있다. 설정이 없는 로컬 검사 결과를 실제 Actions 통과로 보고하지 않는다.

공식 문서: [Codex GitHub Action](https://learn.chatgpt.com/docs/github-action), [Codex Skills](https://learn.chatgpt.com/docs/build-skills).
