import { create } from 'zustand';
import { onSessionEvent } from '@/shared/lib/sessionEvents';
import type { SystemCommand } from '@/shared/api/systemCommands';

type State = {
  pending: Partial<Record<string, SystemCommand>>;
  set: (key: string, value: SystemCommand | null) => void;
};
export const useSystemOperations = create<State>((set) => ({
  pending: {},
  set: (key, value) => {
    set((state) => {
      const pending = value
        ? { ...state.pending, [key]: value }
        : Object.fromEntries(
            Object.entries(state.pending).filter(([storedKey]) => storedKey !== key),
          );
      return { pending };
    });
  },
}));
onSessionEvent((event) => {
  if (event === 'logout' || event === 'switched') useSystemOperations.setState({ pending: {} });
});
