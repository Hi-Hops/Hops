import { Navigate, Outlet, useLocation } from 'react-router';
import { useSession } from '@/features/auth';

export function RequireAuth() {
  const user = useSession((state) => state.user);
  const previousUserId = useSession((state) => state.previousUserId);
  const location = useLocation();
  if (!user)
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: location.pathname + location.search, expired: Boolean(previousUserId) }}
      />
    );
  return <Outlet />;
}
