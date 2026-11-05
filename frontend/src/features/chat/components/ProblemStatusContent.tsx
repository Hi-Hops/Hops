import { SystemConfirmations, SystemResultPanel } from '@/features/inquiry';
import type { InquiryResult } from '@/shared/api/types/hopsSystem1';
import styles from './ChatView.module.css';

type ProblemStatusContentProps = {
  inquiry: InquiryResult;
  analyzing: boolean;
  noticeOpen?: boolean;
  onNoticeClose?: () => void;
};

// 문제별 진행 상태와 문의자가 처리할 선택·확인을 한 곳에 모은다.
export function ProblemStatusContent({
  inquiry,
  analyzing,
  noticeOpen,
  onNoticeClose,
}: ProblemStatusContentProps) {
  if (!inquiry.problems.length)
    return <p className={styles.railEmpty}>문제를 확인하면 진행 상태가 여기에 표시돼요.</p>;
  return (
    <div className={styles.statusContent}>
      {!analyzing && <SystemConfirmations inquiry={inquiry} />}
      <SystemResultPanel result={inquiry} noticeOpen={noticeOpen} onNoticeClose={onNoticeClose} />
    </div>
  );
}
