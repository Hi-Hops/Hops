import type { ApiErrorBody } from './types';
import { MockHttpError, mockBackend } from './mockBackend';

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
export function registerTokenProvider(provider: () => string | null) {
  getToken = provider;
}
export function registerUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

type RequestOptions = {
  method?: 'GET' | 'POST';
  body?: unknown;
  token?: string;
  signal?: AbortSignal;
};

/** Mock 전용 HTTP 어댑터. 실제 서비스 연결은 운영 계약 확정 후 교체한다. */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (options.signal?.aborted) throw new DOMException('요청 취소', 'AbortError');
  await new Promise<void>((resolve) => setTimeout(resolve, 40));
  if (options.signal?.aborted) throw new DOMException('요청 취소', 'AbortError');
  try {
    // 공용 JSON/메모리 서버 대역의 응답 경계에서만 타입을 변환한다.
    return mockBackend.request(
      path,
      options.method ?? 'GET',
      options.body,
      options.token ?? getToken(),
    ) as T;
  } catch (error) {
    if (error instanceof MockHttpError) {
      if (error.status === 401 && path !== '/api/auth/login') onUnauthorized?.();
      throw new ApiError(error.status, error.body);
    }
    throw error;
  }
}
