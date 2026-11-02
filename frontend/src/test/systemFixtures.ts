import { users } from '@/shared/api/systemMockData';
import type { LoginResponse } from '@/shared/api/types';

export function sessionFor(role: 'requester' | 'staff' | 'fileStaff' | 'admin'): LoginResponse {
  const user = { requester: users[0], staff: users[1], fileStaff: users[3], admin: users[2] }[role];
  return {
    accessToken: `synthetic-${crypto.randomUUID()}`,
    expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
    user: structuredClone(user),
  };
}
