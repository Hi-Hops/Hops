import { io } from 'socket.io-client';
import type { Socket } from 'socket.io-client';
import { liveConnection } from '@/shared/config/connection';
import { recoverAuthorization, reportUnauthorized } from '@/shared/api/client';
import { isApiError, isRecord } from '@/shared/api/wire';
import type { SubscriptionCommand } from '@/shared/api/types';
import type {
  CommandMap,
  CommandName,
  CommandResult,
  ConnectionState,
  EventMap,
  RealtimeTransport,
} from './types';

type ServerEvents = { [K in keyof EventMap]: (event: EventMap[K]) => void };
type ClientEvents = Record<CommandName, (command: unknown, ack: (reply: unknown) => void) => void>;
type SubscriptionEntry = { count: number; reply: Promise<CommandResult<'subscription.request'>> };

function validReply(name: CommandName, command: CommandMap[CommandName], reply: unknown) {
  if (!isRecord(reply) || reply.requestId !== command.requestId) return false;
  if (reply.ok === false)
    return isApiError(reply.error) && reply.error.requestId === command.requestId;
  if (reply.ok !== true) return false;
  if (name === 'subscription.request' || name === 'subscription.release') {
    return (
      'target' in command &&
      isRecord(reply.target) &&
      reply.target.type === command.target.type &&
      reply.target.id === command.target.id
    );
  }
  return (
    typeof reply.inquiryId === 'string' &&
    typeof reply.messageId === 'string' &&
    Number.isInteger(reply.inquiryVersion) &&
    Number(reply.inquiryVersion) >= 1 &&
    Number.isInteger(reply.revision) &&
    Number(reply.revision) >= 1 &&
    (reply.runId === null || typeof reply.runId === 'string') &&
    typeof reply.effect === 'string' &&
    (!('inquiryId' in command) || reply.inquiryId === command.inquiryId) &&
    (!('messageId' in command) || reply.messageId === command.messageId)
  );
}

export class SocketTransport implements RealtimeTransport {
  private socket: Socket<ServerEvents, ClientEvents> | null = null;
  private token: string | null = null;
  private state: ConnectionState = 'disconnected';
  private connectionListeners = new Set<(state: ConnectionState) => void>();
  private listeners = new Map<keyof EventMap, Set<(event: unknown) => void>>();
  private pending = new Set<() => void>();
  private subscriptions = new Map<string, SubscriptionEntry>();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private ackTimeout = 10_000,
    private reconnectWindow = 30_000,
  ) {}
  private clearReconnectTimer() {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
  }
  private startReconnectTimer(windowMs = this.reconnectWindow) {
    if (this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.socket?.disconnect();
      this.setState('disconnected');
    }, windowMs);
  }

  getState() {
    return this.state;
  }
  private setState(state: ConnectionState) {
    this.state = state;
    for (const listener of this.connectionListeners) listener(state);
  }
  onConnection(listener: (state: ConnectionState) => void) {
    this.connectionListeners.add(listener);
    return () => {
      this.connectionListeners.delete(listener);
    };
  }
  on<K extends keyof EventMap>(name: K, handler: (event: EventMap[K]) => void) {
    // Socket.IO 이벤트 역직렬화 경계. 서버가 공통 EventMap 형태로 전송한다.
    const receive = (event: unknown) => {
      handler(event as EventMap[K]);
    };
    const group = this.listeners.get(name) ?? new Set();
    group.add(receive);
    this.listeners.set(name, group);
    return () => {
      group.delete(receive);
    };
  }
  private publish<K extends keyof EventMap>(name: K, event: EventMap[K]) {
    if (this.state !== 'connected') return;
    for (const listener of this.listeners.get(name) ?? []) listener(event);
  }
  private reset() {
    this.subscriptions.clear();
    for (const settle of this.pending) settle();
    this.pending.clear();
  }
  connect(token: string) {
    this.disconnect();
    const config = liveConnection();
    this.token = token;
    const socket: Socket<ServerEvents, ClientEvents> = io(config.socketUrl, {
      path: config.socketPath,
      auth: { token },
      autoConnect: false,
      forceNew: true,
      reconnection: true,
      reconnectionDelay: 1000,
      reconnectionDelayMax: 5000,
      randomizationFactor: 0.5,
      timeout: 5000,
    });
    this.socket = socket;
    this.setState('connecting');
    this.startReconnectTimer(Math.max(5000, this.reconnectWindow));
    socket.on('connect', () => {
      this.clearReconnectTimer();
      this.setState('connected');
    });
    socket.on('disconnect', () => {
      this.reset();
      this.setState('connecting');
      this.startReconnectTimer();
    });
    socket.on('connect_error', (error) => {
      this.reset();
      const data: unknown = error.data;
      this.setState(
        isApiError(data) && data.code === 'SERVICE_UNAVAILABLE' ? 'relayError' : 'disconnected',
      );
      if (
        isApiError(data) &&
        ['UNAUTHENTICATED', 'ACCESS_TOKEN_EXPIRED', 'AUTH_CONTEXT_CHANGED'].includes(data.code)
      ) {
        this.clearReconnectTimer();
        socket.disconnect();
        if (data.code === 'AUTH_CONTEXT_CHANGED') reportUnauthorized(token);
        else
          void recoverAuthorization(token).catch(() => {
            this.setState('disconnected');
          });
      }
    });
    socket.on('agent.progress', (event) => {
      this.publish('agent.progress', event);
    });
    socket.on('answer.delta', (event) => {
      this.publish('answer.delta', event);
    });
    socket.on('message.stored', (event) => {
      this.publish('message.stored', event);
    });
    socket.on('state.changed', (event) => {
      this.publish('state.changed', event);
    });
    socket.connect();
  }
  disconnect() {
    this.clearReconnectTimer();
    this.reset();
    this.socket?.removeAllListeners();
    this.socket?.disconnect();
    this.socket = null;
    this.token = null;
    this.setState('disconnected');
  }

  private emit<K extends CommandName>(name: K, command: CommandMap[K]): Promise<CommandResult<K>>;
  private emit(
    name: CommandName,
    command: CommandMap[CommandName],
  ): Promise<CommandResult<CommandName>> {
    const socket = this.socket;
    const token = this.token;
    if (!socket?.connected) return Promise.resolve({ ok: 'unknown' });
    return new Promise((resolve) => {
      let done = false;
      const unknown = () => {
        finish({ ok: 'unknown' });
      };
      const finish = (reply: CommandResult<CommandName>) => {
        if (done) return;
        done = true;
        this.pending.delete(unknown);
        resolve(reply);
      };
      this.pending.add(unknown);
      socket.timeout(this.ackTimeout).emit(name, command, (error: Error | null, reply: unknown) => {
        if (done) return;
        if (error || !validReply(name, command, reply)) {
          unknown();
          return;
        }
        // 이벤트별 ACK variant와 requestId를 확인한 외부 데이터 경계다.
        const accepted = reply as CommandResult<CommandName>;
        if (
          accepted.ok === false &&
          ['UNAUTHENTICATED', 'ACCESS_TOKEN_EXPIRED'].includes(accepted.error.code)
        ) {
          finish({ ok: 'unknown' });
          void recoverAuthorization(token).catch(() => {
            this.setState('disconnected');
          });
          return;
        }
        if (accepted.ok === false && accepted.error.code === 'AUTH_CONTEXT_CHANGED')
          reportUnauthorized(token);
        finish(accepted);
      });
    });
  }
  private async subscribe(
    name: 'subscription.request' | 'subscription.release',
    command: SubscriptionCommand,
  ) {
    const key = JSON.stringify(command.target);
    const entry = this.subscriptions.get(key);
    if (name === 'subscription.release') {
      if (entry && --entry.count > 0)
        return { ok: true as const, requestId: command.requestId, target: command.target };
      this.subscriptions.delete(key);
      return this.emit(name, command);
    }
    const current = entry ?? { count: 0, reply: this.emit('subscription.request', command) };
    current.count++;
    this.subscriptions.set(key, current);
    const reply = await current.reply;
    if (reply.ok !== true && this.subscriptions.get(key) === current)
      this.subscriptions.delete(key);
    return reply.ok === 'unknown'
      ? reply
      : {
          ...reply,
          requestId: command.requestId,
          ...(!reply.ok ? { error: { ...reply.error, requestId: command.requestId } } : {}),
        };
  }
  async send<K extends CommandName>(name: K, command: CommandMap[K]): Promise<CommandResult<K>> {
    if (name === 'subscription.request' || name === 'subscription.release') {
      // 제네릭 명령명으로 좁히지 못하는 TS 경계. 호출부는 CommandMap으로 검사한다.
      return (await this.subscribe(name, command as SubscriptionCommand)) as CommandResult<K>;
    }
    return this.emit(name, command);
  }
}
