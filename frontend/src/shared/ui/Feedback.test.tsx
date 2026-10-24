import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatusBadge } from './StatusBadge';
import { LoadingState } from './LoadingState';
import { ErrorState } from './ErrorState';

describe('공통 상태 표시', () => {
  it('상태는 색 외에 문구를 제공한다', () => {
    render(<StatusBadge label="배정 완료" tone="progress" />);
    expect(screen.getByText('배정 완료')).toBeVisible();
  });
  it('조회 진행을 보조 기술에 알린다', () => {
    render(<LoadingState />);
    expect(screen.getByRole('status')).toHaveTextContent('불러오는 중');
  });
  it('오류와 재시도를 빈 결과와 구분한다', () => {
    render(<ErrorState onRetry={() => undefined} />);
    expect(screen.getByRole('alert')).toHaveTextContent('불러오지 못했어요');
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeVisible();
  });
});
