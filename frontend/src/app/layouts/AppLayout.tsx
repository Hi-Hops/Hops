import { paths } from '@/shared/config/paths';
import { connectionMode } from '@/shared/config/connection';
import { NavLink, Outlet, useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { logout, useSession } from '@/features/auth';
import { RealtimeBoundary } from '@/features/realtime';
import { RecentInquiries } from '@/features/inquiry';
import { Button } from '@/shared/ui/Button';
import { onSessionEvent } from '@/shared/lib/sessionEvents';
import styles from './AppLayout.module.css';

export function AppLayout() {
  const user = useSession((state) => state.user);
  const expiresAt = useSession((state) => state.expiresAt);
  const client = useQueryClient();
  const navigate = useNavigate();
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
        useSession.getState().expire();
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
      </header>
      <div className={styles.body}>
        <aside className={styles.sidebar}>
          <nav aria-label="주 메뉴">
            <NavLink
              end
              to="/"
              className={({ isActive }) => (isActive ? styles.active : styles.link)}
            >
              <span className={styles.navIcon} aria-hidden="true" />+ 새 문의
            </NavLink>
            {(user.department.isRoutable || user.roles.includes('BIZ_ADMIN')) && (
              <>
                <p className={styles.group}>업무</p>
                <NavLink
                  to={paths.tickets}
                  className={({ isActive }) => (isActive ? styles.active : styles.link)}
                >
                  <span className={styles.navIcon} aria-hidden="true" />
                  부서 티켓 현황
                </NavLink>
              </>
            )}
            {user.roles.includes('BIZ_ADMIN') && (
              <NavLink
                to={paths.adminTickets}
                className={({ isActive }) => (isActive ? styles.active : styles.link)}
              >
                <span className={styles.navIcon} aria-hidden="true" />
                관리자 페이지
              </NavLink>
            )}
          </nav>
          <RecentInquiries />
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
