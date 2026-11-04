import { create } from 'zustand';
import { realtime } from '@/shared/realtime';
import type { ConnectionState } from '@/shared/realtime/types';

export const useConnection = create<{
  state: ConnectionState;
  setState: (state: ConnectionState) => void;
}>((set) => ({
  // transport 초기값과 맞춘다. 연결 전 첫 렌더에 끊김 안내가 깜빡이지 않게 한다.
  state: 'idle',
  setState: (state) => {
    set({ state });
  },
}));

/** 자동 재시도가 멈춘 연결을 사용자가 다시 연다. */
export function reconnect(token: string | null) {
  try {
    if (token) realtime.connect(token);
  } catch {
    useConnection.getState().setState('disconnected');
  }
}
