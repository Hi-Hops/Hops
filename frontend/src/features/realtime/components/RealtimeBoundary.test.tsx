import { CancelledError } from '@tanstack/react-query';
import { act } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { SubscriptionTarget } from '@/shared/api/types';
import { realtime } from '@/shared/realtime';
import { renderWithProviders } from '@/test/renderWithProviders';
import { RealtimeBoundary } from './RealtimeBoundary';

afterEach(() => {
  vi.restoreAllMocks();
});

it('구독 전환으로 중단된 조회는 다시 확인하고 실제 조회 실패는 전달한다', async () => {
  let synchronize: ((target: SubscriptionTarget) => Promise<void>) | undefined;
  vi.spyOn(realtime, 'onSync').mockImplementation((listener) => {
    synchronize = listener;
    return () => undefined;
  });
  const { client } = renderWithProviders(<RealtimeBoundary>티켓</RealtimeBoundary>);
  const failed = new Error('현재 권한을 확인하지 못했어요');
  const invalidate = vi
    .spyOn(client, 'invalidateQueries')
    .mockRejectedValueOnce(new CancelledError({ revert: true }))
    .mockResolvedValueOnce(undefined)
    .mockRejectedValueOnce(failed);
  const target = { type: 'CASE', id: crypto.randomUUID() } as const;

  if (!synchronize) throw new Error('실시간 동기화 콜백 필요');
  const sync = synchronize;
  await act(async () => {
    await expect(sync(target)).resolves.toBeUndefined();
  });
  expect(invalidate).toHaveBeenCalledTimes(2);
  await act(async () => {
    await expect(sync(target)).rejects.toBe(failed);
  });
  expect(invalidate).toHaveBeenCalledTimes(3);
});
