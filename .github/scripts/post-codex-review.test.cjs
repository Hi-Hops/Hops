const { test } = require('node:test');
const assert = require('node:assert/strict');
const postReview = require('./post-codex-review.cjs');
const { commentableLines, findingKey } = postReview;

// backend/A.java: 8~14줄이 한 hunk(11·12 추가), 30~31줄이 다른 hunk.
const FILES = [
  { filename: 'backend/A.java', patch: '@@ -8,5 +8,7 @@ class A {\n ctx\n ctx\n ctx\n+add\n+add\n ctx\n ctx\n@@ -40,2 +30,2 @@\n ctx\n-old\n+new' },
  { filename: 'frontend/b.ts', patch: '@@ -1,3 +1,6 @@\n a\n+b\n+c\n+d\n+e\n f' },
  { filename: 'image.png' },
];

function setup({ current = {}, review = '검토 결과', baseRef = 'develop', files = FILES, threads = [],
  failReview = false, failFileComment = false, failThreads = false, appendix } = {}) {
  const calls = [];
  const expected = { number: 42, head: { sha: 'abc' }, base: { ref: baseRef, sha: 'def' } };
  const context = { repo: { owner: 'example', repo: 'hops' }, payload: { pull_request: expected } };
  const github = {
    rest: {
      pulls: {
        get: async () => ({ data: { ...expected, state: 'open', draft: false, ...current } }),
        listFiles: () => {},
        createReview: async args => {
          if (failReview && args.comments?.length) throw new Error('Validation Failed');
          calls.push({ type: 'review', ...args });
        },
        createReviewComment: async args => {
          if (failFileComment) throw new Error('Unprocessable');
          calls.push({ type: 'file', ...args });
        },
      },
    },
    paginate: async (method, args) => {
      assert.equal(method, github.rest.pulls.listFiles);
      assert.deepEqual(args, { owner: 'example', repo: 'hops', pull_number: 42, per_page: 100 });
      return files;
    },
    graphql: async (query, variables) => {
      if (failThreads) throw new Error('Something went wrong while executing your query');
      assert.doesNotMatch(query, /mutation/);
      assert.deepEqual(variables, { owner: 'example', repo: 'hops', number: 42 });
      return { repository: { pullRequest: { reviewThreads: { nodes: threads } } } };
    },
  };
  const summaries = [];
  const core = {
    info() {},
    warning: message => calls.push({ type: 'warning', message }),
    summary: { addRaw(text) { summaries.push(text); return this; }, async write() {} },
  };
  const reviewCall = () => calls.find(call => call.type === 'review');
  return { calls, summaries, reviewCall,
    run: () => postReview({ github, context, core, review, appendix }) };
}

function finding(overrides = {}) {
  return { priority: 1, title: '권한 확인 누락', path: 'backend/A.java', line: 10, end_line: 12,
    body: '다른 사용자의 문의를 조회할 수 있다.', fix: '소유권 검사', test: '타인 ID 요청 시 403',
    confidence: 0.9, ...overrides };
}

function structured(overrides = {}) {
  return JSON.stringify({
    mode: 'fast', base_sha: 'def', head_sha: 'abc', complete: true,
    covered_files: ['backend/A.java', 'frontend/b.ts'], unread_files: [],
    findings: [
      finding({ priority: 2, title: '일반 결함', path: 'frontend/b.ts', line: 5, end_line: 5, confidence: 0.8 }),
      finding(),
      finding({ title: '불확실한 추정', line: 20, end_line: 20, confidence: 0.3 }),
    ],
    verified_behaviors: [{ scenario: '정상 조회', evidence: 'A.java:8 소유권 확인' }],
    overall_correctness: 'incorrect',
    overall_explanation: 'P1 결함이 있다.',
    ...overrides,
  });
}

function thread(title, path, extra = {}) {
  return { isResolved: false, comments: { nodes: [{ author: { login: 'github-actions' },
    body: `<!-- hops-codex-finding:${findingKey({ title, path })} -->\n본문` }] }, ...extra };
}

const positions = review => review.comments.map(({ body, ...position }) => position);

test('unified diff에서 head 쪽 줄 번호와 hunk를 계산한다', () => {
  const lines = commentableLines(FILES[0].patch);
  assert.deepEqual([...lines.keys()], [8, 9, 10, 11, 12, 13, 14, 30, 31]);
  assert.equal(lines.get(14), 0);
  assert.equal(lines.get(31), 1);
  assert.equal(commentableLines(undefined).size, 0);
});

test('지적을 해당 코드 줄의 COMMENT 리뷰 스레드로 게시한다', async () => {
  const { run, reviewCall } = setup({ review: structured() });
  await run();
  const review = reviewCall();
  assert.equal(review.event, 'COMMENT');
  assert.equal(review.commit_id, 'abc');
  assert.deepEqual(positions(review), [
    { path: 'backend/A.java', start_line: 10, start_side: 'RIGHT', line: 12, side: 'RIGHT' },
    { path: 'frontend/b.ts', line: 5, side: 'RIGHT' },
  ]);
  assert.match(review.comments[0].body, /^<!-- hops-codex-finding:[a-f0-9]{12} -->\n\*\*\[P1\] 권한 확인 누락\*\*/);
  assert.match(review.comments[0].body, /수정 방향:\*\* 소유권 검사/);
  assert.match(review.body, /수정이 필요한 결함 있음/);
  assert.match(review.body, /새 스레드 2개/);
  assert.match(review.body, /팀원 리뷰 승인을 대신하지 않습니다/);
  assert.doesNotMatch(review.body + review.comments.map(c => c.body).join(), /불확실한 추정/);
});

test('확신이 낮은 지적과 확인한 동작은 Actions 실행 요약에만 남긴다', async () => {
  const { run, summaries } = setup({ review: structured() });
  await run();
  assert.match(summaries.join(), /불확실한 추정[\s\S]*신뢰도 30%/);
  assert.match(summaries.join(), /확인한 동작: \*\*정상 조회\*\*/);
});

test('범위가 hunk를 넘으면 같은 hunk 안까지만 표시한다', async () => {
  const { run, reviewCall } = setup({ review: structured({ findings: [finding({ line: 13, end_line: 31 })] }) });
  await run();
  assert.deepEqual(positions(reviewCall()), [
    { path: 'backend/A.java', start_line: 13, start_side: 'RIGHT', line: 14, side: 'RIGHT' },
  ]);
});

test('hunk 밖 줄은 파일 단위 스레드로, PR 밖 파일은 리뷰 본문에 남긴다', async () => {
  const { run, calls, reviewCall } = setup({ review: structured({ findings: [
    finding({ title: 'diff 밖 호출자', line: 100, end_line: 100 }),
    finding({ title: '다른 파일', path: 'backend/B.java', line: 3, end_line: 3 }),
  ] }) });
  await run();
  const file = calls.find(call => call.type === 'file');
  assert.equal(file.subject_type, 'file');
  assert.equal(file.path, 'backend/A.java');
  assert.match(file.body, /diff 밖 호출자/);
  assert.match(reviewCall().body, /코드에 달지 못한 지적[\s\S]*다른 파일[\s\S]*소유권 검사/);
  assert.match(reviewCall().body, /새 스레드 1개/);
  assert.deepEqual(reviewCall().comments, []);
});

test('파일 단위 스레드 생성이 실패하면 리뷰 본문에 남긴다', async () => {
  const { run, calls, reviewCall } = setup({ failFileComment: true,
    review: structured({ findings: [finding({ title: 'diff 밖 호출자', line: 100, end_line: 100 })] }) });
  await run();
  assert.ok(calls.some(call => call.type === 'warning' && /Unprocessable/.test(call.message)));
  assert.match(reviewCall().body, /코드에 달지 못한 지적[\s\S]*diff 밖 호출자/);
});

test('같은 지적의 스레드가 해결되기 전에는 새 스레드를 만들지 않는다', async () => {
  const human = thread('일반 결함', 'frontend/b.ts');
  human.comments.nodes[0].author.login = 'teammate';
  const { run, reviewCall } = setup({ review: structured(),
    threads: [thread('권한 확인 누락', 'backend/A.java'), human] });
  await run();
  assert.deepEqual(reviewCall().comments.map(comment => comment.path), ['frontend/b.ts']);
  assert.match(reviewCall().body, /새 스레드 1개 · 이미 열린 스레드와 같은 지적 1개 생략/);
});

test('해결된 스레드의 지적이 다시 발견되면 새 스레드를 만든다', async () => {
  const { run, reviewCall } = setup({ review: structured(),
    threads: [thread('권한 확인 누락', 'backend/A.java', { isResolved: true })] });
  await run();
  assert.equal(reviewCall().comments.length, 2);
});

test('스레드 조회가 실패해도 경고 후 지적과 CI 결과를 게시한다', async () => {
  const { run, calls, reviewCall } = setup({ failThreads: true, review: structured(),
    appendix: '\n\n**CI 실행 검증**\n- 실패' });
  await run();
  assert.ok(calls.some(call => call.type === 'warning' && /스레드 조회에 실패/.test(call.message)));
  assert.equal(reviewCall().comments.length, 2);
  assert.match(reviewCall().body, /새 스레드 2개[\s\S]*CI 실행 검증/);
});

test('새로 알릴 내용이 없으면 리뷰를 만들지 않고 스레드를 resolve하지 않는다', async () => {
  const { run, calls } = setup({ review: structured({ findings: [finding()] }),
    threads: [thread('권한 확인 누락', 'backend/A.java'), thread('사라진 지적', 'backend/A.java')] });
  await run();
  assert.equal(calls.length, 0);
});

test('결함이 없으면 리뷰를 만들지 않는다', async () => {
  const { run, calls } = setup({ review: structured({ findings: [], overall_correctness: 'correct' }) });
  await run();
  assert.equal(calls.length, 0);
});

test('일부 파일을 읽지 못하면 지적이 없어도 미완료를 알린다', async () => {
  const { run, reviewCall } = setup({ review: structured({ complete: false, unread_files: ['ai/src/big.py'],
    findings: [], overall_correctness: 'incomplete' }) });
  await run();
  assert.match(reviewCall().body, /검토 미완료/);
  assert.match(reviewCall().body, /`ai\/src\/big\.py`/);
});

test('CI 실패 appendix가 있으면 지적이 없어도 리뷰 본문에 붙인다', async () => {
  const { run, reviewCall } = setup({ appendix: '\n\n## CI 실행 검증\n- 실패',
    review: structured({ findings: [], overall_correctness: 'correct' }) });
  await run();
  assert.match(reviewCall().body, /새 스레드 0개[\s\S]*## CI 실행 검증/);
});

test('줄 단위 스레드 게시가 실패하면 경고하고 리뷰 본문에 지적을 남긴다', async () => {
  const { run, calls, reviewCall } = setup({ failReview: true, review: structured() });
  await run();
  assert.ok(calls.some(call => call.type === 'warning' && /Validation Failed/.test(call.message)));
  assert.equal(reviewCall().comments, undefined);
  assert.match(reviewCall().body, /코드에 달지 못한 지적[\s\S]*권한 확인 누락/);
});

test('모델 값의 줄바꿈과 백틱이 제목·경로 구조를 깨지 않는다', async () => {
  const { run, reviewCall } = setup({ review: structured({ findings: [
    finding({ priority: 9, title: '제목\n## 가짜 헤더', path: 'a`b.ts', line: -1, end_line: 0, confidence: 2 }),
  ] }) });
  await run();
  assert.match(reviewCall().body, /#### \[P3\] 제목 ## 가짜 헤더/);
  assert.match(reviewCall().body, /\[`a'b\.ts`\]/);
  assert.match(reviewCall().body, /신뢰도 100%/);
});

for (const [name, current] of [
  ['head 변경', { head: { sha: 'new' } }],
  ['대상 브랜치 변경', { base: { ref: 'main', sha: 'def' } }],
  ['닫힌 PR', { state: 'closed' }],
  ['Draft 전환', { draft: true }],
]) {
  test(`${name} 시 오래된 리뷰를 게시하지 않는다`, async () => {
    const { run, calls } = setup({ current, review: structured() });
    await run();
    assert.equal(calls.length, 0);
  });
}

test('대상 브랜치의 커밋만 전진하면 원래 head 커밋에 게시한다', async () => {
  const { run, reviewCall } = setup({ current: { base: { ref: 'develop', sha: 'new' } }, review: structured() });
  await run();
  assert.equal(reviewCall().commit_id, 'abc');
});

test('빈 결과를 게시하지 않는다', async () => {
  const { run, calls } = setup({ review: '  \n' });
  await run();
  assert.equal(calls.length, 0);
});

test('JSON이 아닌 출력은 리뷰 본문으로 게시하고 형식 오류를 안내한다', async () => {
  const plain = setup({ review: '기존 Markdown 리뷰', appendix: '\n\nCI' });
  await plain.run();
  assert.match(plain.reviewCall().body, /^기존 Markdown 리뷰\n\nCI/);
  const broken = setup({ review: '{"unexpected": true}' });
  await broken.run();
  assert.match(broken.reviewCall().body, /리뷰 형식과 맞지 않아[\s\S]*"unexpected"/);
});

test('모델 출력에 명령 문자열이 있어도 리뷰 문자열로만 전달한다', async () => {
  const review = '${{ secrets.EXAMPLE }}\n$(echo fake)\n`example`';
  const { run, reviewCall } = setup({ review });
  await run();
  assert.ok(reviewCall().body.includes(review));
});

test('긴 출력은 한도 이내로 제한하고 이모지 surrogate pair를 자르지 않는다', async () => {
  const { run, reviewCall } = setup({ review: '가'.repeat(59999) + '😀후속 내용' });
  await run();
  assert.ok(reviewCall().body.isWellFormed());
  assert.ok(reviewCall().body.length < 65536);
  assert.match(reviewCall().body, /일부 결과가 생략/);
});

test('워크플로가 appendix 지원 여부를 확인할 수 있다', () => {
  assert.equal(typeof postReview.renderReview, 'function');
});
