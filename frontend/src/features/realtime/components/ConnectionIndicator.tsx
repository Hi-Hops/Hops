import { useSession } from '@/features/auth';
import { reconnect, useConnection } from '../model/connectionStore';
import styles from './ConnectionIndicator.module.css';

const labels = {
  connected: '실시간 연결됨',
  connecting: '실시간 연결 중',
  disconnected: '실시간 연결이 끊겼어요. 눌러서 다시 연결해요.',
} as const;

export function ConnectionIndicator() {
  const token = useSession((session) => session.token);
  const state = useConnection((connection) => connection.state);
  // 구독 대상이 없는 화면은 SSE를 열지 않으므로 표시하지 않는다.
  if (state === 'idle') return null;
  if (state !== 'disconnected')
    return (
      <span
        className={`${styles.dot} ${styles[state]}`}
        role="status"
        aria-label={labels[state]}
        title={labels[state]}
      />
    );
  return (
    <button
      type="button"
      className={`${styles.dot} ${styles.disconnected}`}
      aria-label={labels.disconnected}
      title={labels.disconnected}
      onClick={() => {
        reconnect(token);
      }}
    />
  );
}
