import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { useConnection } from '@/features/realtime';
import { mockBackend } from '@/shared/api/mockBackend';
import { fixture } from '@/shared/api/mockData';
import type { MessagePage } from '@/shared/api/types';
import { realtime } from '@/shared/realtime';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { renderWithProviders } from '@/test/renderWithProviders';
import * as chatApi from '../api/chatApi';
import { useDrafts } from '../model/draftStore';
import { ChatView } from './ChatView';

const seed = fixture('inquiry-result', 'InquiryResult');
function messagePage(after: number, total: number): MessagePage {
  const end = Math.min(after + 20, total);
  return {
    items: Array.from({ length: end - after }, (_, index) => {
      const sequenceNo = after + index + 1;
      return {
        ...fixture(sequenceNo % 2 ? 'message-user' : 'message-ai', 'ChatMessage'),
        id: `message-${String(sequenceNo)}`,
        inquiryId: seed.id,
        sequenceNo,
        content: `대화 ${String(sequenceNo)}번`,
      };
    }),
    nextAfterSequenceNo: end < total ? end : null,
  };
}
beforeEach(async () => {
  mockBackend.reset();
  useSession.getState().logout();
  await login(TEST_LOGINS.requester);
  useConnection.getState().setState('connected');
  useDrafts.getState().set(seed.id, { content: '보존할 미전송 내용', pending: null });
  // 구독 성공에 따른 전체 캐시 갱신 없이 메시지 페이지 동작만 검사한다.
  vi.spyOn(realtime, 'send').mockResolvedValue({ ok: 'unknown' });
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('F003 최신 대화 확보', () => {
  it('F003-AC05 41개 대화를 자동 조회하며 최신 페이지 전 전송·정정을 막는다', async () => {
    let finishSecond: ((page: MessagePage) => void) | undefined;
    const request = vi
      .spyOn(chatApi, 'getMessages')
      .mockResolvedValueOnce(messagePage(0, 41))
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishSecond = resolve;
          }),
      )
      .mockResolvedValueOnce(messagePage(40, 41));
    renderWithProviders(<ChatView inquiryId={seed.id} />);
    await screen.findByText('대화 20번');
    const input = screen.getByLabelText('메시지 입력');
    expect(input).toBeDisabled();
    expect(input).toHaveValue('보존할 미전송 내용');
    expect(screen.getByRole('button', { name: '보내기 ↑' })).toBeDisabled();
    expect(
      screen
        .getAllByRole('button', { name: '내용 정정' })
        .every((button) => button.hasAttribute('disabled')),
    ).toBe(true);
    const form = input.closest('form');
    if (!form) throw new Error('입력 폼 없음');
    fireEvent.submit(form);
    expect(vi.mocked(realtime.send).mock.calls.some(([name]) => name === 'message.send')).toBe(
      false,
    );
    act(() => {
      finishSecond?.(messagePage(20, 41));
    });
    expect(await screen.findByText('대화 41번')).toBeVisible();
    await waitFor(() => {
      expect(input).toBeEnabled();
    });
    const messages = screen.getAllByLabelText(/^(사용자 메시지|Hops 답변)$/u);
    expect(messages).toHaveLength(41);
    expect(messages[0]).toHaveTextContent('대화 1번');
    expect(messages.at(-1)).toHaveTextContent('대화 41번');
    expect(request.mock.calls.map(([, after]) => after)).toEqual([0, 20, 40]);
    expect(screen.getByRole('button', { name: '보내기 ↑' })).toBeEnabled();
    expect(input).toHaveValue('보존할 미전송 내용');
    expect(screen.getAllByRole('button', { name: '내용 정정' })[0]).toBeEnabled();
  });
  it('F003-AC05 후속 페이지 실패 시 입력을 보존하고 실패한 위치부터 재시도한다', async () => {
    const request = vi
      .spyOn(chatApi, 'getMessages')
      .mockResolvedValueOnce(messagePage(0, 21))
      .mockRejectedValueOnce(new Error('후속 페이지 실패'))
      .mockResolvedValueOnce(messagePage(20, 21));
    renderWithProviders(<ChatView inquiryId={seed.id} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('대화를 불러오지 못했어요');
    expect(screen.getByLabelText('메시지 입력')).toBeDisabled();
    expect(screen.getByLabelText('메시지 입력')).toHaveValue('보존할 미전송 내용');
    expect(screen.getByText('대화 1번')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('대화 21번')).toBeVisible();
    await waitFor(() => {
      expect(screen.getByLabelText('메시지 입력')).toBeEnabled();
    });
    expect(request.mock.calls.map(([, after]) => after)).toEqual([0, 20, 20]);
    expect(screen.getByLabelText('메시지 입력')).toHaveValue('보존할 미전송 내용');
  });
  it('F003-AC05 최초 대화 조회 실패도 전송을 막고 재조회 후 복구한다', async () => {
    vi.spyOn(chatApi, 'getMessages')
      .mockRejectedValueOnce(new Error('최초 조회 실패'))
      .mockResolvedValueOnce(messagePage(0, 2));
    renderWithProviders(<ChatView inquiryId={seed.id} />);
    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: '보내기 ↑' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await screen.findByText('대화 2번');
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '보내기 ↑' })).toBeEnabled();
    });
  });
  it('F003-AC05 F004-AC01 재조회 중 열린 정정 입력을 보존하고 우회 제출도 차단한다', async () => {
    let finish: ((page: MessagePage) => void) | undefined;
    vi.spyOn(chatApi, 'getMessages')
      .mockResolvedValueOnce(messagePage(0, 2))
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
    expect(screen.getByRole('button', { name: '정정 저장' })).toBeDisabled();
    const form = input.closest('form');
    if (!form) throw new Error('정정 폼 없음');
    fireEvent.submit(form);
    expect(vi.mocked(realtime.send).mock.calls.some(([name]) => name === 'message.revise')).toBe(
      false,
    );
    act(() => {
      finish?.(messagePage(0, 2));
    });
    await waitFor(() => {
      expect(input).toBeEnabled();
    });
    expect(input).toHaveValue('대화 1번 보존할 정정');
  });
});
