import type {
  ApiErrorBody,
  SubscriptionTarget,
  CommandMap,
  CommandAck,
  StoreCommand,
  BusinessName,
} from './types.js';
import { randomUUID } from 'node:crypto';

export const uuid = (v: unknown): v is string =>
  typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v);

export const integer = (v: unknown): v is number =>
  typeof v === 'number' && Number.isInteger(v) && v >= 1 && v <= 2147483647;

export const object = (v: unknown): v is Record<string, unknown> =>
  v !== null && typeof v === 'object' && !Array.isArray(v);

export const keys = (v: unknown, names: readonly string[]): v is Record<string, unknown> =>
  object(v) && Object.keys(v).length === names.length && names.every((k) => Object.hasOwn(v, k));

export const target = (v: unknown): v is SubscriptionTarget =>
  keys(v, ['type', 'id']) &&
  typeof v.type === 'string' &&
  ['INQUIRY', 'DEPARTMENT_TICKETS', 'TICKET'].includes(v.type) &&
  uuid(v.id);

export const room = (v: SubscriptionTarget) => `${v.type}:${v.id}`;

export const sameTarget = (a: unknown, b: unknown) => target(a) && target(b) && room(a) === room(b);

export const date = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?Z$/.test(v) &&
  Number.isFinite(Date.parse(v));

export const apiError = (v: unknown): v is ApiErrorBody =>
  keys(v, ['code', 'message', 'requestId', 'retryable']) &&
  typeof v.code === 'string' &&
  typeof v.message === 'string' &&
  uuid(v.requestId) &&
  typeof v.retryable === 'boolean';

export function failure(code: string, requestId: string = randomUUID()): ApiErrorBody {
  return {
    code,
    message:
      (
        {
          INVALID_INPUT: '요청 형식을 확인해 주세요.',
          UNAUTHENTICATED: '인증이 필요합니다.',
          ACCESS_TOKEN_EXPIRED: '인증이 만료되었습니다.',
          ACCESS_DENIED: '접근 권한이 없습니다.',
          RATE_LIMITED: '요청이 많습니다. 1초 후 다시 시도해 주세요.',
          SERVICE_UNAVAILABLE:
            '중계 연결 또는 저장 결과를 확인할 수 없습니다. 저장 상태를 조회해 주세요.',
        } as Record<string, string>
      )[code] ?? '인증을 다시 확인해 주세요.',
    requestId,
    retryable: code === 'SERVICE_UNAVAILABLE',
  };
}

export const content = (v: unknown) =>
  typeof v === 'string' &&
  [...v].length >= 1 &&
  [...v].length <= 4000 &&
  !/[\uD800-\uDFFF]/u.test(v) &&
  /[^\u0009-\u000D\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]/u.test(
    v,
  );

export function command<K extends keyof CommandMap>(name: K, v: unknown): v is CommandMap[K] {
  if (!object(v) || !uuid(v.requestId)) return false;

  if (name.startsWith('subscription.')) return keys(v, ['requestId', 'target']) && target(v.target);

  const fields = ['requestId', 'content'];

  if (name !== 'inquiry.create') fields.push('inquiryId', 'expectedInquiryVersion');

  if (name === 'message.revise') fields.push('messageId');

  return (
    keys(v, fields) &&
    content(v.content) &&
    (name === 'inquiry.create' || (uuid(v.inquiryId) && integer(v.expectedInquiryVersion))) &&
    (name !== 'message.revise' || uuid(v.messageId))
  );
}

export function storedAck(v: unknown, input: StoreCommand): v is CommandAck {
  if (object(v) && v.ok === false)
    return (
      keys(v, ['ok', 'requestId', 'error']) &&
      v.requestId === input.requestId &&
      apiError(v.error) &&
      v.error.requestId === input.requestId
    );

  return (
    keys(v, [
      'ok',
      'requestId',
      'inquiryId',
      'inquiryVersion',
      'messageId',
      'revision',
      'runId',
      'effect',
    ]) &&
    v.ok === true &&
    v.requestId === input.requestId &&
    uuid(v.inquiryId) &&
    (!('inquiryId' in input) || input.inquiryId === v.inquiryId) &&
    (!('messageId' in input) || input.messageId === v.messageId) &&
    integer(v.inquiryVersion) &&
    uuid(v.messageId) &&
    integer(v.revision) &&
    ((v.effect === 'ANALYSIS_STARTED' && uuid(v.runId)) ||
      (v.effect === 'SUPPLEMENT_SAVED' && v.runId === null))
  );
}

export function eventTarget(name: BusinessName, v: unknown): SubscriptionTarget | null {
  if (!object(v) || !uuid(v.eventId)) return null;

  if (name === 'state.changed')
    return keys(v, ['eventId', 'target', 'inquiryVersion']) &&
      target(v.target) &&
      (v.target.type === 'INQUIRY' ? integer(v.inquiryVersion) : v.inquiryVersion === null)
      ? v.target
      : null;

  if (!uuid(v.inquiryId) || !integer(v.inquiryVersion)) return null;

  if (name === 'message.stored')
    return keys(v, ['eventId', 'inquiryId', 'inquiryVersion', 'messageId', 'requestId']) &&
      uuid(v.messageId) &&
      (v.requestId === null || uuid(v.requestId))
      ? { type: 'INQUIRY', id: v.inquiryId }
      : null;

  if (!keys(v, ['eventId', 'inquiryId', 'inquiryVersion', 'runId', 'payload']) || !uuid(v.runId))
    return null;

  const p = v.payload;
  const valid =
    name === 'answer.delta'
      ? keys(p, ['messageId', 'seq', 'text']) &&
        uuid(p.messageId) &&
        integer(p.seq) &&
        typeof p.text === 'string'
      : name === 'agent.progress' &&
        keys(p, ['stepNo', 'stepType', 'displaySummary', 'status']) &&
        integer(p.stepNo) &&
        ['stepType', 'displaySummary', 'status'].every((k) => typeof p[k] === 'string');

  return valid ? { type: 'INQUIRY', id: v.inquiryId } : null;
}
