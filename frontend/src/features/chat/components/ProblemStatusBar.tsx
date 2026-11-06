import { useState } from 'react';
import { problemActionLabel } from '@/features/inquiry';
import type { InquiryResult } from '@/shared/api/types/hopsSystem1';
import { RouteModal } from '@/shared/ui/RouteModal';
import { ProblemStatusContent } from './ProblemStatusContent';
import styles from './ChatView.module.css';

const barId = 'problem-status-bar';

type ProblemStatusBarProps = { inquiry: InquiryResult; analyzing: boolean };

// 처리 현황 패널이 들어가지 않는 좁은 화면에서 입력창 위 요약 줄로 현황을 연다.
export function ProblemStatusBar({ inquiry, analyzing }: ProblemStatusBarProps) {
  const [open, setOpen] = useState(false);
  const action = problemActionLabel(inquiry);
  if (!inquiry.problems.length) return null;
  return (
    <>
      <button
        id={barId}
        type="button"
        className={styles.statusBar}
        data-action={action ? true : undefined}
        aria-haspopup="dialog"
        onClick={() => {
          setOpen(true);
        }}
      >
        <span className={styles.statusBarText}>
          <strong>처리 현황 · 문제 {inquiry.problems.length}건</strong>
          {action && <span>{action}</span>}
        </span>
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
          <path
            d="M4 10l4-4 4 4"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>
      {open && (
        <RouteModal
          title="처리 현황"
          returnFocusId={barId}
          size="compact"
          onClose={() => {
            setOpen(false);
          }}
        >
          <ProblemStatusContent inquiry={inquiry} analyzing={analyzing} />
        </RouteModal>
      )}
    </>
  );
}
