import type { ApiErrorBody } from './types';
import { MockHttpError, mockBackend } from './mockBackend';
import { connectionMode, liveConnection } from '@/shared/config/connection';
import { isApiError, localError } from './wire';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: ApiErrorBody,
  ) {
    super(body.message);
    this.name = 'ApiError';
  }
}

let getToken: () => string | null = () => null;
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
};

/** 화면은 모드에 관계없이 같은 REST 경계를 사용한다. live 장애를 Mock으로 대체하지 않는다. */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
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
          ...(token && !['/api/auth/login', '/api/auth/refresh'].includes(path)
            ? { Authorization: `Bearer ${token}` }
            : {}),
        },
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: options.signal,
        credentials: ['/api/auth/login', '/api/auth/refresh', '/api/auth/logout'].includes(path)
          ? 'include'
          : 'omit',
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
    if (response.status === 204 && response.ok) return undefined as T;
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      throw new ApiError(
        response.status,
        localError(
          'INVALID_RESPONSE',
          '서버 응답 형식이 계약과 달라요. JSON 응답을 확인해 주세요.',
        ),
      );
    }
    if (!response.ok) {
      if (isApiError(body) && body.code === 'AUTH_CONTEXT_CHANGED') reportUnauthorized(token);
      else if (
        response.status === 401 &&
        !['/api/auth/login', '/api/auth/refresh'].includes(path)
      ) {
        if (!options.skipRefresh && (await recoverAuthorization(token)))
          return apiRequest<T>(path, {
            ...options,
            token: getToken() ?? undefined,
            skipRefresh: true,
          });
        reportUnauthorized(token);
      }
      throw new ApiError(
        response.status,
        isApiError(body)
          ? body
          : localError('INVALID_RESPONSE', '서버 오류 응답 형식이 계약과 달라요.'),
      );
    }
    // HTTP JSON 역직렬화 경계. 서버는 DATA_TYPES의 응답 객체를 wrapper 없이 반환한다.
    return body as T;
  }
  if (options.signal?.aborted) throw new DOMException('요청 취소', 'AbortError');
  await new Promise<void>((resolve) => setTimeout(resolve, 40));
  if (options.signal?.aborted) throw new DOMException('요청 취소', 'AbortError');
  try {
    // 공용 JSON/메모리 서버 대역의 응답 경계에서만 타입을 변환한다.
    return mockBackend.request(path, options.method ?? 'GET', options.body, token) as T;
  } catch (error) {
    if (error instanceof MockHttpError) {
      if (error.status === 401 && !['/api/auth/login', '/api/auth/refresh'].includes(path)) {
        if (!options.skipRefresh && (await recoverAuthorization(token)))
          return apiRequest<T>(path, {
            ...options,
            token: getToken() ?? undefined,
            skipRefresh: true,
          });
        reportUnauthorized(token);
      }
      if (error.body.code === 'AUTH_CONTEXT_CHANGED') reportUnauthorized(token);
      throw new ApiError(error.status, error.body);
    }
    throw error;
  }
}
