import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { ErrorPage } from './ErrorPage';

describe('초기 세팅 · 오류 경로', () => {
  it('찾을 수 없는 화면에서 복구 경로를 제공한다', () => {
    render(
      <MemoryRouter>
        <ErrorPage />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: '페이지를 찾을 수 없어요' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'IT 문의하기로' })).toHaveAttribute('href', '/');
  });
});
