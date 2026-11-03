/** REST DTO는 hops-system-1 한 계약만 사용한다. */
export type * from './hopsSystem1';
import type { Uuid } from './hopsSystem1';

export type LoginRequest = { loginId: string; password: string };
export type Role = 'BIZ_ADMIN';
export type SubscriptionTarget = {
  type: 'INQUIRY' | 'TICKET' | 'SYSTEM_TICKETS' | 'CASE';
  id: Uuid;
};

// 아래 이벤트는 flat SSE wire를 수신 계층에서 변환한 프론트 내부 알림이다.
export type AuthInvalidatedEvent = {
  eventId: Uuid;
  code: 'ACCESS_TOKEN_EXPIRED' | 'AUTH_CONTEXT_CHANGED' | 'REFRESH_TOKEN_REVOKED';
  message: string;
};
export type ResyncRequiredEvent = { eventId: Uuid };
export type AgentProgressEvent = {
  eventId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  runId: Uuid;
  payload: { stepNo: number; stepType: string; displaySummary: string; status: string };
};
export type AnswerDeltaEvent = {
  eventId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: number;
  runId: Uuid;
  payload: { messageId: Uuid; seq: number; text: string };
};
export type MessageStoredEvent = {
  eventId: Uuid;
  inquiryId: Uuid;
  inquiryVersion: null;
  messageId: Uuid;
  requestId: Uuid | null;
};
export type StateChangedEvent = { eventId: Uuid; target: SubscriptionTarget; inquiryVersion: null };
