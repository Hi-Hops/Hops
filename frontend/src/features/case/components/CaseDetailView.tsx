import { Link, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { useSubscription } from '@/features/realtime';
import { ApiError } from '@/shared/api/client';
import { paths } from '@/shared/config/paths';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { caseKeys, getCase } from '../api/caseApi';
import { CaseEditor } from './CaseEditor';
import styles from './Cases.module.css';

type CaseDetailViewProps = { id: string };
export function CaseDetailView({ id }: CaseDetailViewProps) {
  const user = useSession((s) => s.user);
  const [search] = useSearchParams();
  const allowed = Boolean(user?.primarySystemIds.length);
  const subscription = useSubscription(allowed ? { type: 'CASE', id } : null);
  const query = useQuery({
    queryKey: caseKeys.detail(user?.id ?? '', id),
    queryFn: ({ signal }) => getCase(id, signal),
    enabled: allowed,
    refetchInterval: 5000,
  });
  const permitted = !query.data || user?.primarySystemIds.includes(query.data.version.systemId);
  const visible =
    permitted &&
    !subscription.denied &&
    !['connecting', 'disconnected'].includes(subscription.state);
  return (
    <main className={styles.page}>
      <header className={styles.pageHead}>
        <div>
          <h1 id="page-title" tabIndex={-1}>
            {query.isSuccess && visible ? query.data.version.title : '해결 사례 검토'}
          </h1>
          {query.isSuccess && visible && (
            <p>
              {'사례 문서 v' + String(query.data.version.caseVersion) + ' · 원 문의 처리 기록 기반'}
            </p>
          )}
        </div>
        <div className={styles.actions}>
          <Link
            className={styles.linkButton}
            to={{ pathname: paths.cases, search: search.toString() }}
          >
            사례 목록
          </Link>
          {query.data && !query.isError && visible && (
            <Link
              id={'case-ticket-' + query.data.version.ticketId}
              className={styles.linkButton}
              to={paths.ticket(query.data.version.ticketId) + '?tab=work'}
              state={{ ticketModalOrigin: 'case' }}
            >
              원 티켓 보기
            </Link>
          )}
        </div>
      </header>
      {!allowed || !permitted ? (
        <ErrorState title="이 사례를 검토할 권한이 없어요" />
      ) : query.isError && query.error instanceof ApiError && query.error.status === 404 ? (
        <ErrorState
          title="사례를 찾을 수 없어요"
          description="사례 조회 경로 또는 대상이 없어 불러오지 못했어요. 사례 목록에서 다시 확인해 주세요."
        />
      ) : subscription.denied || (query.error instanceof ApiError && query.error.status === 403) ? (
        <ErrorState title="이 사례를 검토할 권한이 없어요" />
      ) : ['connecting', 'disconnected'].includes(subscription.state) ? (
        <LoadingState label="현재 사례 접근 권한을 다시 확인하고 있어요" />
      ) : query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState
          title="사례를 불러오지 못했어요"
          onRetry={() => {
            void query.refetch();
          }}
        />
      ) : (
        <CaseEditor
          key={query.data.version.id + ':' + String(query.data.version.caseVersion)}
          detail={query.data}
        />
      )}
    </main>
  );
}
