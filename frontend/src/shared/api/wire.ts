import type { ApiErrorBody } from './types';

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function isUuid(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u.test(value)
  );
}

export function isApiError(value: unknown): value is ApiErrorBody {
  return (
    isRecord(value) &&
    typeof value.code === 'string' &&
    typeof value.message === 'string' &&
    isUuid(value.requestId) &&
    typeof value.retryable === 'boolean' &&
    Array.isArray(value.fieldErrors) &&
    value.fieldErrors.every(
      (entry) =>
        isRecord(entry) && typeof entry.field === 'string' && typeof entry.code === 'string',
    )
  );
}

export function localError(code: string, message: string): ApiErrorBody {
  return { code, message, requestId: crypto.randomUUID(), retryable: false, fieldErrors: [] };
}
