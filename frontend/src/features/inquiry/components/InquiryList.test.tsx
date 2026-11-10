import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { renderWithProviders } from '@/test/renderWithProviders';
import * as api from '../api/systemInquiryApi';
import { InquiryList } from './InquiryList';

afterEach(() => {
  vi.restoreAllMocks();
  useSession.getState().logout();
});

it('상태를 바꾸면 첫 페이지부터 조회하고 빈 결과에서도 전체 목록으로 돌아간다', async () => {
  await login(TEST_LOGINS.requester);
  const request = vi.spyOn(api, 'getSystemInquiries').mockResolvedValue({
    page: 0,
    size: 20,
    totalElements: 0,
    items: [],
  });
  const user = userEvent.setup();
  renderWithProviders(<InquiryList />, '/inquiries?page=1');

  await screen.findByText('조건에 맞는 문의가 없어요');
  expect(request).toHaveBeenLastCalledWith(1, expect.any(AbortSignal), undefined);
  const filters = within(screen.getByRole('group', { name: '문의 상태 필터' }));
  const resolved = filters.getByRole('button', { name: '해결 완료' });
  await user.click(resolved);

  expect(await screen.findByText('조건에 맞는 문의가 없어요')).toBeVisible();
  expect(resolved).toHaveAttribute('aria-pressed', 'true');
  expect(request).toHaveBeenLastCalledWith(0, expect.any(AbortSignal), 'RESOLVED');

  await user.click(screen.getByRole('button', { name: '전체 문의 보기' }));
  expect(await screen.findByText('아직 문의가 없어요')).toBeVisible();
  expect(request).toHaveBeenLastCalledWith(0, expect.any(AbortSignal), undefined);
});
