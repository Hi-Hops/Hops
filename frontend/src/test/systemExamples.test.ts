import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { getSystemTickets, getSystemTicket } from '@/features/ticket/api/systemTicketApi';
import { getCases, getCase, indexCommand } from '@/features/case/api/caseApi';
import { searchableCase } from '@/features/case/model/publication';
import { readInquiries, readInquiry } from '@/shared/api/systemWire';
import { systemGet, sendSystemCommand } from '@/shared/api/systemCommands';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { TEST_LOGINS } from './mockCredentials';

beforeEach(() => {
  useSession.getState().logout();
  systemMockBackend.reset();
});
afterEach(() => {
  useSession.getState().logout();
  vi.stubEnv('VITE_MOCK_EXAMPLES', '');
  systemMockBackend.reset();
});
function examples() {
  vi.stubEnv('VITE_MOCK_EXAMPLES', 'true');
  systemMockBackend.reset();
}

describe('F006/F009/F011 로컬 시연 예시', () => {
  it('F009-TARGET-01 명시적인 mock 예시 실행에서만 추가 데이터를 구성한다', async () => {
    await login(TEST_LOGINS.requester);
    expect((await systemGet('/inquiries', readInquiries)).totalElements).toBe(2);
    vi.stubEnv('VITE_DATA_MODE', 'live');
    examples();
    vi.stubEnv('VITE_DATA_MODE', 'mock');
    await login(TEST_LOGINS.requester);
    expect((await systemGet('/inquiries', readInquiries)).totalElements).toBe(2);
  });
  it('F006-TARGET-02/04 본인 목록에서 추가 질문·시스템 대기·GUIDE·문제별 확인 예시를 조회한다', async () => {
    examples();
    await login(TEST_LOGINS.requester);
    const page = await systemGet('/inquiries', readInquiries);
    expect(page.items.some((item) => item.pendingConfirmationCount > 0)).toBe(true);
    const details = await Promise.all(
      page.items.map((item) => systemGet('/inquiries/' + item.id, readInquiry)),
    );
    expect(
      details
        .flatMap((item) => item.problems)
        .some((p) => p.state === 'WAITING_SYSTEM' && p.questionCount === 5),
    ).toBe(true);
    const guide = details.flatMap((item) => item.problems).find((p) => p.guide);
    expect(guide).toMatchObject({
      ticket: null,
      activeResolutionRequest: {
        source: 'GUIDE',
        state: 'PENDING',
        confirmationOrigin: 'HOPS_USER',
      },
    });
    const mixed = details.find((item) => item.problems.length === 2);
    expect(mixed?.problems[0]?.creationResult?.outcome).toBe('STORED');
    expect(mixed?.problems[1]?.creationResult?.outcome).toBe('UNKNOWN');
    await expect(getCases('')).rejects.toMatchObject({ status: 403 });
  });
  it('F009-TARGET-04 모든 칸반 상태와 실패한 초안·구현 검증 예시를 제공한다', async () => {
    examples();
    await login(TEST_LOGINS.network);
    const page = await getSystemTickets('scope=ASSIGNED_SYSTEMS');
    expect(new Set(page.items.map((item) => item.state))).toEqual(
      new Set(['WAITING', 'IN_PROGRESS', 'WAITING_CONFIRMATION', 'RESOLVED']),
    );
    const details = await Promise.all(page.items.map((item) => getSystemTicket(item.id)));
    expect(
      details.some(
        (item) =>
          item.caseGeneration?.state === 'FAILED' &&
          item.workRecords.length === 1 &&
          item.activeResolutionRequest?.state === 'PENDING',
      ),
    ).toBe(true);
    expect(details.some((item) => item.caseGeneration?.state === 'NO_REUSABLE_CASE')).toBe(true);
    expect(
      details.some(
        (item) =>
          item.processingType === 'CHANGE_REQUEST' &&
          item.workRecords.some((r) => r.kind === 'IMPLEMENTED_VERIFIED'),
      ),
    ).toBe(true);
  });
  it('F011-AC02/03/04 초안·같은 버전 공개·부분 실패를 구분하고 사용자 재시도가 동작한다', async () => {
    examples();
    await login(TEST_LOGINS.network);
    const page = await getCases('');
    const details = await Promise.all(page.items.map((item) => getCase(item.id)));
    expect(
      details.some((item) => item.version.state === 'DRAFT' && item.indexes.length === 0),
    ).toBe(true);
    expect(details.some(searchableCase)).toBe(true);
    const partial = details.find((item) => item.indexes.some((index) => index.state === 'FAILED'));
    if (!partial) throw new Error('부분 실패 예시 필요');
    expect(searchableCase(partial)).toBe(false);
    expect(partial.indexes.find((item) => item.store === 'VECTOR')?.state).toBe('SUCCEEDED');
    await sendSystemCommand(
      indexCommand(
        partial.version.id,
        {
          requestId: crypto.randomUUID(),
          expectedCaseVersion: partial.version.caseVersion,
          store: 'NEO4J',
        },
        true,
      ),
    );
    expect(
      (await getCase(partial.version.id)).indexes.find((item) => item.store === 'NEO4J'),
    ).toMatchObject({ state: 'RUNNING', attempt: 2 });
    await login(TEST_LOGINS.backend);
    await expect(getCase(partial.version.id)).rejects.toMatchObject({ status: 403 });
    expect((await getCases('')).items.every((item) => item.title.includes('파일 공유'))).toBe(true);
    await login(TEST_LOGINS.admin);
    await expect(getCase(partial.version.id)).rejects.toMatchObject({ status: 403 });
  });
  it('F011-AC07 미리 설정한 응답과 시연 중 직접 저장한 응답의 출처를 구분한다', async () => {
    examples();
    await login(TEST_LOGINS.network);
    const cases = await getCases('');
    const preset = cases.items.find((item) => item.title.includes('사례 초안'));
    const pending = cases.items.find((item) => item.title.includes('해결 확인 대기'));
    if (!preset || !pending) throw new Error('확인 출처 예시 필요');
    expect((await getCase(preset.id)).version.confirmationOrigin).toBe('SYNTHETIC_PRESET');
    const detail = await getCase(pending.id);
    const ticket = await getSystemTicket(detail.version.ticketId);
    const request = ticket.activeResolutionRequest;
    if (!request) throw new Error('확인 요청 필요');
    expect(request.state).toBe('PENDING');
    await login(TEST_LOGINS.requester);
    await sendSystemCommand({
      path: '/api/v2/resolution-requests/' + request.id + '/responses',
      body: {
        requestId: crypto.randomUUID(),
        expectedRequestRevision: request.requestRevision,
        decision: 'RESOLVED',
      },
    });
    await login(TEST_LOGINS.network);
    expect((await getCase(pending.id)).version.confirmationOrigin).toBe('HOPS_USER');
    expect((await getCase(preset.id)).version.confirmationOrigin).toBe('SYNTHETIC_PRESET');
  });
});
