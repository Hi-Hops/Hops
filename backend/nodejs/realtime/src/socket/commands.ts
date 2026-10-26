import { randomUUID } from 'node:crypto';
import type { ApiErrorBody, AuthFailureCode } from '../contracts/types.js';
import { apiError, command, failure, object, storedAck, uuid } from '../contracts/validation.js';
import { authCodes } from './auth.js';
import { handleSubscription, type SubscriptionName } from './subscription.js';
import type { SocketContext } from './types.js';

export function registerCommandHandlers(context: SocketContext) {
  const {
    clients,
    relay: state,
    ackTimeout,
    auth: { authenticate, revoke },
  } = context;

  clients.on('connection', (socket) => {
    // 구독 request/release의 비동기 권한 조회가 순서를 뒤집지 않게 직렬화한다.
    let subscriptions = Promise.resolve();
    let pending = 0;

    for (const name of [
      'inquiry.create',
      'message.send',
      'message.revise',
      'subscription.request',
      'subscription.release',
    ] as const) {
      socket.on(name, (input, ack) => {
        if (typeof ack !== 'function') return;

        const requestId = object(input) && uuid(input.requestId) ? input.requestId : randomUUID();
        const reject = (error: ApiErrorBody) =>
          ack({ ok: false, requestId, error: { ...error, requestId } });

        if (!command(name, input)) {
          reject(failure('INVALID_INPUT'));
          return;
        }

        if (pending >= 32) {
          reject(failure('RATE_LIMITED'));
          return;
        }

        pending++;
        const deadline = Date.now() + ackTimeout;
        const run = async () => {
          try {
            if ('target' in input) {
              ack(
                await handleSubscription(
                  context,
                  socket,
                  name as SubscriptionName,
                  input,
                  deadline,
                ),
              );
              return;
            }

            const service = state.socket,
              generation = state.epoch;

            if (!service?.connected || Date.now() >= deadline) throw failure('SERVICE_UNAVAILABLE');

            await authenticate(socket);

            if (generation !== state.epoch || !socket.connected || socket.data.revoked)
              throw failure('SERVICE_UNAVAILABLE');

            // 내부 외피는 서비스 연결 전용이다. Spring은 JWT/멱등성/업무 권한을 다시 검사한다.
            const remaining = deadline - Date.now();

            if (remaining <= 0) throw failure('SERVICE_UNAVAILABLE');

            if (
              'target' in input ||
              name === 'subscription.request' ||
              name === 'subscription.release'
            )
              throw failure('INVALID_INPUT');

            const reply: unknown = await service.timeout(remaining).emitWithAck(name, {
              accessToken: socket.data.token,
              command: input,
            });

            if (!storedAck(reply, input)) throw failure('SERVICE_UNAVAILABLE');

            if (socket.connected && !socket.data.revoked) ack(reply);

            if (reply.ok === false && authCodes.includes(reply.error.code))
              revoke(socket, reply.error.code as AuthFailureCode);
          } catch (error) {
            const safe = apiError(error) ? error : failure('SERVICE_UNAVAILABLE');
            reject(safe);

            if (authCodes.includes(safe.code)) revoke(socket, safe.code as AuthFailureCode);
          } finally {
            pending--;
          }
        };

        if (name.startsWith('subscription.')) subscriptions = subscriptions.then(run);
        else void run();
      });
    }
  });
}
