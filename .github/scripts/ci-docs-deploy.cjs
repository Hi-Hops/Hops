const { execFileSync } = require('node:child_process');

function shouldSkip({ docsOnly, before, runs, getJobs, getLog }) {
  if (!docsOnly || !/^[a-f0-9]{40}$/.test(before || '') || /^0+$/.test(before)) return false;
  const run = runs[0];
  if (!run || run.head_sha !== before || run.head_branch !== 'develop' ||
      run.event !== 'push' || run.status !== 'completed' || run.conclusion !== 'success') return false;
  const jobs = getJobs(run.id, run.run_attempt);
  const job = jobs.find(item => item.name === 'Deploy verified develop to EC2');
  if (!job || job.conclusion !== 'success' ||
      !job.steps?.some(step => step.name === 'Deploy through Systems Manager' && step.conclusion === 'success')) return false;
  const expected = `Deployment succeeded for commit ${before}.`;
  return getLog(job.id).split('\n').some(line =>
    /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+Z /.test(line) && line.trimEnd().endsWith(` ${expected}`));
}

module.exports = { shouldSkip };
if (require.main === module) {
  const { appendFileSync } = require('node:fs');
  const { DOCS_ONLY, BEFORE_SHA, GITHUB_REPOSITORY: repo, GITHUB_OUTPUT } = process.env;
  function api(endpoint, json = true) {
    const args = json ? ['api', endpoint] : ['api', '--allow-escape-sequences', endpoint];
    const data = execFileSync('gh', args, { encoding: 'utf8', timeout: 30000,
      maxBuffer: 16 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
    return json ? JSON.parse(data) : data;
  }
  let skip = false;
  if (DOCS_ONLY === 'true' && /^[a-f0-9]{40}$/.test(BEFORE_SHA || '')) {
    try {
      const prefix = `repos/${repo}/actions`;
      const query = `event=push&branch=develop&head_sha=${BEFORE_SHA}&per_page=100`;
      const runs = api(`${prefix}/workflows/ci.yml/runs?${query}`).workflow_runs;
      skip = shouldSkip({ docsOnly: true, before: BEFORE_SHA, runs,
        getJobs: (id, attempt) => api(`${prefix}/runs/${id}/attempts/${attempt}/jobs?per_page=100`).jobs,
        getLog: id => api(`${prefix}/jobs/${id}/logs`, false) });
    } catch (error) {
      console.log('직전 배포 확인에 실패해 보수적으로 배포를 진행합니다.');
    }
  }
  appendFileSync(GITHUB_OUTPUT, `skip=${skip}\n`);
  console.log(skip ? '문서만 변경됐고 직전 커밋의 배포 성공이 확인되어 배포를 생략합니다.' : '배포 검증이 필요합니다.');
}
