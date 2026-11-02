const { test } = require('node:test');
const assert = require('node:assert/strict');
const postReview = require('./post-codex-review.cjs');

function setup({ current = {}, comments = [], review = '검토 결과', baseRef = 'develop' } = {}) {
  const calls = [];
  const expected = { number: 42, head: { sha: 'abc' }, base: { ref: baseRef, sha: 'def' } };
  const context = { repo: { owner: 'example', repo: 'hops' }, payload: { pull_request: expected } };
  const github = {
    rest: {
      pulls: { get: async () => ({ data: {
        ...expected, state: 'open', draft: false, ...current,
      } }) },
      issues: {
        listComments: () => {},
        createComment: async args => calls.push({ type: 'create', ...args }),
        updateComment: async args => calls.push({ type: 'update', ...args }),
      },
    },
    paginate: async (method, args) => {
      assert.equal(method, github.rest.issues.listComments);
      assert.deepEqual(args, { owner: 'example', repo: 'hops', issue_number: 42, per_page: 100 });
      return comments;
    },
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

test('대상 브랜치의 커밋만 전진하면 원래 검토 기준을 표시하고 게시한다', async () => {
  const { run, calls } = setup({ current: { base: { ref: 'develop', sha: 'new' } } });
  await run();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].type, 'create');
  assert.match(calls[0].body, /검토 기준: `develop` \/ `def`/);
});

test('게시 대상 브랜치는 이벤트의 base를 따른다', async () => {
  const { run, calls } = setup({ baseRef: 'release/example' });
  await run();
  assert.equal(calls.length, 1);
  assert.match(calls[0].body, /검토 기준: `release\/example`/);
});

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

test('출력 경계에 이모지가 있어도 surrogate pair를 자르지 않는다', async () => {
  const { run, calls } = setup({ review: '가'.repeat(47999) + '😀후속 내용' });
  await run();
  assert.ok(calls[0].body.isWellFormed());
  assert.ok(calls[0].body.length < 65536);
  assert.match(calls[0].body, /일부 결과가 생략/);
});

test('경계 안에 들어가는 이모지는 그대로 게시한다', async () => {
  const review = '가'.repeat(47998) + '😀';
  const { run, calls } = setup({ review });
  await run();
  assert.ok(calls[0].body.includes(review));
  assert.ok(calls[0].body.isWellFormed());
  assert.doesNotMatch(calls[0].body, /일부 결과가 생략/);
});

function structured(overrides = {}) {
  return JSON.stringify({
    mode: 'fast', base_sha: 'def', head_sha: 'abc', complete: true,
    covered_files: ['backend/A.java', 'frontend/b.ts'], unread_files: [],
    findings: [
      { priority: 2, title: '일반 결함', path: 'frontend/b.ts', line: 5, end_line: 5,
        body: '조건 설명', fix: '수정', test: '테스트', confidence: 0.8 },
      { priority: 1, title: '권한 확인 누락', path: 'backend/A.java', line: 10, end_line: 12,
        body: '다른 사용자의 문의를 조회할 수 있다.', fix: '소유권 검사', test: '타인 ID 요청 시 403', confidence: 0.9 },
      { priority: 1, title: '불확실한 추정', path: 'backend/A.java', line: 20, end_line: 20,
        body: '근거 부족', fix: '', test: '', confidence: 0.3 },
    ],
    resolved_previous: ['이전 결함'],
    verified_behaviors: [{ scenario: '정상 조회', evidence: 'A.java:8 소유권 확인' }],
    test_gaps: ['만료 토큰 재시도'],
    validation_summary: 'frontend test 통과',
    overall_correctness: 'incorrect',
    overall_explanation: 'P1 결함이 있다.',
    ...overrides,
  });
}

test('구조화된 결과를 심각도 순으로 렌더링하고 head 링크를 붙인다', async () => {
  const { run, calls } = setup({ review: structured() });
  await run();
  const body = calls[0].body;
  assert.match(body, /수정이 필요한 결함 있음/);
  assert.ok(body.indexOf('권한 확인 누락') < body.indexOf('일반 결함'));
  assert.match(body, /\(https:\/\/github\.com\/example\/hops\/blob\/abc\/backend\/A\.java#L10-L12\)/);
  assert.match(body, /발견 사항 \(2\)/);
  assert.match(body, /<details><summary>확신이 낮은 지적 \(1\)[\s\S]*불확실한 추정/);
  assert.match(body, /해결된 지적:\*\* 이전 결함/);
  assert.match(body, /만료 토큰 재시도/);
  assert.doesNotMatch(body, /"findings"/);
});

test('읽지 못한 파일이 있으면 미완료 경고와 목록을 표시한다', async () => {
  const { run, calls } = setup({ review: structured({
    complete: false, unread_files: ['ai/src/big.py'], findings: [], overall_correctness: 'incomplete',
  }) });
  await run();
  assert.match(calls[0].body, /검토 미완료/);
  assert.match(calls[0].body, /1개 미확인/);
  assert.match(calls[0].body, /`ai\/src\/big\.py`/);
  assert.match(calls[0].body, /결함을 찾지 못했습니다/);
});

test('모델 값의 줄바꿈과 백틱이 제목·경로 구조를 깨지 않는다', async () => {
  const { run, calls } = setup({ review: structured({ findings: [
    { priority: 9, title: '제목\n## 가짜 헤더', path: 'a`b.ts', line: -1, end_line: 0,
      body: '본문', fix: '', test: '', confidence: 2 },
  ] }) });
  await run();
  assert.match(calls[0].body, /#### \[P3\] 제목 ## 가짜 헤더/);
  assert.match(calls[0].body, /\[`a'b\.ts`\]/);
  assert.match(calls[0].body, /신뢰도 100%/);
});

test('형식이 맞지 않는 JSON은 원문과 안내를 게시한다', async () => {
  const { run, calls } = setup({ review: '{"unexpected": true}' });
  await run();
  assert.match(calls[0].body, /리뷰 형식과 맞지 않아/);
  assert.match(calls[0].body, /"unexpected"/);
});

test('검증 결과 appendix를 렌더링한 리뷰 뒤에 붙인다', async () => {
  const calls = [];
  const expected = { number: 42, head: { sha: 'abc' }, base: { ref: 'develop', sha: 'def' } };
  const github = {
    rest: {
      pulls: { get: async () => ({ data: { ...expected, state: 'open', draft: false } }) },
      issues: { listComments() {}, createComment: async args => calls.push(args) },
    },
    paginate: async () => [],
  };
  await postReview({ github, core: { info() {} }, review: structured(), appendix: '\n\n## CI 실행 검증',
    context: { repo: { owner: 'example', repo: 'hops' }, payload: { pull_request: expected } } });
  assert.ok(calls[0].body.indexOf('발견 사항') < calls[0].body.indexOf('## CI 실행 검증'));
  assert.equal(typeof postReview.renderReview, 'function');
});
