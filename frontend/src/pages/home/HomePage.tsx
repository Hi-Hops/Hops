import { useSession } from '@/features/auth';
import { SystemMessageComposer } from '@/features/chat';
import { useLocation, useNavigate } from 'react-router';
import { paths } from '@/shared/config/paths';
import typography from '@/shared/styles/chatTypography.module.css';
import styles from './HomePage.module.css';

const steps = ['문제 파악', '필요한 질문', '가이드 또는 담당자 연결'];

export function HomePage() {
  const name = useSession((state) => state.user?.name);
  const navigate = useNavigate();
  const location = useLocation();
  return (
    <div className={`${styles.layout} ${typography.enlarged}`}>
      <main className={styles.page}>
        <div className={styles.column}>
          <h1>무엇을 도와드릴까요, {name} 님?</h1>
          <div className={styles.composer}>
            <SystemMessageComposer
              focusKey={location.key}
              onStored={(id) => {
                void navigate(paths.inquiryChat(id));
              }}
            />
          </div>
        </div>
      </main>
      <section className={styles.rail} aria-labelledby="home-status-title">
        <h2 id="home-status-title">처리 현황</h2>
        <ol>
          {steps.map((step) => (
            <li key={step}>
              <strong>{step}</strong>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}
