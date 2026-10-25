import { create } from 'zustand';
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
