import { useSession } from '@/features/auth';
import { Button } from '@/shared/ui/Button';
import { reconnect, useConnection } from '../model/connectionStore';
import styles from './ReconnectNotice.module.css';

// 자동 재시도가 멈춘 경우에만 띄운다. 기본은 본문 흐름 밖에 두어 콘텐츠를 밀어내지 않는다.
// 모달은 바깥 요소를 가리고 막으므로 모달 안에서는 inline으로 둔다.
export function ReconnectNotice({ inline = false }: { inline?: boolean }) {
  const token = useSession((session) => session.token);
  const state = useConnection((connection) => connection.state);
  if (state !== 'disconnected') return null;
  return (
    <div className={inline ? styles.inline : styles.notice} role="status">
      <span className={styles.long} title="입력과 저장된 결과는 유지돼요.">
        실시간 연결이 끊겼어요
      </span>
      {/* 화면에 따라 둘 중 하나만 표시되므로 스크린리더도 보이는 문구 하나만 읽는다. */}
      <span className={styles.short}>연결 끊김</span>
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
