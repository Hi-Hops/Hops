import { NavLink, Outlet, useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { logout, useSession } from '@/features/auth';
import { RealtimeBoundary } from '@/features/realtime';
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
        <span className={styles.mock}>Mock 체험</span>
        <div className={styles.user}>
          <span className={styles.avatar}>{user.name.slice(0, 1)}</span>
          <div>
            <strong>{user.name}</strong>
            <p>데모시스템 · {user.department.name}</p>
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
            <p className={styles.group}>문의</p>
            <NavLink
              end
              to="/"
              className={({ isActive }) => (isActive ? styles.active : styles.link)}
            >
              <span className={styles.navIcon} aria-hidden="true" />
              IT 문의하기
            </NavLink>
            <NavLink
              to="/inquiries"
              className={({ isActive }) => (isActive ? styles.active : styles.link)}
            >
              <span className={styles.navIcon} aria-hidden="true" />내 문의 내역
            </NavLink>
            {(user.department.isRoutable || user.roles.includes('BIZ_ADMIN')) && (
              <>
                <p className={styles.group}>업무</p>
                <NavLink
                  to="/tickets"
                  className={({ isActive }) => (isActive ? styles.active : styles.link)}
                >
                  <span className={styles.navIcon} aria-hidden="true" />
                  부서 티켓 현황
                </NavLink>
              </>
            )}
          </nav>
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
