import { useSession } from '@/features/auth';

export function HomePage() {
  const name = useSession((state) => state.user?.name);
  return (
    <main>
      <h1>무엇을 도와드릴까요, {name} 님?</h1>
      <p>겪고 있는 IT 문제를 알려 주세요.</p>
    </main>
  );
}
