import { beforeEach, describe, expect, it } from 'vitest';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { fixture } from './mockData';
import { mockBackend } from './mockBackend';
import { mockWorkspace } from './mockWorkspace';
import type { LoginResponse, MessageHistoryPage } from './types';
beforeEach(() => {
  mockBackend.reset();
});
describe('F003 Mock 메시지 조회 계약', () => {
  it('F003-AC05 size 생략도 최신 30개이며 이전 커서는 중복 없이 과거를 반환한다', () => {
    const session = mockBackend.request(
      '/api/auth/login',
      'POST',
      TEST_LOGINS.requester,
      null,
    ) as LoginResponse;
    const actor = session.user;
    const token = session.accessToken;
    const seed = fixture('inquiry-result', 'InquiryResult');
    for (let index = 0; index < 31; index++)
      mockWorkspace.command(actor, 'message.send', {
        requestId: crypto.randomUUID(),
        inquiryId: seed.id,
        expectedInquiryVersion: seed.version + index,
        content: `과거 조회 검증 ${String(index)}`,
      });
    const latest = mockBackend.request(
      `/api/inquiries/${seed.id}/messages`,
      'GET',
      undefined,
      token,
    ) as MessageHistoryPage;
    expect(latest.items).toHaveLength(30);
    expect(latest.items[0]?.sequenceNo).toBe(4);
    expect(latest.items.at(-1)?.sequenceNo).toBe(33);
    expect(latest.nextBeforeSequenceNo).toBe(4);
    const older = mockBackend.request(
      `/api/inquiries/${seed.id}/messages?beforeSequenceNo=4`,
      'GET',
      undefined,
      token,
    ) as MessageHistoryPage;
    expect(older.items.map((item) => item.sequenceNo)).toEqual([1, 2, 3]);
    expect(older.nextBeforeSequenceNo).toBeNull();
    expect(() =>
      mockBackend.request(
        `/api/inquiries/${seed.id}/messages?beforeSequenceNo=4&afterSequenceNo=0`,
        'GET',
        undefined,
        token,
      ),
    ).toThrow('메시지 커서');
  });
});
