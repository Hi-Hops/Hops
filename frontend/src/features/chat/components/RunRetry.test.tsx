import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { mockBackend } from '@/shared/api/mockBackend';
import { mockWorkspace } from '@/shared/api/mockWorkspace';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { renderWithProviders } from '@/test/renderWithProviders';
import type { RunSummary } from '@/shared/api/types';
import { ApiError } from '@/shared/api/client';
import * as chatApi from '../api/chatApi';
import { RunRetry } from './RunRetry';
const failed: RunSummary = {
  id: 'failed-run',
  inquiryVersion: 2,
  status: 'TIMED_OUT',
  error: {
    code: 'DEADLINE_EXCEEDED',
    message: '시간 초과',
    requestId: 'error-id',
    retryable: true,
  },
};
beforeEach(async () => {
  mockBackend.reset();
  useSession.getState().logout();
  await login(TEST_LOGINS.requester);
});
afterEach(() => {
  vi.restoreAllMocks();
});
describe('F003 분석 재시도', () => {
  it('F003-AC10 응답 유실 뒤 같은 요청 ID로 재확인하고 중복 클릭을 막는다', async () => {
    let complete: ((run: RunSummary) => void) | undefined;
    const request = vi
      .spyOn(chatApi, 'retryRun')
      .mockRejectedValueOnce(
        new ApiError(0, {
          code: 'NETWORK_ERROR',
          message: '연결 오류',
          requestId: 'error-id',
          retryable: true,
        }),
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            complete = resolve;
          }),
      );
    renderWithProviders(<RunRetry inquiryId="inquiry" version={2} run={failed} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '다시 분석' }));
    await screen.findByRole('status');
    await user.dblClick(screen.getByRole('button', { name: '다시 분석' }));
    expect(screen.getByRole('button', { name: '재시도 요청 중' })).toBeDisabled();
    expect(request).toHaveBeenCalledTimes(2);
    expect(request.mock.calls[0]?.[1]).toEqual(request.mock.calls[1]?.[1]);
    act(() => {
      complete?.({ ...failed, status: 'RUNNING', error: null });
    });
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent('재시도를 접수했어요');
    });
  });
  it('F003-AC10 재시도 불가능 오류나 실행 중에는 버튼을 제공하지 않는다', () => {
    renderWithProviders(
      <RunRetry inquiryId="inquiry" version={2} run={{ ...failed, status: 'RUNNING' }} />,
    );
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
  it('F003-AC10 Mock 서버는 소유권·버전을 검증하고 같은 재시도는 한 번만 접수한다', () => {
    const actor = useSession.getState().user;
    if (!actor) throw new Error('사용자 필요');
    const ack = mockWorkspace.command(actor, 'inquiry.create', {
      requestId: crypto.randomUUID(),
      content: '시간 초과',
    });
    if (!ack.ok || !ack.runId) throw new Error('문의 필요');
    const runId = ack.runId;
    mockWorkspace.flushPending();
    const request = { requestId: crypto.randomUUID(), expectedInquiryVersion: 1 };
    expect(() =>
      mockWorkspace.retryRun({ ...actor, id: crypto.randomUUID() }, runId, request),
    ).toThrow('이 문의를 볼 수 없어요');
    expect(() =>
      mockWorkspace.retryRun(actor, runId, { ...request, expectedInquiryVersion: 2 }),
    ).toThrow('최신 내용을 확인');
    const tickets = mockWorkspace.readInquiry(actor, ack.inquiryId).tickets;
    const first = mockWorkspace.retryRun(actor, ack.runId, request);
    expect(mockWorkspace.retryRun(actor, ack.runId, request)).toEqual(first);
    expect(
      mockWorkspace.retryRun(actor, runId, { ...request, requestId: crypto.randomUUID() }),
    ).toEqual(first);
    expect(first.status).toBe('RUNNING');
    mockWorkspace.flushPending();
    expect(mockWorkspace.readInquiry(actor, ack.inquiryId).tickets).toEqual(tickets);
    expect(mockWorkspace.readRun(actor, first.id).status).toBe('SUCCEEDED');
  });
});
