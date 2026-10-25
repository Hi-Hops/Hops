import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { useConnection } from '@/features/realtime';
import { mockBackend } from '@/shared/api/mockBackend';
import { fixture } from '@/shared/api/mockData';
import type { MessageHistoryPage } from '@/shared/api/types';
import { realtime } from '@/shared/realtime';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { renderWithProviders } from '@/test/renderWithProviders';
import * as chatApi from '../api/chatApi';
import { useDrafts } from '../model/draftStore';
import { ChatView } from './ChatView';
const seed = fixture('inquiry-result', 'InquiryResult');
function page(start: number, end: number, next: number | null): MessageHistoryPage {
  return {
    items: Array.from({ length: end - start + 1 }, (_, index) => {
      const sequenceNo = start + index;
      return {
        ...fixture(sequenceNo % 2 ? 'message-user' : 'message-ai', 'ChatMessage'),
        id: `message-${String(sequenceNo)}`,
        inquiryId: seed.id,
        sequenceNo,
        content: `대화 ${String(sequenceNo)}번`,
      };
    }),
    nextBeforeSequenceNo: next,
  };
}
beforeEach(async () => {
  mockBackend.reset();
  useSession.getState().logout();
  await login(TEST_LOGINS.requester);
  useConnection.getState().setState('connected');
  useDrafts.getState().set(seed.id, { content: '보존할 미전송 내용', pending: null });
  vi.spyOn(realtime, 'send').mockResolvedValue({ ok: 'unknown' });
});
afterEach(() => {
  vi.restoreAllMocks();
});
describe('F003 최신 30개와 이전 대화 조회', () => {
  it('F003-AC05 이전 메시지가 위에 추가되어도 현재 스크롤 위치를 보정한다', async () => {
    let finish: ((value: MessageHistoryPage) => void) | undefined;
    vi.spyOn(chatApi, 'getMessages')
      .mockResolvedValueOnce(page(32, 61, 32))
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      );
    renderWithProviders(<ChatView inquiryId={seed.id} />);
    await screen.findByText('대화 61번');
    const log = screen.getByLabelText('대화 내역');
    let height = 3000;
    Object.defineProperty(log, 'scrollHeight', { configurable: true, get: () => height });
    Object.defineProperty(log, 'clientHeight', { configurable: true, value: 600 });
    fireEvent.scroll(log, { target: { scrollTop: 100 } });
    await waitFor(() => {
      expect(finish).toBeDefined();
    });
    act(() => {
      height = 5000;
      finish?.(page(2, 31, 2));
    });
    await screen.findByText('대화 2번');
    expect(log.scrollTop).toBe(2100);
    expect(screen.queryByRole('button', { name: '새 메시지 ↓' })).not.toBeInTheDocument();
  });
  it('F003-AC05 과거를 읽는 중 새 메시지는 강제 이동하지 않고 이동 버튼을 제공한다', async () => {
    vi.spyOn(chatApi, 'getMessages')
      .mockResolvedValueOnce(page(32, 61, null))
      .mockResolvedValueOnce(page(33, 62, null));
    const rendered = renderWithProviders(<ChatView inquiryId={seed.id} />);
    await screen.findByText('대화 61번');
    const log = screen.getByLabelText('대화 내역');
    Object.defineProperty(log, 'scrollHeight', { configurable: true, value: 3000 });
    Object.defineProperty(log, 'clientHeight', { configurable: true, value: 600 });
    fireEvent.scroll(log, { target: { scrollTop: 1000 } });
    act(() => {
      void rendered.client.invalidateQueries({ queryKey: ['chat', seed.id, 'messages'] });
    });
    await screen.findByText('대화 62번');
    expect(log.scrollTop).toBe(1000);
    await userEvent.click(screen.getByRole('button', { name: '새 메시지 ↓' }));
    expect(log.scrollTop).toBe(3000);
  });
  it('F003-AC05 최신 30개만 오름차순 표시하고 위로 스크롤하면 이전 페이지를 조회한다', async () => {
    const request = vi
      .spyOn(chatApi, 'getMessages')
      .mockResolvedValueOnce(page(32, 61, 32))
      .mockResolvedValueOnce(page(2, 31, 2));
    renderWithProviders(<ChatView inquiryId={seed.id} />);
    await screen.findByText('대화 61번');
    expect(request).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('대화 1번')).not.toBeInTheDocument();
    const rows = screen.getAllByLabelText(/^(사용자 메시지|Hops 답변)$/u);
    expect(rows).toHaveLength(30);
    expect(rows[0]).toHaveTextContent('대화 32번');
    expect(rows.at(-1)).toHaveTextContent('대화 61번');
    await waitFor(() => {
      expect(screen.getByLabelText('메시지 입력')).toBeEnabled();
    });
    fireEvent.scroll(screen.getByLabelText('대화 내역'), { target: { scrollTop: 0 } });
    await screen.findByText('대화 2번');
    expect(request.mock.calls.map(([, before]) => before)).toEqual([null, 32]);
    expect(screen.getAllByLabelText(/^(사용자 메시지|Hops 답변)$/u)[0]).toHaveTextContent(
      '대화 2번',
    );
  });
  it('F003-AC05 이전 대화 실패에도 최신 대화와 전송을 유지하고 같은 커서로 재시도한다', async () => {
    const request = vi
      .spyOn(chatApi, 'getMessages')
      .mockResolvedValueOnce(page(2, 31, 2))
      .mockRejectedValueOnce(new Error('이전 조회 실패'))
      .mockResolvedValueOnce(page(1, 1, null));
    renderWithProviders(<ChatView inquiryId={seed.id} />);
    await screen.findByText('대화 31번');
    await userEvent.click(screen.getByRole('button', { name: '이전 대화 불러오기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('이전 대화를 불러오지 못했어요');
    expect(screen.getByText('대화 31번')).toBeVisible();
    expect(screen.getByLabelText('메시지 입력')).toBeEnabled();
    expect(screen.getByLabelText('메시지 입력')).toHaveValue('보존할 미전송 내용');
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await screen.findByText('대화 1번');
    expect(request.mock.calls.map(([, before]) => before)).toEqual([null, 2, 2]);
  });
  it('F003-AC05 최초 조회 실패 중 전송을 막고 최신 조회 성공 후 허용한다', async () => {
    vi.spyOn(chatApi, 'getMessages')
      .mockRejectedValueOnce(new Error('최초 실패'))
      .mockResolvedValueOnce(page(1, 2, null));
    renderWithProviders(<ChatView inquiryId={seed.id} />);
    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: '보내기 ↑' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '보내기 ↑' })).toBeEnabled();
    });
  });
  it('F003-AC05 F004-AC01 최신 재조회 중 정정 초안을 유지하고 우회 제출도 차단한다', async () => {
    let finish: ((value: MessageHistoryPage) => void) | undefined;
    vi.spyOn(chatApi, 'getMessages')
      .mockResolvedValueOnce(page(1, 2, null))
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      );
    const rendered = renderWithProviders(<ChatView inquiryId={seed.id} />);
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '내용 정정' })).toBeEnabled();
    });
    await userEvent.click(screen.getByRole('button', { name: '내용 정정' }));
    await userEvent.type(screen.getByLabelText('메시지 정정'), ' 보존할 정정');
    act(() => {
      void rendered.client.invalidateQueries({ queryKey: ['chat', seed.id, 'messages'] });
    });
    const input = screen.getByLabelText('메시지 정정');
    await waitFor(() => {
      expect(input).toBeDisabled();
    });
    const form = input.closest('form');
    if (!form) throw new Error('정정 폼 없음');
    fireEvent.submit(form);
    expect(vi.mocked(realtime.send).mock.calls.some(([name]) => name === 'message.revise')).toBe(
      false,
    );
    act(() => {
      finish?.(page(1, 2, null));
    });
    await waitFor(() => {
      expect(input).toBeEnabled();
    });
    expect(input).toHaveValue('대화 1번 보존할 정정');
  });
});
