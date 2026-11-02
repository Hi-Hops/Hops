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
  const index = currentIndex(detail, store);
  const canRetry = index?.state === 'FAILED';
  const canCheck = ['PENDING', 'RUNNING', 'UNKNOWN'].includes(index?.state ?? '');
  return (
    <section className={styles.indexCard} aria-label={store + ' 반영 상태'}>
      <strong>
        {store === 'VECTOR' ? 'Vector' : 'Neo4j'} · 승인 v{detail.version.caseVersion}
      </strong>
      <StatusBadge
        label={labels[index?.state ?? ''] ?? '현재 버전의 상태 확인 필요'}
        tone={
          index?.state === 'SUCCEEDED'
            ? 'success'
            : index?.state === 'FAILED'
              ? 'danger'
              : 'attention'
        }
      />
      {index && (
        <p>
          현재 시도 {index.attempt} · {index.error?.message ?? '현재 승인 버전의 반영 결과예요.'}
        </p>
      )}
      {(canRetry || canCheck || command.pending) && (
        <Button
          variant="secondary"
          disabled={!allowed || command.busy || command.expired || command.remaining > 0}
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
          {command.label ?? (canRetry ? '실패 반영 다시 실행' : '현재 반영 상태 확인')}
        </Button>
      )}
      {command.error && <p role="alert">{command.error}</p>}
      {command.notice && <p role="status">{command.notice}</p>}
      {command.remaining > 0 && (
        <p role="status">{command.remaining}초 후 다시 요청할 수 있어요.</p>
      )}
    </section>
  );
}
