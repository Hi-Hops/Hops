import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { SystemMockBackend } from './systemMockBackend';
import { SystemMockWorkflow } from './systemMockWorkflow';
import { users } from './systemMockData';
import { readCase, readLogin, readTicket, readTickets } from './systemWire';

let backend: SystemMockBackend;
let workflow: SystemMockWorkflow;
let caseId: string;
beforeEach(() => {
  vi.useFakeTimers();
  backend = new SystemMockBackend();
  const token = readLogin(
    backend.request('/api/v2/auth/login', 'POST', TEST_LOGINS.network, null),
  ).accessToken;
  const id = readTickets(
    backend.request('/api/v2/tickets?scope=ASSIGNED_SYSTEMS', 'GET', undefined, token),
  ).items[0]?.id;
  if (!id) throw new Error('담당 티켓 필요');
  const ticket = readTicket(backend.request('/api/v2/tickets/' + id, 'GET', undefined, token));
  workflow = new SystemMockWorkflow({
    people: () => users,
    ticket: () => ticket,
    findTicket: () => ticket,
    inquiryOwner: () => users[0].id,
    sync: vi.fn(),
    guideResponse: vi.fn(),
    evidenceAllowed: () => true,
    changed: vi.fn(),
  });
  workflow.seedExample(ticket, users[0], 'DRAFT_READY');
  const generated = ticket.caseGeneration?.caseId;
  if (!generated) throw new Error('사례 초안 필요');
  caseId = generated;
  workflow.post(users[1], ['cases', caseId, 'approvals'], { expectedCaseVersion: 1 });
});
afterEach(() => {
  workflow.reset();
  backend.reset();
  vi.useRealTimers();
});
function detail() {
  return readCase(workflow.get(users[1], ['cases', caseId], new URLSearchParams())?.value);
}
function check() {
  workflow.post(users[1], ['cases', caseId, 'index-checks'], {
    expectedCaseVersion: 1,
    store: 'NEO4J',
  });
}

describe('F011 Mock 색인 확인과 공개 경계', () => {
  it.each(['PENDING', 'RUNNING', 'UNKNOWN'] as const)(
    'F011-AC04 %s 확인만으로 성공·새 attempt·검색 공개를 만들지 않는다',
    (state) => {
      // 단위 대역의 저장 상태를 구성한다. 원격 작업 완료 타이머는 아직 실행하지 않는다.
      const current = detail();
      const vector = current.indexes.find((index) => index.store === 'VECTOR');
      const neo = current.indexes.find((index) => index.store === 'NEO4J');
      if (!vector || !neo) throw new Error('양쪽 반영 필요');
      vector.state = 'SUCCEEDED';
      neo.state = state;
      const before = structuredClone(current);
      check();
      expect(detail()).toEqual(before);
      expect(detail().publication).toMatchObject({ state: 'PRIVATE', canSearch: false });
    },
  );

  it('F011-AC04 다른 저장소가 실패했어도 미확인 상태를 임의로 성공 처리하지 않는다', () => {
    const current = detail();
    const vector = current.indexes.find((index) => index.store === 'VECTOR');
    const neo = current.indexes.find((index) => index.store === 'NEO4J');
    if (!vector || !neo) throw new Error('양쪽 반영 필요');
    vector.state = 'FAILED';
    neo.state = 'UNKNOWN';
    const before = structuredClone(current);
    check();
    expect(detail()).toEqual(before);
    expect(detail().publication.canSearch).toBe(false);
  });

  it('F011-AC03 비동기 작업이 같은 승인 버전의 양쪽 성공을 확정해야 공개한다', async () => {
    check();
    expect(detail().indexes.every((index) => index.state === 'RUNNING')).toBe(true);
    expect(detail().publication.canSearch).toBe(false);
    await vi.advanceTimersByTimeAsync(200);
    expect(detail().indexes.every((index) => index.state === 'SUCCEEDED')).toBe(true);
    expect(detail().publication).toMatchObject({ state: 'SEARCHABLE', canSearch: true });
  });
});
