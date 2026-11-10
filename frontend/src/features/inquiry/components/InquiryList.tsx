import { Link, useSearchParams } from 'react-router';
import type { InquiryStatus } from '@/shared/api/types/hopsSystem1';
import { paths } from '@/shared/config/paths';
import { displayDate } from '@/shared/lib/date';
import { Button } from '@/shared/ui/Button';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { useInquiries } from '../api/inquiryApi';
import { systemStatus } from '../model/systemStatus';
import styles from './InquiryList.module.css';

const statusFilters: { value?: InquiryStatus; label: string }[] = [
  { label: '전체' },
  { value: 'IN_PROGRESS', label: '진행 중' },
  { value: 'WAITING_USER', label: '응답 대기' },
  { value: 'RESOLVED', label: '해결 완료' },
  { value: 'CLOSED', label: '범위 밖 종료' },
];

export function InquiryList() {
  const [params, setParams] = useSearchParams();
  const rawPage = Number(params.get('page') ?? '0');
  const page = Number.isInteger(rawPage) && rawPage >= 0 && rawPage <= 2_147_483_647 ? rawPage : 0;
  const status = statusFilters.find((filter) => filter.value === params.get('status'))?.value;
  const query = useInquiries(page, status);
  const pages = query.data?.pages ?? [];
  const items = [
    ...new Map(pages.flatMap((part) => part.items).map((item) => [item.id, item])).values(),
  ];
  const total = pages.at(-1)?.totalElements;
  const filtered = status !== undefined || page > 0;

  function changeFilter(value?: InquiryStatus) {
    const next = new URLSearchParams(params);
    if (value) next.set('status', value);
    else next.delete('status');
    next.delete('page');
    setParams(next);
  }

  return (
    <section className={styles.list} aria-label="내 문의 목록">
      <div className={styles.toolbar}>
        <div className={styles.filters} role="group" aria-label="문의 상태 필터">
          {statusFilters.map((filter) => (
            <Button
              key={filter.label}
              variant="ghost"
              className={styles.filter}
              aria-pressed={filter.value === status}
              onClick={() => {
                changeFilter(filter.value);
              }}
            >
              {filter.label}
            </Button>
          ))}
        </div>
        {total !== undefined && (
          <p className={styles.count} aria-live="polite">
            <strong>{total.toLocaleString('ko-KR')}건</strong> · 최근 변경순
          </p>
        )}
      </div>

      {query.isPending ? (
        <LoadingState />
      ) : query.isError && !query.data ? (
        <ErrorState
          title="문의 목록을 불러오지 못했어요"
          onRetry={() => {
            void query.refetch();
          }}
        />
      ) : items.length === 0 ? (
        <div className={styles.empty}>
          <h2>{filtered ? '조건에 맞는 문의가 없어요' : '아직 문의가 없어요'}</h2>
          {filtered ? (
            <Button
              variant="secondary"
              onClick={() => {
                changeFilter();
              }}
            >
              전체 문의 보기
            </Button>
          ) : (
            <Link to={paths.home}>새 문의 시작하기</Link>
          )}
        </div>
      ) : (
        <>
          <div className={styles.tableWrap}>
            <table className={styles.table} aria-label="문의 내역" role="table">
              <thead role="rowgroup">
                <tr role="row">
                  <th scope="col" role="columnheader">
                    문의 제목
                  </th>
                  <th scope="col" role="columnheader">
                    상태
                  </th>
                  <th scope="col" role="columnheader">
                    등록 시간
                  </th>
                  <th scope="col" role="columnheader">
                    해결 확인
                  </th>
                </tr>
              </thead>
              <tbody role="rowgroup">
                {items.map((item) => {
                  const path = paths.inquiryChat(item.id);
                  return (
                    <tr key={item.id} role="row">
                      <td className={styles.subject} role="cell">
                        <Link to={path} className={styles.subjectLink} title={item.title}>
                          {item.title}
                        </Link>
                      </td>
                      <td className={styles.state} role="cell">
                        <StatusBadge {...systemStatus(item.status)} />
                      </td>
                      <td className={styles.created} role="cell">
                        <time dateTime={item.createdAt}>{displayDate(item.createdAt)}</time>
                      </td>
                      <td
                        className={styles.confirmation}
                        data-empty={!item.pendingConfirmationCount || undefined}
                        role="cell"
                      >
                        {item.pendingConfirmationCount > 0 ? (
                          <Link
                            className={styles.confirmationLink}
                            to={path + '#problem-confirmations'}
                          >
                            확인 필요 {item.pendingConfirmationCount}건
                          </Link>
                        ) : (
                          <span className={styles.dash} aria-label="확인 요청 없음">
                            –
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {(query.hasNextPage || query.isError) && (
            <div className={styles.more}>
              {query.isError && (
                <p role="alert">
                  {query.isFetchNextPageError
                    ? '다음 문의를 불러오지 못했어요.'
                    : '최신 문의 상태를 불러오지 못했어요.'}
                </p>
              )}
              <Button
                variant="secondary"
                isLoading={query.isFetchingNextPage}
                disabled={query.isFetching}
                onClick={() => {
                  void (query.isError && !query.isFetchNextPageError
                    ? query.refetch()
                    : query.fetchNextPage());
                }}
              >
                {query.isError ? '다시 시도' : '문의 더 보기'}
              </Button>
            </div>
          )}
        </>
      )}
    </section>
  );
}
