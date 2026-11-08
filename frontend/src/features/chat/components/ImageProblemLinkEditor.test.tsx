import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useSession } from '@/features/auth';
import { useSystemOperations } from '@/features/inquiry';
import * as commands from '@/shared/api/systemCommands';
import type { InquiryResult } from '@/shared/api/types/hopsSystem1';
import * as chatApi from '../api/systemChatApi';
import { ImageProblemLinkEditor } from './ImageProblemLinkEditor';

const actor = crypto.randomUUID();
const image = crypto.randomUUID();
const message = crypto.randomUUID();
const problem = crypto.randomUUID();
const inquiry = {
  id: crypto.randomUUID(),
  inquiryVersion: 3,
  problems: [{ id: problem, summary: '로그인 오류' }],
} as unknown as InquiryResult;
const metadata = {
  id: image,
  state: 'BOUND' as const,
  mediaType: 'image/png' as const,
  byteSize: 4,
  createdAt: '2026-11-08T00:00:00Z',
  expiresAt: null,
  messageId: message,
  linkRevision: 1,
  problemIds: [] as string[],
};

beforeEach(() => {
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
});
afterEach(() => {
  cleanup();
  useSystemOperations.setState({ pending: {} });
  vi.restoreAllMocks();
});

function show() {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ImageProblemLinkEditor
        inquiry={inquiry}
        messageId={message}
        attachmentId={image}
        disabled={false}
      />
    </QueryClientProvider>,
  );
}

it('문제를 선택하고 이미지 전체 공개를 확인해야 저장할 수 있다', async () => {
  vi.spyOn(chatApi, 'getImageMetadata').mockResolvedValue(metadata);
  const send = vi.spyOn(commands, 'sendSystemCommand').mockImplementation((command) =>
    Promise.resolve({
      requestId: command.body.requestId,
      operationId: crypto.randomUUID(),
      state: 'SUCCEEDED',
      resource: { type: 'INQUIRY', id: inquiry.id },
      error: null,
    }),
  );
  const user = userEvent.setup();
  show();
  await user.click(screen.getByRole('button', { name: '공개 문제 선택' }));
  await user.click(await screen.findByRole('checkbox', { name: '로그인 오류' }));
  expect(screen.getByRole('button', { name: '공개 대상 저장' })).toBeDisabled();
  await user.click(screen.getByRole('checkbox', { name: /이미지 전체가 보이는 것을 확인/ }));
  await user.click(screen.getByRole('button', { name: '공개 대상 저장' }));
  expect(await screen.findByText('저장했어요. 최신 상태를 확인해 주세요.')).toBeVisible();
  expect(send.mock.calls[0]?.[0]).toMatchObject({
    body: {
      expectedInquiryVersion: 3,
      expectedLinkRevision: 1,
      problemIds: [problem],
      confirmImageDisclosure: true,
    },
  });
});

it('모든 대상을 해제해 공개를 철회할 수 있다', async () => {
  vi.spyOn(chatApi, 'getImageMetadata').mockResolvedValue({ ...metadata, problemIds: [problem] });
  const send = vi.spyOn(commands, 'sendSystemCommand').mockImplementation((command) =>
    Promise.resolve({
      requestId: command.body.requestId,
      operationId: crypto.randomUUID(),
      state: 'SUCCEEDED',
      resource: { type: 'INQUIRY', id: inquiry.id },
      error: null,
    }),
  );
  const user = userEvent.setup();
  show();
  await user.click(screen.getByRole('button', { name: '공개 문제 선택' }));
  await user.click(await screen.findByRole('checkbox', { name: '로그인 오류' }));
  await user.click(screen.getByRole('button', { name: '공개 대상 저장' }));
  expect(await screen.findByText('저장했어요. 최신 상태를 확인해 주세요.')).toBeVisible();
  expect(send.mock.calls[0]?.[0].body).toMatchObject({
    problemIds: [],
    confirmImageDisclosure: false,
  });
});
