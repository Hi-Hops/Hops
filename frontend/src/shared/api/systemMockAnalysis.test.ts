import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { SystemMockBackend } from './systemMockBackend';
import { systems, users } from './systemMockData';
import { readInquiry, readLogin, readReceipt } from './systemWire';

let backend: SystemMockBackend;
let token: string;
beforeEach(() => {
  vi.useFakeTimers();
  backend = new SystemMockBackend();
  token = readLogin(
    backend.request('/api/v2/auth/login', 'POST', TEST_LOGINS.requester, null),
  ).accessToken;
});
afterEach(() => {
  backend.reset();
  vi.useRealTimers();
});
function post(path: string, input: Record<string, unknown>) {
  const body = { ...input, requestId: crypto.randomUUID() };
  return readReceipt(backend.request('/api/v2' + path, 'POST', body, token, body.requestId));
}
const inquiry = (id: string) =>
  readInquiry(backend.request('/api/v2/inquiries/' + id, 'GET', undefined, token));

describe('F005 Mock 비동기 배정 실패', () => {
  it('F005-TARGET-03 PRIMARY 조회 실패는 티켓 없이 재시도 가능한 실행 실패로 종료한다', async () => {
    const receipt = post('/inquiries', {
      content: '전자결재 문서 오류',
      reportedSystemId: systems[0].id,
    });
    if (!receipt.resource) throw new Error('문의 필요');
    const id = receipt.resource.id;
    await vi.advanceTimersByTimeAsync(200);
    const command = post(`/inquiries/${id}/messages`, {
      expectedInquiryVersion: inquiry(id).inquiryVersion,
      content: '문서를 열 때 오류가 발생해요.',
      problemIds: [],
    });
    backend.revokeSystem(users[1].id, systems[0].id);
    const changed = vi.fn();
    backend.onChange(changed);
    await vi.advanceTimersByTimeAsync(200);
    const failed = inquiry(id);
    expect(failed.problems[0]?.ticket).toBeNull();
    expect(failed.currentRun).toMatchObject({
      status: 'FAILED',
      error: { code: 'ASSIGNMENT_UNAVAILABLE', requestId: command.requestId, retryable: true },
    });
    expect(changed).toHaveBeenCalled();
    const retry = post(`/runs/${failed.currentRun?.id ?? ''}/retries`, {
      expectedInquiryVersion: failed.inquiryVersion,
    });
    await vi.advanceTimersByTimeAsync(200);
    expect(inquiry(id).currentRun).toMatchObject({
      id: retry.resource?.id,
      status: 'FAILED',
      error: { code: 'ASSIGNMENT_UNAVAILABLE', requestId: retry.requestId, retryable: true },
    });
    expect(inquiry(id).problems[0]?.ticket).toBeNull();
  });
});
