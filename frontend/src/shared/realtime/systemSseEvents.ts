import { isRecord, isUuid } from '@/shared/api/wire';
import type { SubscriptionTarget } from '@/shared/api/types';
import type { PublicEvent } from './sseEvents';
const positive = (v: unknown) => Number.isSafeInteger(v) && Number(v) > 0;
/** 신규 flat wire 이벤트를 기존 전송 계층의 내부 알림 형태로 투영한다. */
export function decodeSystemEvent(
  name: string,
  value: unknown,
  target: SubscriptionTarget,
): PublicEvent | null {
  if (!isRecord(value) || !isUuid(value.eventId)) return null;
  const eventId = value.eventId;
  if (name === 'auth.invalidated') {
    if (
      !['ACCESS_TOKEN_EXPIRED', 'AUTH_CONTEXT_CHANGED', 'REFRESH_TOKEN_REVOKED'].includes(
        String(value.code),
      ) ||
      typeof value.message !== 'string'
    )
      return null;
    return {
      name,
      value: {
        eventId,
        code: value.code as
          'ACCESS_TOKEN_EXPIRED' | 'AUTH_CONTEXT_CHANGED' | 'REFRESH_TOKEN_REVOKED',
        message: value.message,
      },
    };
  }
  if (name === 'state.changed') {
    if (
      value.targetType !== target.type ||
      value.targetId !== target.id ||
      !isUuid(value.resourceId) ||
      typeof value.resourceType !== 'string' ||
      !positive(value.revision)
    )
      return null;
    return { name, value: { eventId, target, inquiryVersion: null } };
  }
  if (target.type !== 'INQUIRY' || value.inquiryId !== target.id) return null;
  if (name === 'message.stored') {
    if (!isUuid(value.messageId) || !(value.requestId === null || isUuid(value.requestId)))
      return null;
    return {
      name,
      value: {
        eventId,
        inquiryId: target.id,
        inquiryVersion: null,
        messageId: value.messageId,
        requestId: value.requestId,
      },
    };
  }
  if (!positive(value.inquiryVersion) || !isUuid(value.runId)) return null;
  if (
    name === 'answer.delta' &&
    isUuid(value.messageId) &&
    positive(value.seq) &&
    typeof value.text === 'string'
  )
    return {
      name,
      value: {
        eventId,
        inquiryId: target.id,
        inquiryVersion: Number(value.inquiryVersion),
        runId: value.runId,
        payload: { messageId: value.messageId, seq: Number(value.seq), text: value.text },
      },
    };
  if (
    name === 'agent.progress' &&
    positive(value.stepNo) &&
    ['SUPERVISOR', 'INQUIRY', 'KNOWLEDGE', 'TICKET'].includes(String(value.role)) &&
    typeof value.displaySummary === 'string'
  )
    return {
      name,
      value: {
        eventId,
        inquiryId: target.id,
        inquiryVersion: Number(value.inquiryVersion),
        runId: value.runId,
        payload: {
          stepNo: Number(value.stepNo),
          stepType: String(value.role),
          displaySummary: value.displaySummary,
          status: 'RUNNING',
        },
      },
    };
  return null;
}
