import { useId, useState } from 'react';
import type { ProgressStep } from '../hooks/useSystemChat';
import styles from './ChatView.module.css';

// 단계가 이 개수 이상 쌓이면 자동으로 접고 최신 단계 하나만 보여준다.
export const COLLAPSE_AT = 3;

export function ProgressSteps({ steps, done = false }: { steps: ProgressStep[]; done?: boolean }) {
  const id = useId();
  const [expanded, setExpanded] = useState(false);
  const distinct = Array.from(new Map(steps.map((step) => [step.text, step])).values()).sort(
    (a, b) => a.stepNo - b.stepNo,
  );
  const collapsible = distinct.length >= COLLAPSE_AT;
  const shown = collapsible && !expanded ? distinct.slice(-1) : distinct;
  const latest = distinct.at(-1);
  if (done) {
    return (
      <details className={styles.completedSteps}>
        <summary>확인 과정 보기</summary>
        <ol className={styles.steps} aria-label="완료된 진행 단계">
          {distinct.map((step) => (
            <li key={step.stepNo}>{step.text}</li>
          ))}
        </ol>
      </details>
    );
  }
  return (
    <ol className={styles.steps} aria-label="진행 단계" id={id}>
      {shown.map((step) => (
        <li key={step.stepNo} data-current={step === latest || undefined}>
          {step.text}
          {/* 목록 맨 위 단계 문장 바로 뒤에 둔다. 접혀 있으면 최신 단계, 펼치면 첫 단계다. */}
          {step === shown[0] && collapsible && (
            <button
              type="button"
              className={styles.stepToggle}
              aria-expanded={expanded}
              aria-controls={id}
              aria-label={
                expanded
                  ? '진행 단계 접기'
                  : `이전 진행 단계 ${String(distinct.length - 1)}개 펼치기`
              }
              title={expanded ? '접기' : '펼치기'}
              onClick={() => {
                setExpanded((v) => !v);
              }}
            >
              <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true">
                <path
                  d="M4 6l4 4 4-4"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.6"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </button>
          )}
        </li>
      ))}
    </ol>
  );
}
