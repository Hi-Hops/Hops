import { apiRequest, ApiError } from './client';
import { isRecord, isUuid } from './wire';
import type {
  ApiErrorBody,
  InquiryCreateCommand,
  MessageSendCommand,
  MessageReviseCommand,
  MutationResult,
} from './types';

export type StoredCommandName = 'inquiry.create' | 'message.send' | 'message.revise';
export type StoredCommand = InquiryCreateCommand | MessageSendCommand | MessageReviseCommand;
// 화면 내부 상태이며 HTTP wire 응답에 ok 외피를 추가하지 않는다.
export type MutationOutcome =
  | (MutationResult & { ok: true })
  | { ok: false; error: ApiErrorBody; retryAfterSeconds?: number }
  | { ok: 'unknown' };

function validResult(value: unknown, command: StoredCommand): value is MutationResult {
  return (
    isRecord(value) &&
    value.requestId === command.requestId &&
    isUuid(value.requestId) &&
    isUuid(value.inquiryId) &&
    isUuid(value.messageId) &&
    Number.isSafeInteger(value.inquiryVersion) &&
    Number(value.inquiryVersion) >= 1 &&
    Number(value.inquiryVersion) <= 2147483647 &&
    Number.isSafeInteger(value.revision) &&
    Number(value.revision) >= 1 &&
    Number(value.revision) <= 2147483647 &&
    (value.effect === 'ANALYSIS_STARTED'
      ? isUuid(value.runId)
      : value.effect === 'SUPPLEMENT_SAVED' && value.runId === null) &&
    (!('inquiryId' in command) || value.inquiryId === command.inquiryId) &&
    (!('messageId' in command) || value.messageId === command.messageId)
  );
}

/** DATA_TYPES의 MutationResult를 검사하며 결과 미확인 요청을 자동 재전송하지 않는다. */
export async function postMutation(path: string, command: StoredCommand): Promise<MutationOutcome> {
  try {
    const value = await apiRequest<unknown>(path, {
      method: 'POST',
      body: command,
      idempotencyKey: command.requestId,
    });
    return validResult(value, command) ? { ...value, ok: true } : { ok: 'unknown' };
  } catch (error) {
    if (
      error instanceof ApiError &&
      error.status !== 0 &&
      error.status !== 401 &&
      error.body.code !== 'INVALID_RESPONSE'
    )
      return {
        ok: false,
        retryAfterSeconds: error.retryAfterSeconds,
        error:
          error.retryAfterSeconds > 0
            ? {
                ...error.body,
                message: `${error.body.message} ${String(error.retryAfterSeconds)}초 후 다시 시도해 주세요.`,
              }
            : error.body,
      };
    return { ok: 'unknown' };
  }
}
