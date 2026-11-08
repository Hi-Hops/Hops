import { afterEach, expect, it, vi } from 'vitest';
import { apiImageRequest, registerRefreshHandler, registerTokenProvider } from './client';

vi.mock('@/shared/config/connection', () => ({
  connectionMode: () => 'live',
  liveConnection: () => ({ apiBaseUrl: 'https://example.test' }),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  registerTokenProvider(() => null);
});

it('F003-CONTRACT 이미지 요청에 인증 헤더를 사용하고 브라우저 캐시를 막는다', async () => {
  registerTokenProvider(() => 'test-token');
  const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(
    new Response(new Uint8Array([1, 2, 3]), {
      status: 200,
      headers: { 'Content-Type': 'image/png' },
    }),
  );
  vi.stubGlobal('fetch', fetch);
  const result = await apiImageRequest('/api/v2/inquiries/a/image-attachments/b/content');
  expect(result.type).toBe('image/png');
  expect(result.size).toBe(3);
  expect(fetch.mock.calls[0]?.[0]).toBe(
    'https://example.test/api/v2/inquiries/a/image-attachments/b/content',
  );
  expect(fetch.mock.calls[0]?.[1]).toMatchObject({
    cache: 'no-store',
    headers: { Authorization: 'Bearer test-token' },
  });
});

it('F003-CONTRACT 만료된 인증을 갱신한 뒤 같은 이미지 조회를 재시도한다', async () => {
  let token = 'expired-token';
  registerTokenProvider(() => token);
  registerRefreshHandler(() => {
    token = 'refreshed-token';
    return Promise.resolve(true);
  });
  const fetch = vi
    .fn<typeof globalThis.fetch>()
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: 'ACCESS_TOKEN_EXPIRED',
          message: '만료',
          requestId: crypto.randomUUID(),
          retryable: false,
          fieldErrors: [],
        }),
        { status: 401, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    .mockResolvedValueOnce(
      new Response(new Uint8Array([1]), { status: 200, headers: { 'Content-Type': 'image/png' } }),
    );
  vi.stubGlobal('fetch', fetch);
  await expect(
    apiImageRequest('/api/v2/inquiries/a/image-attachments/b/content'),
  ).resolves.toBeInstanceOf(Blob);
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(fetch.mock.calls[1]?.[1]?.headers).toMatchObject({
    Authorization: 'Bearer refreshed-token',
  });
});

it('F003-CONTRACT 이미지가 아닌 응답과 크기 초과 응답은 거부한다', async () => {
  registerTokenProvider(() => 'test-token');
  const fetch = vi
    .fn<typeof globalThis.fetch>()
    .mockResolvedValueOnce(
      new Response('not image', { status: 200, headers: { 'Content-Type': 'text/html' } }),
    )
    .mockResolvedValueOnce(
      new Response(new Uint8Array(8 * 1024 * 1024 + 1), {
        status: 200,
        headers: { 'Content-Type': 'image/png' },
      }),
    );
  vi.stubGlobal('fetch', fetch);
  await expect(
    apiImageRequest('/api/v2/inquiries/a/image-attachments/b/content'),
  ).rejects.toThrow();
  await expect(
    apiImageRequest('/api/v2/inquiries/a/image-attachments/b/content'),
  ).rejects.toThrow();
});
