import { useLocation, useNavigate } from 'react-router';
import { LoginForm } from '@/features/auth';
import type { CurrentUser } from '@/shared/api/types';
import styles from './LoginPage.module.css';

export function LoginPage() {
  const location = useLocation();
  const navigate = useNavigate();
  // React Router의 외부 상태 경계. 주소·토큰·입력 원문을 쿼리에 넣지 않는다.
  const state = location.state as { from?: string; expired?: boolean } | null;
  function handleSuccess(user: CurrentUser) {
    const from = state?.from;
    const safeFrom =
      from?.startsWith('/') && !from.startsWith('//') && from !== '/login' ? from : null;
    void navigate(
      safeFrom ??
        (user.department.isRoutable || user.roles.includes('BIZ_ADMIN') ? '/tickets' : '/'),
      { replace: true },
    );
  }
  return (
    <main className={styles.page}>
      <LoginForm onSuccess={handleSuccess} expired={state?.expired} />
    </main>
  );
}
