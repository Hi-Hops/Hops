import { systemMockBackend } from '@/shared/api/systemMockBackend';
import type { SubscriptionTarget } from '@/shared/api/types';
import { TransportEvents } from './transportEvents';
import type { RealtimeTransport, TargetState } from './types';

export class SystemMockTransport extends TransportEvents implements RealtimeTransport {
  private token: string | null = null;
  private entries = new Map<
    string,
    { target: SubscriptionTarget; listeners: Set<(state: TargetState) => void> }
  >();
  private offChange: (() => void) | null = null;
  connect(token: string) {
    systemMockBackend.currentUser(token);
    this.token = token;
    this.setState('connected');
    this.offChange?.();
    this.offChange = systemMockBackend.onChange(() => {
      this.sync();
    });
    this.sync();
  }
  private sync() {
    if (!this.token) return;
    for (const { target, listeners } of this.entries.values()) {
      try {
        systemMockBackend.authorize(this.token, target);
        listeners.forEach((listener) => {
          listener('connected');
        });
        void this.synchronize(target).catch(() => undefined);
      } catch {
        listeners.forEach((listener) => {
          listener('denied');
        });
      }
    }
  }
  disconnect() {
    this.offChange?.();
    this.offChange = null;
    this.token = null;
    this.entries.clear();
    this.setState('idle');
  }
  subscribe(target: SubscriptionTarget, listener?: (state: TargetState) => void) {
    const key = `${target.type}:${target.id}`;
    const entry = this.entries.get(key) ?? {
      target,
      listeners: new Set<(state: TargetState) => void>(),
    };
    const notify = (state: TargetState) => {
      listener?.(state);
    };
    entry.listeners.add(notify);
    this.entries.set(key, entry);
    if (this.token) this.sync();
    else notify('idle');
    return () => {
      entry.listeners.delete(notify);
      if (!entry.listeners.size) this.entries.delete(key);
    };
  }
}
export const systemMockTransport = new SystemMockTransport();
