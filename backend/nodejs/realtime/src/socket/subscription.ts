import type { SubscriptionCommand, SubscriptionAck } from '../contracts/types.js';
import { failure, room } from '../contracts/validation.js';
import type { ClientSocket, SocketContext } from './types.js';

export type SubscriptionName = 'subscription.request' | 'subscription.release';

export async function handleSubscription(
  context: SocketContext,
  socket: ClientSocket,
  name: SubscriptionName,
  input: SubscriptionCommand,
  deadline: number,
): Promise<SubscriptionAck> {
  if (name === 'subscription.release') {
    socket.leave(room(input.target));

    return { ok: true, requestId: input.requestId, target: input.target };
  }

  const { relay, spring, auth } = context;
  const generation = relay.epoch;

  if (!relay.socket?.connected || Date.now() >= deadline) throw failure('SERVICE_UNAVAILABLE');

  await auth.authenticate(socket);
  await spring.authorize(socket.data.token, input.target);

  if (
    generation !== relay.epoch ||
    Date.now() >= deadline ||
    !socket.connected ||
    socket.data.revoked ||
    Date.now() >= socket.data.expiresAt
  )
    throw failure('SERVICE_UNAVAILABLE');

  socket.join(room(input.target));

  return { ok: true, requestId: input.requestId, target: input.target };
}
