import { Link, useSearchParams } from 'react-router';
import { paths } from '@/shared/config/paths';
import { displayDate } from '@/shared/lib/date';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { useInquiries } from '../api/inquiryApi';
import { inquiryStatus } from '../model/status';
import styles from './InquiryList.module.css';

export function InquiryList() {
  const [params] = useSearchParams();
  const page = Number(params.get('page') ?? '0');
  const query = useInquiries(page);
  if (query.isPending) return <LoadingState />;
  if (query.isError)
    return (
      <ErrorState
        onRetry={() => {
          void query.refetch();
        }}
      />
    );
  return (
    <section className={styles.list}>
      {query.data.items.length === 0 ? (
        <div className={styles.empty}>
          <h2>아직 문의가 없어요</h2>
          <p>IT 문제를 알려 주시면 함께 확인할게요.</p>
          <Link to={paths.home}>새 문의 시작하기</Link>
        </div>
      ) : (
        <>
          <p className={styles.count}>{query.data.totalElements}건 · 등록 최신순</p>
          <table>
            <thead>
              <tr>
                <th>문의</th>
                <th>상태</th>
                <th>등록</th>
                <th>
                  <span>대화</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {query.data.items.map((item) => (
                <tr key={item.id}>
                  <td>
                    <Link to={paths.inquiryChat(item.id)}>{item.title}</Link>
                  </td>
                  <td>
                    <StatusBadge
                      label={inquiryStatus[item.status].label}
                      tone={inquiryStatus[item.status].tone}
                    />
                  </td>
                  <td>{displayDate(item.createdAt)}</td>
                  <td>
                    <Link to={paths.inquiryChat(item.id)}>대화 이어가기</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}
