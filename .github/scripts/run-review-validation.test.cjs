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
