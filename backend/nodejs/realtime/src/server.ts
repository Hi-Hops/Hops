import { createServer } from 'node:http';
import { Server } from 'socket.io';
import type {
  RealtimeConfig,
  SpringClient,
  ClientEvents,
  PublicEvents,
  InterServerEvents,
  SocketData,
} from './contracts/types.js';
import { createSpringClient } from './clients/spring.client.js';
import { registerAuthentication } from './socket/auth.js';
import { registerRelayHandlers } from './socket/relay.js';
import { registerCommandHandlers } from './socket/commands.js';
import type { ServiceNamespace, RelayState, SocketContext } from './socket/types.js';

export function createRealtimeServer(
  config: RealtimeConfig,
  spring: SpringClient = createSpringClient(config),
) {
  if (
    !config.springCredential ||
    !config.nodeCredential ||
    config.springCredential === config.nodeCredential
  )
    throw new Error('서로 다른 Node/Spring 서비스 자격증명이 필요합니다.');

  if (!config.origins?.length) throw new Error('허용 Origin이 필요합니다.');

  const http = createServer((req, res) => {
    res.writeHead(req.url === '/healthz' ? 200 : 404, {
      'Content-Type': 'application/json',
    });
    res.end(JSON.stringify(req.url === '/healthz' ? { status: 'UP' } : { code: 'NOT_FOUND' }));
  });
  const io = new Server<ClientEvents, PublicEvents, InterServerEvents, SocketData>(http, {
    path: config.path ?? '/socket.io',
    maxHttpBufferSize: 65536,
    cors: { origin: config.origins, methods: ['GET', 'POST'] },
    allowRequest: (req, done) =>
      done(null, !req.headers.origin || config.origins.includes(req.headers.origin)),
  });
  const clients = io.of('/');
  const services: ServiceNamespace = io.of('/spring');
  const relay: RelayState = { socket: null, epoch: 0 };
  const auth = registerAuthentication(clients, services, config, spring, relay);
  const context: SocketContext = {
    clients,
    services,
    relay,
    spring,
    auth,
    ackTimeout: config.ackTimeout ?? 10000,
  };
  registerRelayHandlers(context);
  registerCommandHandlers(context);

  return {
    http,
    io,
    close: () =>
      new Promise<void>((resolve, reject) =>
        io.close((error) => (error ? reject(error) : resolve())),
      ),
  };
}
