// 실제 개인 .env나 Docker 데몬 없이 Compose CLI의 설정 로딩을 검증한다.
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');

const root = path.resolve(__dirname, '..');
const examples = [
  'frontend/.env.example',
  'backend/spring/hops/.env.example',
  'ai/.env.example',
  'infra/postgres/.env.example',
];

function fixture(t) {
  const parent = path.join(root, '.local');
  fs.mkdirSync(parent, { recursive: true });
  const directory = fs.mkdtempSync(path.join(parent, 'compose-env-test-'));
  assert.equal(path.dirname(directory), parent);
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  fs.copyFileSync(path.join(root, 'compose.yaml'), path.join(directory, 'compose.yaml'));
  for (const example of examples) {
    const target = path.join(directory, example.replace(/\.example$/, ''));
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(root, example), target);
  }
  return directory;
}

function configure(directory, { rootEnv, shell = {} } = {}) {
  const filename = path.join(directory, '.env');
  if (rootEnv !== undefined) fs.writeFileSync(filename, rootEnv);
  const env = Object.fromEntries(
    Object.entries(process.env).filter(
      ([name]) =>
        !/^(COMPOSE_|DOCKER_|VITE_|HOPS_|LANGFUSE_|SPRING_|POSTGRES_)/i.test(name) &&
        ![
          'OPENAI_API_KEY',
          'AI_SERVICE_TOKEN',
          'FRONTEND_PORT',
          'BACKEND_PORT',
          'AI_PORT',
        ].includes(name),
    ),
  );
  const result = spawnSync(
    'docker',
    [
      'compose',
      ...(rootEnv === undefined ? [] : ['--env-file', filename]),
      '--project-directory',
      directory,
      '-f',
      path.join(directory, 'compose.yaml'),
      'config',
      '--format',
      'json',
    ],
    { cwd: directory, encoding: 'utf8', env: { ...env, ...shell } },
  );
  if (result.error) throw result.error;
  return result;
}

function config(directory, options) {
  const result = configure(directory, options);
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}

function setValue(directory, service, name, value) {
  const filename = path.join(directory, service, '.env');
  const text = fs.readFileSync(filename, 'utf8');
  assert.match(text, new RegExp(`^${name}=`, 'm'));
  fs.writeFileSync(
    filename,
    text.replace(new RegExp(`^${name}=.*$`, 'm'), () => `${name}=${value}`),
  );
}

test('서비스별 예시만으로 루트 .env 없이 기본 포트의 설정을 구성한다', (t) => {
  const directory = fixture(t);
  const { services } = config(directory);
  assert.equal(fs.existsSync(path.join(directory, '.env')), false);
  assert.equal(services.frontend.environment.VITE_API_BASE_URL, 'http://localhost:8080');
  assert.equal(services.ai.environment.HOPS_AI_MODE, 'scaffold');
  assert.equal(String(services.frontend.ports[0].published), '5173');
});

test('작은따옴표 비밀번호의 달러·샵 문자를 변수나 주석으로 처리하지 않는다', (t) => {
  const directory = fixture(t);
  const password = 'synthetic-$password#suffix';
  setValue(directory, 'backend/spring/hops', 'SPRING_DATASOURCE_PASSWORD', `'${password}'`);
  setValue(directory, 'infra/postgres', 'POSTGRES_PASSWORD', `'${password}'`);
  const { services } = config(directory);
  // config 출력은 다시 Compose로 읽을 수 있도록 리터럴 $를 $$로 직렬화한다.
  const literal = (value) => value.replaceAll('$$', () => '$');
  assert.equal(literal(services.backend.environment.SPRING_DATASOURCE_PASSWORD), password);
  assert.equal(literal(services.postgres.environment.POSTGRES_PASSWORD), password);
});

test('서비스 파일 값이 루트·셸의 이전 설정에 덮어써지거나 다른 서비스로 전달되지 않는다', (t) => {
  const directory = fixture(t);
  setValue(directory, 'ai', 'OPENAI_API_KEY', 'synthetic-ai-key');
  setValue(directory, 'ai', 'HOPS_AI_MAX_LLM_CALLS', '3');
  setValue(
    directory,
    'backend/spring/hops',
    'HOPS_JWT_SECRET',
    'synthetic-jwt-secret-with-32-characters',
  );
  setValue(directory, 'backend/spring/hops', 'SPRING_DATASOURCE_PASSWORD', 'synthetic-db-password');
  setValue(directory, 'infra/postgres', 'POSTGRES_PASSWORD', 'synthetic-db-password');
  setValue(directory, 'frontend', 'VITE_DATA_MODE', 'mock');
  const { services } = config(directory, {
    rootEnv: 'OPENAI_API_KEY=old-root-key\nHOPS_AI_MAX_LLM_CALLS=7\nVITE_DATA_MODE=live\n',
    shell: { HOPS_AI_MAX_LLM_CALLS: '6', OPENAI_API_KEY: 'old-shell-key', VITE_DATA_MODE: 'live' },
  });
  assert.equal(services.ai.environment.OPENAI_API_KEY, 'synthetic-ai-key');
  assert.equal(services.ai.environment.HOPS_AI_MAX_LLM_CALLS, '3');
  assert.equal(services.frontend.environment.VITE_DATA_MODE, 'mock');
  assert.equal(
    services.backend.environment.SPRING_DATASOURCE_PASSWORD,
    services.postgres.environment.POSTGRES_PASSWORD,
  );
  for (const [service, definition] of Object.entries(services)) {
    const env = definition.environment ?? {};
    if (service !== 'ai') {
      assert.equal(env.OPENAI_API_KEY, undefined);
      assert.equal(env.AI_SERVICE_TOKEN, undefined);
    }
    if (service !== 'backend') assert.equal(env.HOPS_JWT_SECRET, undefined);
    if (['frontend', 'ai', 'redis'].includes(service)) {
      assert.equal(env.POSTGRES_PASSWORD, undefined);
      assert.equal(env.SPRING_DATASOURCE_PASSWORD, undefined);
    }
  }
});

test('공개 포트·브라우저 API·CORS와 컨테이너 주소를 각각 설정할 수 있다', (t) => {
  const directory = fixture(t);
  setValue(directory, 'frontend', 'VITE_API_BASE_URL', 'http://localhost:18080');
  setValue(
    directory,
    'backend/spring/hops',
    'HOPS_CORS_ALLOWEDORIGINS',
    'http://localhost:15173,http://127.0.0.1:15173',
  );
  const { services } = config(directory, {
    rootEnv: 'FRONTEND_PORT=15173\nBACKEND_PORT=18080\nAI_PORT=18000\nPOSTGRES_PORT=15432\n',
  });
  for (const [name, published, target] of [
    ['frontend', '15173', 5173],
    ['backend', '18080', 8080],
    ['ai', '18000', 8000],
    ['postgres', '15432', 5432],
  ]) {
    assert.equal(String(services[name].ports[0].published), published);
    assert.equal(services[name].ports[0].target, target);
    assert.equal(services[name].ports[0].host_ip, '127.0.0.1');
  }
  assert.equal(services.frontend.environment.VITE_API_BASE_URL, 'http://localhost:18080');
  assert.equal(
    services.backend.environment.HOPS_CORS_ALLOWEDORIGINS,
    'http://localhost:15173,http://127.0.0.1:15173',
  );
  assert.equal(
    services.backend.environment.SPRING_DATASOURCE_URL,
    'jdbc:postgresql://postgres:5432/hops',
  );
  assert.equal(services.backend.environment.SPRING_DATA_REDIS_HOST, 'redis');
  assert.equal(services.backend.environment.HOPS_AGENT_BASEURL, 'http://ai:8000');
});

test('서비스 필수 파일이 없으면 다른 개인 설정을 사용하지 않고 오류를 낸다', (t) => {
  const directory = fixture(t);
  fs.unlinkSync(path.join(directory, 'backend/spring/hops/.env'));
  const result = configure(directory);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /backend[\\/]spring[\\/]hops[\\/]\.env/);
});
