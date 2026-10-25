import { create } from 'zustand';
import type { CurrentUser, LoginResponse } from '@/shared/api/types';
import {
  apiRequest,
  ApiError,
  registerRefreshHandler,
  registerTokenProvider,
  registerUnauthorizedHandler,
} from '@/shared/api/client';
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

let refreshFlight: { token: string; promise: Promise<boolean> } | null = null;
export function refreshSession(token: string): Promise<boolean> {
  if (refreshFlight?.token === token) return refreshFlight.promise;
  const promise = (async () => {
    const previous = useSession.getState();
    if (previous.token !== token || !previous.user) return false;
    try {
      const result = await apiRequest<LoginResponse>('/api/auth/refresh', {
        method: 'POST',
        skipRefresh: true,
      });
      if (useSession.getState().token !== token) return false;
      if (
        result.user.id !== previous.user.id ||
        result.user.department.id !== previous.user.department.id ||
        JSON.stringify([...result.user.roles].sort()) !==
          JSON.stringify([...previous.user.roles].sort())
      ) {
        useSession.getState().expire();
        return false;
      }
      useSession.getState().activate(result);
      return true;
    } catch (error) {
      if (
        error instanceof ApiError &&
        (error.status === 401 || error.body.code === 'AUTH_CONTEXT_CHANGED')
      ) {
        if (useSession.getState().token === token) useSession.getState().expire();
        return false;
      }
      throw error;
    } finally {
      if (refreshFlight?.token === token) refreshFlight = null;
    }
  })();
  refreshFlight = { token, promise };
  return promise;
}
registerRefreshHandler(refreshSession);
