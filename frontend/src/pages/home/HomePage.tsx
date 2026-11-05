import { useSession } from '@/features/auth';
import { SystemMessageComposer } from '@/features/chat';
import { QUESTION_LIMIT } from '@/features/inquiry';
import { useLocation, useNavigate } from 'react-router';
import { paths } from '@/shared/config/paths';
import { connectionMode } from '@/shared/config/connection';
import styles from './HomePage.module.css';

const steps = [
  { title: '문제 파악', text: '적어 주신 내용을 문제별로 나눠요.' },
  { title: '필요한 질문', text: `문제마다 최대 ${String(QUESTION_LIMIT)}번까지 여쭤봐요.` },
  {
    title: '가이드 또는 담당자 연결',
    text: '승인된 가이드로 안내하거나 시스템 주담당자에게 티켓을 배정해요.',
  },
];

export function HomePage() {
  const name = useSession((state) => state.user?.name);
  const navigate = useNavigate();
  const location = useLocation();
  return (
    <div className={styles.layout}>
      <main className={styles.page}>
        <div className={styles.column}>
          <h1>무엇을 도와드릴까요, {name} 님?</h1>
          <p>
            원인이나 담당 시스템을 몰라도 괜찮아요. 겪고 있는 문제를 그대로 적어 주시면 Hops가
            확인하고, 필요하면 시스템 주담당자로 연결합니다.
          </p>
          <div className={styles.composer}>
            <SystemMessageComposer
              focusKey={location.key}
              onStored={(id) => {
                void navigate(paths.inquiryChat(id));
              }}
            />
          </div>
          {connectionMode() === 'mock' && (
            <p className={styles.mockNote}>
              Mock 체험에서는 미리 준비된 질문과 처리 결과를 사용해요.
            </p>
          )}
        </div>
      </main>
      <section className={styles.rail} aria-labelledby="home-status-title">
        <h2 id="home-status-title">처리 현황</h2>
        <p>문의를 시작하면 문제별 진행 상태가 여기에 표시돼요.</p>
        <ol>
          {steps.map((step) => (
            <li key={step.title}>
              <strong>{step.title}</strong>
              <span>{step.text}</span>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}
