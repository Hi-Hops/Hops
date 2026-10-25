import { connectionMode } from '@/shared/config/connection';
import { realtime as mock } from './mockTransport';
import { SocketTransport } from './socketTransport';
import type { RealtimeTransport } from './types';

const live = new SocketTransport();
function transport(): RealtimeTransport {
  return connectionMode() === 'mock' ? mock : live;
}

// 실행 중에는 모드를 바꾸지 않는다. 테스트는 호출 전에 모드를 고정한다.
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
  send: (name, command) => transport().send(name, command),
};
