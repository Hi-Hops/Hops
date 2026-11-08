import type { ApiErrorBody } from './types';
import { MockHttpError } from './mockError';
import { connectionMode, liveConnection } from '@/shared/config/connection';
import { isApiError, localError } from './wire';
import { systemMockBackend } from './systemMockBackend';
import { isSystemErrorBody } from './systemWire';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: ApiErrorBody,
    readonly retryAfterSeconds = 0,
  ) {
    super(body.message);
    this.name = 'ApiError';
  }
}

let getToken: () => string | null = () => null;
export function currentAccessToken() {
  return getToken();
}
let onUnauthorized: (() => void) | null = null;
let refreshHandler: ((token: string) => Promise<boolean>) | null = null;
export function registerRefreshHandler(handler: (token: string) => Promise<boolean>) {
  refreshHandler = handler;
}
export async function recoverAuthorization(token: string | null) {
  if (!token || token !== getToken()) return false;
  if (refreshHandler && (await refreshHandler(token))) return true;
  reportUnauthorized(token);
  return false;
}
export function registerTokenProvider(provider: () => string | null) {
  getToken = provider;
}
export function registerUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

export function reportUnauthorized(token: string | null) {
  if (token && token === getToken()) onUnauthorized?.();
}

type RequestOptions = {
  method?: 'GET' | 'POST';
  body?: unknown;
  token?: string;
  signal?: AbortSignal;
  skipRefresh?: boolean;
  idempotencyKey?: string;
  timeoutMs?: number;
  responseType?: 'png';
};

/** 인증된 문의 첨부만 메모리로 읽는다. 토큰을 이미지 URL에 넣지 않는다. */
export function apiImageRequest(path: string, signal?: AbortSignal): Promise<Blob> {
  return apiRequest<Blob>(path, { signal, responseType: 'png' });
}

/** 화면은 모드에 관계없이 같은 REST 경계를 사용한다. live 장애를 Mock으로 대체하지 않는다. */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (!path.startsWith('/api/v2/'))
    throw new ApiError(0, localError('INVALID_API_PATH', '지원하지 않는 API 경로예요.'));
  const controller = new AbortController();
  const abort = () => {
    controller.abort();
  };
  options.signal?.addEventListener('abort', abort, { once: true });
  if (options.signal?.aborted) controller.abort();
  const timer = setTimeout(abort, options.timeoutMs ?? 10_000);
  try {
    return await performRequest<T>(path, { ...options, signal: controller.signal });
  } catch (error) {
    if (controller.signal.aborted && !options.signal?.aborted)
      throw new ApiError(
        0,
        localError(
          'REQUEST_TIMEOUT',
          '응답을 확인하지 못했어요. 같은 요청으로 다시 확인해 주세요.',
        ),
      );
    throw error;
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener('abort', abort);
  }
}

async function performRequest<T>(path: string, options: RequestOptions): Promise<T> {
  const token = options.token ?? getToken();
  let mode;
  try {
    mode = connectionMode();
  } catch {
    throw new ApiError(
      0,
      localError('CONNECTION_NOT_CONFIGURED', 'VITE_DATA_MODE 설정을 확인해 주세요.'),
    );
  }
  if (mode === 'live') {
    let baseUrl;
    try {
      baseUrl = liveConnection().apiBaseUrl;
    } catch (error) {
      throw new ApiError(
        0,
        localError(
          'CONNECTION_NOT_CONFIGURED',
          error instanceof Error ? error.message : '서버 주소 설정을 확인해 주세요.',
        ),
      );
    }
    let response: Response;
    try {
      response = await fetch(`${baseUrl}${path}`, {
        method: options.method ?? 'GET',
        headers: {
          Accept: 'application/json',
          ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
          ...(options.idempotencyKey ? { 'Idempotency-Key': options.idempotencyKey } : {}),
          ...(token && !/^\/api\/v2\/auth\/(?:login|refresh)$/u.test(path)
            ? { Authorization: `Bearer ${token}` }
            : {}),
        },
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: options.signal,
        cache: options.responseType === 'png' ? 'no-store' : 'default',
        credentials: /^\/api\/v2\/auth\/(?:login|refresh|logout)$/u.test(path) ? 'include' : 'omit',
      });
    } catch (error) {
      if (options.signal?.aborted) throw error;
      throw new ApiError(0, {
        ...localError(
          'NETWORK_ERROR',
          '서버에 연결하지 못했어요. 서버 주소와 네트워크·CORS 설정을 확인해 주세요.',
        ),
        retryable: true,
      });
    }
    if (response.status === 204 && response.ok && options.responseType !== 'png')
      return undefined as T;
    if (response.ok && options.responseType === 'png') {
      if (
        response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() !== 'image/png' ||
        !response.body
      )
        throw new ApiError(0, localError('INVALID_RESPONSE', '이미지 응답을 확인하지 못했어요.'));
      const reader = response.body.getReader();
      const chunks: Uint8Array[] = [];
      let total = 0;
      try {
        for (;;) {
          const part = await reader.read();
          if (part.done) break;
          total += part.value.byteLength;
          if (total > 8 * 1024 * 1024) {
            await reader.cancel();
            throw new ApiError(
              0,
              localError('INVALID_RESPONSE', '이미지 크기를 확인하지 못했어요.'),
            );
          }
          chunks.push(part.value);
        }
      } finally {
        reader.releaseLock();
      }
      if (total === 0)
        throw new ApiError(0, localError('INVALID_RESPONSE', '이미지 응답이 비어 있어요.'));
      const bytes = new Uint8Array(total);
      let offset = 0;
      for (const chunk of chunks) {
        bytes.set(chunk, offset);
        offset += chunk.byteLength;
      }
      return new Blob([bytes.buffer], { type: 'image/png' }) as T;
    }
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      // 비계약 응답의 HTTP 상태만으로 업무 거절을 확정하거나 원 요청 키를 버리지 않는다.
      throw new ApiError(
        0,
        localError(
          'INVALID_RESPONSE',
          '서버 응답 형식이 계약과 달라요. JSON 응답을 확인해 주세요.',
        ),
      );
    }
    if (!response.ok) {
      if (!isSystemErrorBody(body))
        throw new ApiError(
          0,
          localError(
            'INVALID_RESPONSE',
            '서버 오류 응답 형식이 계약과 달라요. 원 요청의 저장 결과를 확인해 주세요.',
          ),
        );
      if (isApiError(body) && body.code === 'AUTH_CONTEXT_CHANGED') reportUnauthorized(token);
      else if (response.status === 401 && !/^\/api\/v2\/auth\/(?:login|refresh)$/u.test(path)) {
        if (
          body.code === 'ACCESS_TOKEN_EXPIRED' &&
          !options.skipRefresh &&
          (await recoverAuthorization(token)) &&
          !options.idempotencyKey
        )
          return apiRequest<T>(path, {
            ...options,
            token: getToken() ?? undefined,
            skipRefresh: true,
          });
        if (!options.idempotencyKey || body.code !== 'ACCESS_TOKEN_EXPIRED')
          reportUnauthorized(token);
      }
      throw new ApiError(
        response.status,
        isApiError(body)
          ? body
          : localError('INVALID_RESPONSE', '서버 오류 응답 형식이 계약과 달라요.'),
        response.status === 429 ? Math.max(0, Number(response.headers.get('Retry-After')) || 0) : 0,
      );
    }
    if (
      options.idempotencyKey &&
      (!body ||
        typeof body !== 'object' ||
        !('state' in body) ||
        (response.status === 200
          ? body.state !== 'SUCCEEDED'
          : response.status !== 202 || !['PENDING', 'UNKNOWN'].includes(String(body.state))))
    )
      throw new ApiError(
        0,
        localError('INVALID_RESPONSE', '명령 응답의 HTTP 상태와 본문이 계약과 달라요.'),
      );
    // HTTP JSON 역직렬화 경계. 신규 DTO는 기능 API에서 검증한다.
    return body as T;
  }
  if (options.responseType === 'png')
    throw new ApiError(
      0,
      localError('IMAGE_UNAVAILABLE', '현재 연결에서는 이미지를 불러올 수 없어요.'),
    );
  if (options.signal?.aborted) throw new DOMException('요청 취소', 'AbortError');
  await new Promise<void>((resolve) => setTimeout(resolve, 40));
  if (options.signal?.aborted) throw new DOMException('요청 취소', 'AbortError');
  try {
    // 공용 JSON/메모리 서버 대역의 응답 경계에서만 타입을 변환한다.
    const result = systemMockBackend.request(
      path,
      options.method ?? 'GET',
      options.body,
      token,
      options.idempotencyKey,
    );
    if (options.idempotencyKey && systemMockBackend.takeLostResponse())
      throw new ApiError(0, localError('NETWORK_ERROR', '저장 응답을 확인하지 못했어요.'));
    return result as T;
  } catch (error) {
    if (error instanceof MockHttpError) {
      if (error.status === 401 && !/^\/api\/v2\/auth\/(?:login|refresh)$/u.test(path)) {
        if (
          error.body.code === 'ACCESS_TOKEN_EXPIRED' &&
          !options.skipRefresh &&
          (await recoverAuthorization(token)) &&
          !options.idempotencyKey
        )
          return apiRequest<T>(path, {
            ...options,
            token: getToken() ?? undefined,
            skipRefresh: true,
          });
        if (!options.idempotencyKey || error.body.code !== 'ACCESS_TOKEN_EXPIRED')
          reportUnauthorized(token);
      }
      if (error.body.code === 'AUTH_CONTEXT_CHANGED') reportUnauthorized(token);
      throw new ApiError(error.status, error.body);
    }
    throw error;
  }
}
