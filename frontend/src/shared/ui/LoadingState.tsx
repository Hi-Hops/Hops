import styles from './Feedback.module.css';

export function LoadingState({ label = '불러오는 중이에요' }: { label?: string }) {
  return (
    <div role="status" className={styles.loading}>
      <span className={styles.spinner} aria-hidden="true" />
      {label}
    </div>
  );
}
