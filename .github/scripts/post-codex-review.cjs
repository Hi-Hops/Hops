const MARKER = '<!-- hops-codex-pr-review -->';

module.exports = async function postReview({ github, context, core, review }) {
  if (!review?.trim()) {
    core.info('빈 리뷰는 게시하지 않습니다.');
    return;
  }
  const expected = context.payload.pull_request;
  const repo = context.repo;
  const { data: current } = await github.rest.pulls.get({
    ...repo,
    pull_number: expected.number,
  });
  if (current.state !== 'open' || current.draft || current.base.ref !== 'develop' ||
      current.head.sha !== expected.head.sha || current.base.sha !== expected.base.sha) {
    core.info('PR 상태 또는 커밋이 바뀌어 오래된 리뷰를 게시하지 않습니다.');
    return;
  }
  const comments = await github.paginate(github.rest.issues.listComments, {
    ...repo,
    issue_number: expected.number,
    per_page: 100,
  });
  const previous = comments.find(comment =>
    comment.user?.login === 'github-actions[bot]' && comment.body?.startsWith(MARKER));
  const truncated = review.length > 48000;
  const body = `${MARKER}\n## Codex 코드 리뷰\n\n대상 커밋: \`${expected.head.sha}\`\n\n` +
    review.slice(0, 48000) +
    (truncated ? '\n\n출력 길이 제한으로 일부 결과가 생략되었습니다.' : '') +
    '\n\n---\nAI 검토 결과이며 팀원 리뷰 승인을 대신하지 않습니다.';
  if (previous) {
    await github.rest.issues.updateComment({ ...repo, comment_id: previous.id, body });
  } else {
    await github.rest.issues.createComment({ ...repo, issue_number: expected.number, body });
  }
};
