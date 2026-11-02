const MARKER = '<!-- hops-codex-pr-review -->';
const VERDICTS = {
  correct: '차단할 결함 없음',
  incorrect: '수정이 필요한 결함 있음',
  incomplete: '검토 미완료',
};
// 확신이 이보다 낮은 지적은 접어서 표시한다.
const LOW_CONFIDENCE = 0.5;

// 모델이 만든 한 줄 값이 Markdown 구조를 깨지 않도록 정리한다.
function inline(value, limit = 300) {
  return String(value ?? '').replace(/[\r\n]+/g, ' ').replace(/`/g, "'").slice(0, limit);
}

function parseReview(raw) {
  try {
    const review = JSON.parse(raw);
    if (review && Array.isArray(review.findings) && typeof review.overall_correctness === 'string') {
      return review;
    }
  } catch {}
  return null;
}

function renderFinding(finding, { repoUrl, headSha }) {
  const priority = [0, 1, 2, 3].includes(finding.priority) ? finding.priority : 3;
  const path = inline(finding.path, 200);
  const line = Number.isInteger(finding.line) && finding.line > 0 ? finding.line : null;
  const end = Number.isInteger(finding.end_line) && finding.end_line > line ? finding.end_line : null;
  const anchor = line ? `#L${line}${end ? `-L${end}` : ''}` : '';
  const location = `[\`${path}${line ? `:${line}` : ''}\`](${repoUrl}/blob/${headSha}/${encodeURI(path)}${anchor})`;
  const confidence = typeof finding.confidence === 'number' ?
    ` · 신뢰도 ${Math.round(Math.min(Math.max(finding.confidence, 0), 1) * 100)}%` : '';
  return `#### [P${priority}] ${inline(finding.title, 120)}\n${location}${confidence}\n\n` +
    `${String(finding.body ?? '').trim()}\n\n` +
    (finding.fix ? `- **수정 방향:** ${inline(finding.fix, 1000)}\n` : '') +
    (finding.test ? `- **회귀 테스트:** ${inline(finding.test, 1000)}\n` : '');
}

function renderReview(review, { repoUrl, headSha }) {
  const findings = [...review.findings].sort((a, b) =>
    (a.priority ?? 3) - (b.priority ?? 3) || (b.confidence ?? 0) - (a.confidence ?? 0));
  const confident = findings.filter(item => (item.confidence ?? 1) >= LOW_CONFIDENCE);
  const uncertain = findings.filter(item => (item.confidence ?? 1) < LOW_CONFIDENCE);
  const unread = Array.isArray(review.unread_files) ? review.unread_files : [];
  const covered = Array.isArray(review.covered_files) ? review.covered_files : [];
  const options = { repoUrl, headSha };
  let body = `**판정:** ${VERDICTS[review.overall_correctness] ?? inline(review.overall_correctness)}` +
    ` (${review.mode === 'deep' ? 'deep' : 'fast'} 모드)\n\n${inline(review.overall_explanation, 1000)}\n\n`;
  body += `**검토 범위:** 파일 ${covered.length}개 확인`;
  if (review.complete === false || unread.length) {
    body += `, **${unread.length}개 미확인**\n\n> 일부 파일을 읽지 못했습니다. 아래 결과는 전체 변경에 대한 검토가 아닙니다.\n` +
      unread.slice(0, 30).map(file => `> - \`${inline(file, 200)}\``).join('\n') + '\n\n';
  } else {
    body += '\n\n';
  }
  body += `### 발견 사항 (${confident.length})\n\n`;
  body += confident.length ? confident.map(item => renderFinding(item, options)).join('\n') :
    '지적 기준을 만족하는 결함을 찾지 못했습니다.\n';
  if (uncertain.length) {
    body += `\n<details><summary>확신이 낮은 지적 (${uncertain.length})</summary>\n\n` +
      uncertain.map(item => renderFinding(item, options)).join('\n') + '\n</details>\n';
  }
  if (review.resolved_previous?.length) {
    body += '\n**이전 리뷰에서 해결된 지적:** ' +
      review.resolved_previous.map(title => inline(title, 120)).join(' · ') + '\n';
  }
  if (review.verified_behaviors?.length) {
    body += `\n<details><summary>확인한 동작 (${review.verified_behaviors.length})</summary>\n\n` +
      review.verified_behaviors.map(item =>
        `- **${inline(item.scenario, 300)}** — ${inline(item.evidence, 800)}`).join('\n') +
      '\n</details>\n';
  }
  return body;
}

async function postReview({ github, context, core, review, appendix = '' }) {
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
  if (current.state !== 'open' || current.draft || current.base.ref !== expected.base.ref ||
      current.head.sha !== expected.head.sha) {
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
  const parsed = parseReview(review);
  const repoUrl = `${context.serverUrl ?? 'https://github.com'}/${repo.owner}/${repo.repo}`;
  const content = (parsed ? renderReview(parsed, { repoUrl, headSha: expected.head.sha }) :
    review.trim().startsWith('{') ?
      '> 모델 출력이 리뷰 형식과 맞지 않아 원문을 표시합니다.\n\n' + review : review) + appendix;
  // UTF-16 길이 한도를 지키면서 경계의 surrogate pair를 분리하지 않는다.
  const excerpt = content.slice(0, 48000).replace(/[\uD800-\uDBFF]$/, '');
  const truncated = excerpt.length < content.length;
  const body = `${MARKER}\n## Codex 코드 리뷰\n\n대상 커밋: \`${expected.head.sha}\`\n` +
    `검토 기준: \`${expected.base.ref}\` / \`${expected.base.sha}\`\n\n` +
    excerpt +
    (truncated ? '\n\n출력 길이 제한으로 일부 결과가 생략되었습니다.' : '') +
    '\n\n---\nAI 검토 결과이며 팀원 리뷰 승인을 대신하지 않습니다.';
  if (previous) {
    await github.rest.issues.updateComment({ ...repo, comment_id: previous.id, body });
  } else {
    await github.rest.issues.createComment({ ...repo, issue_number: expected.number, body });
  }
}

module.exports = postReview;
module.exports.renderReview = renderReview;
module.exports.parseReview = parseReview;
