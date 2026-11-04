import { useSession } from '@/features/auth';
import { Button } from '@/shared/ui/Button';
import { reconnect, useConnection } from '../model/connectionStore';
import styles from './ReconnectNotice.module.css';

// 자동 재시도가 멈춘 경우에만 띄운다. 본문 흐름 밖에 두어 콘텐츠를 밀어내지 않는다.
export function ReconnectNotice() {
  const token = useSession((session) => session.token);
  const state = useConnection((connection) => connection.state);
  if (state !== 'disconnected') return null;
  return (
    <div className={styles.notice} role="status">
      <span className={styles.long} title="입력과 저장된 결과는 유지돼요.">
        실시간 연결이 끊겼어요
      </span>
      <span className={styles.short} aria-hidden="true">
        연결 끊김
      </span>
      <Button
        variant="secondary"
        className={styles.button}
        onClick={() => {
          reconnect(token);
        }}
      >
        다시 연결
      </Button>
    </div>
  );
}
