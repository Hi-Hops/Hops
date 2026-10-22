import type { SubscriptionCommand, SubscriptionTarget, ErrorAck } from '@/shared/api/types';
import { mockBackend, MockHttpError } from '@/shared/api/mockBackend';
import type {
  CommandMap,
  CommandName,
  CommandProcessor,
  CommandResult,
  ConnectionState,
  EventMap,
  StoredCommand,
} from './types';

type ConnectionListener = (state: ConnectionState) => void;

export class MockTransport {
  private token: string | null = null;
  private state: ConnectionState = 'disconnected';
  private listeners = new Map<keyof EventMap, Set<(event: unknown) => void>>();
  private connectionListeners = new Set<ConnectionListener>();
  private subscriptions = new Map<string, number>();
  private processor: CommandProcessor | null = null;
  private loseAck = false;

  setProcessor(processor: CommandProcessor) {
    this.processor = processor;
  }
  loseNextAck() {
    this.loseAck = true;
  }
  getState() {
    return this.state;
  }
  private setState(state: ConnectionState) {
    this.state = state;
    for (const listener of this.connectionListeners) listener(state);
  }
  onConnection(listener: ConnectionListener) {
    this.connectionListeners.add(listener);
    return () => {
      this.connectionListeners.delete(listener);
    };
  }
  connect(token: string) {
    mockBackend.currentUser(token);
    this.token = token;
    this.setState('connected');
  }
  disconnect() {
    this.token = null;
    this.subscriptions.clear();
    this.setState('disconnected');
  }
  interrupt(relay = false) {
    this.subscriptions.clear();
    this.setState(relay ? 'relayError' : 'disconnected');
  }
  clear() {
    this.disconnect();
    this.listeners.clear();
    this.connectionListeners.clear();
    this.loseAck = false;
  }
  subscriptionCount() {
    return [...this.subscriptions.values()].reduce((sum, count) => sum + count, 0);
  }

  on<K extends keyof EventMap>(name: K, handler: (event: EventMap[K]) => void) {
    // 이벤트 수신 경계. 공통 이벤트명으로 연결된 payload만 전달한다.
    const receive = (value: unknown) => {
      handler(value as EventMap[K]);
    };
    const group = this.listeners.get(name) ?? new Set();
    group.add(receive);
    this.listeners.set(name, group);
    return () => {
      group.delete(receive);
    };
  }

  publish<K extends keyof EventMap>(name: K, event: EventMap[K]) {
    if (this.state !== 'connected') return;
    const target: SubscriptionTarget =
      'target' in event ? event.target : { type: 'INQUIRY', id: event.inquiryId };
    if (!this.subscriptions.has(JSON.stringify(target))) return;
    for (const listener of this.listeners.get(name) ?? []) listener(event);
  }

  async send<K extends CommandName>(name: K, command: CommandMap[K]): Promise<CommandResult<K>> {
    await new Promise<void>((resolve) => setTimeout(resolve, 35));
    if (!this.token || this.state !== 'connected') return { ok: 'unknown' };
    let result: unknown;
    try {
      if (name === 'subscription.request' || name === 'subscription.release') {
        // 명령 경계: event 이름과 SubscriptionCommand의 타입 짝은 호출 시 검사한다.
        const subscription = command as SubscriptionCommand;
        const key = JSON.stringify(subscription.target);
        if (name === 'subscription.request') {
          mockBackend.authorizeTarget(this.token, subscription.target);
          this.subscriptions.set(key, (this.subscriptions.get(key) ?? 0) + 1);
        } else {
          const count = this.subscriptions.get(key) ?? 0;
          if (count <= 1) this.subscriptions.delete(key);
          else this.subscriptions.set(key, count - 1);
        }
        result = { ok: true, requestId: command.requestId, target: subscription.target };
      } else {
        const processor = this.processor;
        if (!processor)
          throw new MockHttpError(503, {
            code: 'SERVICE_UNAVAILABLE',
            message: '메시지 연결을 준비하고 있어요.',
            requestId: command.requestId,
            retryable: true,
          });
        // 저장 명령 variant는 이 경계에서만 변환한다. 구독 ACK와 섞지 않는다.
        result = processor(name, command as StoredCommand, this.token);
      }
    } catch (error) {
      if (!(error instanceof MockHttpError)) throw error;
      result = {
        ok: false,
        requestId: command.requestId,
        error: { ...error.body, requestId: command.requestId },
      } satisfies ErrorAck;
    }
    if (this.loseAck) {
      this.loseAck = false;
      return { ok: 'unknown' };
    }
    // 전송 경계: event별 반환형은 Reply<K>이며 서버 스키마 variant와 일치한다.
    return result as CommandResult<K>;
  }
}

export const realtime = new MockTransport();
