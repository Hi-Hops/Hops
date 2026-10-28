import { isRecord } from '@/shared/api/wire';
import type { SubscriptionTarget } from '@/shared/api/types';
import type { EventMap } from './types';

export type PublicEvent = {
  [K in Exclude<keyof EventMap, 'resync.required' | 'stream.reset'>]: {
    name: K;
    value: EventMap[K];
  };
}[Exclude<keyof EventMap, 'resync.required' | 'stream.reset'>];
const positive = (value: unknown) => Number.isSafeInteger(value) && Number(value) > 0;

/** 공통 SSE 타입과 현재 연결 대상을 수신 경계에서 검사한다. */
export function decodeEvent(
  name: string,
  input: unknown,
  target: SubscriptionTarget,
): PublicEvent | null {
  if (!isRecord(input) || typeof input.eventId !== 'string' || !input.eventId) return null;
  let value = input;
  if (name === 'auth.invalidated') {
    if (
      !['ACCESS_TOKEN_EXPIRED', 'AUTH_CONTEXT_CHANGED', 'REFRESH_TOKEN_REVOKED'].includes(
        String(value.code),
      ) ||
      typeof value.message !== 'string'
    )
      return null;
  } else if (name === 'state.changed') {
    if (!isRecord(value.target)) return null;
    const eventTarget = {
      type: value.target.type,
      id: value.target.id,
    };
    if (
      eventTarget.type !== target.type ||
      eventTarget.id !== target.id ||
      !(value.inquiryVersion === null || positive(value.inquiryVersion))
    )
      return null;
    value = { ...value, target: eventTarget };
  } else {
    if (
      target.type !== 'INQUIRY' ||
      value.inquiryId !== target.id ||
      !positive(value.inquiryVersion)
    )
      return null;
    if (name === 'message.stored') {
      if (
        typeof value.messageId !== 'string' ||
        !(value.requestId === null || typeof value.requestId === 'string')
      )
        return null;
    } else {
      if (typeof value.runId !== 'string' || !isRecord(value.payload)) return null;
      if (name === 'agent.progress') {
        if (
          !positive(value.payload.stepNo) ||
          typeof value.payload.stepType !== 'string' ||
          typeof value.payload.displaySummary !== 'string' ||
          typeof value.payload.status !== 'string'
        )
          return null;
      } else if (name === 'answer.delta') {
        if (
          typeof value.payload.messageId !== 'string' ||
          !positive(value.payload.seq) ||
          typeof value.payload.text !== 'string'
        )
          return null;
      } else return null;
    }
  }
  // 모든 지원 이벤트의 필드와 대상 권한 문맥을 위에서 검사한 JSON 경계다.
  return { name, value } as PublicEvent;
}
