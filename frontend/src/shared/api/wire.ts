import type { ApiErrorBody } from './types';

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function isApiError(value: unknown): value is ApiErrorBody {
  return (
    isRecord(value) &&
    typeof value.code === 'string' &&
    typeof value.message === 'string' &&
    typeof value.requestId === 'string' &&
    typeof value.retryable === 'boolean'
  );
}

export function localError(code: string, message: string): ApiErrorBody {
  return { code, message, requestId: crypto.randomUUID(), retryable: false };
}
