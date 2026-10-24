import type { CurrentUser } from '@/shared/api/types';

export function canReadTickets(user: CurrentUser | null) {
  return Boolean(user && (user.department.isRoutable || user.roles.includes('BIZ_ADMIN')));
}
