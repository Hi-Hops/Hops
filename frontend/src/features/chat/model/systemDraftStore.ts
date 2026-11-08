import { create } from 'zustand';
import { onSessionEvent } from '@/shared/lib/sessionEvents';
import type { PendingSystemCommand } from '@/shared/api/systemCommands';
type Draft = { content: string; systemId: string | null; pending: PendingSystemCommand | null };
export type DraftImage = {
  blob: Blob;
  requestId: string;
  attachmentId: string | null;
  attempted: boolean;
};
type State = {
  drafts: Partial<Record<string, Draft>>;
  images: Partial<Record<string, DraftImage[]>>;
  set: (key: string, draft: Draft) => void;
  setImages: (key: string, images: DraftImage[]) => void;
  clear: () => void;
};
export const useSystemDrafts = create<State>((set) => ({
  drafts: {},
  images: {},
  set: (key, draft) => {
    set((state) => ({ drafts: { ...state.drafts, [key]: draft } }));
  },
  setImages: (key, images) => {
    set((state) => ({ images: { ...state.images, [key]: images } }));
  },
  clear: () => {
    set({ drafts: {}, images: {} });
  },
}));
onSessionEvent((event) => {
  if (event === 'logout' || event === 'switched') useSystemDrafts.getState().clear();
});
