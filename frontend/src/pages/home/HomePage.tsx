import { useSession } from '@/features/auth';
import { MessageComposer } from '@/features/chat';
import { useNavigate } from 'react-router';
import { paths } from '@/shared/config/paths';
import { connectionMode } from '@/shared/config/connection';
import styles from './HomePage.module.css';

export function HomePage() {
  const name = useSession((state) => state.user?.name);
  const navigate = useNavigate();
  return (
    <main className={styles.page}>
      <h1>무엇을 도와드릴까요, {name} 님?</h1>
      <p>
        원인이나 담당 부서를 몰라도 괜찮아요. 겪고 있는 문제를 그대로 적어 주시면 Hops가 확인하고,
        필요하면 담당 부서로 연결합니다.
      </p>
      <div className={styles.composer}>
        <MessageComposer
          isHome
          onStored={(ack) => {
            void navigate(paths.inquiryChat(ack.inquiryId));
          }}
        />
      </div>
      {connectionMode() === 'mock' && (
        <p className={styles.mockNote}>Mock 체험에서는 미리 준비된 질문과 처리 결과를 사용해요.</p>
      )}
    </main>
  );
}
