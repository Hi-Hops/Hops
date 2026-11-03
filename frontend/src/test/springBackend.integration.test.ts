// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, logout, useSession } from '@/features/auth';
import { getSystemTickets } from '@/features/ticket/api/systemTicketApi';
import { getInquirySystems, getSystemInquiries } from '@/features/inquiry/api/systemInquiryApi';
import { SseTransport } from '@/shared/realtime/sseTransport';

// 신규 /api/v2를 구현한 개발 서버와 현재 PRIMARY 계정을 명시한 경우에만 실행한다.
const baseUrl = process.env.HOPS_TEST_SPRING_URL;
const loginId = process.env.HOPS_TEST_LOGIN_ID;
const password = process.env.HOPS_TEST_PASSWORD;
const frontendOrigin = process.env.HOPS_TEST_FRONTEND_ORIGIN ?? 'http://localhost:5173';
let transport: SseTransport | undefined;
describe.skipIf(!baseUrl || !loginId || !password)('hops-system-1 실제 Spring 읽기 통합', () => {
  beforeEach(() => {
    useSession.getState().logout();
    vi.stubEnv('VITE_DATA_MODE', 'live');
    vi.stubEnv('VITE_API_BASE_URL', baseUrl);
    // Node fetch는 브라우저와 달리 Origin을 자동으로 보내지 않는다.
    const fetchWithoutOrigin = globalThis.fetch;
    vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
      const headers = new Headers(init?.headers);
      headers.set('Origin', frontendOrigin);
      return fetchWithoutOrigin(input, { ...init, headers });
    });
  });
  afterEach(async () => {
    transport?.disconnect();
    transport = undefined;
    if (useSession.getState().token) await logout();
    useSession.getState().logout();
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });
  async function signIn() {
    if (!loginId || !password) throw new Error('개발용 계정 설정 필요');
    return login({ loginId, password });
  }
  it('F001-CONTRACT-01 로그인·내 정보의 시스템 권한을 화면 세션에 반영한다', async () => {
    const user = await signIn();
    expect(useSession.getState().user).toEqual(user);
    expect(user.employeeNo).toBeTruthy();
    expect(user.primarySystemIds.length).toBeGreaterThan(0);
    expect(useSession.getState().token).toBeTruthy();
  }, 20000);
  it('F001-CONTRACT-03 잘못된 로그인은 인증 성공으로 대체하지 않는다', async () => {
    await expect(
      login({ loginId: loginId ?? '', password: crypto.randomUUID() }),
    ).rejects.toMatchObject({ status: 401 });
    expect(useSession.getState().token).toBeNull();
  });
  it('실제 Spring에서 시스템과 본인 문의 목록을 계약 DTO로 읽는다', async () => {
    await signIn();
    const systems = await getInquirySystems();
    const inquiries = await getSystemInquiries(0);
    expect(systems.length).toBeGreaterThan(0);
    expect(inquiries.page).toBe(0);
    expect(inquiries.size).toBe(20);
    expect(inquiries.totalElements).toBeGreaterThanOrEqual(inquiries.items.length);
  }, 20000);
  it('F009-CONTRACT-01 시스템 카탈로그와 담당 범위 티켓 DTO를 읽는다', async () => {
    const user = await signIn();
    const systems = await getInquirySystems();
    expect(user.primarySystemIds.every((id) => systems.some((system) => system.id === id))).toBe(
      true,
    );
    const tickets = await getSystemTickets('scope=ASSIGNED_SYSTEMS&page=0&size=20');
    expect(
      tickets.items.every((ticket) => user.primarySystemIds.includes(ticket.responsibleSystem.id)),
    ).toBe(true);
  }, 20000);
  it('F002-CONTRACT-01 현재 PRIMARY의 메타데이터 SSE와 REST 동기화를 연결한다', async () => {
    const user = await signIn();
    const id = user.primarySystemIds[0];
    const token = useSession.getState().token;
    if (!id || !token) throw new Error('현재 PRIMARY 개발 계정 필요');
    transport = new SseTransport();
    const sync = vi.fn(async () => {
      await getSystemTickets('scope=ASSIGNED_SYSTEMS&page=0&size=20');
    });
    transport.onSync(sync);
    transport.connect(token);
    const release = transport.subscribe({ type: 'SYSTEM_TICKETS', id });
    await vi.waitFor(
      () => {
        expect(transport?.getState()).toBe('connected');
      },
      { timeout: 5000 },
    );
    expect(sync).toHaveBeenCalledWith({ type: 'SYSTEM_TICKETS', id });
    release();
    expect(transport.getState()).toBe('idle');
  }, 20000);
});
