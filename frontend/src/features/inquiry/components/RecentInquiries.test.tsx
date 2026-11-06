import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import * as api from '../api/systemInquiryApi';
import type { Page, InquirySummary } from '@/shared/api/types';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { renderWithProviders } from '@/test/renderWithProviders';
import { RecentInquiries } from './RecentInquiries';

const statuses = ['IN_PROGRESS', 'WAITING_USER', 'RESOLVED', 'CLOSED'];
const ids = Array.from({ length: 21 }, () => crypto.randomUUID());
function page(pageNo = 0, total = 21): Page<InquirySummary> {
  return {
    page: pageNo,
    size: 20,
    totalElements: total,
    items: Array.from({ length: Math.min(20, total - pageNo * 20) }, (_, index) => ({
      id: ids[pageNo * 20 + index] ?? crypto.randomUUID(),
      inquiryNumber: `Q-${pageNo * 20 + index}`,
      title: `문의 ${pageNo * 20 + index}`,
      status: statuses[index % statuses.length] ?? 'IN_PROGRESS',
      problemCount: 1,
      ticketCount: 0,
      pendingConfirmationCount: 0,
      createdAt: '2026-11-01T00:00:00Z',
      updatedAt: '2026-11-01T00:00:00Z',
    })),
  };
}
beforeEach(async () => {
  systemMockBackend.reset();
  useSession.getState().logout();
  await login(TEST_LOGINS.requester);
});
afterEach(() => {
  vi.restoreAllMocks();
  systemMockBackend.reset();
});

describe('F006 사이드바 채팅 세션', () => {
  it('F006-AC10/12 개인 세션과 상태를 표시하고 현재 대화를 구분한다', async () => {
    const request = vi.spyOn(api, 'getSystemInquiries').mockResolvedValue(page());
    renderWithProviders(<RecentInquiries />, `/inquiries/${ids[0] ?? ''}/chat`);
    const list = screen.getByRole('region', { name: '채팅 세션' });
    expect(await within(list).findAllByRole('link')).toHaveLength(20);
    expect(request).toHaveBeenCalledWith(0, expect.any(AbortSignal));
    expect(within(list).getByRole('link', { name: /^문의 0/u })).toHaveAttribute(
      'aria-current',
      'page',
    );
    for (const label of ['진행 중', '사용자 응답 대기', '해결 완료', '지원 범위 밖 안내 종료'])
      expect(within(list).getAllByText(new RegExp(`^${label} ·`, 'u')).length).toBeGreaterThan(0);
    for (const link of within(list).getAllByRole('link'))
      expect(link.querySelector('svg')).toBeInTheDocument();
    expect(
      within(list).getByRole('heading', { name: '채팅 세션' }).querySelector('svg'),
    ).toBeNull();
  });
  it('F006-AC11 추가 조회 실패에도 목록을 유지하고 같은 페이지를 재시도하며 중복을 제거한다', async () => {
    let complete: ((value: Page<InquirySummary>) => void) | undefined;
    const request = vi
      .spyOn(api, 'getSystemInquiries')
      .mockResolvedValueOnce(page())
      .mockRejectedValueOnce(new Error('추가 조회 실패'))
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            complete = resolve;
          }),
      );
    renderWithProviders(<RecentInquiries />);
    await screen.findByRole('link', { name: /^문의 0/u });
    await userEvent.click(screen.getByRole('button', { name: '더 보기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('불러오지 못했어요');
    expect(screen.getAllByRole('link')).toHaveLength(20);
    await userEvent.click(screen.getByRole('button', { name: '채팅 세션 다시 시도' }));
    expect(screen.getByRole('button', { name: /더 보기|채팅 세션 다시 시도/u })).toBeDisabled();
    act(() => {
      complete?.({ ...page(1), items: [...page(1).items, ...page().items.slice(0, 1)] });
    });
    expect(await screen.findByRole('link', { name: /^문의 20/u })).toBeVisible();
    expect(screen.getAllByRole('link')).toHaveLength(21);
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument();
    expect(request.mock.calls.map(([pageNo]) => pageNo)).toEqual([0, 1, 1]);
  });
  it('F006-AC12 최초 로딩·오류·빈 목록을 구분하고 다시 조회한다', async () => {
    let fail: ((error: Error) => void) | undefined;
    vi.spyOn(api, 'getSystemInquiries')
      .mockImplementationOnce(
        () =>
          new Promise((_, reject) => {
            fail = reject;
          }),
      )
      .mockResolvedValueOnce(page(0, 0));
    renderWithProviders(<RecentInquiries />);
    expect(screen.getByRole('status')).toHaveTextContent('불러오고 있어요');
    act(() => {
      fail?.(new Error('조회 실패'));
    });
    await screen.findByRole('alert');
    expect(screen.queryByText(/아직 문의가 없어요/u)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '채팅 세션 다시 시도' }));
    expect(await screen.findByText(/아직 문의가 없어요/u)).toBeVisible();
  });
  it('F006-AC10 관리자도 본인 문의만 조회하고 계정 전환 시 이전 목록을 표시하지 않는다', async () => {
    await login(TEST_LOGINS.admin);
    const rendered = renderWithProviders(<RecentInquiries />);
    expect(await screen.findByText(/아직 문의가 없어요/u)).toBeVisible();
    await act(async () => {
      await login(TEST_LOGINS.requester);
    });
    await waitFor(() => {
      expect(screen.getAllByRole('link').length).toBeGreaterThan(0);
    });
    act(() => {
      useSession.getState().logout();
    });
    expect(screen.queryAllByRole('link')).toHaveLength(0);
    rendered.client.clear();
  });
});
