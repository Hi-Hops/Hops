import { create } from 'zustand';
import { realtime } from '@/shared/realtime';
import type { ConnectionState } from '@/shared/realtime/types';

export const useConnection = create<{
  state: ConnectionState;
  setState: (state: ConnectionState) => void;
}>((set) => ({
  state: 'disconnected',
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
