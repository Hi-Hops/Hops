import { randomUUID } from 'node:crypto';
import type {
  AuthFailureCode,
  BusinessName,
  BusinessEventMap,
  PublicEvents,
} from '../contracts/types.js';
import { apiError, eventTarget, object, room, uuid } from '../contracts/validation.js';
import { authCodes } from './auth.js';
import type { ClientSocket, SocketContext } from './types.js';

function publish<K extends BusinessName>(
  socket: ClientSocket,
  name: K,
  event: BusinessEventMap[K],
) {
  socket.emit(name, ...([event] as Parameters<PublicEvents[K]>));
}

export function registerRelayHandlers(context: SocketContext) {
  const {
    clients,
    services,
    relay: state,
    spring,
    auth: { authenticate, revoke },
  } = context;
  const status = (value: 'AVAILABLE' | 'UNAVAILABLE') =>
    clients.emit('relay.status', {
      eventId: randomUUID(),
      status: value,
      occurredAt: new Date().toISOString(),
    });
  services.on('connection', (socket) => {
    state.epoch++;
    status('AVAILABLE');
    socket.on('disconnect', () => {
      if (state.socket !== socket) return;

      state.socket = null;
      state.epoch++;

      for (const client of clients.sockets.values())
        for (const name of client.rooms) if (name !== client.id) client.leave(name);

      status('UNAVAILABLE');
    });
    socket.on('auth.invalidated', (input) => {
      if (
        state.socket !== socket ||
        !object(input) ||
        !uuid(input.userId) ||
        typeof input.code !== 'string' ||
        !['ACCESS_TOKEN_EXPIRED', 'AUTH_CONTEXT_CHANGED', 'REFRESH_TOKEN_REVOKED'].includes(
          input.code,
        )
      )
        return;

      for (const client of clients.sockets.values())
        if (client.data.userId === input.userId) revoke(client, input.code as AuthFailureCode);
    });

    for (const name of [
      'agent.progress',
      'answer.delta',
      'message.stored',
      'state.changed',
    ] as const) {
      socket.on(name, async (event) => {
        const target = eventTarget(name, event);

        if (!target || state.socket !== socket) return;

        const generation = state.epoch;
        // room은 후보 집합일 뿐이다. 이관/차단 뒤 통지 유실에도 현재 권한을 재검증한다.
        await Promise.all(
          [...clients.sockets.values()]
            .filter((c) => c.rooms.has(room(target)))
            .map(async (client) => {
              try {
                await authenticate(client);
                await spring.authorize(client.data.token, target);

                if (
                  generation === state.epoch &&
                  state.socket === socket &&
                  client.connected &&
                  !client.data.revoked &&
                  client.rooms.has(room(target)) &&
                  Date.now() < client.data.expiresAt
                )
                  publish(client, name, event as BusinessEventMap[typeof name]);
              } catch (error) {
                client.leave(room(target));

                if (apiError(error) && authCodes.includes(error.code))
                  revoke(client, error.code as AuthFailureCode);
                else if (!apiError(error) || error.code !== 'ACCESS_DENIED')
                  client.disconnect(true);
              }
            }),
        );
      });
    }
  });
  clients.on('connection', (socket) => {
    socket.emit('relay.status', {
      eventId: randomUUID(),
      status: state.socket ? 'AVAILABLE' : 'UNAVAILABLE',
      occurredAt: new Date().toISOString(),
    });
  });
}
