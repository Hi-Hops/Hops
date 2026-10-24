import { Link } from 'react-router';
import { InquiryList } from '@/features/inquiry';
import { paths } from '@/shared/config/paths';
import styles from './InquiriesPage.module.css';

export function InquiriesPage() {
  return (
    <main className={styles.page}>
      <header>
        <div>
          <h1>내 문의 내역</h1>
          <p>대화 한 번이 문의 한 건이에요. 같은 문의의 대화를 이어갈 수 있어요.</p>
        </div>
        <Link to={paths.home}>+ 새 문의</Link>
      </header>
      <InquiryList />
    </main>
  );
}
