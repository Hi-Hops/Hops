const { createHash } = require('node:crypto');

const FINDING_MARKER = /<!-- hops-codex-finding:([a-f0-9]{12}) -->/;
const VERDICTS = {
  correct: '차단할 결함 없음',
  incorrect: '수정이 필요한 결함 있음',
  incomplete: '검토 미완료',
};
// 확신이 이보다 낮은 지적은 PR에 게시하지 않고 Actions 실행 요약에만 남긴다.
const LOW_CONFIDENCE = 0.5;
const FOOTER = '\n\n---\nAI 검토 결과이며 팀원 리뷰 승인을 대신하지 않습니다.';

// 모델이 만든 한 줄 값이 Markdown 구조를 깨지 않도록 정리한다.
function inline(value, limit = 300) {
  return String(value ?? '').replace(/[\r\n]+/g, ' ').replace(/`/g, "'").slice(0, limit);
}

// UTF-16 길이 한도를 지키면서 경계의 surrogate pair를 분리하지 않는다.
function limit(text, max = 60000) {
  const excerpt = text.slice(0, max).replace(/[\uD800-\uDBFF]$/, '');
  return excerpt.length < text.length ? excerpt + '\n\n출력 길이 제한으로 일부 결과가 생략되었습니다.' : excerpt;
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

function normalizeFinding(finding) {
  const line = Number.isInteger(finding.line) && finding.line > 0 ? finding.line : null;
  return {
    ...finding,
    priority: [0, 1, 2, 3].includes(finding.priority) ? finding.priority : 3,
    path: inline(finding.path, 200),
    line,
    end_line: line && Number.isInteger(finding.end_line) && finding.end_line > line ? finding.end_line : null,
  };
}

// 제목이 같아도 파일이 다르면 다른 지적으로 본다. 줄 번호는 커밋마다 바뀌므로 제외한다.
function findingKey(finding) {
  return createHash('sha256').update(`${finding.path}\n${inline(finding.title, 120).trim()}`)
    .digest('hex').slice(0, 12);
}

// unified diff에서 head 쪽(RIGHT)에 댓글을 달 수 있는 줄 번호를 hunk별로 모은다.
function commentableLines(patch) {
  const lines = new Map();
  let hunk = -1;
  let next = 0;
  for (const text of String(patch ?? '').split('\n')) {
    const header = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(text);
    if (header) {
      hunk += 1;
      next = Number(header[1]);
    } else if (hunk >= 0 && !text.startsWith('-') && !text.startsWith('\\')) {
      lines.set(next, hunk);
      next += 1;
    }
  }
  return lines;
}

function locate(finding, patches) {
  const lines = patches.get(finding.path);
  if (!lines || !finding.line || !lines.has(finding.line)) return null;
  const hunk = lines.get(finding.line);
  if (finding.end_line) {
    let end = finding.line;
    while (end < finding.end_line && lines.get(end + 1) === hunk) end += 1;
    if (end > finding.line) return { start_line: finding.line, start_side: 'RIGHT', line: end, side: 'RIGHT' };
  }
  return { line: finding.line, side: 'RIGHT' };
}

function renderDetails(finding) {
  const confidence = typeof finding.confidence === 'number' ?
    `\n<sub>Codex · 신뢰도 ${Math.round(Math.min(Math.max(finding.confidence, 0), 1) * 100)}%</sub>\n` : '';
  return `${String(finding.body ?? '').trim()}\n\n` +
    (finding.fix ? `- **수정 방향:** ${inline(finding.fix, 1000)}\n` : '') +
    (finding.test ? `- **회귀 테스트:** ${inline(finding.test, 1000)}\n` : '') +
    confidence;
}

function renderThread(finding) {
  return `<!-- hops-codex-finding:${findingKey(finding)} -->\n` +
    `**[P${finding.priority}] ${inline(finding.title, 120)}**\n\n${renderDetails(finding)}`;
}

function locationLink(finding, { repoUrl, headSha }) {
  const anchor = finding.line ? `#L${finding.line}${finding.end_line ? `-L${finding.end_line}` : ''}` : '';
  return `[\`${finding.path}${finding.line ? `:${finding.line}` : ''}\`](${repoUrl}/blob/${headSha}/${encodeURI(finding.path)}${anchor})`;
}

// 리뷰 본문에는 이번 실행에서 새로 알릴 내용만 담는다.
function renderReviewBody(review, { repoUrl, headSha, threads, skipped, unplaced }) {
  const unread = Array.isArray(review.unread_files) ? review.unread_files : [];
  let body = `**Codex 리뷰:** ${VERDICTS[review.overall_correctness] ?? inline(review.overall_correctness)}` +
    ` (${review.mode === 'deep' ? 'deep' : 'fast'} 모드)\n\n${inline(review.overall_explanation, 1000)}\n\n` +
    `새 스레드 ${threads}개` + (skipped ? ` · 이미 열린 스레드와 같은 지적 ${skipped}개 생략` : '') + '\n';
  if (review.complete === false || unread.length) {
    body += `\n> 일부 파일을 읽지 못했습니다. 이 리뷰는 전체 변경에 대한 검토가 아닙니다.\n` +
      unread.slice(0, 30).map(file => `> - \`${inline(file, 200)}\``).join('\n') + '\n';
  }
  if (unplaced.length) {
    body += '\n**PR 변경 파일 밖이라 코드에 달지 못한 지적**\n\n' + unplaced.map(finding =>
      `#### [P${finding.priority}] ${inline(finding.title, 120)}\n` +
      `${locationLink(finding, { repoUrl, headSha })}\n\n${renderDetails(finding)}`).join('\n');
  }
  return body;
}

// 확신이 낮은 지적과 확인한 동작은 PR이 아니라 Actions 실행 요약에서 확인한다.
async function writeJobSummary(core, review, findings, options) {
  if (!core.summary?.addRaw) return;
  let text = `## Codex 리뷰\n\n판정: ${VERDICTS[review.overall_correctness] ?? inline(review.overall_correctness)}\n\n`;
  for (const finding of findings) {
    text += `- [P${finding.priority}] ${inline(finding.title, 120)} — ${locationLink(finding, options)}` +
      ` (신뢰도 ${Math.round((finding.confidence ?? 1) * 100)}%)\n`;
  }
  for (const item of review.verified_behaviors ?? []) {
    text += `\n- 확인한 동작: **${inline(item.scenario, 300)}** — ${inline(item.evidence, 800)}`;
  }
  await core.summary.addRaw(limit(text)).write();
}

const THREADS_QUERY = `query($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    pullRequest(number: $number) {
      reviewThreads(first: 100) {
        nodes { isResolved comments(first: 1) { nodes { author { login } body } } }
      }
    }
  }
}`;

// 봇이 열고 아직 해결되지 않은 스레드의 지적만 모은다. 해결된 스레드는 다시 지적할 수 있다.
async function openThreadKeys(github, repo, number) {
  const data = await github.graphql(THREADS_QUERY, { owner: repo.owner, repo: repo.repo, number });
  const keys = new Set();
  for (const thread of data.repository.pullRequest.reviewThreads.nodes ?? []) {
    const first = thread.comments.nodes[0];
    const key = FINDING_MARKER.exec(first?.body ?? '')?.[1];
    if (key && !thread.isResolved && first.author?.login?.startsWith('github-actions')) keys.add(key);
  }
  return keys;
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
  const target = { ...repo, pull_number: expected.number, commit_id: expected.head.sha, event: 'COMMENT' };
  const parsed = parseReview(review);
  if (!parsed) {
    const notice = review.trim().startsWith('{') ? '> 모델 출력이 리뷰 형식과 맞지 않아 원문을 표시합니다.\n\n' : '';
    await github.rest.pulls.createReview({ ...target, body: limit(notice + review + appendix) + FOOTER });
    return;
  }

  const options = { repoUrl: `${context.serverUrl ?? 'https://github.com'}/${repo.owner}/${repo.repo}`,
    headSha: expected.head.sha };
  const files = await github.paginate(github.rest.pulls.listFiles, {
    ...repo, pull_number: expected.number, per_page: 100,
  });
  const patches = new Map(files.map(file => [file.filename, commentableLines(file.patch)]));
  const findings = parsed.findings.map(normalizeFinding).sort((a, b) =>
    a.priority - b.priority || (b.confidence ?? 0) - (a.confidence ?? 0));
  await writeJobSummary(core, parsed, findings, options);

  // 스레드 조회가 실패해도 지적과 CI 결과는 게시한다. 이 경우 중복 게시를 막지 못할 수 있다.
  let open = new Set();
  try {
    open = await openThreadKeys(github, repo, expected.number);
  } catch (error) {
    core.warning(`기존 리뷰 스레드 조회에 실패해 중복 확인 없이 게시합니다: ${error.message}`);
  }
  const comments = [];
  const fileComments = [];
  const unplaced = [];
  let skipped = 0;
  for (const finding of findings.filter(item => (item.confidence ?? 1) >= LOW_CONFIDENCE)) {
    // 같은 지적의 스레드가 해결되기 전에는 새 스레드를 만들지 않는다.
    if (open.has(findingKey(finding))) {
      skipped += 1;
      continue;
    }
    const position = locate(finding, patches);
    if (position) comments.push({ path: finding.path, ...position, body: renderThread(finding), finding });
    else if (patches.has(finding.path)) fileComments.push(finding);
    else unplaced.push(finding);
  }

  // hunk 밖 줄은 파일 단위 스레드로 연다. 실패하면 리뷰 본문에 남긴다.
  let fileThreads = 0;
  for (const finding of fileComments) {
    try {
      await github.rest.pulls.createReviewComment({
        ...repo, pull_number: expected.number, commit_id: expected.head.sha,
        path: finding.path, subject_type: 'file', body: renderThread(finding),
      });
      fileThreads += 1;
    } catch (error) {
      core.warning(`파일 단위 스레드 생성 실패(${finding.path}): ${error.message}`);
      unplaced.push(finding);
    }
  }
  // 알릴 내용이 없으면 리뷰를 만들지 않는다. 결과는 Actions 실행 요약에서 확인한다.
  if (!comments.length && !unplaced.length && parsed.complete !== false && !appendix.trim()) {
    core.info(`새 줄 단위 스레드 없음. 파일 단위 스레드 ${fileThreads}개, 열린 스레드와 같은 지적 ${skipped}개 생략.`);
    return;
  }
  const bodyFor = (threads, moved) =>
    limit(renderReviewBody(parsed, { ...options, threads, skipped, unplaced: moved }) + appendix) + FOOTER;
  try {
    await github.rest.pulls.createReview({ ...target,
      comments: comments.map(({ finding, ...comment }) => comment),
      body: bodyFor(comments.length + fileThreads, unplaced) });
  } catch (error) {
    core.warning(`줄 단위 스레드 게시에 실패해 리뷰 본문에 지적을 남깁니다: ${error.message}`);
    await github.rest.pulls.createReview({ ...target,
      body: bodyFor(fileThreads, unplaced.concat(comments.map(comment => comment.finding))) });
  }
}

module.exports = postReview;
module.exports.parseReview = parseReview;
module.exports.commentableLines = commentableLines;
module.exports.findingKey = findingKey;
// 워크플로가 appendix 지원 여부를 확인하는 데 사용한다.
module.exports.renderReview = renderReviewBody;
