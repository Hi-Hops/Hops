import type {
  AgentProgressEvent,
  AnswerDeltaEvent,
  CommandAck,
  InquiryCreateCommand,
  MessageReviseCommand,
  MessageSendCommand,
  MessageStoredEvent,
  StateChangedEvent,
  SubscriptionAck,
  SubscriptionCommand,
} from '@/shared/api/types';

export type ConnectionState = 'connecting' | 'connected' | 'disconnected' | 'relayError';
export type EventMap = {
  'agent.progress': AgentProgressEvent;
  'answer.delta': AnswerDeltaEvent;
  'message.stored': MessageStoredEvent;
  'state.changed': StateChangedEvent;
};
export type CommandMap = {
  'inquiry.create': InquiryCreateCommand;
  'message.send': MessageSendCommand;
  'message.revise': MessageReviseCommand;
  'subscription.request': SubscriptionCommand;
  'subscription.release': SubscriptionCommand;
};
export type CommandName = keyof CommandMap;
export type Reply<K extends CommandName> = K extends 'subscription.request' | 'subscription.release'
  ? SubscriptionAck
  : CommandAck;
export type CommandResult<K extends CommandName> = Reply<K> | { ok: 'unknown' };
export type StoredCommandName = 'inquiry.create' | 'message.send' | 'message.revise';
export type StoredCommand = InquiryCreateCommand | MessageSendCommand | MessageReviseCommand;
export type CommandProcessor = (
  name: StoredCommandName,
  command: StoredCommand,
  token: string,
) => CommandAck;

export type RealtimeTransport = {
  connect: (token: string) => void;
  disconnect: () => void;
  getState: () => ConnectionState;
  onConnection: (listener: (state: ConnectionState) => void) => () => void;
  on: <K extends keyof EventMap>(name: K, handler: (event: EventMap[K]) => void) => () => void;
  send: <K extends CommandName>(name: K, command: CommandMap[K]) => Promise<CommandResult<K>>;
};
