import { create } from 'zustand';
import type { CurrentUser, LoginResponse } from '@/shared/api/types';
import { registerTokenProvider, registerUnauthorizedHandler } from '@/shared/api/client';
import { emitSessionEvent } from '@/shared/lib/sessionEvents';

type SessionState = {
  user: CurrentUser | null;
  token: string | null;
  expiresAt: string | null;
  previousUserId: string | null;
  activate: (session: LoginResponse) => void;
  expire: () => void;
  logout: () => void;
};

export const useSession = create<SessionState>((set, get) => ({
  user: null,
  token: null,
  expiresAt: null,
  previousUserId: null,
  activate: (session) => {
    const previous = get().user?.id ?? get().previousUserId;
    if (previous && previous !== session.user.id) emitSessionEvent('switched');
    set({
      user: session.user,
      token: session.accessToken,
      expiresAt: session.expiresAt,
      previousUserId: session.user.id,
    });
  },
  expire: () => {
    emitSessionEvent('expired');
    set({ user: null, token: null, expiresAt: null });
  },
  logout: () => {
    emitSessionEvent('logout');
    set({ user: null, token: null, expiresAt: null, previousUserId: null });
  },
}));
registerTokenProvider(() => useSession.getState().token);
registerUnauthorizedHandler(() => {
  useSession.getState().expire();
});
