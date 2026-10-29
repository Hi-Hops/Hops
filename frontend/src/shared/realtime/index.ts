import { connectionMode } from '@/shared/config/connection';
import { realtime as mock } from './mockTransport';
import { SseTransport } from './sseTransport';
import type { RealtimeTransport } from './types';
const live = new SseTransport();
const transport = (): RealtimeTransport => (connectionMode() === 'mock' ? mock : live);
export const realtime: RealtimeTransport = {
  connect: (token) => {
    transport().connect(token);
  },
  disconnect: () => {
    transport().disconnect();
  },
  getState: () => transport().getState(),
  onConnection: (listener) => transport().onConnection(listener),
  on: (name, handler) => transport().on(name, handler),
  onSync: (listener) => transport().onSync(listener),
  subscribe: (target, listener) => transport().subscribe(target, listener),
};
