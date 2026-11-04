import { useEffect } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { LoginForm, restoreSession, useSession } from '@/features/auth';
import type { CurrentUser } from '@/shared/api/types';
import { LoadingState } from '@/shared/ui/LoadingState';
import styles from './LoginPage.module.css';

export function LoginPage() {
  const location = useLocation();
  const navigate = useNavigate();
  // React Router의 외부 상태 경계. 주소·토큰·입력 원문을 쿼리에 넣지 않는다.
  const state = location.state as { from?: string; expired?: boolean } | null;
  const currentUser = useSession((session) => session.user);
  const restoreStatus = useSession((session) => session.restoreStatus);
  // 로그인 화면에서 새로고침해도 Refresh 쿠키가 유효하면 입력 없이 첫 화면으로 보낸다.
  useEffect(() => {
    if (!currentUser && restoreStatus === 'pending') void restoreSession();
  }, [currentUser, restoreStatus]);
  function destination(user: CurrentUser) {
    const from = state?.from;
    const safeFrom =
      from?.startsWith('/') && !from.startsWith('//') && from !== '/login' ? from : null;
    return (
      safeFrom ??
      (user.roles.includes('BIZ_ADMIN')
        ? '/admin/tickets'
        : user.primarySystemIds.length > 0
          ? '/tickets'
          : '/')
    );
  }
  function handleSuccess(user: CurrentUser) {
    void navigate(destination(user), { replace: true });
  }
  if (currentUser) return <Navigate to={destination(currentUser)} replace />;
  if (restoreStatus === 'pending')
    return (
      <main className={styles.page}>
        <LoadingState label="로그인 상태를 확인하고 있어요" />
      </main>
    );
  return (
    <main className={styles.page}>
      <LoginForm onSuccess={handleSuccess} expired={state?.expired} />
    </main>
  );
}
