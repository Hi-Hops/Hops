import { Link } from 'react-router';

export function ErrorPage() {
  return (
    <main>
      <h1>페이지를 찾을 수 없어요</h1>
      <p>주소를 확인하거나 메인으로 이동해 주세요.</p>
      <Link to="/">IT 문의하기로</Link>
    </main>
  );
}
