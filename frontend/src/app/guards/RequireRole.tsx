import { Navigate, Outlet } from 'react-router';
import { useSession } from '@/features/auth';
import type { Role } from '@/shared/api/types';

export function RequireRole({ role }: { role: Role }) {
  const user = useSession((state) => state.user);
  return user?.roles.includes(role) ? <Outlet /> : <Navigate to="/403" replace />;
}
