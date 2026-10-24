import { afterEach, describe, expect, it, vi } from 'vitest';
import * as api from '@/shared/api/client';
import { fixture } from '@/shared/api/mockData';
import { getMessages } from './chatApi';

afterEach(() => {
  vi.restoreAllMocks();
});
describe('F003 대화 페이지 커서', () => {
  it.each([0, 20])('F003-AC05 진행하지 않는 다음 커서 %s를 오류로 처리한다', async (next) => {
    vi.spyOn(api, 'apiRequest').mockResolvedValue({
      ...fixture('messages', 'MessagePage'),
      nextAfterSequenceNo: next,
    });
    await expect(getMessages('inquiry-id', 20)).rejects.toThrow(
      '대화 조회 위치가 진행되지 않았어요',
    );
  });
  it('F003-AC05 마지막 페이지와 취소 신호를 기존 계약대로 전달한다', async () => {
    const page = { ...fixture('messages', 'MessagePage'), nextAfterSequenceNo: null };
    const request = vi.spyOn(api, 'apiRequest').mockResolvedValue(page);
    const signal = new AbortController().signal;
    await expect(getMessages('inquiry-id', 0, signal)).resolves.toEqual(page);
    expect(request).toHaveBeenCalledWith(
      '/api/inquiries/inquiry-id/messages?afterSequenceNo=0&size=20',
      { signal },
    );
  });
});
