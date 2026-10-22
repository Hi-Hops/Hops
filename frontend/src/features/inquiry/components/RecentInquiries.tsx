import { NavLink } from 'react-router';
import { paths } from '@/shared/config/paths';
import { useInquiries } from '../api/inquiryApi';
import styles from './InquiryList.module.css';

export function RecentInquiries() {
  const query = useInquiries();
  const items = query.data?.items.slice(0, 5);
  if (!items?.length) return null;
  return (
    <div className={styles.recent}>
      <h2>최근 문의</h2>
      {items.map((item) => (
        <NavLink
          key={item.id}
          to={paths.inquiryChat(item.id)}
          className={({ isActive }) => (isActive ? styles.current : styles.recentLink)}
        >
          <strong>{item.title}</strong>
          <span>{item.status === 'WAITING_USER' ? '추가 확인 중' : '진행 중'}</span>
        </NavLink>
      ))}
    </div>
  );
}
