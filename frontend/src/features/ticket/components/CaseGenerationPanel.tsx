import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { useSystemCommand } from '@/shared/api/useSystemCommand';
import { Button } from '@/shared/ui/Button';
import { ErrorState } from '@/shared/ui/ErrorState';
import { paths } from '@/shared/config/paths';
import type { CaseGeneration } from '@/shared/api/types/hopsSystem1';
import { getGeneration, generationRetryCommand } from '../api/systemTicketApi';
import styles from './Workflow.module.css';

type CaseGenerationPanelProps = { generation: CaseGeneration | null };
export function CaseGenerationPanel({ generation }: CaseGenerationPanelProps) {
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
  if (query.isError)
    return (
      <ErrorState
        title="사례 초안 상태를 확인하지 못했어요"
        onRetry={() => {
          void query.refetch();
        }}
      />
    );
  return (
    <section className={styles.info} aria-label="해결 사례 초안">
      <strong>해결 사례 초안</strong>
      {!data ? (
        <p>아직 초안이 없어요.</p>
      ) : data.state === 'SUCCEEDED' && data.caseId ? (
        <Link to={paths.case(data.caseId)}>사례 초안 확인</Link>
      ) : data.state === 'FAILED' ? (
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
        <p role="status">사례 초안을 준비하고 있어요.</p>
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
}
