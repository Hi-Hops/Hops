import { ApiError } from '@/shared/api/client';
import { connectionMode } from '@/shared/config/connection';
import { displayDate } from '@/shared/lib/date';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { TicketStatuses } from '@/features/inquiry';
import { Button } from '@/shared/ui/Button';
import { useTicketDetail } from '../hooks/useTicketDetail';
import styles from './Tickets.module.css';
export function TicketDetail({ id, onClose }: { id: string; onClose: () => void }) {
  const query = useTicketDetail(id);
  const { data, error } = query;
  if (query.isPending) return <LoadingState label="티켓 상세를 불러오는 중이에요" />;
  if (error)
    return (
      <div className={styles.detailError}>
        <ErrorState
          title={
            error instanceof ApiError && error.status === 403
              ? '이 티켓을 볼 권한이 없어요'
              : error instanceof ApiError && error.status === 404
                ? '티켓을 찾을 수 없어요'
                : '티켓을 불러오지 못했어요'
          }
          description={
            error instanceof ApiError && error.status === 403
              ? '현재 접근 가능한 부서 티켓 목록으로 돌아가 주세요.'
              : error instanceof ApiError && error.status === 404
                ? '티켓 주소를 확인하거나 목록에서 다시 선택해 주세요.'
                : undefined
          }
          onRetry={() => {
            void query.refetch();
          }}
        />
        <Button variant="secondary" onClick={onClose}>
          부서 티켓 목록으로
        </Button>
      </div>
    );
  if (!data) return null;
  return (
    <article className={styles.detail}>
      <div className={styles.main}>
        <p className={styles.ticketNumber}>{data.ticketNumber}</p>
        <h3>{data.title}</h3>
        <div className={styles.statuses}>
          <TicketStatuses ticket={data} />
        </div>
        <section>
          <h4>문의 요약</h4>
          <p>{data.description}</p>
        </section>
        <section className={styles.diagnosis}>
          <h4>AI 진단 요약</h4>
          <p>{data.diagnosisSummary ?? '저장된 AI 진단이 없어요.'}</p>
          {connectionMode() === 'mock' && <small>Mock 데이터 · 실제 진단 결과가 아니에요</small>}
        </section>
        <section>
          <h4>배정 이력</h4>
          <ol className={styles.history}>
            {data.assignmentHistory.map((assignment) => (
              <li key={`${assignment.department.id}:${assignment.assignedAt}`}>
                <strong>{assignment.department.name}</strong>
                <p>
                  {displayDate(assignment.assignedAt)} 배정 ·{' '}
                  {assignment.endedAt
                    ? `${displayDate(assignment.endedAt)} 종료`
                    : '현재 처리 부서'}
                </p>
              </li>
            ))}
          </ol>
        </section>
      </div>
      <aside className={styles.properties}>
        <h4>티켓 정보</h4>
        <dl>
          <dt>현재 처리 부서</dt>
          <dd>{data.department.name}</dd>
          <dt>생성 시각</dt>
          <dd>{displayDate(data.createdAt)}</dd>
          <dt>티켓 식별자</dt>
          <dd className={styles.identifier}>{data.id}</dd>
        </dl>
        <p>현재 서버가 허용한 부서 범위에서 조회한 결과예요.</p>
      </aside>
    </article>
  );
}
