import { apiRequest } from '@/shared/api/client';
import type { LoginRequest } from '@/shared/api/types';
import { useSession } from '../model/sessionStore';
import { apiPath } from '@/shared/config/api';
import { readLogin, readUser } from '@/shared/api/systemWire';

export async function login(credentials: LoginRequest) {
  const raw = await apiRequest<unknown>(apiPath('/auth/login'), {
    method: 'POST',
    body: credentials,
  });
  const result = readLogin(raw);
  const rawUser = await apiRequest<unknown>(apiPath('/auth/me'), { token: result.accessToken });
  const user = readUser(rawUser);
  useSession.getState().activate({ ...result, user });
  return user;
}

export async function logout() {
  try {
    await apiRequest<undefined>(apiPath('/auth/logout'), { method: 'POST' });
  } finally {
    useSession.getState().logout();
  }
}
