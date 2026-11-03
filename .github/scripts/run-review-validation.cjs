const { spawnSync } = require('node:child_process');
const { mkdirSync, readdirSync, writeFileSync } = require('node:fs');
const path = require('node:path');

function selectAreas(files) {
  const contracts = files.some(file => /^(contracts|specs\/contracts)\//.test(file));
  return {
    ci: files.some(file => /^(\.github|\.agents)\//.test(file)),
    compose: files.some(file => /^(compose\.yaml|\.env\.example|scripts\/compose-env\.test\.cjs)$/.test(file)
      || /^(frontend|ai|backend\/spring\/hops|infra\/postgres)\/\.env\.example$/.test(file)),
    frontend: contracts || files.some(file => file.startsWith('frontend/')),
    ai: contracts || files.some(file => file.startsWith('ai/')),
    backend: contracts || files.some(file => file.startsWith('backend/')),
  };
}

function execute(command, args, cwd) {
  const result = spawnSync(command, args, {
    cwd, encoding: 'utf8', timeout: 240000, maxBuffer: 8 * 1024 * 1024,
    env: { ...process.env, CI: 'true', NO_COLOR: '1' },
  });
  return {
    exitCode: result.status,
    details: [result.stdout, result.stderr, result.error?.message].filter(Boolean).join('\n'),
  };
}

function validate({ root, files, baseSha, headSha, output, run = execute }) {
  const checks = [];
  function check(name, command, args, directory = '.', prerequisite = true) {
    const display = [command, ...args].join(' ');
    if (!prerequisite) {
      checks.push({ name, command: display, status: 'skipped', details: '선행 준비 실패' });
      return false;
    }
    const started = Date.now();
    const result = run(command, args, path.join(root, directory));
    const passed = result.exitCode === 0;
    checks.push({ name, command: display, status: passed ? 'passed' : 'failed',
      exitCode: result.exitCode, durationMs: Date.now() - started,
      details: (result.details || '').slice(passed ? -1500 : -6000) });
    return passed;
  }
  check('diff 공백 검사', 'git', ['diff', '--check', `${baseSha}...${headSha}`]);
  const areas = selectAreas(files);
  if (areas.ci) {
    const tests = readdirSync(path.join(root, '.github/scripts'))
      .filter(file => file.endsWith('.test.cjs')).map(file => `.github/scripts/${file}`);
    if (tests.length) check('CI 스크립트 회귀 테스트', 'node', ['--test', ...tests]);
    check('Actions 문법·표현식 검사', 'actionlint', ['-shellcheck=']);
  }
  if (areas.compose) check('Compose 환경변수 분리 검사', 'node', ['--test', 'scripts/compose-env.test.cjs']);
  if (areas.frontend) {
    const installed = check('frontend 의존성 설치', 'npm', ['ci', '--ignore-scripts'], 'frontend');
    for (const task of ['typecheck', 'lint', 'format:check', 'test', 'build']) {
      check(`frontend ${task}`, 'npm', ['run', task], 'frontend', installed);
    }
  }
  if (areas.ai) {
    const installed = check('AI 의존성 설치', 'uv', ['sync', '--locked'], 'ai');
    for (const [name, args] of [
      ['AI lint', ['ruff', 'check', '.']],
      ['AI format', ['ruff', 'format', '--check', '.']],
      ['AI 테스트', ['pytest', '-ra']],
    ]) check(name, 'uv', ['run', '--no-sync', ...args], 'ai', installed);
  }
  if (areas.backend) {
    const database = check('Spring 임시 테스트 DB', 'bash', ['-c',
      'docker run -d --rm --name hops-review-db -p 127.0.0.1:5432:5432 ' +
      '-e POSTGRES_DB=hops_test -e POSTGRES_USER=hops -e POSTGRES_PASSWORD=ci-only-fixture ' +
      'pgvector/pgvector:0.8.7-pg18-bookworm && ' +
      'for i in $(seq 1 30); do ' +
      'if docker exec hops-review-db pg_isready -h 127.0.0.1 -U hops -d hops_test; then ' +
      'docker exec hops-review-db psql -U hops -d hops_test -c "CREATE EXTENSION IF NOT EXISTS vector"; exit $?; fi; ' +
      'sleep 2; done; exit 1']);
    check('Spring 컴파일·테스트', 'bash', ['gradlew', '--no-daemon', 'test'], 'backend/spring/hops', database);
  }
  const report = { schemaVersion: 1, baseSha, headSha, areas, checks,
    limitations: [
      '선택한 프로젝트 검사 결과이며 테스트가 없는 동작과 제품 E2E·실제 모델 품질은 보장하지 않는다.',
      'AI의 PostgreSQL 전용 테스트는 DSN 미설정으로 skip될 수 있다. pytest의 skipped 결과를 확인한다.',
      ...(!Object.values(areas).some(Boolean) ? ['지원 실행 영역 변경 없음: diff 검사만 수행했다.'] : []),
    ] };
  mkdirSync(output, { recursive: true });
  writeFileSync(path.join(output, 'validation.json'), JSON.stringify(report, null, 2));
  return report;
}

module.exports = { selectAreas, validate };
if (require.main === module) {
  const baseSha = process.env.BASE_SHA;
  const headSha = process.env.HEAD_SHA;
  if (![baseSha, headSha].every(sha => /^[a-f0-9]{40}$/.test(sha || ''))) {
    throw new Error('BASE_SHA와 HEAD_SHA가 필요합니다.');
  }
  const actual = execute('git', ['rev-parse', 'HEAD'], process.cwd());
  if (actual.exitCode !== 0 || actual.details.trim() !== headSha) throw new Error('검증 HEAD 불일치');
  const diff = execute('git', ['diff', '--name-only', '-z', `${baseSha}...${headSha}`, '--'], process.cwd());
  if (diff.exitCode !== 0) throw new Error('변경 파일 조회 실패');
  const report = validate({ root: process.cwd(), files: diff.details.split('\0').filter(Boolean),
    baseSha, headSha, output: process.env.REVIEW_EVIDENCE_DIR });
  for (const item of report.checks) console.log(`${item.status}: ${item.name} (${item.command})`);
  if (report.checks.some(item => item.status === 'failed')) process.exitCode = 1;
}
