import type { AddressInfo } from 'node:net';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { once } from 'node:events';

const main = new URL('../src/main.js', import.meta.url);

const environment = {
  SPRING_BASE_URL: 'http://127.0.0.1:8080',
  NODE_SERVICE_TOKEN: 'fixture-node',
  SPRING_SERVICE_TOKEN: 'fixture-spring',
  ALLOWED_ORIGINS: 'http://localhost:5173',
};

test(
  '실행 진입점이 환경변수로 기동하고 healthz 응답 후 SIGTERM으로 종료한다',
  { timeout: 5000 },
  async (t) => {
    const reservation = createServer();
    reservation.listen(0, '127.0.0.1');
    await once(reservation, 'listening');
    const port = (reservation.address() as AddressInfo).port;
    await new Promise<void>((resolve) => reservation.close(() => resolve()));
    const child = spawn(process.execPath, [main.pathname], {
      env: { ...environment, PORT: String(port) },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    t.after(() => {
      if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
    });
    await Promise.race([
      once(child.stdout!, 'data'),
      once(child, 'exit').then(() => {
        throw new Error('서버가 시작 전에 종료되었습니다.');
      }),
    ]);
    const response = await fetch(`http://127.0.0.1:${port}/healthz`);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: 'UP' });
    assert.equal((await fetch(`http://127.0.0.1:${port}/missing`)).status, 404);
    const exited = once(child, 'exit');
    child.kill('SIGTERM');
    assert.equal((await exited)[0], 0);
  },
);

test('서비스 자격증명이 같으면 실행을 거부한다', { timeout: 5000 }, async () => {
  const child = spawn(process.execPath, [main.pathname], {
    env: {
      ...environment,
      PORT: '3001',
      SPRING_SERVICE_TOKEN: environment.NODE_SERVICE_TOKEN,
    },
    stdio: 'ignore',
  });
  assert.notEqual((await once(child, 'exit'))[0], 0);
});
