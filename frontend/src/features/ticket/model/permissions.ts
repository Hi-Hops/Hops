import type { CurrentUser } from '@/shared/api/types';

export function canReadTickets(user: CurrentUser | null) {
  if (!user) return false;
  return user.primarySystemIds.length > 0 || user.roles.includes('BIZ_ADMIN');
}
