import { useState } from 'react';
import type { ProgressStep } from '../hooks/useSystemChat';
import styles from './ChatView.module.css';

// 단계가 이 개수 이상 쌓이면 자동으로 접고 최신 단계 하나만 보여준다.
export const COLLAPSE_AT = 3;

export function ProgressSteps({ steps }: { steps: ProgressStep[] }) {
  const [expanded, setExpanded] = useState(false);
  const collapsible = steps.length >= COLLAPSE_AT;
  const shown = collapsible && !expanded ? steps.slice(-1) : steps;
  const latest = steps[steps.length - 1];
  return (
    <ol className={styles.steps} aria-label="진행 단계" id="progress-steps">
      {shown.map((step) => (
        <li key={step.stepNo} data-current={step === latest || undefined}>
          {step.text}
          {/* 펼침 여부와 관계없이 항상 보이는 최신 단계 문장 바로 뒤에 둔다. */}
          {step === latest && collapsible && (
            <button
              type="button"
              className={styles.stepToggle}
              aria-expanded={expanded}
              aria-controls="progress-steps"
              aria-label={
                expanded ? '진행 단계 접기' : `이전 진행 단계 ${String(steps.length - 1)}개 펼치기`
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
