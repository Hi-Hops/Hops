import { afterEach, describe, expect, it, vi } from 'vitest';
import * as api from '@/shared/api/client';
import { fixture } from '@/shared/api/mockData';
import { getMessages } from './chatApi';
afterEach(() => {
  vi.restoreAllMocks();
});
describe('F003 이전 대화 커서', () => {
  it.each([0, 20, 30, undefined])('F003-AC05 잘못된 이전 커서 %s는 중단한다', async (next) => {
    vi.spyOn(api, 'apiRequest').mockResolvedValue({
      ...fixture('messages', 'MessagePage'),
      nextBeforeSequenceNo: next,
    });
    await expect(getMessages('inquiry-id', 20)).rejects.toThrow(
      '대화 조회 위치가 진행되지 않았어요',
    );
  });
  it('F003-AC05 최신 30개 요청에 이전 커서를 넣지 않고 취소 신호를 전달한다', async () => {
    const page = { items: fixture('messages', 'MessagePage').items, nextBeforeSequenceNo: null };
    const request = vi.spyOn(api, 'apiRequest').mockResolvedValue(page);
    const signal = new AbortController().signal;
    await expect(getMessages('inquiry-id', null, signal)).resolves.toEqual(page);
    expect(request).toHaveBeenCalledWith('/api/inquiries/inquiry-id/messages?size=30', { signal });
  });
});
