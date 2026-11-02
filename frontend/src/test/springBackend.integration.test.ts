// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { apiRequest } from '@/shared/api/client';
import { SseTransport } from '@/shared/realtime/sseTransport';
import { readSse } from '@/shared/realtime/sseParser';

// 개발용 seed 계정으로만 실행한다. 기본 검사는 외부 서버를 요구하지 않는다.
const baseUrl = process.env.HOPS_TEST_SPRING_URL;
const loginId = process.env.HOPS_TEST_LOGIN_ID;
const password = process.env.HOPS_TEST_PASSWORD;
let transport: SseTransport | undefined;

describe.skipIf(!baseUrl || !loginId || !password)(
  '실제 Spring 로그인·내 정보·부서 SSE 연동',
  () => {
    beforeEach(() => {
      vi.stubEnv('VITE_DATA_MODE', 'live');
      vi.stubEnv('VITE_API_BASE_URL', baseUrl);
    });
    afterEach(() => {
      transport?.disconnect();
      transport = undefined;
      // 서버 logout은 아직 구현되지 않았다. 테스트 세션만 해제한다.
      useSession.getState().logout();
      vi.unstubAllEnvs();
    });
    async function signIn() {
      if (!loginId || !password) throw new Error('개발용 계정 설정 필요');
      return login({ loginId, password });
    }
    it('로그인 POST 응답 토큰으로 /me를 조회하고 화면 세션에 사용자·부서를 반영한다', async () => {
      const user = await signIn();
      expect(useSession.getState().user).toEqual(user);
      expect(user.department.isRoutable).toBe(true);
      expect(user.employeeNo).toBeTruthy();
      expect(useSession.getState().token).toBeTruthy();
    }, 20000);
    it('실제 서버의 로그인 실패를 사용자 오류로 전달한다', async () => {
      await expect(
        login({ loginId: loginId ?? '', password: 'invalid-test-password' }),
      ).rejects.toMatchObject({ status: 401 });
      expect(useSession.getState().token).toBeNull();
    });
    it('화면에서 사용하는 transport로 현재 부서 스트림을 열고 REST 동기화를 요청한다', async () => {
      const user = await signIn();
      const token = useSession.getState().token;
      if (!token) throw new Error('토큰 필요');
      const target = { type: 'DEPARTMENT_TICKETS' as const, id: user.department.id };
      transport = new SseTransport();
      // 현재 백엔드에는 티켓 조회 API가 없지만 부서 SSE 연결은 유지해야 한다.
      const sync = vi.fn(async () => {
        await apiRequest('/api/tickets?page=0&size=20');
      });
      transport.onSync(sync);
      transport.connect(token);
      const unsubscribe = transport.subscribe(target);
      await vi.waitFor(
        () => {
          expect(transport?.getState()).toBe('connected');
        },
        { timeout: 5000 },
      );
      expect(sync).toHaveBeenCalledWith(target);
      await expect(sync.mock.results[0]?.value).rejects.toMatchObject({ status: 404 });
      expect(transport.getState()).toBe('connected');
      unsubscribe();
      expect(transport.getState()).toBe('idle');
    });
    it('Last-Event-ID 요청의 resync.required를 프론트 파서로 읽고 EOF를 확인한다', async () => {
      const user = await signIn();
      const response = await fetch(
        `${baseUrl ?? ''}/api/realtime/events?targetType=DEPARTMENT_TICKETS&targetId=${user.department.id}`,
        {
          headers: {
            Authorization: `Bearer ${useSession.getState().token ?? ''}`,
            'Last-Event-ID': crypto.randomUUID(),
          },
          signal: AbortSignal.timeout(5000),
        },
      );
      expect(response.status).toBe(200);
      if (!response.body) throw new Error('스트림 필요');
      const events: { id?: string; event: string; data: string }[] = [];
      await readSse(
        response.body,
        (event) => {
          events.push(event);
        },
        AbortSignal.timeout(5000),
      );
      expect(events).toHaveLength(1);
      expect(events[0]?.event).toBe('resync.required');
      expect(JSON.parse(events[0]?.data ?? '{}') as unknown).toEqual({ eventId: events[0]?.id });
    });
    it('다른 부서 SSE를 요청하면 실제 서버의 403을 보존한다', async () => {
      const user = await signIn();
      // 개발 seed의 영업팀 ID. 현재 처리 부서와 다름을 먼저 검증한다.
      const otherId = 'd0000000-0000-7000-8000-000000000011';
      expect(user.department.id).not.toBe(otherId);
      await expect(
        apiRequest(`/api/realtime/events?targetType=DEPARTMENT_TICKETS&targetId=${otherId}`),
      ).rejects.toMatchObject({ status: 403, body: { code: 'ACCESS_DENIED' } });
    });
    it('미구현 문의 SSE의 503을 성공으로 대체하지 않는다', async () => {
      await signIn();
      await expect(
        apiRequest(`/api/realtime/events?targetType=INQUIRY&targetId=${crypto.randomUUID()}`),
      ).rejects.toMatchObject({ status: 503, body: { code: 'SERVICE_UNAVAILABLE' } });
    });
  },
);
