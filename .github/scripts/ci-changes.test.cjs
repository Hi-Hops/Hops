const { test } = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { mkdtempSync, mkdirSync, writeFileSync, rmSync } = require('node:fs');
const { join } = require('node:path');
const { tmpdir } = require('node:os');
const { selectChecks, changedFiles } = require('./ci-changes.cjs');

test('PR에서는 변경된 서비스만 검사한다', () => {
  assert.deepEqual(selectChecks(['frontend/src/app.ts'], 'pull_request'),
    { spring: false, frontend: true, ai: false, deployment: false, docs_only: false });
  assert.deepEqual(selectChecks(['backend/spring/hops/src/A.java', 'ai/src/a.py'], 'pull_request'),
    { spring: true, frontend: false, ai: true, deployment: false, docs_only: false });
});

test('공통 계약과 미분류 파일은 모든 검사를 선택한다', () => {
  for (const file of ['contracts/agents/README.md', 'specs/contracts/a.md', '.github/workflows/ci.yml', 'compose.yaml']) {
    assert.deepEqual(selectChecks([file], 'pull_request'),
      { spring: true, frontend: true, ai: true, deployment: true, docs_only: false });
  }
});

test('문서 전용 PR은 서비스 검사를 생략하지만 push에서는 전체 검사한다', () => {
  assert.deepEqual(selectChecks(['docs/PRD.md', 'README.md'], 'pull_request'),
    { spring: false, frontend: false, ai: false, deployment: false, docs_only: true });
  assert.deepEqual(selectChecks(['docs/PRD.md'], 'push'),
    { spring: true, frontend: true, ai: true, deployment: true, docs_only: true });
  assert.equal(selectChecks(['frontend/README.md'], 'push').docs_only, false);
  assert.equal(selectChecks([], 'push').docs_only, false);
});

test('다른 영역으로 파일을 이동해도 이전 및 새 경로를 모두 검사한다', () => {
  const dir = mkdtempSync(join(tmpdir(), 'hops-ci-changes-'));
  const previousDir = process.cwd();
  const git = (...args) => execFileSync('git', args, { cwd: dir, stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' }).trim();
  try {
    git('init');
    git('config', 'user.name', 'CI test');
    git('config', 'user.email', 'ci@example.invalid');
    mkdirSync(join(dir, 'frontend'), { recursive: true });
    mkdirSync(join(dir, 'docs'), { recursive: true });
    writeFileSync(join(dir, 'frontend', 'example.md'), 'fixture\n');
    git('add', '.');
    git('commit', '-m', 'base');
    const base = git('rev-parse', 'HEAD');
    git('mv', 'frontend/example.md', 'docs/example.md');
    git('commit', '-m', 'move');
    const head = git('rev-parse', 'HEAD');
    process.chdir(dir);
    const files = changedFiles('pull_request', base, head);
    assert.deepEqual(files.sort(), ['docs/example.md', 'frontend/example.md']);
    assert.equal(selectChecks(files, 'pull_request').frontend, true);
  } finally {
    process.chdir(previousDir);
    rmSync(dir, { recursive: true, force: true });
  }
});
