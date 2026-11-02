# develop PR 자동 리뷰

`develop` 대상의 같은 저장소 PR을 생성·재개하거나 커밋을 추가하고 Draft를 해제하면 [워크플로](../workflows/codex-review.yml)가 실행된다. 기존 PR의 base만 `develop`으로 바꾸면 실행되지 않으며 새 커밋 추가나 PR 재개가 필요하다. Draft·fork·봇 작성 PR과 `[bot]` 계정이 발생시킨 실행은 제외한다. Codex Action의 기본 호출자 제한에 따라 저장소 쓰기 권한이 있는 사용자의 실행을 대상으로 한다.

## 활성화

1. 이 변경을 팀원 리뷰 후 `develop`에 병합한다. 리뷰는 base 버전의 스킬과 게시 스크립트를 사용하므로 이 설정을 처음 도입하는 PR 자체에서는 안내 메시지를 남기고 모델 실행·댓글 게시를 건너뛴다.
2. 저장소 Settings → Secrets and variables → Actions에서 `OPENAI_API_KEY`를 Secret으로 등록한다. 키 값을 파일이나 PR에 남기지 않는다.
3. 팀에서 사용할 모델과 예산을 정하고 해당 모델 ID를 `CODEX_REVIEW_MODEL` 저장소 Variable로 등록한다. 키나 모델이 미설정이면 notice를 남기고 모델 실행·게시를 건너뛴다. 해당 API 프로젝트에 사용량·예산 관리도 설정한다.
4. Actions와 `GITHUB_TOKEN`의 PR 댓글 쓰기가 조직 정책상 허용되는지 확인한다.
5. 작은 `develop` 대상 PR로 `review`, `post-review` 성공과 댓글 생성을 확인한다. 새 커밋에서는 기존 댓글이 갱신되어야 한다.

## 동작과 제한

- [리뷰 스킬](../../.agents/skills/pr-review/SKILL.md)을 프롬프트에서 명시적으로 호출한다. 한국어로 변경 코드의 결함·근거·위치와 검토 한계를 보고한다.
- PR head를 가져와 Git 객체를 확보한 뒤 base로 전환한다. PR에서 바꾼 스킬·지침을 현재 리뷰 지침으로 실행하지 않는다. PR 코드는 `git diff`와 `git show`로 읽으며 빌드·테스트·설치 스크립트를 실행하지 않는다.
- 정확한 공통 조상(merge-base)과 필요한 과거 파일을 오프라인으로 조회하기 위해 현재는 전체 Git 이력을 가져온다. 저장소가 커지면 base/head의 이력을 공통 조상까지 확보하는 방식으로 최적화할 수 있다.
- 분석은 `read-only`, `unprivileged-user`, `contents: read`로 실행한다. 공식 Action 예제를 따라 sudo 권한이 없는 별도 `codex-review` 사용자에게 저장소 읽기 권한을 주고, Git 저장소 신뢰 경로를 해당 사용자의 설정에 등록한다. 댓글 쓰기 권한은 별도 runner에만 부여한다. 모델 출력은 명령으로 실행하지 않고 API의 문자열 인자로 전달한다. `pull_request_target`은 사용하지 않는다.
- 일부 Ubuntu runner에서 `drop-sudo`의 DBus socket 접근 제한이 실패해 모델 실행 전 종료되는 문제를 피한다. 리뷰 사용자에게 sudo가 허용되지 않는지와 head Git 객체를 읽을 수 있는지 실행 전 확인한다. 사용자 전환으로 환경 변수가 사라져도 검토할 커밋을 알 수 있도록 base 프롬프트 사본에 base/head SHA만 덧붙인다.
- 같은 PR의 이전 실행을 취소한다. 게시 직전에 head·대상 브랜치·열린 상태를 다시 확인하며 오래된 결과를 버린다. 대상 브랜치의 커밋만 전진했으면 리뷰를 게시하고 댓글에 실행 당시의 base SHA를 표시한다. 봇의 전용 마커가 있는 댓글 하나를 갱신한다. 줄별 Review·Approve·Request changes와 자동 병합은 수행하지 않는다.
- 분석 작업은 최대 15분, reasoning effort는 `low`다. 시간 제한은 금액 상한이 아니며 모델 호출에는 API 비용이 든다. 실패·취소 시 새 리뷰를 게시하지 않으며 이전 댓글의 대상 커밋으로 최신 여부를 확인한다.
- 리뷰는 이벤트의 base 지침을 사용하므로 스킬·규칙 변경은 병합 후 새 이벤트의 리뷰부터 적용된다. 이전 이벤트를 재실행하면 이전 base SHA와 지침을 유지한다.
- 자동 리뷰는 팀원 승인과 CI 테스트를 대체하지 않는다. 정적 검토로 발견하지 못하는 실행 오류가 있을 수 있다.

## 검증

로컬 게시 로직 검증: `node --test .github/scripts/post-codex-review.test.cjs`

실제 OpenAI 호출·GitHub 댓글 게시·권한 검증은 Secret/Variable 설정 후 PR에서 별도로 확인한다. 설정이 없는 로컬 검사 결과를 실제 Actions 통과로 보고하지 않는다.

공식 문서: [Codex GitHub Action](https://learn.chatgpt.com/docs/github-action), [Codex Skills](https://learn.chatgpt.com/docs/build-skills).
