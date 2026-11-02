import { paths } from '@/shared/config/paths';
import { connectionMode } from '@/shared/config/connection';
import { NavLink, Outlet, useNavigate } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { logout, refreshSession, useSession } from '@/features/auth';
import { RealtimeBoundary } from '@/features/realtime';
import { RecentInquiries } from '@/features/inquiry';
import { getSystems } from '@/features/ticket';
import { Button } from '@/shared/ui/Button';
import { onSessionEvent } from '@/shared/lib/sessionEvents';
import styles from './AppLayout.module.css';

export function AppLayout() {
  const user = useSession((state) => state.user);
  const expiresAt = useSession((state) => state.expiresAt);
  const [refreshError, setRefreshError] = useState(false);
  const client = useQueryClient();
  const navigate = useNavigate();
  const catalog = useQuery({
    queryKey: ['hops-system-1', 'systems', user?.id],
    queryFn: ({ signal }) => getSystems(signal),
    enabled: Boolean(user),
  });
  useEffect(
    () =>
      onSessionEvent(() => {
        client.clear();
      }),
    [client],
  );
  useEffect(() => {
    if (!expiresAt) return;
    const timer = setTimeout(
      () => {
        const token = useSession.getState().token;
        if (token)
          void refreshSession(token).catch(() => {
            setRefreshError(true);
          });
      },
      Math.max(0, Date.parse(expiresAt) - Date.now()),
    );
    return () => {
      clearTimeout(timer);
    };
  }, [expiresAt]);
  if (!user) return null;
  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <NavLink className={styles.brand} to="/">
          <span aria-hidden="true" />
          Hops
        </NavLink>
        {connectionMode() === 'mock' && <span className={styles.mock}>Mock 체험</span>}
        <div className={styles.user}>
          <span className={styles.avatar}>{user.name.slice(0, 1)}</span>
          <div>
            <strong>{user.name}</strong>
            <p>{user.department.name}</p>
          </div>
        </div>
      </header>
      {refreshError && (
        <div role="alert">
          인증 서버에 연결하지 못했어요. 입력은 유지됩니다.{' '}
          <Button
            onClick={() => {
              const token = useSession.getState().token;
              if (token)
                void refreshSession(token)
                  .then(() => {
                    setRefreshError(false);
                  })
                  .catch(() => {
                    setRefreshError(true);
                  });
            }}
          >
            인증 다시 연결
          </Button>
        </div>
      )}
      <div className={styles.body}>
        <aside className={styles.sidebar}>
          <nav aria-label="주 메뉴">
            <NavLink
              end
              to="/"
              className={({ isActive }) => (isActive ? styles.active : styles.link)}
            >
              + 새 문의
            </NavLink>
            <NavLink
              to={paths.inquiries}
              className={({ isActive }) => (isActive ? styles.active : styles.link)}
            >
              내 문의
            </NavLink>
            {user.primarySystemIds.length > 0 && (
              <>
                <p className={styles.group}>업무</p>
                <NavLink
                  to={paths.tickets}
                  className={({ isActive }) => (isActive ? styles.active : styles.link)}
                >
                  담당 시스템 업무
                </NavLink>
              </>
            )}
            {user.roles.includes('BIZ_ADMIN') && (
              <NavLink
                to={paths.adminTickets}
                className={({ isActive }) => (isActive ? styles.active : styles.link)}
              >
                업무 관리
              </NavLink>
            )}
          </nav>
          <RecentInquiries />
          <footer className={styles.sidebarFooter}>
            <p>{catalog.data?.map((system) => system.name).join(' · ')}</p>
            <Button
              variant="ghost"
              onClick={() => {
                void logout()
                  .catch(() => undefined)
                  .finally(() => {
                    client.clear();
                    void navigate('/login', { replace: true });
                  });
              }}
            >
              로그아웃
            </Button>
          </footer>
        </aside>
        <div className={styles.content}>
          <RealtimeBoundary>
            <Outlet />
          </RealtimeBoundary>
        </div>
      </div>
    </div>
  );
}
