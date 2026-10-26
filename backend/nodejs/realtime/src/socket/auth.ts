import { randomUUID, timingSafeEqual } from 'node:crypto';
import type { RealtimeConfig, SpringClient, AuthFailureCode } from '../contracts/types.js';
import { apiError, failure, object } from '../contracts/validation.js';
import type { ClientNamespace, ServiceNamespace, ClientSocket, RelayState } from './types.js';

export const authCodes: readonly string[] = [
  'UNAUTHENTICATED',
  'ACCESS_TOKEN_EXPIRED',
  'AUTH_CONTEXT_CHANGED',
  'REFRESH_TOKEN_REVOKED',
];

function credentialMatches(value: unknown, expected: string) {
  if (typeof value !== 'string') return false;

  const a = Buffer.from(value),
    b = Buffer.from(expected);

  return a.length === b.length && timingSafeEqual(a, b);
}

export function registerAuthentication(
  clients: ClientNamespace,
  services: ServiceNamespace,
  config: RealtimeConfig,
  spring: SpringClient,
  state: RelayState,
) {
  const revoke = (socket: ClientSocket, code: AuthFailureCode) => {
    if (socket.data.revoked) return;

    socket.data.revoked = true;

    for (const name of socket.rooms) if (name !== socket.id) socket.leave(name);

    socket.emit('auth.invalidated', {
      eventId: randomUUID(),
      code: code === 'UNAUTHENTICATED' ? 'REFRESH_TOKEN_REVOKED' : code,
      message: '인증을 다시 확인해 주세요.',
    });
    socket.disconnect(true);
  };

  async function authenticate(socket: ClientSocket) {
    if (socket.data.revoked || Date.now() >= socket.data.expiresAt)
      throw failure('ACCESS_TOKEN_EXPIRED');

    const value = await spring.authenticate(socket.data.token);

    if (value.user.id !== socket.data.userId) throw failure('AUTH_CONTEXT_CHANGED');

    if (!socket.connected || socket.data.revoked) throw failure('UNAUTHENTICATED');

    if (Date.now() >= socket.data.expiresAt) throw failure('ACCESS_TOKEN_EXPIRED');

    return value;
  }

  clients.use(async (socket, next) => {
    try {
      const handshake: unknown = socket.handshake.auth;
      const token = object(handshake) ? handshake.token : undefined;

      if (typeof token !== 'string' || !token || token.length > 16384)
        throw failure('UNAUTHENTICATED');

      const value = await spring.authenticate(token);
      socket.data = {
        token,
        userId: value.user.id,
        expiresAt: Date.parse(value.expiresAt),
        revoked: false,
      };
      next();
    } catch (error) {
      const err = new Error('연결을 인증할 수 없습니다.');
      Object.assign(err, {
        data: apiError(error) ? error : failure('SERVICE_UNAVAILABLE'),
      });
      next(err);
    }
  });
  services.use((socket, next) => {
    if (
      !credentialMatches(
        (socket.handshake.auth as Record<string, unknown>).serviceToken,
        config.springCredential,
      ) ||
      state.socket
    ) {
      const err = new Error('서비스 연결을 허용할 수 없습니다.');
      Object.assign(err, { data: failure('ACCESS_DENIED') });
      next(err);
    } else {
      state.socket = socket;
      next();
    }
  });
  clients.on('connection', (socket) => {
    const timer = setTimeout(
      () => revoke(socket, 'ACCESS_TOKEN_EXPIRED'),
      Math.max(0, socket.data.expiresAt - Date.now()),
    );
    timer.unref();
    socket.on('disconnect', () => clearTimeout(timer));
  });

  return { authenticate, revoke };
}
