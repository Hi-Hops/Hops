import { create } from 'zustand';
import { onSessionEvent } from './sessionEvents';

type Draft = { version: number; values: Record<string, string> };
type DraftState = {
  drafts: Partial<Record<string, Draft>>;
  set: (key: string, draft: Draft | null) => void;
};
const useDrafts = create<DraftState>((set) => ({
  drafts: {},
  set: (key, draft) => {
    set((state) => ({
      drafts: draft
        ? { ...state.drafts, [key]: draft }
        : Object.fromEntries(Object.entries(state.drafts).filter(([name]) => name !== key)),
    }));
  },
}));
onSessionEvent((event) => {
  if (event === 'logout' || event === 'switched') useDrafts.setState({ drafts: {} });
});

/** 서버 조회 사본이 아닌, 작성 중인 입력과 검토 당시 버전만 메모리에 보관한다. */
export function useFormDraft(key: string, version: number, initial: Record<string, string>) {
  const draft = useDrafts((state) => state.drafts[key]);
  const changeFields = (changes: Record<string, string>) => {
    const current = useDrafts.getState().drafts[key];
    useDrafts.getState().set(key, {
      version: current?.version ?? version,
      values: { ...initial, ...current?.values, ...changes },
    });
  };
  return {
    values: draft?.values ?? initial,
    version: draft?.version ?? version,
    stale: Boolean(draft && draft.version !== version),
    dirty: Boolean(draft),
    change: (name: string, value: string) => {
      changeFields({ [name]: value });
    },
    changeFields,
    reviewLatest: () => {
      if (draft) useDrafts.getState().set(key, { ...draft, version });
    },
    clear: () => {
      useDrafts.getState().set(key, null);
    },
  };
}
