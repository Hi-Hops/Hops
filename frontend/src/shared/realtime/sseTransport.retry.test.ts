// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { registerTokenProvider } from '@/shared/api/client';
import { localError } from '@/shared/api/wire';
import { SseTransport } from './sseTransport';

const target = { type: 'SYSTEM_TICKETS' as const, id: '00000000-0000-4000-8000-000000000001' };
let transport: SseTransport;
let fetch: ReturnType<typeof vi.fn<typeof globalThis.fetch>>;
beforeEach(() => {
  vi.useFakeTimers();
  vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.test');
  registerTokenProvider(() => 'synthetic-current');
  fetch = vi
    .fn<typeof globalThis.fetch>()
    .mockImplementation(() =>
      Promise.resolve(Response.json(localError('UNAVAILABLE', '준비 중'), { status: 503 })),
    );
  vi.stubGlobal('fetch', fetch);
  transport = new SseTransport();
  transport.connect('synthetic-current');
});
afterEach(() => {
  transport.disconnect();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.useRealTimers();
});

describe('F002 신규 프로필 재접속 지연', () => {
  it.each([
    [0, 800],
    [0.5, 900],
    [1 - Number.EPSILON, 1000],
  ])(
    'F002-TARGET-04 난수 %s에서 첫 상한 이내의 지터 지연 %sms를 적용한다',
    async (random, delay) => {
      vi.spyOn(Math, 'random').mockReturnValue(random);
      transport.subscribe(target);
      await vi.advanceTimersByTimeAsync(delay - 1);
      expect(fetch).toHaveBeenCalledOnce();
      await vi.advanceTimersByTimeAsync(1);
      expect(fetch).toHaveBeenCalledTimes(2);
    },
  );

  it('F002-TARGET-04 30초가 지나도 단계별 상한으로 재시도하고 화면 이탈 시 중단한다', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    const unsubscribe = transport.subscribe(target);
    await vi.advanceTimersByTimeAsync(0);
    for (const [index, delay] of [900, 1800, 4500, 9000, 27000, 27000].entries()) {
      await vi.advanceTimersByTimeAsync(delay - 1);
      expect(fetch).toHaveBeenCalledTimes(index + 1);
      expect(transport.getState()).toBe('connecting');
      await vi.advanceTimersByTimeAsync(1);
      expect(fetch).toHaveBeenCalledTimes(index + 2);
    }
    unsubscribe();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fetch).toHaveBeenCalledTimes(7);
    expect(transport.getState()).toBe('idle');
  });

  it('F002-TARGET-04 429 Retry-After가 지터보다 길면 서버가 정한 시간까지 기다린다', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0);
    fetch.mockImplementation(() =>
      Promise.resolve(
        Response.json(localError('RATE_LIMITED', '잠시 후 재시도'), {
          status: 429,
          headers: { 'Retry-After': '2' },
        }),
      ),
    );
    transport.subscribe(target);
    await vi.advanceTimersByTimeAsync(1999);
    expect(fetch).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(1);
    expect(fetch).toHaveBeenCalledTimes(2);
  });
});
