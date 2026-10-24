import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import * as api from '@/shared/api/client';
import type { InquiryPage, InquiryStatus } from '@/shared/api/types';
import { mockBackend } from '@/shared/api/mockBackend';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { renderWithProviders } from '@/test/renderWithProviders';
import { RecentInquiries } from './RecentInquiries';

const statuses: InquiryStatus[] = [
  'IN_PROGRESS',
  'WAITING_USER',
  'RESOLVED',
  'CLOSED_UNCONFIRMED',
  'CLOSED',
];
function page(pageNo = 0, total = 21): InquiryPage {
  return {
    page: pageNo,
    size: 20,
    totalElements: total,
    items: Array.from({ length: Math.min(20, total - pageNo * 20) }, (_, index) => ({
      id: `session-${String(pageNo * 20 + index)}`,
      title: `문의 ${String(pageNo * 20 + index)}`,
      status: statuses[index % statuses.length] ?? 'IN_PROGRESS',
      createdAt: '2026-10-23T00:00:00Z',
    })),
  };
}
beforeEach(async () => {
  mockBackend.reset();
  useSession.getState().logout();
  await login(TEST_LOGINS.requester);
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('F006 사이드바 채팅 세션', () => {
  it('F006-AC10/12 5건을 넘는 개인 세션과 모든 상태를 표시하고 현재 대화를 구분한다', async () => {
    const request = vi.spyOn(api, 'apiRequest').mockResolvedValue(page());
    renderWithProviders(<RecentInquiries />, '/inquiries/session-0/chat');
    const list = screen.getByRole('region', { name: '채팅 세션' });
    expect(await within(list).findAllByRole('link')).toHaveLength(20);
    expect(request).toHaveBeenCalledWith('/api/inquiries?page=0&size=20');
    expect(within(list).getByRole('link', { name: /^문의 0/u })).toHaveAttribute(
      'aria-current',
      'page',
    );
    for (const label of ['진행 중', '추가 확인 중', '해결 완료', '미확인 종료', '종료']) {
      expect(within(list).getAllByText(new RegExp(`^${label} ·`, 'u')).length).toBeGreaterThan(0);
    }
  });
  it('F006-AC11 추가 조회 실패에도 목록을 유지하고 같은 페이지를 재시도하며 중복을 제거한다', async () => {
    let complete: ((value: InquiryPage) => void) | undefined;
    const request = vi
      .spyOn(api, 'apiRequest')
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
      complete?.({
        ...page(1),
        items: [...page(1).items, page().items[0]].filter((item) => item !== undefined),
      });
    });
    expect(await screen.findByRole('link', { name: /^문의 20/u })).toBeVisible();
    expect(screen.getAllByRole('link')).toHaveLength(21);
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument();
    expect(request.mock.calls.map(([url]) => url)).toEqual([
      '/api/inquiries?page=0&size=20',
      '/api/inquiries?page=1&size=20',
      '/api/inquiries?page=1&size=20',
    ]);
  });
  it('F006-AC12 최초 로딩·오류·빈 목록을 구분하고 다시 조회한다', async () => {
    let fail: ((error: Error) => void) | undefined;
    vi.spyOn(api, 'apiRequest')
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
    expect(screen.getByRole('heading', { name: '채팅 세션' })).toBeVisible();
  });
  it('F006-AC10 관리자도 본인 문의만 조회하고 계정 전환 시 이전 목록을 노출하지 않는다', async () => {
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
