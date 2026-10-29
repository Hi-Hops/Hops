import { create } from 'zustand';
import type { StoredCommand, StoredCommandName } from '@/shared/api/mutations';
import { onSessionEvent } from '@/shared/lib/sessionEvents';

export type PendingCommand = { event: StoredCommandName; command: StoredCommand };
type Draft = { content: string; pending: PendingCommand | null };
type State = {
  drafts: Partial<Record<string, Draft>>;
  set: (key: string, draft: Draft) => void;
  clear: () => void;
};
export const useDrafts = create<State>((set) => ({
  drafts: {},
  set: (key, draft) => {
    set((state) => ({ drafts: { ...state.drafts, [key]: draft } }));
  },
  clear: () => {
    set({ drafts: {} });
  },
}));
onSessionEvent((event) => {
  if (event === 'logout' || event === 'switched') useDrafts.getState().clear();
});
