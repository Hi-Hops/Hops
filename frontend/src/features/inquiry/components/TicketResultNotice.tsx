import { displayTicketNumber } from '@/shared/lib/ticketNumber';
import type { InquiryResult, Problem } from '@/shared/api/types/hopsSystem1';
import { RouteModal } from '@/shared/ui/RouteModal';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import type { Tone } from '@/shared/ui/StatusBadge';
import { systemStatus, ticketStateLabel } from '../model/systemStatus';
import styles from './TicketResultNotice.module.css';

function outcomeBadge(problem: Problem): { label: string; tone: Tone } {
  if (problem.ticket)
    return {
      label: ticketStateLabel(problem.ticket.state),
      tone: systemStatus(problem.ticket.state).tone,
    };
  if (problem.creationResult?.outcome === 'NOT_CREATED')
    return { label: '미생성 확인', tone: 'danger' };
  if (problem.creationResult) return { label: '저장 결과 확인 중', tone: 'attention' };
  return systemStatus(problem.state);
}

function ResultItem({ problem }: { problem: Problem }) {
  const { ticket } = problem;
  return (
    <li className={styles.item}>
      <div className={styles.itemHead}>
        <span className={ticket ? styles.number : styles.noNumber} title={ticket?.ticketNumber}>
          {ticket ? displayTicketNumber(ticket.ticketNumber) : '티켓 없음'}
        </span>
        <StatusBadge {...outcomeBadge(problem)} />
      </div>
      <strong className={styles.summary}>{ticket?.title ?? problem.summary}</strong>
      {ticket && (
        <dl className={styles.facts}>
          <div>
            <dt>책임 시스템</dt>
            <dd>{ticket.responsibleSystem.name}</dd>
          </div>
          <div>
            <dt>담당자</dt>
            <dd>{ticket.assignee.name}</dd>
          </div>
        </dl>
      )}
    </li>
  );
}

export function TicketResultNotice({
  result,
  banner,
  onClose,
}: {
  result: InquiryResult;
  banner: { text: string; tone: string };
  onClose: () => void;
}) {
  return (
    <RouteModal title="티켓 접수 결과" onClose={onClose} returnFocusId="chat-title" size="narrow">
      <div className={styles.notice}>
        <p className={`${styles.banner} ${styles[banner.tone] ?? ''}`}>{banner.text}</p>
        <ul className={styles.items}>
          {result.problems.map((problem) => (
            <ResultItem key={problem.id} problem={problem} />
          ))}
        </ul>
      </div>
    </RouteModal>
  );
}
