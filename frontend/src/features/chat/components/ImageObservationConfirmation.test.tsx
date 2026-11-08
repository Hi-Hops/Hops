import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useSession } from '@/features/auth';
import { useSystemOperations } from '@/features/inquiry';
import * as commands from '@/shared/api/systemCommands';
import { ImageObservationConfirmation } from './ImageObservationConfirmation';

const actor = crypto.randomUUID();
const inquiryId = crypto.randomUUID();
const observation = {
  observationId: crypto.randomUUID(),
  observationRevision: 1,
  attachmentId: crypto.randomUUID(),
  problemId: crypto.randomUUID(),
  text: '오류 화면',
  state: 'UNCONFIRMED' as const,
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

it('선택한 응답과 현재 차수를 저장한다', async () => {
  const send = vi.spyOn(commands, 'sendSystemCommand').mockImplementation((command) =>
    Promise.resolve({
      requestId: command.body.requestId,
      operationId: crypto.randomUUID(),
      state: 'SUCCEEDED',
      resource: { type: 'INQUIRY', id: inquiryId },
      error: null,
    }),
  );
  const user = userEvent.setup();
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ImageObservationConfirmation
        inquiryId={inquiryId}
        inquiryVersion={3}
        observation={observation}
        disabled={false}
      />
    </QueryClientProvider>,
  );
  expect(screen.getByRole('button', { name: '응답 저장' })).toBeDisabled();
  await user.click(screen.getByRole('radio', { name: '아니에요' }));
  await user.click(screen.getByRole('button', { name: '응답 저장' }));
  expect(await screen.findByText('저장했어요. 최신 상태를 확인해 주세요.')).toBeVisible();
  expect(send.mock.calls[0]?.[0]).toMatchObject({
    path: `/api/v2/inquiries/${inquiryId}/image-observation-responses`,
    body: {
      expectedInquiryVersion: 3,
      responses: [
        {
          observationId: observation.observationId,
          expectedObservationRevision: 1,
          decision: 'INCORRECT',
        },
      ],
    },
  });
});

it('결과가 미확인이면 응답을 변경하지 않고 같은 요청을 다시 확인한다', async () => {
  const send = vi
    .spyOn(commands, 'sendSystemCommand')
    .mockRejectedValueOnce(new Error('연결 실패'))
    .mockImplementation((command) =>
      Promise.resolve({
        requestId: command.body.requestId,
        operationId: crypto.randomUUID(),
        state: 'SUCCEEDED',
        resource: { type: 'INQUIRY', id: inquiryId },
        error: null,
      }),
    );
  const user = userEvent.setup();
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ImageObservationConfirmation
        inquiryId={inquiryId}
        inquiryVersion={3}
        observation={observation}
        disabled={false}
      />
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole('radio', { name: '맞아요' }));
  await user.click(screen.getByRole('button', { name: '응답 저장' }));
  const retry = await screen.findByRole('button', { name: '같은 요청 결과 확인' });
  expect(screen.getByRole('radio', { name: '아니에요' })).toBeDisabled();
  await user.click(retry);
  expect(await screen.findByText('저장했어요. 최신 상태를 확인해 주세요.')).toBeVisible();
  expect(send.mock.calls[1]?.[0]).toEqual(send.mock.calls[0]?.[0]);
  expect(send.mock.calls[1]?.[1]).toBe(true);
});
