import type { RealtimeConfig } from '../contracts/types.js';

export function loadEnvironment(env: NodeJS.ProcessEnv = process.env) {
  const required = (name: string) => {
    const value = env[name];

    if (!value) throw new Error(`${name} 설정이 필요합니다.`);

    return value;
  };
  const port = Number(required('PORT'));

  if (!Number.isInteger(port) || port < 1 || port > 65535)
    throw new Error('PORT 범위가 올바르지 않습니다.');

  const springUrl = new URL(required('SPRING_BASE_URL'));

  if (!['http:', 'https:'].includes(springUrl.protocol) || springUrl.username || springUrl.password)
    throw new Error('SPRING_BASE_URL을 확인해 주세요.');

  const config: RealtimeConfig = {
    springUrl,
    nodeCredential: required('NODE_SERVICE_TOKEN'),
    springCredential: required('SPRING_SERVICE_TOKEN'),
    origins: required('ALLOWED_ORIGINS')
      .split(',')
      .map((v) => v.trim()),
    path: env.SOCKET_PATH ?? '/socket.io',
  };

  return { port, config };
}
