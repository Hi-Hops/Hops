import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { SystemMockBackend } from './systemMockBackend';
import { systems } from './systemMockData';
import { readInquiry, readLogin, readMessages, readReceipt } from './systemWire';
import type { InquiryResult } from './types/hopsSystem1';

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
const messages = (id: string) =>
  readMessages(backend.request(`/api/v2/inquiries/${id}/messages`, 'GET', undefined, token)).items;
async function create(reportedSystemId: string | null) {
  const receipt = post('/inquiries', {
    content: '문서 오류와 기능 요청을 동시에 문의',
    reportedSystemId,
  });
  if (!receipt.resource) throw new Error('문의 필요');
  await vi.advanceTimersByTimeAsync(200);
  return inquiry(receipt.resource.id);
}
function send(result: InquiryResult, problemIds: string[], content = '접근 오류 보완') {
  post(`/inquiries/${result.id}/messages`, {
    expectedInquiryVersion: result.inquiryVersion,
    content,
    problemIds,
  });
  return inquiry(result.id);
}
async function mixed() {
  const result = await create(null);
  const first = result.problems[0];
  if (!first) throw new Error('첫 문제 필요');
  post(`/inquiries/${result.id}/problems/${first.id}/system-confirmations`, {
    expectedInquiryVersion: result.inquiryVersion,
    systemId: systems[0].id,
  });
  await vi.advanceTimersByTimeAsync(200);
  const current = inquiry(result.id);
  expect(current.problems[0]?.ticket).not.toBeNull();
  expect(current.problems[1]?.ticket).toBeNull();
  return current;
}

describe('F003 Mock 복합 문의의 보완 대상 격리', () => {
  it('F003-CONTRACT-05 대상 미지정 보완은 실행·질문·티켓을 변경하지 않는다', async () => {
    const before = await mixed();
    const pending = send(before, []);
    expect(pending.pendingTargetMessageIds).toHaveLength(1);
    expect(pending.currentRun).toEqual(before.currentRun);
    await vi.advanceTimersByTimeAsync(1000);
    expect(inquiry(before.id).problems).toEqual(before.problems);
    expect(messages(before.id).at(-1)).toMatchObject({ sender: 'USER', problemIds: [] });
  });

  it('F003-CONTRACT-05 연결 확정은 선택한 문제의 보완만 저장하고 재분석하지 않는다', async () => {
    const before = await mixed();
    const pending = send(before, []);
    const messageId = pending.pendingTargetMessageIds[0];
    const problemId = before.problems[0]?.id;
    if (!messageId || !problemId) throw new Error('대상 선택 필요');
    post(`/inquiries/${before.id}/messages/${messageId}/problem-links`, {
      expectedInquiryVersion: pending.inquiryVersion,
      problemIds: [problemId],
    });
    await vi.advanceTimersByTimeAsync(1000);
    const linked = inquiry(before.id);
    expect(linked.pendingTargetMessageIds).toEqual([]);
    expect(linked.currentRun).toEqual(before.currentRun);
    expect(linked.problems).toEqual(before.problems);
    expect(messages(before.id).find((m) => m.id === messageId)?.problemIds).toEqual([problemId]);
  });

  it('보류한 메시지를 새 문제로 분석하고 기존 문제를 그대로 둔다', async () => {
    const before = await create(null);
    const pending = send(before, [], '별개로 파일 다운로드도 실패해요');
    const messageId = pending.pendingTargetMessageIds[0];
    if (!messageId) throw new Error('대기 메시지 필요');

    post(`/inquiries/${before.id}/messages/${messageId}/problem-links`, {
      expectedInquiryVersion: pending.inquiryVersion,
      problemIds: [],
      newProblem: true,
    });
    await vi.advanceTimersByTimeAsync(200);

    const after = inquiry(before.id);
    expect(after.pendingTargetMessageIds).toEqual([]);
    expect(after.problems.slice(0, 2)).toEqual(before.problems);
    const added = after.problems[2];
    expect(added).toMatchObject({
      summary: '별개로 파일 다운로드도 실패해요',
      state: 'WAITING_USER',
    });
    expect(messages(before.id).find((m) => m.id === messageId)?.problemIds).toEqual([added?.id]);
  });

  it('F003-CONTRACT-05 명시한 대상만 분석하고 배정 후 보완을 다른 문제에 적용하지 않는다', async () => {
    const before = await create(systems[0].id);
    const first = before.problems[0];
    const second = before.problems[1];
    if (!first || !second) throw new Error('두 문제 필요');
    send(before, [first.id]);
    await vi.advanceTimersByTimeAsync(200);
    const stored = inquiry(before.id);
    expect(stored.problems[0]?.ticket).not.toBeNull();
    expect(stored.problems[1]).toEqual(second);
    expect(
      messages(before.id)
        .filter((m) => m.sender === 'USER')
        .at(-1)?.problemIds,
    ).toEqual([first.id]);
    send(stored, [first.id], '저장된 티켓의 보완 정보');
    await vi.advanceTimersByTimeAsync(200);
    expect(inquiry(before.id).currentRun).toEqual(stored.currentRun);
    expect(inquiry(before.id).problems).toEqual(stored.problems);
  });

  it('F003-CONTRACT-05 대상 대기 원문 정정도 연결 전까지 분석하지 않는다', async () => {
    const before = await mixed();
    const pending = send(before, []);
    const messageId = pending.pendingTargetMessageIds[0];
    if (!messageId) throw new Error('대상 선택 필요');
    post(`/inquiries/${before.id}/messages/${messageId}/revisions`, {
      expectedInquiryVersion: pending.inquiryVersion,
      expectedMessageRevision: 1,
      content: '대상을 아직 고르지 않은 보완 정정',
    });
    await vi.advanceTimersByTimeAsync(1000);
    expect(inquiry(before.id).problems).toEqual(before.problems);
    expect(inquiry(before.id).currentRun).toEqual(before.currentRun);
    expect(inquiry(before.id).pendingTargetMessageIds).toEqual([messageId]);
    expect(messages(before.id).find((m) => m.id === messageId)).toMatchObject({
      revision: 2,
      problemIds: [],
    });
  });

  it('F003-CONTRACT-05 대기 중 새 입력은 이전 실행 결과를 반영하지 않는다', async () => {
    const before = await mixed();
    const second = before.problems[1];
    if (!second) throw new Error('미접수 문제 필요');
    const queued = send(before, [second.id]);
    expect(queued.currentRun?.status).toBe('QUEUED');
    const pending = send(queued, []);
    await vi.advanceTimersByTimeAsync(1000);
    const after = inquiry(before.id);
    expect(after.currentRun).toMatchObject({ id: queued.currentRun?.id, status: 'SUPERSEDED' });
    expect(after.pendingTargetMessageIds).toEqual(pending.pendingTargetMessageIds);
    expect(after.problems).toEqual(before.problems);
  });
});
