import { Link, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { getSystems } from '@/features/ticket';
import { paths } from '@/shared/config/paths';
import { Button } from '@/shared/ui/Button';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { displayDate } from '@/shared/lib/date';
import { caseKeys, getCases } from '../api/caseApi';
import styles from './Cases.module.css';

export function CaseList() {
  const user = useSession((s) => s.user);
  const allowed = Boolean(user?.primarySystemIds.length);
  const [search, setSearch] = useSearchParams();
  const params = new URLSearchParams();
  for (const key of ['systemId', 'state', 'page', 'size']) {
    const value = search.get(key);
    if (value !== null) params.set(key, value);
  }
  const catalog = useQuery({
    queryKey: ['hops-system-1', 'systems', user?.id],
    queryFn: ({ signal }) => getSystems(signal),
    enabled: allowed,
  });
  const query = useQuery({
    queryKey: caseKeys.list(user?.id ?? '', params.toString()),
    queryFn: ({ signal }) => getCases(params.toString(), signal),
    enabled: allowed,
    refetchInterval: 5000,
  });
  function change(key: string, value: string) {
    const next = new URLSearchParams(search);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== 'page') next.delete('page');
    setSearch(next);
  }
  return (
    <main className={styles.page}>
      <header className={styles.pageHead}>
        <div>
          <h1 id="page-title" tabIndex={-1}>
            해결 사례 검토
          </h1>
        </div>
      </header>
      {!allowed ? (
        <ErrorState title="해결 사례 검토 권한이 없어요" />
      ) : (
        <section className={styles.panel}>
          <div className={styles.toolbar}>
            <label htmlFor="case-system">책임 시스템</label>
            <select
              id="case-system"
              value={search.get('systemId') ?? ''}
              onChange={(e) => {
                change('systemId', e.target.value);
              }}
            >
              <option value="">전체 시스템</option>
              {catalog.data
                ?.filter((s) => user?.primarySystemIds.includes(s.id))
                .map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
            </select>
            <label htmlFor="case-state">사례 상태</label>
            <select
              id="case-state"
              value={search.get('state') ?? ''}
              onChange={(e) => {
                change('state', e.target.value);
              }}
            >
              <option value="">전체 상태</option>
              <option value="DRAFT">초안</option>
              <option value="APPROVED">승인됨</option>
            </select>
            <Button
              variant="secondary"
              onClick={() => {
                void query.refetch();
                void catalog.refetch();
              }}
            >
              새로고침
            </Button>
          </div>
          {query.isPending || catalog.isPending ? (
            <LoadingState />
          ) : query.isError || catalog.isError ? (
            <ErrorState
              title="사례 목록을 불러오지 못했어요"
              onRetry={() => {
                void query.refetch();
                void catalog.refetch();
              }}
            />
          ) : !query.data.items.length ? (
            <div className={styles.empty}>
              <h2>검토할 사례가 없어요</h2>
            </div>
          ) : (
            <div className={styles.tableWrap}>
              <table>
                <caption>담당 시스템 해결 사례 검토 목록</caption>
                <thead>
                  <tr>
                    <th>사례 제목</th>
                    <th>시스템</th>
                    <th>버전</th>
                    <th>상태</th>
                    <th>검색 공개</th>
                    <th>최근 변경</th>
                    <th>검토</th>
                  </tr>
                </thead>
                <tbody>
                  {query.data.items.map((item) => {
                    const published =
                      item.latestState === 'APPROVED' &&
                      item.publishedVersions.length === 1 &&
                      item.publishedVersions[0] === item.latestCaseVersion;
                    return (
                      <tr key={item.id}>
                        <td>
                          <Link to={{ pathname: paths.case(item.id), search: search.toString() }}>
                            {item.title}
                          </Link>
                        </td>
                        <td>
                          {catalog.data.find((s) => s.id === item.systemId)?.name ??
                            '시스템 확인 필요'}
                        </td>
                        <td>v{item.latestCaseVersion}</td>
                        <td>
                          <StatusBadge
                            label={
                              item.latestState === 'DRAFT'
                                ? '초안·검토 필요'
                                : item.latestState === 'APPROVED'
                                  ? '승인됨'
                                  : '상태 확인 필요'
                            }
                          />
                        </td>
                        <td>
                          <StatusBadge
                            label={published ? '검색 공개' : '비공개'}
                            tone={published ? 'success' : 'neutral'}
                          />
                        </td>
                        <td>{displayDate(item.updatedAt)}</td>
                        <td>
                          <Link
                            className={styles.linkButton}
                            to={{ pathname: paths.case(item.id), search: search.toString() }}
                          >
                            검토하기
                          </Link>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
          {query.data && !query.isError && (
            <nav className={styles.pagination} aria-label="사례 페이지">
              <span>
                전체 {query.data.totalElements}건 · {query.data.page + 1} 페이지
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
