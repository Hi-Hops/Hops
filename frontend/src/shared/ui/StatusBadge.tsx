import styles from './Feedback.module.css';

export type Tone = 'neutral' | 'progress' | 'attention' | 'success' | 'danger';
export function StatusBadge({ label, tone = 'neutral' }: { label: string; tone?: Tone }) {
  const icon =
    tone === 'progress'
      ? 'status-progress.svg'
      : tone === 'success'
        ? 'status-success.svg'
        : tone === 'attention'
          ? 'status-waiting.svg'
          : null;
  return (
    <span className={`${styles.badge} ${styles[tone]}`}>
      {icon && <img src={`/assets/${icon}`} width="6" height="6" alt="" />}
      {label}
    </span>
  );
}
