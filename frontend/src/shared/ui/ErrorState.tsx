import { Button } from './Button';
import styles from './Feedback.module.css';

export function ErrorState({
  title = '정보를 불러오지 못했어요',
  onRetry,
  description = '저장된 결과는 유지돼요. 잠시 후 다시 시도해 주세요.',
}: {
  title?: string;
  onRetry?: () => void;
  description?: string;
}) {
  return (
    <div role="alert" className={styles.error}>
      <strong>{title}</strong>
      <p>{description}</p>
      {onRetry && (
        <Button variant="secondary" onClick={onRetry}>
          다시 시도
        </Button>
      )}
    </div>
  );
}
