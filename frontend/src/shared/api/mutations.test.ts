import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiRequest, ApiError } from './client';
import type * as clientModule from './client';
import { postMutation } from './mutations';
import type { MessageReviseCommand, MutationResult } from './types';

vi.mock('./client', async (importOriginal) => {
  const actual = await importOriginal<typeof clientModule>();
  return { ...actual, apiRequest: vi.fn() };
});

let command: MessageReviseCommand;
let result: MutationResult;
beforeEach(() => {
  vi.mocked(apiRequest).mockReset();
  command = {
    requestId: crypto.randomUUID(),
    inquiryId: crypto.randomUUID(),
    messageId: crypto.randomUUID(),
    expectedInquiryVersion: 2,
    content: '정정 원문',
  };
  result = {
    requestId: command.requestId,
    inquiryId: command.inquiryId,
    messageId: command.messageId,
    inquiryVersion: 3,
    revision: 2,
    runId: crypto.randomUUID(),
    effect: 'ANALYSIS_STARTED',
  };
});
describe('F003·F004 MutationResult 수신 계약', () => {
  it.each(['ANALYSIS_STARTED', 'SUPPLEMENT_SAVED'] as const)(
    'F003-AC01 F004-AC04 %s 저장 결과를 구분한다',
    async (effect) => {
      result = { ...result, effect, runId: effect === 'SUPPLEMENT_SAVED' ? null : result.runId };
      vi.mocked(apiRequest).mockResolvedValue(result);
      expect(await postMutation('/api/inquiries', command)).toEqual({ ...result, ok: true });
    },
  );
  it.each([
    { requestId: '잘못된 UUID' },
    { inquiryId: '잘못된 UUID' },
    { messageId: '' },
    { runId: '' },
    { inquiryVersion: 0 },
    { inquiryVersion: 2147483648 },
    { revision: 1.5 },
    { effect: 'UNKNOWN' },
    { effect: 'ANALYSIS_STARTED', runId: null },
    { effect: 'SUPPLEMENT_SAVED', runId: '00000000-0000-4000-8000-000000000001' },
  ])('F003-AC04 F004-AC06 잘못된 응답 %j는 저장 성공으로 처리하지 않는다', async (invalid) => {
    vi.mocked(apiRequest).mockResolvedValue({ ...result, ...invalid });
    expect(await postMutation('/api/inquiries', command)).toEqual({ ok: 'unknown' });
  });
  it.each(['requestId', 'inquiryId', 'messageId'] as const)(
    'F003-AC04 F004-AC06 다른 요청의 %s 결과를 합치지 않는다',
    async (field) => {
      vi.mocked(apiRequest).mockResolvedValue({ ...result, [field]: crypto.randomUUID() });
      expect(await postMutation('/api/inquiries', command)).toEqual({ ok: 'unknown' });
    },
  );
  it('F003-AC04 같은 요청 재확인은 동일 키·본문을 사용한다', async () => {
    vi.mocked(apiRequest)
      .mockRejectedValueOnce(
        new ApiError(0, {
          code: 'NETWORK_ERROR',
          message: '연결 종료',
          requestId: command.requestId,
          retryable: true,
        }),
      )
      .mockResolvedValueOnce(result);
    expect(await postMutation('/api/inquiries', command)).toEqual({ ok: 'unknown' });
    expect(await postMutation('/api/inquiries', command)).toMatchObject({
      ok: true,
      requestId: command.requestId,
    });
    const calls = vi.mocked(apiRequest).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[0]).toEqual(calls[1]);
    expect(calls[1]?.[1]).toMatchObject({ body: command, idempotencyKey: command.requestId });
  });
  it('F003-AC03 429는 대기시간을 안내하고 자동 재전송하지 않는다', async () => {
    vi.mocked(apiRequest).mockRejectedValue(
      new ApiError(
        429,
        {
          code: 'RATE_LIMITED',
          message: '요청이 많아요.',
          requestId: command.requestId,
          retryable: true,
        },
        3,
      ),
    );
    const outcome = await postMutation('/api/inquiries', command);
    expect(outcome.ok).toBe(false);
    if (outcome.ok !== false) throw new Error('오류 결과 필요');
    expect(outcome.error.code).toBe('RATE_LIMITED');
    expect(outcome.error.message).toContain('3초');
    expect(apiRequest).toHaveBeenCalledOnce();
  });
});
