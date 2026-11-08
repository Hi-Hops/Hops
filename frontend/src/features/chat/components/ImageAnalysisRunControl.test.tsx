import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useSession } from '@/features/auth';
import { useSystemOperations } from '@/features/inquiry';
import * as commands from '@/shared/api/systemCommands';
import { ImageAnalysisRunControl } from './ImageAnalysisRunControl';

const actor = crypto.randomUUID();
const inquiryId = crypto.randomUUID();
const attachmentId = crypto.randomUUID();
const operationKey = `image-analysis-run:${inquiryId}:${attachmentId}`;

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

function show(available: boolean, disabled = false) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ImageAnalysisRunControl
        available={available}
        operationKey={operationKey}
        inquiryId={inquiryId}
        inquiryVersion={3}
        attachmentId={attachmentId}
        revision={2}
        disabled={disabled}
      />
    </QueryClientProvider>,
  );
}

it('F003-CONTRACT 실제 연결 전에는 재분석 요청을 제공하지 않는다', () => {
  const send = vi.spyOn(commands, 'sendSystemCommand');
  show(false);
  expect(screen.getByText('이미지 재분석은 아직 사용할 수 없어요.')).toBeVisible();
  expect(screen.queryByRole('button', { name: '이미지 재분석 요청' })).not.toBeInTheDocument();
  expect(send).not.toHaveBeenCalled();
});

it('F003-CONTRACT 연결 검증 대역에서 선택 차수와 문의 버전으로 실행을 접수한다', async () => {
  const send = vi.spyOn(commands, 'sendSystemCommand').mockImplementation((command) =>
    Promise.resolve({
      requestId: command.body.requestId,
      operationId: crypto.randomUUID(),
      state: 'SUCCEEDED',
      resource: { type: 'RUN', id: crypto.randomUUID() },
      error: null,
    }),
  );
  const user = userEvent.setup();
  show(true);
  await user.click(screen.getByRole('button', { name: '이미지 재분석 요청' }));
  expect(await screen.findByText('저장했어요. 최신 상태를 확인해 주세요.')).toBeVisible();
  expect(send.mock.calls[0]?.[0]).toMatchObject({
    path: `/api/v2/inquiries/${inquiryId}/image-attachments/${attachmentId}/analysis-runs`,
    body: { expectedInquiryVersion: 3, expectedAnalysisRevision: 2 },
    expectedResource: { type: 'RUN' },
  });
});

it('F003-CONTRACT 결과 미확인 시 동일한 요청으로 재확인한다', async () => {
  const send = vi
    .spyOn(commands, 'sendSystemCommand')
    .mockRejectedValueOnce(new Error('network'))
    .mockImplementationOnce((command) =>
      Promise.resolve({
        requestId: command.body.requestId,
        operationId: crypto.randomUUID(),
        state: 'SUCCEEDED',
        resource: { type: 'RUN', id: crypto.randomUUID() },
        error: null,
      }),
    );
  const user = userEvent.setup();
  show(true);
  await user.click(screen.getByRole('button', { name: '이미지 재분석 요청' }));
  await user.click(await screen.findByRole('button', { name: '같은 요청 결과 확인' }));
  expect(await screen.findByText('저장했어요. 최신 상태를 확인해 주세요.')).toBeVisible();
  expect(send.mock.calls[1]?.[0]).toEqual(send.mock.calls[0]?.[0]);
  expect(send.mock.calls[1]?.[1]).toBe(true);
});

it('F003-CONTRACT 대상이 준비되지 않으면 실행을 요청하지 않는다', () => {
  show(true, true);
  expect(screen.getByRole('button', { name: '이미지 재분석 요청' })).toBeDisabled();
});
