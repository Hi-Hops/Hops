import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useSession } from '@/features/auth';
import * as api from '../api/systemTicketApi';
import { TicketImages } from './TicketImages';

const actor = crypto.randomUUID();
const ticket = crypto.randomUUID();
const image = crypto.randomUUID();
const revoke = vi.fn();

beforeEach(() => {
  useSession.setState({
    user: {
      id: actor,
      name: '담당자',
      employeeNo: 'TEST',
      roles: [],
      primarySystemIds: [],
      department: { id: crypto.randomUUID(), code: 'TEST', name: '테스트' },
    },
  });
  vi.stubGlobal(
    'URL',
    class extends URL {
      static override createObjectURL = vi.fn(() => 'blob:ticket-image');
      static override revokeObjectURL = revoke;
    },
  );
  vi.spyOn(api, 'getTicketImage').mockResolvedValue(new Blob(['fixture'], { type: 'image/png' }));
});
afterEach(() => {
  cleanup();
  revoke.mockClear();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it('현재 권한으로 조회한 이미지를 표시하고 화면을 벗어나면 임시 URL을 폐기한다', async () => {
  vi.spyOn(api, 'getTicketImages').mockResolvedValue([
    { id: image, mediaType: 'image/png', byteSize: 4 },
  ]);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={client}>
      <TicketImages ticketId={ticket} />
    </QueryClientProvider>,
  );
  expect(await screen.findByRole('img', { name: '문의자가 공개한 이미지 1' })).toHaveAttribute(
    'src',
    'blob:ticket-image',
  );
  view.unmount();
  expect(revoke).toHaveBeenCalledWith('blob:ticket-image');
});

it('공개 권한 재확인에 실패하면 이전 이미지를 숨기고 임시 URL을 폐기한다', async () => {
  vi.spyOn(api, 'getTicketImages')
    .mockResolvedValueOnce([{ id: image, mediaType: 'image/png', byteSize: 4 }])
    .mockRejectedValue(new Error('권한 회수'));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <TicketImages ticketId={ticket} />
    </QueryClientProvider>,
  );
  expect(await screen.findByRole('img', { name: '문의자가 공개한 이미지 1' })).toBeVisible();
  await client.invalidateQueries({
    queryKey: ['hops-system-1', 'tickets', 'images', actor, ticket],
  });
  expect(await screen.findByRole('alert')).toHaveTextContent('공개 상태를 확인하지 못했어요');
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
  await waitFor(() => {
    expect(revoke).toHaveBeenCalledWith('blob:ticket-image');
  });
});
