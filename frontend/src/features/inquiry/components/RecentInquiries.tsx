import { NavLink } from 'react-router';
import { paths } from '@/shared/config/paths';
import { displayDate } from '@/shared/lib/date';
import { Button } from '@/shared/ui/Button';
import { useInquirySessions } from '../api/inquiryApi';
import { inquiryStatus } from '../model/status';
import styles from './InquiryList.module.css';

export function RecentInquiries() {
  const query = useInquirySessions();
  const items = [
    ...new Map(
      query.data?.pages.flatMap((page) => page.items).map((item) => [item.id, item]),
    ).values(),
  ];
  return (
    <section className={styles.recent} aria-label="채팅 세션">
      <h2>채팅 세션</h2>
      <div className={styles.sessions}>
        {query.isPending && <p role="status">채팅 세션을 불러오고 있어요.</p>}
        {!query.isPending && !query.isError && items.length === 0 && (
          <p>아직 문의가 없어요. + 새 문의로 시작해 주세요.</p>
        )}
        {items.map((item) => (
          <NavLink
            key={item.id}
            to={paths.inquiryChat(item.id)}
            className={({ isActive }) => (isActive ? styles.current : styles.recentLink)}
          >
            <span
              className={styles.dot}
              data-tone={inquiryStatus[item.status].tone}
              aria-hidden="true"
            />
            <div className={styles.sessionText}>
              <strong>{item.title}</strong>
              <span>
                {inquiryStatus[item.status].label} · {displayDate(item.createdAt)}
              </span>
            </div>
          </NavLink>
        ))}
        {query.isError && <p role="alert">채팅 세션을 불러오지 못했어요.</p>}
        {query.isError ? (
          <Button
            variant="ghost"
            isLoading={query.isFetching}
            onClick={() => {
              void (query.isFetchNextPageError ? query.fetchNextPage() : query.refetch());
            }}
          >
            채팅 세션 다시 시도
          </Button>
        ) : (
          query.hasNextPage && (
            <Button
              variant="ghost"
              isLoading={query.isFetchingNextPage}
              disabled={query.isFetching}
              onClick={() => {
                void query.fetchNextPage();
              }}
            >
              더 보기
            </Button>
          )
        )}
      </div>
    </section>
  );
}
