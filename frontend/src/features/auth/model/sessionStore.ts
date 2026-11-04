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
import { apiPath } from '@/shared/config/api';
import { readLogin } from '@/shared/api/systemWire';

type SessionState = {
  user: CurrentUser | null;
  token: string | null;
  expiresAt: string | null;
  previousUserId: string | null;
  /** 새로고침 직후 Refresh 쿠키로 세션을 되살리는 단계. pending은 확인 전·중, failed는 일시 장애다. */
  restoreStatus: 'pending' | 'failed' | 'settled';
  activate: (session: LoginResponse) => void;
  expire: () => void;
  logout: () => void;
  skipRestore: () => void;
};

export const useSession = create<SessionState>((set, get) => ({
  user: null,
  token: null,
  expiresAt: null,
  previousUserId: null,
  restoreStatus: 'pending',
  activate: (session) => {
    const previous = get().user?.id ?? get().previousUserId;
    if (previous && previous !== session.user.id) emitSessionEvent('switched');
    set({
      user: session.user,
      token: session.accessToken,
      expiresAt: session.expiresAt,
      previousUserId: session.user.id,
      restoreStatus: 'settled',
    });
  },
  expire: () => {
    emitSessionEvent('expired');
    set({ user: null, token: null, expiresAt: null, restoreStatus: 'settled' });
  },
  logout: () => {
    emitSessionEvent('logout');
    set({
      user: null,
      token: null,
      expiresAt: null,
      previousUserId: null,
      restoreStatus: 'settled',
    });
  },
  skipRestore: () => {
    set({ restoreStatus: 'settled' });
  },
}));
registerTokenProvider(() => useSession.getState().token);
registerUnauthorizedHandler(() => {
  useSession.getState().expire();
});

let restoreFlight: Promise<void> | null = null;
/** Access Token은 메모리에만 두므로 새로고침하면 사라진다. 남아 있는 Refresh 쿠키로 페이지당 한 번 복원한다. */
export function restoreSession(): Promise<void> {
  if (restoreFlight) return restoreFlight;
  restoreFlight = (async () => {
    useSession.setState({ restoreStatus: 'pending' });
    try {
      const raw = await apiRequest<unknown>(apiPath('/auth/refresh'), {
        method: 'POST',
        skipRefresh: true,
      });
      const result = readLogin(raw);
      // 확인 중에 로그인·로그아웃이 먼저 끝났으면 늦게 온 복원 결과를 반영하지 않는다.
      if (useSession.getState().restoreStatus !== 'pending') return;
      useSession.getState().activate(result);
    } catch (error) {
      if (useSession.getState().restoreStatus !== 'pending') return;
      // 네트워크 오류·429·5xx는 인증 만료로 확정하지 않는다. 쿠키 없음·만료·폐기 등은 비로그인으로 본다.
      const transient =
        !(error instanceof ApiError) ||
        error.status === 0 ||
        error.status === 429 ||
        error.status >= 500;
      useSession.setState({ restoreStatus: transient ? 'failed' : 'settled' });
    } finally {
      restoreFlight = null;
    }
  })();
  return restoreFlight;
}

let refreshFlight: { token: string; promise: Promise<boolean> } | null = null;
export function refreshSession(token: string): Promise<boolean> {
  if (refreshFlight?.token === token) return refreshFlight.promise;
  const promise = (async () => {
    const previous = useSession.getState();
    if (previous.token !== token || !previous.user) return false;
    try {
      const raw = await apiRequest<unknown>(apiPath('/auth/refresh'), {
        method: 'POST',
        skipRefresh: true,
      });
      const result = readLogin(raw);
      if (useSession.getState().token !== token) return false;
      if (
        result.user.id !== previous.user.id ||
        JSON.stringify([...result.user.primarySystemIds].sort()) !==
          JSON.stringify([...previous.user.primarySystemIds].sort()) ||
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
