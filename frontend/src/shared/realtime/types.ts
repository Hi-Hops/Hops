import type {
  AgentProgressEvent,
  AnswerDeltaEvent,
  AuthInvalidatedEvent,
  MessageStoredEvent,
  ResyncRequiredEvent,
  StateChangedEvent,
  SubscriptionTarget,
} from '@/shared/api/types';
export type ConnectionState = 'idle' | 'connecting' | 'connected' | 'disconnected';
export type EventMap = {
  'agent.progress': AgentProgressEvent;
  'answer.delta': AnswerDeltaEvent;
  'message.stored': MessageStoredEvent;
  'state.changed': StateChangedEvent;
  'auth.invalidated': AuthInvalidatedEvent;
  'resync.required': ResyncRequiredEvent;
  // 서버 이벤트가 아닌 로컬 복구 신호다. 제어 알림 유실에 따른 복구에도 사용한다.
  'stream.reset': { target: SubscriptionTarget };
};
export type TargetState = ConnectionState | 'denied';
export type RealtimeTransport = {
  connect: (token: string) => void;
  disconnect: () => void;
  getState: () => ConnectionState;
  onConnection: (listener: (state: ConnectionState) => void) => () => void;
  on: <K extends keyof EventMap>(name: K, handler: (event: EventMap[K]) => void) => () => void;
  onSync: (listener: (target: SubscriptionTarget) => Promise<void>) => () => void;
  subscribe: (target: SubscriptionTarget, listener?: (state: TargetState) => void) => () => void;
};
