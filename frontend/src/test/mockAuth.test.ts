import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { apiRequest } from '@/shared/api/client';
import { createInquiryCommand } from '@/features/chat/api/systemChatApi';
import { savedResource, sendSystemCommand, systemGet } from '@/shared/api/systemCommands';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { systems, users } from '@/shared/api/systemMockData';
import { readInquiries, readTickets } from '@/shared/api/systemWire';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { readMockAuth } from '@/shared/config/mockAuth';

beforeEach(() => {
  systemMockBackend.reset();
  useSession.getState().logout();
});
afterEach(() => {
  systemMockBackend.reset();
  useSession.getState().logout();
});

describe('F001 환경 변수 Mock 로그인', () => {
  it('F001-AC01 체험 계정에 시스템 담당·관리 권한과 본인 새 문의를 제공한다', async () => {
    const loginId = crypto.randomUUID();
    vi.stubEnv('VITE_MOCK_PREVIEW_ID', loginId);
    const user = await login({ loginId, password: TEST_LOGINS.requester.password });
    expect(user.roles).toContain('BIZ_ADMIN');
    expect(user.primarySystemIds).toEqual([systems[0].id, systems[1].id]);
    expect((await systemGet('/inquiries?page=0&size=20', readInquiries)).items).toHaveLength(0);
    const assigned = await systemGet('/tickets?scope=ASSIGNED_SYSTEMS', readTickets);
    expect(
      assigned.items.every((ticket) => user.primarySystemIds.includes(ticket.responsibleSystem.id)),
    ).toBe(true);
    expect((await systemGet('/tickets?scope=ADMIN', readTickets)).items).toHaveLength(2);
    const command = createInquiryCommand({
      requestId: crypto.randomUUID(),
      content: '전자결재 문의',
      reportedSystemId: null,
    });
    expect(savedResource(await sendSystemCommand(command), 'INQUIRY').id).toBeTruthy();
  });
  it('F001-AC02 체험 모드는 설정한 한 계정만 허용한다', async () => {
    vi.stubEnv('VITE_MOCK_PREVIEW_ID', crypto.randomUUID());
    await expect(login(TEST_LOGINS.requester)).rejects.toThrow('아이디 또는 비밀번호');
    vi.stubEnv('VITE_MOCK_PASSWORD', '');
    expect(readMockAuth()).toBeNull();
  });
  it('F001-AC04 체험 관리자도 다른 사용자의 문의 소유권을 우회하지 않는다', async () => {
    await login(TEST_LOGINS.requester);
    const command = createInquiryCommand({
      requestId: crypto.randomUUID(),
      content: '문의자 비공개 문의',
      reportedSystemId: null,
    });
    const id = savedResource(await sendSystemCommand(command), 'INQUIRY').id;
    const loginId = crypto.randomUUID();
    vi.stubEnv('VITE_MOCK_PREVIEW_ID', loginId);
    await login({ loginId, password: TEST_LOGINS.requester.password });
    await expect(apiRequest(`/api/v2/inquiries/${id}`)).rejects.toMatchObject({ status: 403 });
  });
  it.each([
    ['requester', 0],
    ['network', 1],
    ['backend', 3],
    ['admin', 2],
  ] as const)('F001-AC01 %s 계정에 현재 시스템 역할을 부여한다', async (role, index) => {
    expect(await login(TEST_LOGINS[role])).toEqual(users[index]);
  });
  it('F001-AC01 환경 값을 바꾸면 새 값만 적용한다', async () => {
    const loginId = crypto.randomUUID();
    const password = crypto.randomUUID();
    vi.stubEnv('VITE_MOCK_REQUESTER_ID', loginId);
    vi.stubEnv('VITE_MOCK_PASSWORD', password);
    expect(await login({ loginId, password })).toEqual(users[0]);
    await expect(login(TEST_LOGINS.requester)).rejects.toThrow('아이디 또는 비밀번호');
  });
  it('F001-AC02 누락 설정은 기본 로그인 값으로 대체하지 않는다', async () => {
    vi.stubEnv('VITE_MOCK_PASSWORD', '');
    expect(readMockAuth()).toBeNull();
    await expect(login(TEST_LOGINS.requester)).rejects.toThrow('Mock 로그인 환경 설정');
  });
  it('F001-AC02 중복 아이디는 다른 권한의 계정으로 허용하지 않는다', () => {
    vi.stubEnv('VITE_MOCK_ADMIN_ID', TEST_LOGINS.requester.loginId);
    expect(readMockAuth()).toBeNull();
  });
  it('F001-AC02 아이디 누락·공백 비밀번호는 설정 오류다', () => {
    vi.stubEnv('VITE_MOCK_BACKEND_ID', '');
    expect(readMockAuth()).toBeNull();
    vi.stubEnv('VITE_MOCK_BACKEND_ID', TEST_LOGINS.backend.loginId);
    vi.stubEnv('VITE_MOCK_PASSWORD', '   ');
    expect(readMockAuth()).toBeNull();
  });
});
