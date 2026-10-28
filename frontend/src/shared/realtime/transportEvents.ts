import type { SubscriptionTarget } from '@/shared/api/types';
import type { ConnectionState, EventMap } from './types';

export class TransportEvents {
  private state: ConnectionState = 'idle';
  private connections = new Set<(state: ConnectionState) => void>();
  private listeners = new Map<keyof EventMap, Set<(value: unknown) => void>>();
  private syncListeners = new Set<(target: SubscriptionTarget) => Promise<void>>();
  getState() {
    return this.state;
  }
  protected setState(state: ConnectionState) {
    this.state = state;
    for (const listener of this.connections) listener(state);
  }
  onConnection(listener: (state: ConnectionState) => void) {
    this.connections.add(listener);
    return () => {
      this.connections.delete(listener);
    };
  }
  on<K extends keyof EventMap>(name: K, handler: (event: EventMap[K]) => void) {
    // 수신 경계에서 이벤트명·payload 검사 후 전달한다.
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
  protected emit<K extends keyof EventMap>(name: K, event: EventMap[K]) {
    for (const listener of this.listeners.get(name) ?? []) listener(event);
  }
  onSync(listener: (target: SubscriptionTarget) => Promise<void>) {
    this.syncListeners.add(listener);
    return () => {
      this.syncListeners.delete(listener);
    };
  }
  protected async synchronize(target: SubscriptionTarget) {
    await Promise.all([...this.syncListeners].map((listener) => listener(target)));
  }
}
