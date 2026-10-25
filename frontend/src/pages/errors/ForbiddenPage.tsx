import { Link } from 'react-router';
import { paths } from '@/shared/config/paths';
import { ErrorState } from '@/shared/ui/ErrorState';
export function ForbiddenPage() {
  return (
    <main>
      <ErrorState
        title="이 화면을 볼 권한이 없어요"
        description="접근 가능한 문의 화면에서 계속할 수 있어요."
      />
      <Link to={paths.home}>IT 문의하기로 돌아가기</Link>
    </main>
  );
}
