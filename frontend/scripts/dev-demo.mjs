import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// 공개된 가상 계정만 사용하는 로컬 시연 실행기. 개인 .env는 수정하지 않는다.
const child = spawn(
  process.execPath,
  [
    fileURLToPath(new URL('../node_modules/vite/bin/vite.js', import.meta.url)),
    '--host',
    '127.0.0.1',
    '--port',
    '5178',
    '--strictPort',
    ...process.argv.slice(2),
  ],
  {
    cwd: fileURLToPath(new URL('..', import.meta.url)),
    stdio: 'inherit',
    env: {
      ...process.env,
      VITE_DATA_MODE: 'mock',
      VITE_MOCK_EXAMPLES: 'true',
      VITE_MOCK_PREVIEW_ID: '',
      VITE_MOCK_REQUESTER_ID: 'demo.requester',
      VITE_MOCK_NETWORK_ID: 'demo.staff',
      VITE_MOCK_BACKEND_ID: 'demo.file',
      VITE_MOCK_ADMIN_ID: 'demo.admin',
      VITE_MOCK_PASSWORD: 'hops-demo-only',
    },
  },
);
child.on('error', (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
child.on('exit', (code) => {
  process.exitCode = code ?? 1;
});
process.on('SIGINT', () => {
  child.kill('SIGINT');
});
process.on('SIGTERM', () => {
  child.kill('SIGTERM');
});
