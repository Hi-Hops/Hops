const { test } = require('node:test');
const assert = require('node:assert/strict');
const { mkdtempSync, writeFileSync, readFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { spawnSync } = require('node:child_process');
const { shouldSkip } = require('./ci-docs-deploy.cjs');

const before = 'a'.repeat(40);
const run = { id: 1, run_attempt: 1, head_sha: before, head_branch: 'develop', event: 'push', status: 'completed', conclusion: 'success' };
const job = { id: 2, name: 'Deploy verified develop to EC2', conclusion: 'success', steps: [{ name: 'Deploy through Systems Manager', conclusion: 'success' }] };
const log = `2026-01-01T00:00:00.1234567Z Deployment succeeded for commit ${before}.\n`;
const input = { docsOnly: true, before, runs: [run], getJobs: () => [job], getLog: () => log };

test('문서 전용 변경은 직전 커밋의 실제 배포 성공이 입증된 경우만 생략한다', () => {
  assert.equal(shouldSkip(input), true);
  assert.equal(shouldSkip({ ...input, docsOnly: false }), false);
  assert.equal(shouldSkip({ ...input, runs: [] }), false);
  assert.equal(shouldSkip({ ...input, runs: [{ ...run, status: 'in_progress' }] }), false);
  assert.equal(shouldSkip({ ...input, runs: [{ ...run, conclusion: 'failure' }] }), false);
  assert.equal(shouldSkip({ ...input, getJobs: () => [{ ...job, conclusion: 'skipped' }] }), false);
  assert.equal(shouldSkip({ ...input, getLog: () => 'A newer develop commit exists; this deployment is skipped.' }), false);
  assert.equal(shouldSkip({ ...input, getLog: () => log.replace(before, 'b'.repeat(40)) }), false);
  assert.equal(shouldSkip({ ...input, getLog: () => log.replace('2026-01-01T00:00:00.1234567Z', 'echo') }), false);
});

test('이전 배포 기록 조회가 실패하면 배포를 생략하지 않는다', () => {
  const dir = mkdtempSync(join(tmpdir(), 'hops-ci-docs-'));
  try {
    const output = join(dir, 'output');
    writeFileSync(join(dir, 'gh'), '#!/bin/sh\nexit 1\n', { mode: 0o755 });
    const result = spawnSync(process.execPath, [join(__dirname, 'ci-docs-deploy.cjs')], {
      env: { ...process.env, PATH: `${dir}:${process.env.PATH}`, GITHUB_OUTPUT: output,
        GITHUB_REPOSITORY: 'Hi-Hops/Hops', DOCS_ONLY: 'true', BEFORE_SHA: before }, encoding: 'utf8',
    });
    assert.equal(result.status, 0);
    assert.match(readFileSync(output, 'utf8'), /^skip=false\n$/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
