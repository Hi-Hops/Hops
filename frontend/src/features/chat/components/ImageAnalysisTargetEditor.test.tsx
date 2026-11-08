import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useSession } from '@/features/auth';
import { useSystemOperations } from '@/features/inquiry';
import * as commands from '@/shared/api/systemCommands';
import type { InquiryResult } from '@/shared/api/types/hopsSystem1';
import * as chatApi from '../api/systemChatApi';
import { ImageAnalysisTargetEditor } from './ImageAnalysisTargetEditor';

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
  analysisRevision: 2,
  analysisProblemIds: [] as string[],
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

function show(disabled = false) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ImageAnalysisTargetEditor
        inquiry={inquiry}
        messageId={message}
        attachmentId={image}
        disabled={disabled}
      />
    </QueryClientProvider>,
  );
}

it('F003-CONTRACT 이미지 분석 대상만 저장하며 담당자 공개나 분석 실행을 요청하지 않는다', async () => {
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
  await user.click(screen.getByRole('button', { name: '분석 문제 선택' }));
  await user.click(await screen.findByRole('checkbox', { name: '로그인 오류' }));
  await user.click(screen.getByRole('button', { name: '분석 대상 저장' }));
  expect(await screen.findByText('저장했어요. 최신 상태를 확인해 주세요.')).toBeVisible();
  expect(send).toHaveBeenCalledTimes(1);
  expect(send.mock.calls[0]?.[0]).toMatchObject({
    path: `/api/v2/inquiries/${inquiry.id}/image-attachments/${image}/analysis-targets`,
    body: { expectedInquiryVersion: 3, expectedAnalysisRevision: 2, problemIds: [problem] },
  });
});

it('F003-CONTRACT 선택을 모두 해제하면 빈 목록을 전송한다', async () => {
  vi.spyOn(chatApi, 'getImageMetadata').mockResolvedValue({
    ...metadata,
    analysisProblemIds: [problem],
  });
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
  await user.click(screen.getByRole('button', { name: '분석 문제 선택' }));
  await user.click(await screen.findByRole('checkbox', { name: '로그인 오류' }));
  await user.click(screen.getByRole('button', { name: '분석 대상 저장' }));
  expect(send.mock.calls[0]?.[0].body).toMatchObject({ problemIds: [] });
});

it('F003-CONTRACT 알 수 없는 선택이나 이전 서버 응답은 저장하지 않는다', async () => {
  const get = vi.spyOn(chatApi, 'getImageMetadata').mockResolvedValue({
    ...metadata,
    analysisProblemIds: [crypto.randomUUID()],
  });
  const user = userEvent.setup();
  show();
  await user.click(screen.getByRole('button', { name: '분석 문제 선택' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('문의를 다시 불러와');
  expect(screen.getByRole('button', { name: '분석 대상 저장' })).toBeDisabled();
  cleanup();
  get.mockResolvedValue({
    ...metadata,
    analysisRevision: undefined,
    analysisProblemIds: undefined,
  });
  show();
  await user.click(screen.getByRole('button', { name: '분석 문제 선택' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('사용할 수 없어요');
  expect(screen.queryByRole('button', { name: '분석 대상 저장' })).not.toBeInTheDocument();
});

it('F003-CONTRACT 응답을 확인하지 못하면 같은 요청으로 결과를 다시 확인한다', async () => {
  vi.spyOn(chatApi, 'getImageMetadata').mockResolvedValue(metadata);
  const send = vi
    .spyOn(commands, 'sendSystemCommand')
    .mockRejectedValueOnce(new Error('network'))
    .mockImplementationOnce((command) =>
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
  await user.click(screen.getByRole('button', { name: '분석 문제 선택' }));
  await user.click(await screen.findByRole('checkbox', { name: '로그인 오류' }));
  await user.click(screen.getByRole('button', { name: '분석 대상 저장' }));
  await user.click(await screen.findByRole('button', { name: '같은 요청 결과 확인' }));
  expect(await screen.findByText('저장했어요. 최신 상태를 확인해 주세요.')).toBeVisible();
  expect(send.mock.calls[1]?.[0]).toEqual(send.mock.calls[0]?.[0]);
  expect(send.mock.calls[1]?.[1]).toBe(true);
});
