const { spawnSync } = require('node:child_process');
const { mkdirSync, readdirSync, readFileSync, writeFileSync } = require('node:fs');
const path = require('node:path');

const SPRING_RESULTS_DIR = 'backend/spring/hops/build/test-results/test';
const FAILED_TEST_LIMIT = 20;
const FAILED_MESSAGE_LIMIT = 300;

function decodeXml(text) {
  return text.replace(/&(lt|gt|quot|apos|amp|#\d+|#x[0-9a-f]+);/gi, (_, entity) => {
    const named = { lt: '<', gt: '>', quot: '"', apos: "'", amp: '&' }[entity.toLowerCase()];
    if (named) return named;
    const code = entity[1].toLowerCase() === 'x' ? parseInt(entity.slice(2), 16) : parseInt(entity.slice(1), 10);
    return Number.isFinite(code) && code <= 0x10ffff ? String.fromCodePoint(code) : '';
  });
}

function attribute(tag, name) {
  const match = tag.match(new RegExp(`\\s${name}="([^"]*)"`));
  return match ? decodeXml(match[1]) : '';
}

// 실패 원인을 남기되 로그 원문·비밀값이 길게 노출되지 않도록 메시지 첫 줄만 짧게 자른다.
function shortMessage(text) {
  const line = text.split(/\r?\n/).find(part => part.trim()) || '';
  const clean = line.replace(/[\u0000-\u001f\u007f]/g, ' ').trim();
  return clean.length > FAILED_MESSAGE_LIMIT ? `${clean.slice(0, FAILED_MESSAGE_LIMIT)}…` : clean;
}

// Gradle JUnit XML에서 failure·error가 있는 테스트만 뽑는다.
function collectFailedTests(directory) {
  let files;
  try {
    files = readdirSync(directory).filter(file => /^TEST-.*\.xml$/.test(file)).sort();
  } catch {
    return { total: 0, tests: [], error: '테스트 결과 XML 없음' };
  }
  const tests = [];
  let total = 0;
  for (const file of files) {
    let xml;
    try { xml = readFileSync(path.join(directory, file), 'utf8'); } catch { continue; }
    for (const [, open, body = ''] of xml.matchAll(/(<testcase\b(?:"[^"]*"|[^>"\/])*)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
      const problem = body.match(/<(failure|error)\b((?:"[^"]*"|[^>"\/])*)(?:\/>|>([\s\S]*?)<\/\1>)/);
      if (!problem) continue;
      total += 1;
      if (tests.length >= FAILED_TEST_LIMIT) continue;
      const message = attribute(problem[2], 'message') || decodeXml(problem[3] || '');
      tests.push({
        className: attribute(open, 'classname').slice(0, 200),
        name: attribute(open, 'name').slice(0, 200),
        kind: problem[1],
        type: attribute(problem[2], 'type').slice(0, 200),
        message: shortMessage(message),
      });
    }
  }
  return { total, tests };
}

function formatFailedTests({ total, tests, error }) {
  if (error) return `[실패 테스트] ${error}`;
  if (!total) return '[실패 테스트] XML에서 failure·error 테스트를 찾지 못함';
  const lines = tests.map(test => `- ${test.className} > ${test.name} (${test.kind}: ${test.type}) ${test.message}`);
  if (total > tests.length) lines.push(`- 외 ${total - tests.length}개`);
  return `[실패 테스트 ${total}개]\n${lines.join('\n')}`;
}

function selectAreas(files) {
  const contracts = files.some(file => /^(contracts|specs\/contracts)\//.test(file));
  return {
    ci: files.some(file => /^(\.github|\.agents)\//.test(file)),
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
    const springPassed = check('Spring 컴파일·테스트', 'bash', ['gradlew', '--no-daemon', 'test'],
      'backend/spring/hops', database);
    const spring = checks[checks.length - 1];
    if (!springPassed && spring.status === 'failed') {
      // 출력 끝부분만으로는 어떤 테스트가 실패했는지 알 수 없어 결과 XML을 요약해 앞에 둔다.
      const failed = collectFailedTests(path.join(root, SPRING_RESULTS_DIR));
      spring.failedTests = failed.tests;
      spring.failedTestCount = failed.total;
      spring.details = `${formatFailedTests(failed)}\n\n[출력 마지막 부분]\n${spring.details}`;
    }
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

module.exports = { selectAreas, validate, collectFailedTests };
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
  for (const item of report.checks) {
    console.log(`${item.status}: ${item.name} (${item.command})`);
    for (const test of item.failedTests || []) console.log(`  - ${test.className} > ${test.name}: ${test.message}`);
  }
  if (report.checks.some(item => item.status === 'failed')) process.exitCode = 1;
}
