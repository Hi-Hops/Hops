import { paths } from '@/shared/config/paths';
import { connectionMode } from '@/shared/config/connection';
import { NavLink, Outlet, useNavigate } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { logout, refreshSession, useSession } from '@/features/auth';
import { RealtimeBoundary } from '@/features/realtime';
import { RecentInquiries } from '@/features/inquiry';
import { getSystems } from '@/features/ticket';
import { Button } from '@/shared/ui/Button';
import { onSessionEvent } from '@/shared/lib/sessionEvents';
import styles from './AppLayout.module.css';

const sidebarStorageKey = 'hops:sidebar-open';

function readSidebarOpen() {
  try {
    return localStorage.getItem(sidebarStorageKey) === 'true';
  } catch {
    return false;
  }
}

function Chevron({ direction }: { direction: 'left' | 'right' }) {
  return (
    <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true">
      <path
        d={direction === 'left' ? 'M12 5 7 10l5 5' : 'M8 5l5 5-5 5'}
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function AppLayout() {
  const user = useSession((state) => state.user);
  const expiresAt = useSession((state) => state.expiresAt);
  const [refreshError, setRefreshError] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(readSidebarOpen);
  const sidebarToggled = useRef(false);
  const openButton = useRef<HTMLButtonElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
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
  useEffect(() => {
    try {
      localStorage.setItem(sidebarStorageKey, String(sidebarOpen));
    } catch {
      // 저장소를 쓸 수 없으면 현재 화면에서만 상태를 유지한다.
    }
    // 누른 버튼이 숨겨지므로 키보드 포커스를 반대편 버튼으로 옮긴다.
    if (sidebarToggled.current) (sidebarOpen ? closeButton : openButton).current?.focus();
    sidebarToggled.current = false;
  }, [sidebarOpen]);
  function toggleSidebar(open: boolean) {
    sidebarToggled.current = true;
    setSidebarOpen(open);
  }
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
          className={styles.logout}
          aria-label="로그아웃"
          title="로그아웃"
          onClick={() => {
            void logout()
              .catch(() => undefined)
              .finally(() => {
                client.clear();
                void navigate('/login', { replace: true });
              });
          }}
        >
          <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true">
            <path
              d="M8 4H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3M12 6.5 15.5 10 12 13.5M15.5 10H8"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </Button>
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
        <div
          className={`${styles.sidebarRail ?? ''} ${sidebarOpen ? (styles.railOpen ?? '') : ''}`}
        >
          <button
            ref={openButton}
            type="button"
            className={`${styles.sidebarOpen ?? ''} ${sidebarOpen ? (styles.hidden ?? '') : ''}`}
            aria-label="사이드바 열기"
            aria-expanded={sidebarOpen}
            aria-controls="app-sidebar"
            title="사이드바 열기"
            onClick={() => {
              toggleSidebar(true);
            }}
          >
            <Chevron direction="right" />
          </button>
          <aside
            id="app-sidebar"
            className={`${styles.sidebar ?? ''} ${sidebarOpen ? '' : (styles.closed ?? '')}`}
          >
            <nav aria-label="주 메뉴">
              <NavLink
                end
                to="/"
                className={({ isActive }) => (isActive ? styles.active : styles.link)}
              >
                + 새 문의
              </NavLink>
              {/* 채팅 경로도 /inquiries로 시작하므로 내 문의 목록에서만 선택 표시한다. */}
              <NavLink
                end
                to={paths.inquiries}
                className={({ isActive }) => (isActive ? styles.active : styles.link)}
              >
                내 문의
              </NavLink>
              {user.primarySystemIds.length > 0 && (
                <section className={styles.navSection} aria-labelledby="work-nav-title">
                  <h2 id="work-nav-title" className={styles.group}>
                    업무
                  </h2>
                  <NavLink
                    to={paths.tickets}
                    className={({ isActive }) => (isActive ? styles.active : styles.link)}
                  >
                    담당 시스템 업무
                  </NavLink>
                  <NavLink
                    to={paths.cases}
                    className={({ isActive }) => (isActive ? styles.active : styles.link)}
                  >
                    해결 사례 검토
                  </NavLink>
                </section>
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
            </footer>
            <button
              ref={closeButton}
              type="button"
              className={styles.sidebarClose}
              aria-label="사이드바 닫기"
              title="사이드바 닫기"
              onClick={() => {
                toggleSidebar(false);
              }}
            >
              <Chevron direction="left" />
            </button>
          </aside>
        </div>
        <div className={styles.content}>
          <RealtimeBoundary>
            <Outlet />
          </RealtimeBoundary>
        </div>
      </div>
    </div>
  );
}
