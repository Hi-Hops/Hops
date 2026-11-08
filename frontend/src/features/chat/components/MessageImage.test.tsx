import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { getMessageImage } from '../api/systemChatApi';
import { MessageImage } from './MessageImage';

vi.mock('../api/systemChatApi', () => ({ getMessageImage: vi.fn() }));

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it('F003-CONTRACT 이미지 조회 후 표시하고 화면을 벗어나면 임시 URL을 폐기한다', async () => {
  const create = vi.fn(() => 'blob:fixture');
  const revoke = vi.fn();
  vi.stubGlobal(
    'URL',
    class extends URL {
      static override createObjectURL = create;
      static override revokeObjectURL = revoke;
    },
  );
  vi.mocked(getMessageImage).mockResolvedValue(new Blob(['fixture'], { type: 'image/png' }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={client}>
      <MessageImage actor="actor" inquiryId="inquiry" attachmentId="image" position={0} />
    </QueryClientProvider>,
  );
  expect(await screen.findByRole('img', { name: '첨부 이미지 1' })).toHaveAttribute(
    'src',
    'blob:fixture',
  );
  view.unmount();
  expect(revoke).toHaveBeenCalledWith('blob:fixture');
  await waitFor(() => {
    expect(client.getQueryCache().getAll()).toHaveLength(0);
  });
});

it('F003-CONTRACT 조회에 실패하면 이미지 대신 실패 상태를 표시한다', async () => {
  vi.mocked(getMessageImage).mockRejectedValue(new Error('실패'));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MessageImage actor="actor" inquiryId="inquiry" attachmentId="image" position={1} />
    </QueryClientProvider>,
  );
  expect(await screen.findByText('첨부 이미지를 불러오지 못했어요.')).toBeInTheDocument();
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
});
