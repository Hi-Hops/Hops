import { mockWorkspace } from '@/shared/api/mockWorkspace';
import { mockBackend } from '@/shared/api/mockBackend';
import type { SubscriptionTarget } from '@/shared/api/types';
import { TransportEvents } from './transportEvents';
import type { EventMap, RealtimeTransport, TargetState } from './types';

export class MockTransport extends TransportEvents implements RealtimeTransport {
  private token: string | null = null;
  private subscriptions = new Map<
    string,
    { target: SubscriptionTarget; listeners: Set<(state: TargetState) => void> }
  >();
  connect(token: string) {
    mockBackend.currentUser(token);
    this.token = token;
    this.setState('connected');
    for (const entry of this.subscriptions.values()) {
      try {
        mockBackend.authorizeTarget(token, entry.target);
        for (const listener of entry.listeners) listener('connected');
        void this.synchronize(entry.target).catch(() => undefined);
      } catch {
        for (const listener of entry.listeners) listener('denied');
      }
    }
  }
  disconnect() {
    this.token = null;
    this.subscriptions.clear();
    this.setState('idle');
  }
  interrupt() {
    this.setState('disconnected');
  }
  clear() {
    this.disconnect();
  }
  subscriptionCount() {
    return [...this.subscriptions.values()].reduce((n, e) => n + e.listeners.size, 0);
  }
  subscribe(target: SubscriptionTarget, listener?: (state: TargetState) => void) {
    const key = JSON.stringify(target);
    const notify = (state: TargetState) => {
      listener?.(state);
    };
    const entry = this.subscriptions.get(key) ?? {
      target,
      listeners: new Set<(state: TargetState) => void>(),
    };
    try {
      if (this.token) mockBackend.authorizeTarget(this.token, target);
    } catch {
      notify('denied');
      return () => undefined;
    }
    entry.listeners.add(notify);
    this.subscriptions.set(key, entry);
    notify(this.token ? 'connected' : 'idle');
    if (this.token) void this.synchronize(target).catch(() => undefined);
    return () => {
      entry.listeners.delete(notify);
      if (!entry.listeners.size) this.subscriptions.delete(key);
    };
  }
  publish<K extends keyof EventMap>(name: K, event: EventMap[K]) {
    if (!this.token || this.getState() !== 'connected') return;
    if (name === 'auth.invalidated' || name === 'resync.required') {
      this.emit(name, event);
      return;
    }
    const target =
      'target' in event
        ? event.target
        : 'inquiryId' in event
          ? { type: 'INQUIRY', id: event.inquiryId }
          : null;
    if (!target || !this.subscriptions.has(JSON.stringify(target))) return;
    try {
      mockBackend.authorizeTarget(this.token, target as SubscriptionTarget);
    } catch {
      return;
    }
    this.emit(name, event);
  }
}
export const realtime = new MockTransport();
mockWorkspace.setPublisher((name, event) => {
  realtime.publish(name, event);
});
