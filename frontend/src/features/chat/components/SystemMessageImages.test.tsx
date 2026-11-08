import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useSession } from '@/features/auth';
import * as commands from '@/shared/api/systemCommands';
import * as chatApi from '../api/systemChatApi';
import * as upload from '../api/imageUpload';
import { useSystemDrafts } from '../model/systemDraftStore';
import { SystemMessageComposer } from './SystemMessageComposer';
import type { InquiryResult } from '@/shared/api/types/hopsSystem1';

vi.mock('./ImageAttachmentEditor', () => ({
  ImageAttachmentEditor: ({
    onReady,
    onSelectionChange,
    disabled,
  }: {
    onReady: (images: Blob[]) => void;
    onSelectionChange: (count: number) => void;
    disabled: boolean;
  }) => (
    <button
      type="button"
      disabled={disabled}
      onClick={() => {
        onSelectionChange(2);
        onReady([new Blob(['redacted-a']), new Blob(['redacted-b'])]);
      }}
    >
      가림 확인 테스트
    </button>
  ),
}));

const actor = crypto.randomUUID();
beforeEach(() => {
  vi.stubEnv('VITE_CHAT_IMAGE_ATTACHMENTS', 'true');
  useSession.setState({
    user: {
      id: actor,
      name: '테스트',
      employeeNo: 'TEST',
      roles: [],
      primarySystemIds: [],
      department: { id: crypto.randomUUID(), code: 'TEST', name: '테스트' },
    },
  });
  vi.spyOn(chatApi, 'getChatSystems').mockResolvedValue([]);
});
afterEach(() => {
  cleanup();
  useSystemDrafts.getState().clear();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

it('F003-CONTRACT 부분 실패 후 같은 이미지 요청을 확인하고 한 번 저장된 이미지는 다시 올리지 않는다', async () => {
  const firstId = crypto.randomUUID();
  const secondId = crypto.randomUUID();
  const inquiryId = crypto.randomUUID();
  const imageUpload = vi
    .spyOn(upload, 'uploadDraftImage')
    .mockResolvedValueOnce(firstId)
    .mockRejectedValueOnce(new Error('연결 실패'))
    .mockResolvedValueOnce(secondId);
  const send = vi.spyOn(commands, 'sendSystemCommand').mockImplementation((command) =>
    Promise.resolve({
      requestId: command.body.requestId,
      operationId: crypto.randomUUID(),
      state: 'SUCCEEDED',
      resource: { type: 'INQUIRY', id: inquiryId },
      error: null,
    }),
  );
  const stored = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const user = userEvent.setup();
  const view = render(
    <QueryClientProvider client={client}>
      <SystemMessageComposer onStored={stored} />
    </QueryClientProvider>,
  );
  await user.type(screen.getByRole('textbox', { name: '문의 내용' }), '화면 오류');
  await user.click(screen.getByRole('button', { name: '가림 확인 테스트' }));
  await user.click(screen.getByRole('checkbox', { name: /보낼 내용에 비밀번호/ }));
  await user.click(screen.getByRole('button', { name: '질문하기 ↑' }));
  const retry = await screen.findByRole('button', { name: '같은 이미지 업로드 확인' });
  expect(screen.getByRole('textbox', { name: '문의 내용' })).toHaveValue('화면 오류');
  expect(send).not.toHaveBeenCalled();
  const secondRequest = imageUpload.mock.calls[1]?.[0].requestId;
  expect(retry).toBeEnabled();
  view.unmount();
  render(
    <QueryClientProvider client={client}>
      <SystemMessageComposer onStored={stored} />
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole('button', { name: '같은 이미지 업로드 확인' }));
  await waitFor(() => {
    expect(stored).toHaveBeenCalledWith(inquiryId);
  });
  expect(imageUpload).toHaveBeenCalledTimes(3);
  expect(imageUpload.mock.calls[2]?.[0]).toMatchObject({
    requestId: secondRequest,
    attempted: true,
  });
  expect(send.mock.calls[0]?.[0].body.attachmentIds).toEqual([firstId, secondId]);
  expect(useSystemDrafts.getState().images[`${actor}:new`]).toEqual([]);
});

it('F003-CONTRACT 후속 메시지에도 확인한 이미지 순서와 현재 문의 버전을 보낸다', async () => {
  const ids = [crypto.randomUUID(), crypto.randomUUID()];
  vi.spyOn(upload, 'uploadDraftImage')
    .mockResolvedValueOnce(ids[0] ?? '')
    .mockResolvedValueOnce(ids[1] ?? '');
  const inquiry: InquiryResult = {
    id: crypto.randomUUID(),
    inquiryNumber: 'INQ-TEST',
    inquiryVersion: 7,
    status: 'IN_PROGRESS',
    problems: [],
    currentRun: null,
    pendingTargetMessageIds: [],
    resultRevision: 1,
    createdAt: '2026-11-08T00:00:00Z',
    updatedAt: '2026-11-08T00:00:00Z',
  };
  const send = vi.spyOn(commands, 'sendSystemCommand').mockImplementation((command) =>
    Promise.resolve({
      requestId: command.body.requestId,
      operationId: crypto.randomUUID(),
      state: 'SUCCEEDED',
      resource: { type: 'INQUIRY', id: inquiry.id },
      error: null,
    }),
  );
  const stored = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const user = userEvent.setup();
  render(
    <QueryClientProvider client={client}>
      <SystemMessageComposer inquiry={inquiry} onStored={stored} />
    </QueryClientProvider>,
  );
  await user.type(screen.getByRole('textbox', { name: '메시지 입력' }), '추가 화면');
  await user.click(screen.getByRole('button', { name: '가림 확인 테스트' }));
  await user.click(screen.getByRole('checkbox', { name: /보낼 내용에 비밀번호/ }));
  await user.click(screen.getByRole('button', { name: '보내기 ↑' }));
  await waitFor(() => {
    expect(stored).toHaveBeenCalledWith(inquiry.id);
  });
  expect(send.mock.calls[0]?.[0]).toMatchObject({
    path: `/api/v2/inquiries/${inquiry.id}/messages`,
    body: { expectedInquiryVersion: 7, attachmentIds: ids },
  });
});
