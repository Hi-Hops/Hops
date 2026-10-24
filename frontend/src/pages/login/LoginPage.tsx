import { Navigate, useLocation, useNavigate } from 'react-router';
import { LoginForm, useSession } from '@/features/auth';
import type { CurrentUser } from '@/shared/api/types';
import styles from './LoginPage.module.css';

export function LoginPage() {
  const location = useLocation();
  const navigate = useNavigate();
  // React Router의 외부 상태 경계. 주소·토큰·입력 원문을 쿼리에 넣지 않는다.
  const state = location.state as { from?: string; expired?: boolean } | null;
  const currentUser = useSession((session) => session.user);
  function destination(user: CurrentUser) {
    const from = state?.from;
    const safeFrom =
      from?.startsWith('/') && !from.startsWith('//') && from !== '/login' ? from : null;
    return (
      safeFrom ??
      (user.roles.includes('BIZ_ADMIN')
        ? '/admin/tickets'
        : user.department.isRoutable
          ? '/tickets'
          : '/')
    );
  }
  function handleSuccess(user: CurrentUser) {
    void navigate(destination(user), { replace: true });
  }
  if (currentUser) return <Navigate to={destination(currentUser)} replace />;
  return (
    <main className={styles.page}>
      <LoginForm onSuccess={handleSuccess} expired={state?.expired} />
    </main>
  );
}
