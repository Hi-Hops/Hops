import { useEffect } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { restoreSession, useSession } from '@/features/auth';
import { Button } from '@/shared/ui/Button';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';

export function RequireAuth() {
  const user = useSession((state) => state.user);
  const previousUserId = useSession((state) => state.previousUserId);
  const restoreStatus = useSession((state) => state.restoreStatus);
  const location = useLocation();
  useEffect(() => {
    if (!user && restoreStatus === 'pending') void restoreSession();
  }, [user, restoreStatus]);
  if (!user && restoreStatus === 'pending')
    return <LoadingState label="로그인 상태를 확인하고 있어요" />;
  if (!user && restoreStatus === 'failed')
    return (
      <div>
        <ErrorState
          title="로그인 상태를 확인하지 못했어요"
          description="인증 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."
          onRetry={() => {
            void restoreSession();
          }}
        />
        <Button
          variant="secondary"
          onClick={() => {
            useSession.getState().skipRestore();
          }}
        >
          로그인 화면으로
        </Button>
      </div>
    );
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
