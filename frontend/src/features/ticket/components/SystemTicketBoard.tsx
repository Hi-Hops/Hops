import { displayTicketNumber } from '@/shared/lib/ticketNumber';
import { useEffect, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { systemStatus, ticketStates } from '@/features/inquiry';
import { realtime } from '@/shared/realtime';
import { ApiError } from '@/shared/api/client';
import { Button } from '@/shared/ui/Button';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { displayDate } from '@/shared/lib/date';
import { getSystems, getSystemTickets, systemTicketKeys } from '../api/systemTicketApi';
import { processingLabels } from '../model/workflow';
import type { TicketSummary } from '@/shared/api/types/hopsSystem1';
import type { TargetState } from '@/shared/realtime/types';
import styles from './Tickets.module.css';

type TicketLinkProps = { ticket: TicketSummary; base: string; search: string };
function TicketLink({ ticket, base, search }: TicketLinkProps) {
  return (
    <Link
      id={'ticket-' + ticket.id}
      className={styles.kanbanCard}
      to={{ pathname: base + '/' + encodeURIComponent(ticket.id), search }}
      state={{ ticketModalOrigin: 'list' }}
    >
      <div className={styles.cardTop}>
        <span className={styles.ticketNumber} title={ticket.ticketNumber}>
          {displayTicketNumber(ticket.ticketNumber)}
        </span>
        <span className={styles.type}>
          {processingLabels[ticket.processingType] ?? '유형 확인 필요'}
        </span>
      </div>
      <strong>{ticket.title}</strong>
      <span className={styles.cardMeta}>
        {displayDate(ticket.createdAt)} · 보고 {ticket.reportedSystem.name}
      </span>
      <span className={styles.systemChip}>{ticket.responsibleSystem.name}</span>
      <span className={styles.assignee}>{ticket.assignee.name}</span>
    </Link>
  );
}
export function SystemTicketBoard() {
  const user = useSession((s) => s.user);
  const [search, setSearch] = useSearchParams();
  const location = useLocation();
  const admin = location.pathname.startsWith('/admin/');
  const base = admin ? '/admin/tickets' : '/tickets';
  const allowed = Boolean(
    user && (admin ? user.roles.includes('BIZ_ADMIN') : user.primarySystemIds.length),
  );
  const [denied, setDenied] = useState(false);
  const [connections, setConnections] = useState<Record<string, TargetState>>({});
  const catalog = useQuery({
    queryKey: ['hops-system-1', 'systems', user?.id],
    queryFn: ({ signal }) => getSystems(signal),
    enabled: allowed,
  });
  const view = admin ? 'list' : (search.get('view') ?? 'board');
  const queryParams = new URLSearchParams({ scope: admin ? 'ADMIN' : 'ASSIGNED_SYSTEMS' });
  for (const name of ['systemId', 'state', 'page', 'size']) {
    const value = search.get(name);
    if (value !== null) queryParams.set(name, value);
  }
  const query = useQuery({
    queryKey: systemTicketKeys.list(user?.id ?? '', queryParams.toString()),
    queryFn: ({ signal }) => getSystemTickets(queryParams.toString(), signal),
    enabled: allowed,
    refetchInterval: 5000,
  });
  const systemIds =
    catalog.data
      ?.filter((s) => admin || user?.primarySystemIds.includes(s.id))
      .map((s) => s.id)
      .join(',') ?? '';
  useEffect(() => {
    if (!allowed || !systemIds) return;
    const off = systemIds.split(',').map((id) =>
      realtime.subscribe({ type: 'SYSTEM_TICKETS', id }, (state) => {
        if (state === 'denied') setDenied(true);
        setConnections((current) => ({ ...current, [id]: state }));
      }),
    );
    return () => {
      off.forEach((unsubscribe) => {
        unsubscribe();
      });
    };
  }, [allowed, systemIds]);
  function change(name: string, value: string) {
    const next = new URLSearchParams(search);
    next.delete('tab');
    if (value) next.set(name, value);
    else next.delete(name);
    if (!['page', 'view'].includes(name)) next.delete('page');
    setSearch(next);
  }
  const items = query.data?.items ?? [];
  const synchronizing = systemIds
    .split(',')
    .some((id) => ['connecting', 'disconnected'].includes(connections[id] ?? 'idle'));
  const columns = Object.entries(ticketStates);
  if (items.some((t) => !Object.hasOwn(ticketStates, t.state)))
    columns.push(['UNKNOWN', '상태 확인 필요']);
  const linkSearch = new URLSearchParams(search);
  linkSearch.delete('tab');
  const failed = query.isError || catalog.isError;
  return (
    <main className={styles.page}>
      <header className={styles.pageHead}>
        <div>
          <h1 id="page-title" tabIndex={-1}>
            {admin ? '업무 관리' : '담당 시스템 업무'}
          </h1>
          <p>
            {admin
              ? '관리 권한이 허용한 업무 메타데이터를 확인하세요.'
              : '내 담당 시스템에 배정된 티켓입니다. 책임 시스템과 상태로 업무를 확인하세요.'}
          </p>
        </div>
        <span className={styles.readOnly}>{admin ? '업무 메타데이터' : '주담당자 자동 배정'}</span>
      </header>
      {!allowed || denied ? (
        <ErrorState
          title="시스템 업무 조회 권한이 없어요"
          description="현재 접근 가능한 메뉴를 선택해 주세요."
        />
      ) : (
        <section
          className={view === 'board' ? styles.boardPanel : styles.panel}
          aria-label="시스템 티켓 목록"
        >
          <div className={styles.toolbar}>
            <div className={styles.quickFilters} aria-label="처리 상태 필터">
              <Button
                variant={!search.get('state') ? 'secondary' : 'ghost'}
                aria-pressed={!search.get('state')}
                onClick={() => {
                  change('state', '');
                }}
              >
                전체 상태
              </Button>
              {Object.entries(ticketStates).map(([state, label]) => (
                <Button
                  key={state}
                  variant={search.get('state') === state ? 'secondary' : 'ghost'}
                  aria-pressed={search.get('state') === state}
                  onClick={() => {
                    change('state', state);
                  }}
                >
                  {label}
                </Button>
              ))}
            </div>
            <div className={styles.filters}>
              <label className={styles.filterLabel} htmlFor="system-filter">
                책임 시스템
              </label>
              <select
                id="system-filter"
                value={search.get('systemId') ?? ''}
                onChange={(e) => {
                  change('systemId', e.target.value);
                }}
              >
                <option value="">전체 시스템</option>
                {catalog.data
                  ?.filter((s) => admin || user?.primarySystemIds.includes(s.id))
                  .map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
              </select>
              {!admin && (
                <div className={styles.viewToggle} role="radiogroup" aria-label="티켓 보기 방식">
                  {(['board', 'list'] as const).map((v) => (
                    <label key={v}>
                      <input
                        type="radio"
                        name="ticket-view"
                        checked={view === v}
                        onChange={() => {
                          change('view', v);
                        }}
                      />
                      <span>
                        <img src={'/assets/view-' + v + '.svg'} width="16" height="16" alt="" />
                        {v === 'board' ? '보드' : '목록'}
                      </span>
                    </label>
                  ))}
                </div>
              )}
              <Button
                variant="ghost"
                onClick={() => {
                  void query.refetch();
                  void catalog.refetch();
                }}
              >
                새로고침
              </Button>
            </div>
          </div>
          {query.isPending || catalog.isPending || (synchronizing && !failed) ? (
            <LoadingState />
          ) : failed ? (
            <ErrorState
              title={
                query.error instanceof ApiError && query.error.status === 403
                  ? '이 시스템 업무를 조회할 권한이 없어요'
                  : '티켓 또는 시스템 목록을 불러오지 못했어요'
              }
              onRetry={() => {
                void query.refetch();
                void catalog.refetch();
              }}
            />
          ) : !items.length ? (
            <div className={styles.empty}>
              <strong>배정된 티켓이 없어요</strong>
              <p>선택한 시스템과 처리 상태를 확인해 주세요.</p>
            </div>
          ) : view === 'board' ? (
            <>
              <div
                className={styles.kanbanScroll}
                tabIndex={0}
                role="region"
                aria-label="티켓 칸반 보드"
              >
                <div className={styles.kanban}>
                  {columns.map(([state, label]) => {
                    const tickets = items.filter((t) =>
                      state === 'UNKNOWN'
                        ? !Object.hasOwn(ticketStates, t.state)
                        : t.state === state,
                    );
                    return (
                      <section className={styles.column} key={state} aria-label={label + ' 컬럼'}>
                        <header className={styles.columnHead}>
                          <h3>
                            <StatusBadge {...systemStatus(state)} label={label} />
                          </h3>
                          <span aria-label={'현재 페이지 ' + String(tickets.length) + '건'}>
                            {tickets.length}
                          </span>
                        </header>
                        <ul className={styles.columnBody}>
                          {tickets.map((ticket) => (
                            <li key={ticket.id}>
                              <TicketLink
                                ticket={ticket}
                                base={base}
                                search={linkSearch.toString()}
                              />
                            </li>
                          ))}
                          {!tickets.length && (
                            <li className={styles.columnEmpty}>현재 페이지에 티켓 없음</li>
                          )}
                        </ul>
                        <p className={styles.columnFoot}>{tickets.length}건 표시</p>
                      </section>
                    );
                  })}
                </div>
              </div>
              <p className={styles.boardHint}>
                컬럼 숫자는 현재 페이지 건수예요. 카드를 선택하면 상세 모달이 열려요. 보드에서
                드래그로 상태를 바꾸지 않아요.
              </p>
            </>
          ) : view === 'list' ? (
            <div className={styles.tableWrap}>
              <table>
                <caption>시스템 업무 목록</caption>
                <thead>
                  <tr>
                    <th>번호</th>
                    <th>티켓</th>
                    <th>책임 시스템</th>
                    <th>처리 유형</th>
                    <th>처리 상태</th>
                    <th>배정 담당자</th>
                    <th>상세</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((ticket) => (
                    <tr key={ticket.id}>
                      <td className={styles.ticketNumber} title={ticket.ticketNumber}>
                        {displayTicketNumber(ticket.ticketNumber)}
                      </td>
                      <td>
                        <Link
                          id={'ticket-' + ticket.id}
                          to={{ pathname: base + '/' + ticket.id, search: linkSearch.toString() }}
                          state={{ ticketModalOrigin: 'list' }}
                        >
                          <strong>{ticket.title}</strong>
                          <span className={styles.cardMeta}>
                            {displayDate(ticket.createdAt)} · 보고 {ticket.reportedSystem.name}
                          </span>
                        </Link>
                      </td>
                      <td>
                        <span className={styles.systemChip}>{ticket.responsibleSystem.name}</span>
                      </td>
                      <td>{processingLabels[ticket.processingType] ?? '유형 확인 필요'}</td>
                      <td>
                        <StatusBadge {...systemStatus(ticket.state)} />
                      </td>
                      <td>
                        <span className={styles.assignee}>{ticket.assignee.name}</span>
                      </td>
                      <td>
                        <Link
                          className={styles.detailLink}
                          to={{ pathname: base + '/' + ticket.id, search: linkSearch.toString() }}
                          state={{ ticketModalOrigin: 'list' }}
                        >
                          상세보기
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <ErrorState title="지원하지 않는 보기 방식이에요" />
          )}
          {query.data && !failed && (
            <nav className={styles.pagination} aria-label="티켓 페이지">
              <span>
                조건에 맞는 티켓 {query.data.totalElements}건 · {query.data.page + 1} 페이지
              </span>
              <div>
                <Button
                  variant="secondary"
                  disabled={query.data.page === 0}
                  onClick={() => {
                    change('page', String(query.data.page - 1));
                  }}
                >
                  이전
                </Button>
                <Button
                  variant="secondary"
                  disabled={(query.data.page + 1) * query.data.size >= query.data.totalElements}
                  onClick={() => {
                    change('page', String(query.data.page + 1));
                  }}
                >
                  다음
                </Button>
              </div>
            </nav>
          )}
        </section>
      )}
    </main>
  );
}
