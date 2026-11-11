import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { useSystemCommand } from '@/shared/api/useSystemCommand';
import { Button } from '@/shared/ui/Button';
import { ErrorState } from '@/shared/ui/ErrorState';
import type { CaseGeneration } from '@/shared/api/types/hopsSystem1';
import { getGeneration, generationRetryCommand } from '../api/systemTicketApi';
import styles from './Workflow.module.css';

type CaseGenerationPanelProps = {
  generation: CaseGeneration | null;
  renderCaseReview: (
    caseId: string | null,
    reviewTarget: HTMLElement,
    generationStatus?: ReactNode,
  ) => ReactNode;
  reviewTarget: HTMLElement | null;
};
export function CaseGenerationPanel({
  generation,
  renderCaseReview,
  reviewTarget,
}: CaseGenerationPanelProps) {
  const actor = useSession((s) => s.user?.id ?? '');
  const query = useQuery({
    queryKey: ['hops-system-1', 'generation', actor, generation?.id],
    queryFn: ({ signal }) => getGeneration(generation?.id ?? '', signal),
    enabled: Boolean(generation),
    refetchInterval: (q) =>
      ['PENDING', 'RUNNING'].includes(q.state.data?.state ?? generation?.state ?? '')
        ? 5000
        : false,
  });
  const command = useSystemCommand(actor, 'generation:' + (generation?.id ?? 'none'));
  const data = query.data ?? generation;
  const caseId = data?.state === 'SUCCEEDED' ? data.caseId : null;
  if (!reviewTarget) return null;
  if (query.isError)
    return renderCaseReview(
      null,
      reviewTarget,
      <ErrorState
        title="사례 초안 상태를 확인하지 못했어요"
        onRetry={() => {
          void query.refetch();
        }}
      />,
    );
  if (caseId && !command.pending) return renderCaseReview(caseId, reviewTarget);
  const generationStatus = (
    <section className={styles.caseStatus} aria-label="해결 사례 초안 상태">
      {!data ? (
        <p>처리 완료 기록을 저장하면 AI가 사례 초안을 준비해요.</p>
      ) : caseId ? null : data.state === 'FAILED' ? (
        <>
          <p role="alert">{data.error?.message ?? '초안 생성에 실패했어요.'}</p>
          {!command.pending && (
            <Button
              variant="secondary"
              disabled={command.busy || command.expired || command.remaining > 0}
              onClick={() => {
                void command.execute((id) => generationRetryCommand(data.id, id));
              }}
            >
              {command.label ?? '초안 생성 다시 시도'}
            </Button>
          )}
        </>
      ) : data.state === 'NO_REUSABLE_CASE' ? (
        <p>재사용할 내용이 없어 사례를 만들지 않았어요.</p>
      ) : ['PENDING', 'RUNNING'].includes(data.state) ? (
        <p role="status">AI 초안 준비 중…</p>
      ) : (
        <p>초안 상태 확인이 필요해요.</p>
      )}
      {data && command.pending && (
        <Button
          variant="secondary"
          disabled={command.busy || command.expired || command.remaining > 0}
          onClick={() => {
            void command.execute((id) => generationRetryCommand(data.id, id));
          }}
        >
          {command.label ?? '같은 요청 결과 확인'}
        </Button>
      )}
      {command.error && <p role="alert">{command.error}</p>}
      {command.notice && <p role="status">{command.notice}</p>}
      {command.remaining > 0 && (
        <p role="status">{command.remaining}초 후 다시 요청할 수 있어요.</p>
      )}
    </section>
  );
  return caseId ? (
    <>
      {generationStatus}
      {renderCaseReview(caseId, reviewTarget)}
    </>
  ) : (
    renderCaseReview(null, reviewTarget, generationStatus)
  );
}
