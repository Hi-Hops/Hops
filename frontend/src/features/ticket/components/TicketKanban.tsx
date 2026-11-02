import { Link } from 'react-router';
import type { TicketCard } from '@/shared/api/types';
import { processing, routingDisplay } from '@/features/inquiry';
import { displayDate } from '@/shared/lib/date';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import styles from './Tickets.module.css';

const columns = [
  { id: 'WAITING', label: '처리 대기', states: ['WAITING'], tone: 'attention' },
  { id: 'IN_PROGRESS', label: '처리 중', states: ['IN_PROGRESS'], tone: 'progress' },
  {
    id: 'WAITING_CONFIRM',
    label: '사용자 확인 대기',
    states: ['WAITING_CONFIRM'],
    tone: 'attention',
  },
  { id: 'CLOSED', label: '종료', states: ['RESOLVED', 'CLOSED_UNCONFIRMED'], tone: 'success' },
] as const;

type TicketKanbanProps = { items: TicketCard[]; base: string; search: string };
export function TicketKanban({ items, base, search }: TicketKanbanProps) {
  const knownStates: readonly string[] = columns.flatMap((column) => [...column.states]);
  const unknown = items.filter((ticket) => !knownStates.includes(ticket.processingStatus));
  const groups = [
    ...columns.map((column) => ({
      ...column,
      items: items.filter((ticket) =>
        column.states.some((state) => state === ticket.processingStatus),
      ),
    })),
    ...(unknown.length
      ? [{ id: 'UNKNOWN', label: '상태 확인 필요', tone: 'neutral' as const, items: unknown }]
      : []),
  ];
  return (
    <div className={styles.kanbanScroll} tabIndex={0} role="region" aria-label="티켓 칸반 보드">
      <div className={styles.kanban}>
        {groups.map((column) => (
          <section className={styles.column} key={column.id} aria-label={`${column.label} 컬럼`}>
            <header className={styles.columnHead}>
              <h3>
                <StatusBadge label={column.label} tone={column.tone} />
              </h3>
              <span aria-label={`현재 페이지 ${column.items.length}건`}>{column.items.length}</span>
            </header>
            <ul className={styles.columnBody}>
              {column.items.map((ticket) => (
                <li key={ticket.id}>
                  <Link
                    id={`ticket-${ticket.id}`}
                    className={styles.kanbanCard}
                    to={{ pathname: `${base}/${encodeURIComponent(ticket.id)}`, search }}
                  >
                    <span className={styles.ticketNumber}>{ticket.ticketNumber}</span>
                    <strong>{ticket.title}</strong>
                    <span className={styles.cardMeta}>
                      {ticket.department.name} · {displayDate(ticket.createdAt)}
                    </span>
                    <span className={styles.statuses}>
                      {ticket.routingStatus !== 'ASSIGNED' && (
                        <StatusBadge {...routingDisplay(ticket.routingStatus)} />
                      )}
                      {column.id === 'CLOSED' && (
                        <StatusBadge {...processing[ticket.processingStatus]} />
                      )}
                      {column.id === 'UNKNOWN' && (
                        <StatusBadge
                          label={`확인되지 않은 처리 상태 (${ticket.processingStatus})`}
                        />
                      )}
                    </span>
                  </Link>
                </li>
              ))}
              {!column.items.length && (
                <li className={styles.columnEmpty}>현재 페이지에 티켓 없음</li>
              )}
            </ul>
          </section>
        ))}
      </div>
    </div>
  );
}
