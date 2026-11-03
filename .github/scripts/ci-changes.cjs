const { execFileSync } = require('node:child_process');

const shaPattern = /^[a-f0-9]{40}$/;

function documentationOnly(file) {
  return file === 'README.md' || (/^docs\/.+\.md$/i.test(file));
}

function selectChecks(files, event) {
  const checks = { spring: false, frontend: false, ai: false, deployment: false };
  let docsOnly = files.length > 0;
  for (const file of files) {
    if (documentationOnly(file)) continue;
    docsOnly = false;
    if (file.startsWith('frontend/')) checks.frontend = true;
    else if (file.startsWith('backend/spring/hops/')) checks.spring = true;
    else if (file.startsWith('ai/')) checks.ai = true;
    else Object.keys(checks).forEach(key => { checks[key] = true; });
  }
  if (event === 'push') Object.keys(checks).forEach(key => { checks[key] = true; });
  return { ...checks, docs_only: docsOnly };
}

function changedFiles(event, base, head) {
  if (![base, head].every(value => shaPattern.test(value || '') && !/^0+$/.test(value))) {
    throw new Error('변경 범위를 식별할 수 없습니다.');
  }
  const from = event === 'pull_request'
    ? execFileSync('git', ['merge-base', base, head], { encoding: 'utf8' }).trim()
    : base;
  return execFileSync('git', ['diff', '--name-only', '-z', '--no-renames', from, head, '--'])
    .toString('utf8').split('\0').filter(Boolean);
}

module.exports = { documentationOnly, selectChecks, changedFiles };
if (require.main === module) {
  const { appendFileSync } = require('node:fs');
  const { GITHUB_EVENT_NAME: event, BASE_SHA: base, HEAD_SHA: head, GITHUB_OUTPUT: output } = process.env;
  let checks;
  try {
    checks = selectChecks(changedFiles(event, base, head), event);
  } catch (error) {
    console.log(`변경 범위를 확인할 수 없어 모든 검사를 실행합니다: ${error.message}`);
    checks = { spring: true, frontend: true, ai: true, deployment: true, docs_only: false };
  }
  for (const [name, enabled] of Object.entries(checks)) appendFileSync(output, `${name}=${enabled}\n`);
  console.log(`선택된 검사: ${JSON.stringify(checks)}`);
}
