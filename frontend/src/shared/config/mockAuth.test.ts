import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mockBackend } from '@/shared/api/mockBackend';
import { fixture } from '@/shared/api/mockData';
import { TEST_LOGINS } from '@/test/mockCredentials';
import { readMockAuth } from './mockAuth';
import type { InquiryPage, LoginResponse, TicketPage } from '@/shared/api/types';
beforeEach(() => {
  mockBackend.reset();
});
describe('F001 환경 변수 Mock 로그인', () => {
  it('F001-AC01 체험 아이디와 비밀번호만으로 로그인하고 채팅·내 문의·전체 티켓을 조회한다', () => {
    const loginId = crypto.randomUUID();
    const password = crypto.randomUUID();
    vi.stubEnv('VITE_MOCK_PREVIEW_ID', loginId);
    vi.stubEnv('VITE_MOCK_PASSWORD', password);
    for (const key of [
      'VITE_MOCK_REQUESTER_ID',
      'VITE_MOCK_NETWORK_ID',
      'VITE_MOCK_BACKEND_ID',
      'VITE_MOCK_ADMIN_ID',
    ])
      vi.stubEnv(key, '');
    const session = mockBackend.request(
      '/api/auth/login',
      'POST',
      { loginId, password },
      null,
    ) as LoginResponse;
    expect(session.user).toEqual(fixture('user-preview', 'CurrentUser'));
    expect(session.user.roles).toContain('BIZ_ADMIN');
    expect(mockBackend.request('/api/auth/me', 'GET', undefined, session.accessToken)).toEqual(
      session.user,
    );
    const inquiries = mockBackend.request(
      '/api/inquiries',
      'GET',
      undefined,
      session.accessToken,
    ) as InquiryPage;
    expect(inquiries.items).toHaveLength(1);
    const id = fixture('inquiry-result', 'InquiryResult').id;
    expect(
      mockBackend.request(`/api/inquiries/${id}`, 'GET', undefined, session.accessToken),
    ).toEqual(fixture('inquiry-result', 'InquiryResult'));
    expect(
      mockBackend.request(`/api/inquiries/${id}/messages`, 'GET', undefined, session.accessToken),
    ).toEqual(fixture('messages', 'MessagePage'));
    const tickets = mockBackend.request(
      '/api/tickets',
      'GET',
      undefined,
      session.accessToken,
    ) as TicketPage;
    expect(new Set(tickets.items.map((ticket) => ticket.department.code)).size).toBe(2);
    for (const ticket of tickets.items) {
      expect(
        mockBackend.request(`/api/tickets/${ticket.id}`, 'GET', undefined, session.accessToken),
      ).toEqual(expect.objectContaining({ id: ticket.id }));
      expect(() => {
        mockBackend.authorizeTarget(session.accessToken, { type: 'TICKET', id: ticket.id });
      }).not.toThrow();
    }
    expect(
      mockBackend.command(
        'inquiry.create',
        { requestId: crypto.randomUUID(), content: 'VPN 연결이 안 돼요' },
        session.accessToken,
      ),
    ).toMatchObject({ ok: true });
  });
  it('F001-AC02 체험 모드는 설정한 한 계정만 허용한다', () => {
    vi.stubEnv('VITE_MOCK_PREVIEW_ID', crypto.randomUUID());
    expect(() => {
      mockBackend.request('/api/auth/login', 'POST', TEST_LOGINS.requester, null);
    }).toThrow('아이디 또는 비밀번호');
    vi.stubEnv('VITE_MOCK_PASSWORD', '');
    expect(readMockAuth()).toBeNull();
  });
  it('F001-AC04 체험 관리자도 다른 사용자의 문의 소유권 검사는 우회하지 않는다', () => {
    const other = mockBackend.request(
      '/api/auth/login',
      'POST',
      TEST_LOGINS.network,
      null,
    ) as LoginResponse;
    const result = mockBackend.command(
      'inquiry.create',
      { requestId: crypto.randomUUID(), content: '다른 사용자 문의' },
      other.accessToken,
    );
    if (!result.ok) throw new Error('테스트 문의 생성 실패');
    const loginId = crypto.randomUUID();
    vi.stubEnv('VITE_MOCK_PREVIEW_ID', loginId);
    const preview = mockBackend.request(
      '/api/auth/login',
      'POST',
      { loginId, password: TEST_LOGINS.requester.password },
      null,
    ) as LoginResponse;
    expect(() => {
      mockBackend.request(
        `/api/inquiries/${result.inquiryId}`,
        'GET',
        undefined,
        preview.accessToken,
      );
    }).toThrow('볼 수 없어요');
  });
  it.each([
    ['requester', 'user-0'],
    ['network', 'user-1'],
    ['backend', 'user-2'],
    ['admin', 'user-3'],
  ] as const)('F001-AC01 %s의 환경 값으로 해당 사용자에 로그인한다', (role, id) => {
    expect(mockBackend.request('/api/auth/login', 'POST', TEST_LOGINS[role], null)).toEqual(
      expect.objectContaining({ user: fixture(id, 'CurrentUser') }),
    );
  });
  it('F001-AC01 환경 값을 바꾸면 새 값만 적용되고 이전 값은 거부된다', () => {
    const loginId = crypto.randomUUID();
    const password = crypto.randomUUID();
    vi.stubEnv('VITE_MOCK_REQUESTER_ID', loginId);
    vi.stubEnv('VITE_MOCK_PASSWORD', password);
    expect(mockBackend.request('/api/auth/login', 'POST', { loginId, password }, null)).toEqual(
      expect.objectContaining({ user: fixture('user-0', 'CurrentUser') }),
    );
    expect(() =>
      mockBackend.request('/api/auth/login', 'POST', TEST_LOGINS.requester, null),
    ).toThrow('아이디 또는 비밀번호');
  });
  it('F001-AC02 누락 설정은 기본 로그인 값으로 대체하지 않는다', () => {
    vi.stubEnv('VITE_MOCK_PASSWORD', '');
    expect(readMockAuth()).toBeNull();
    expect(() =>
      mockBackend.request('/api/auth/login', 'POST', TEST_LOGINS.requester, null),
    ).toThrow('Mock 로그인 설정');
  });
  it('F001-AC02 중복 아이디는 서로 다른 권한의 계정으로 허용하지 않는다', () => {
    vi.stubEnv('VITE_MOCK_ADMIN_ID', TEST_LOGINS.requester.loginId);
    expect(readMockAuth()).toBeNull();
  });
  it('F001-AC02 아이디가 없거나 비밀번호가 공백뿐이면 설정 오류다', () => {
    vi.stubEnv('VITE_MOCK_BACKEND_ID', '');
    expect(readMockAuth()).toBeNull();
    vi.stubEnv('VITE_MOCK_BACKEND_ID', TEST_LOGINS.backend.loginId);
    vi.stubEnv('VITE_MOCK_PASSWORD', '   ');
    expect(readMockAuth()).toBeNull();
  });
});
