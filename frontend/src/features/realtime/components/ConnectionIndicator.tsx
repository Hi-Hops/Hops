import { useConnection } from '../model/connectionStore';
import styles from './ConnectionIndicator.module.css';

const labels = {
  connected: '실시간 연결됨',
  connecting: '실시간 연결 중',
  disconnected: '실시간 연결 끊김',
} as const;

// 상태만 표시한다. 끊김 알림(live region)과 수동 재연결은 ReconnectNotice가 맡는다.
export function ConnectionIndicator() {
  const state = useConnection((connection) => connection.state);
  // 구독 대상이 없는 화면은 SSE를 열지 않으므로 표시하지 않는다.
  if (state === 'idle') return null;
  return (
    <span
      className={`${styles.dot} ${styles[state]}`}
      role="img"
      aria-label={labels[state]}
      title={labels[state]}
    />
  );
}
