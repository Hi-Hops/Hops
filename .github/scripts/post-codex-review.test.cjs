const { test } = require('node:test');
const assert = require('node:assert/strict');
const postReview = require('./post-codex-review.cjs');

function setup({ current = {}, comments = [], review = '검토 결과' } = {}) {
  const calls = [];
  const expected = { number: 42, head: { sha: 'abc' }, base: { sha: 'def' } };
  const context = { repo: { owner: 'example', repo: 'hops' }, payload: { pull_request: expected } };
  const github = {
    rest: {
      pulls: { get: async () => ({ data: {
        ...expected, state: 'open', draft: false, base: { ref: 'develop', sha: 'def' }, ...current,
      } }) },
      issues: {
        listComments: () => {},
        createComment: async args => calls.push({ type: 'create', ...args }),
        updateComment: async args => calls.push({ type: 'update', ...args }),
      },
    },
    paginate: async () => comments,
  };
  return { calls, run: () => postReview({ github, context, core: { info() {} }, review }) };
}

test('현재 커밋의 결과를 PR 댓글로 게시한다', async () => {
  const { run, calls } = setup();
  await run();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].type, 'create');
  assert.equal(calls[0].issue_number, 42);
  assert.match(calls[0].body, /abc/);
  assert.match(calls[0].body, /검토 결과/);
});

test('자체 봇 댓글만 갱신하고 일반 사용자의 마커는 무시한다', async () => {
  const marker = '<!-- hops-codex-pr-review -->';
  const { run, calls } = setup({ comments: [
    { id: 1, user: { login: 'contributor' }, body: marker },
    { id: 2, user: { login: 'github-actions[bot]' }, body: '다른 작업 댓글' },
    { id: 3, user: { login: 'github-actions[bot]' }, body: `${marker}\n이전 결과` },
  ] });
  await run();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].type, 'update');
  assert.equal(calls[0].comment_id, 3);
});

for (const [name, current] of [
  ['head 변경', { head: { sha: 'new' } }],
  ['base 커밋 변경', { base: { ref: 'develop', sha: 'new' } }],
  ['대상 브랜치 변경', { base: { ref: 'main', sha: 'def' } }],
  ['닫힌 PR', { state: 'closed' }],
  ['Draft 전환', { draft: true }],
]) {
  test(`${name} 시 오래된 리뷰를 게시하지 않는다`, async () => {
    const { run, calls } = setup({ current });
    await run();
    assert.equal(calls.length, 0);
  });
}

test('빈 결과를 게시하지 않는다', async () => {
  const { run, calls } = setup({ review: '  \n' });
  await run();
  assert.equal(calls.length, 0);
});

test('긴 출력은 댓글 한도 이내로 제한하고 생략 사실을 알린다', async () => {
  const { run, calls } = setup({ review: '가'.repeat(70000) });
  await run();
  assert.ok(calls[0].body.length < 65536);
  assert.match(calls[0].body, /일부 결과가 생략/);
});

test('모델 출력에 명령 문자열이 있어도 댓글 문자열로만 전달한다', async () => {
  const review = '${{ secrets.EXAMPLE }}\n$(echo fake)\n`example`';
  const { run, calls } = setup({ review });
  await run();
  assert.ok(calls[0].body.includes(review));
});
