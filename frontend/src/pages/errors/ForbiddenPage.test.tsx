import { screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { ForbiddenPage } from './ForbiddenPage';
import { renderWithProviders } from '@/test/renderWithProviders';
it('F001-AC04 권한 없음 화면은 접근 가능한 문의 화면을 안내한다', () => {
  renderWithProviders(<ForbiddenPage />);
  expect(screen.getByRole('alert')).toHaveTextContent('권한이 없어요');
  expect(screen.getByRole('link', { name: 'IT 문의하기로 돌아가기' })).toHaveAttribute('href', '/');
});
