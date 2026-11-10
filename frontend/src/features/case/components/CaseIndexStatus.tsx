import { useSession } from '@/features/auth';
import { useSystemCommand } from '@/shared/api/useSystemCommand';
import { Button } from '@/shared/ui/Button';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import type { CaseDetail, IndexStore } from '@/shared/api/types/hopsSystem1';
import { indexCommand } from '../api/caseApi';
import { currentIndex } from '../model/publication';
import styles from './Cases.module.css';

const labels: Record<string, string> = {
  PENDING: '반영 대기',
  RUNNING: '반영 중',
  SUCCEEDED: '반영 성공',
  FAILED: '반영 실패',
  UNKNOWN: '상태 미확인',
};
type CaseIndexStatusProps = { detail: CaseDetail; store: IndexStore; allowed: boolean };
export function CaseIndexStatus({ detail, store, allowed }: CaseIndexStatusProps) {
  const actor = useSession((s) => s.user?.id ?? '');
  const command = useSystemCommand(actor, 'case-index:' + detail.version.id + ':' + store);
  const name = store === 'VECTOR' ? 'Vector DB' : 'Neo4j';
  const index = currentIndex(detail, store);
  const canRetry = index?.state === 'FAILED';
  const canCheck = ['PENDING', 'RUNNING', 'UNKNOWN'].includes(index?.state ?? '');
  return (
    <section
      className={styles.indexCard}
      aria-label={name + ' 반영 상태'}
      data-complete={index?.state === 'SUCCEEDED'}
    >
      <header className={styles.indexHeader}>
        <strong>{name}</strong>
        <span>승인 v{detail.version.caseVersion}</span>
      </header>
      <div className={styles.indexBadge} role="status">
        {index?.state === 'RUNNING' && <span className={styles.spinner} aria-hidden="true" />}
        <StatusBadge
          label={labels[index?.state ?? ''] ?? '현재 버전의 상태 확인 필요'}
          tone={
            index?.state === 'SUCCEEDED'
              ? 'success'
              : index?.state === 'FAILED'
                ? 'danger'
                : index?.state === 'RUNNING'
                  ? 'progress'
                  : 'attention'
          }
        />
      </div>
      {index?.state === 'UNKNOWN' && <p>저장소의 반영 결과를 확인하지 못했어요.</p>}
      {index && (
        <p>
          현재 시도 {index.attempt}
          {index.error ? ` · ${index.error.message}` : ''}
        </p>
      )}
      {(canRetry || canCheck || command.pending) && (
        <Button
          variant="secondary"
          isLoading={command.busy}
          disabled={!allowed || command.expired || command.remaining > 0}
          onClick={() => {
            void command.execute((requestId) =>
              indexCommand(
                detail.version.id,
                {
                  requestId,
                  expectedCaseVersion: detail.version.caseVersion,
                  store,
                },
                canRetry,
              ),
            );
          }}
        >
          {command.busy && <span className={styles.spinner} aria-hidden="true" />}
          {command.busy
            ? '요청 중…'
            : (command.label ?? (canRetry ? '실패 반영 다시 실행' : '현재 반영 상태 확인'))}
        </Button>
      )}
      {command.error && <p role="alert">{command.error}</p>}
      {command.notice && (canRetry || canCheck) && (
        <p role="status">반영 상태 요청을 접수했어요.</p>
      )}
      {command.remaining > 0 && (
        <p role="status">{command.remaining}초 후 다시 요청할 수 있어요.</p>
      )}
    </section>
  );
}
