import type { InquiryResult } from '@/shared/api/types';
import { displayDate } from '@/shared/lib/date';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { problemDisplay } from '../model/status';
import { TicketStatuses } from './TicketStatuses';
import styles from './ResultPanel.module.css';
export function ResultPanel({ result }: { result: InquiryResult }) {
  const tickets = [...new Map(result.tickets.map((ticket) => [ticket.id, ticket])).values()];
  if (!result.problems.length && result.currentRun?.status !== 'RUNNING') return null;
  return (
    <section className={styles.panel} aria-label="문제별 처리 결과">
      <header className={styles.header}>
        <h2>문의 처리 현황</h2>
        <span>
          {result.problems.length}개 문제 · 티켓 {tickets.length}개
        </span>
        {result.currentRun?.status === 'RUNNING' && <StatusBadge label="분석 중" tone="progress" />}
      </header>
      {result.problems.map((problem) => (
        <section className={styles.problem} key={problem.id} aria-label={problem.summary}>
          <div className={styles.heading}>
            <h3>{problem.summary}</h3>
            <StatusBadge {...problemDisplay(problem.status)} />
          </div>
          {problem.status === 'WAITING_USER' && (
            <p className={styles.question}>
              추가 정보가 필요해요 · 질문 {problem.questionCount} / 3회
            </p>
          )}
          {problem.status === 'OUT_OF_SCOPE' && (
            <p className={styles.meta}>사내 IT 지원 범위 밖으로 티켓을 만들지 않았어요.</p>
          )}
          {tickets
            .filter((ticket) => ticket.problemId === problem.id)
            .map((ticket) => (
              <article
                className={styles.ticket}
                key={ticket.id}
                aria-label={`저장된 티켓 ${ticket.ticketNumber}`}
              >
                <div className={styles.heading}>
                  <span className={styles.number}>{ticket.ticketNumber}</span>
                  <strong>{ticket.title}</strong>
                </div>
                <div className={styles.statuses}>
                  <span>{ticket.department.name}</span>
                  <TicketStatuses ticket={ticket} />
                </div>
                <p className={styles.meta}>생성 {displayDate(ticket.createdAt)}</p>
              </article>
            ))}
          {result.creationFailures
            .filter((failure) => failure.problemId === problem.id)
            .map((failure) => (
              <div className={styles.failure} key={failure.workItemId} role="status">
                <strong>티켓을 만들지 못했어요</strong>
                <p>저장된 다른 티켓은 유지돼요. 담당 운영자가 확인할 수 있어요.</p>
              </div>
            ))}
        </section>
      ))}
      <footer className={styles.footer}>
        저장된 결과예요. 내용을 고치거나 추가 정보를 남기면 기존 티켓을 유지하며 반영해요.
      </footer>
    </section>
  );
}
