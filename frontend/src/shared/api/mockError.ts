import type { ApiErrorBody } from './types';

export class MockHttpError extends Error {
  constructor(
    readonly status: number,
    readonly body: ApiErrorBody,
  ) {
    super(body.message);
    this.name = 'MockHttpError';
  }
}

export function mockError(status: number, code: string, message: string): never {
  throw new MockHttpError(status, {
    code,
    message,
    requestId: crypto.randomUUID(),
    retryable: status === 503,
  });
}
