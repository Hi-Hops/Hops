import { apiRequest } from '@/shared/api/client';
import type { CurrentUser, LoginRequest, LoginResponse } from '@/shared/api/types';
import { useSession } from '../model/sessionStore';

export async function login(credentials: LoginRequest) {
  const result = await apiRequest<LoginResponse>('/api/auth/login', {
    method: 'POST',
    body: credentials,
  });
  const user = await apiRequest<CurrentUser>('/api/auth/me', { token: result.accessToken });
  useSession.getState().activate({ ...result, user });
  return user;
}

export async function logout() {
  try {
    await apiRequest<undefined>('/api/auth/logout', { method: 'POST' });
  } finally {
    useSession.getState().logout();
  }
}
