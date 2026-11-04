const { test } = require('node:test');
const assert = require('node:assert/strict');
const { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const path = require('node:path');
const { selectAreas, validate } = require('./run-review-validation.cjs');

test('계약 변경은 세 소비자 영역을 검증하고 문서만 바뀌면 실행 검사를 추가하지 않는다', () => {
  assert.deepEqual(selectAreas(['contracts/agents/input.json']),
    { ci: false, frontend: true, ai: true, backend: true });
  assert.deepEqual(selectAreas(['docs/PRD.md']),
    { ci: false, frontend: false, ai: false, backend: false });
});

test('설치 실패는 후속 검사를 skip하고 다른 변경 영역 검증을 계속한다', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'hops-review-'));
  try {
    const calls = [];
    const report = validate({ root, files: ['frontend/src/a.ts', 'ai/src/a.py'],
      baseSha: 'base', headSha: 'head', output: path.join(root, 'evidence'),
      run(command, args, cwd) {
        calls.push({ command, args, cwd });
        return { exitCode: command === 'npm' ? 1 : 0, details: 'fixture' };
      },
    });
    assert.equal(report.checks.filter(check => check.status === 'skipped').length, 5);
    assert.ok(report.checks.some(check => check.name === 'AI 테스트' && check.status === 'passed'));
    assert.equal(calls.filter(call => call.command === 'npm').length, 1);
    const saved = JSON.parse(readFileSync(path.join(root, 'evidence/validation.json'), 'utf8'));
    assert.equal(saved.headSha, 'head');
    assert.equal(saved.checks[1].status, 'failed');
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('CI 테스트 실패를 보존하고 actionlint도 실행하며 실패 로그 길이를 제한한다', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'hops-review-'));
  try {
    mkdirSync(path.join(root, '.github/scripts'), { recursive: true });
    writeFileSync(path.join(root, '.github/scripts/example.test.cjs'), '');
    const report = validate({ root, files: ['.github/workflows/codex-review.yml'],
      baseSha: 'base', headSha: 'head', output: path.join(root, 'evidence'),
      run: command => ({ exitCode: command === 'node' ? 1 : 0, details: 'x'.repeat(8000) }),
    });
    assert.equal(report.checks[1].status, 'failed');
    assert.equal(report.checks[1].details.length, 6000);
    assert.equal(report.checks[2].status, 'passed');
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('Spring 테스트 실패 시 결과 XML의 실패 테스트를 details 앞과 별도 필드에 짧게 남긴다', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'hops-review-'));
  try {
    const results = path.join(root, 'backend/spring/hops/build/test-results/test');
    mkdirSync(results, { recursive: true });
    writeFileSync(path.join(results, 'TEST-a.SseTest.xml'), `<?xml version="1.0"?>
<testsuite name="a.SseTest">
  <testcase name="passes()" classname="a.SseTest" time="0.1"/>
  <testcase name="times_out()" classname="a.SseTest" time="5">
    <failure message="expected: &lt;3&gt; but was: &lt;2&gt;&#10;secret-tail" type="org.opentest4j.AssertionFailedError">stack</failure>
  </testcase>
  <testcase name="long()" classname="a.SseTest" time="1">
    <error message="${'y'.repeat(1000)}" type="java.lang.IllegalStateException"/>
  </testcase>
</testsuite>`);
    const report = validate({ root, files: ['backend/spring/hops/src/A.java'],
      baseSha: 'base', headSha: 'head', output: path.join(root, 'evidence'),
      run: (command, args) => ({ exitCode: args.includes('test') ? 1 : 0, details: 'z'.repeat(8000) }),
    });
    const spring = report.checks.find(check => check.name === 'Spring 컴파일·테스트');
    assert.equal(spring.failedTestCount, 2);
    assert.deepEqual(spring.failedTests[0], { className: 'a.SseTest', name: 'times_out()', kind: 'failure',
      type: 'org.opentest4j.AssertionFailedError', message: 'expected: <3> but was: <2>' });
    assert.equal(spring.failedTests[1].kind, 'error');
    assert.equal(spring.failedTests[1].message.length, 301);
    assert.ok(spring.details.startsWith('[실패 테스트 2개]\n- a.SseTest > times_out()'));
    assert.ok(!spring.details.includes('secret-tail'));
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('Spring 결과 XML이 없거나 DB 준비 실패로 skip되면 실패 테스트 필드를 만들지 않거나 없음을 표시한다', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'hops-review-'));
  try {
    const files = ['backend/spring/hops/src/A.java'];
    const output = path.join(root, 'evidence');
    const compileFail = validate({ root, files, baseSha: 'base', headSha: 'head', output,
      run: (command, args) => ({ exitCode: args.includes('test') ? 1 : 0, details: 'compile error' }) });
    const spring = compileFail.checks.find(check => check.name === 'Spring 컴파일·테스트');
    assert.deepEqual(spring.failedTests, []);
    assert.ok(spring.details.startsWith('[실패 테스트] 테스트 결과 XML 없음'));
    const dbFail = validate({ root, files, baseSha: 'base', headSha: 'head', output,
      run: command => ({ exitCode: command === 'bash' ? 1 : 0, details: 'db' }) });
    const skipped = dbFail.checks.find(check => check.name === 'Spring 컴파일·테스트');
    assert.equal(skipped.status, 'skipped');
    assert.equal(skipped.failedTests, undefined);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
