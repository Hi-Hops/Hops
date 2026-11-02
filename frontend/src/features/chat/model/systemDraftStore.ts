import { create } from 'zustand';
import { onSessionEvent } from '@/shared/lib/sessionEvents';
import type { PendingSystemCommand } from '@/shared/api/systemCommands';
type Draft = { content: string; systemId: string | null; pending: PendingSystemCommand | null };
type State = {
  drafts: Partial<Record<string, Draft>>;
  set: (key: string, draft: Draft) => void;
  clear: () => void;
};
export const useSystemDrafts = create<State>((set) => ({
  drafts: {},
  set: (key, draft) => {
    set((state) => ({ drafts: { ...state.drafts, [key]: draft } }));
  },
  clear: () => {
    set({ drafts: {} });
  },
}));
onSessionEvent((event) => {
  if (event === 'logout' || event === 'switched') useSystemDrafts.getState().clear();
});
