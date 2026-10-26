export interface ApiErrorBody {
  code: string;
  message: string;
  requestId: string;
  retryable: boolean;
}

export interface SubscriptionTarget {
  type: 'INQUIRY' | 'DEPARTMENT_TICKETS' | 'TICKET';
  id: string;
}

export interface InquiryCreateCommand {
  requestId: string;
  content: string;
}

export interface MessageSendCommand extends InquiryCreateCommand {
  inquiryId: string;
  expectedInquiryVersion: number;
}

export interface MessageReviseCommand extends MessageSendCommand {
  messageId: string;
}

export type StoreCommand = InquiryCreateCommand | MessageSendCommand | MessageReviseCommand;

export interface SubscriptionCommand {
  requestId: string;
  target: SubscriptionTarget;
}

export interface CommandMap {
  'inquiry.create': InquiryCreateCommand;
  'message.send': MessageSendCommand;
  'message.revise': MessageReviseCommand;
  'subscription.request': SubscriptionCommand;
  'subscription.release': SubscriptionCommand;
}

export type StoreName = 'inquiry.create' | 'message.send' | 'message.revise';

export interface ErrorAck {
  ok: false;
  requestId: string;
  error: ApiErrorBody;
}

export type StoredAck = {
  ok: true;
  requestId: string;
  inquiryId: string;
  inquiryVersion: number;
  messageId: string;
  revision: number;
} & ({ effect: 'ANALYSIS_STARTED'; runId: string } | { effect: 'SUPPLEMENT_SAVED'; runId: null });

export type CommandAck = StoredAck | ErrorAck;

export type SubscriptionAck =
  { ok: true; requestId: string; target: SubscriptionTarget } | ErrorAck;

export type Ack = CommandAck | SubscriptionAck;

export interface Department {
  id: string;
  code: string;
  name: string;
  isRoutable: boolean;
}

export interface CurrentUser {
  id: string;
  employeeNo: string;
  name: string;
  department: Department;
  roles: ('BIZ_ADMIN' | 'AGENT_OPS_ADMIN')[];
}

export interface Authentication {
  user: CurrentUser;
  expiresAt: string;
}

export interface SpringClient {
  authenticate(token: string): Promise<Authentication>;
  authorize(token: string, target: SubscriptionTarget): Promise<void>;
}

export interface SpringConfig {
  springUrl: string | URL;
  nodeCredential: string;
}

export interface RealtimeConfig extends SpringConfig {
  springCredential: string;
  origins: string[];
  path?: string;
  ackTimeout?: number;
}

export type InvalidationCode =
  'ACCESS_TOKEN_EXPIRED' | 'AUTH_CONTEXT_CHANGED' | 'REFRESH_TOKEN_REVOKED';

export type AuthFailureCode = InvalidationCode | 'UNAUTHENTICATED';

export interface SocketData {
  token: string;
  userId: string;
  expiresAt: number;
  revoked: boolean;
}

interface InquiryEvent {
  eventId: string;
  inquiryId: string;
  inquiryVersion: number;
}

export interface BusinessEventMap {
  'agent.progress': InquiryEvent & {
    runId: string;
    payload: {
      stepNo: number;
      stepType: string;
      displaySummary: string;
      status: string;
    };
  };
  'answer.delta': InquiryEvent & {
    runId: string;
    payload: { messageId: string; seq: number; text: string };
  };
  'message.stored': InquiryEvent & {
    messageId: string;
    requestId: string | null;
  };
  'state.changed': { eventId: string } & (
    | {
        target: SubscriptionTarget & { type: 'INQUIRY' };
        inquiryVersion: number;
      }
    | {
        target: SubscriptionTarget & { type: 'TICKET' | 'DEPARTMENT_TICKETS' };
        inquiryVersion: null;
      }
  );
}

export type BusinessName = keyof BusinessEventMap;

export type BusinessEvent = BusinessEventMap[BusinessName];

export type ClientEvents = {
  [K in keyof CommandMap]: (input: unknown, ack: (result: Ack) => void) => void;
};

export type PublicEvents = {
  [K in BusinessName]: (event: BusinessEventMap[K]) => void;
} & {
  'auth.invalidated': (event: { eventId: string; code: InvalidationCode; message: string }) => void;
  'relay.status': (event: {
    eventId: string;
    status: 'AVAILABLE' | 'UNAVAILABLE';
    occurredAt: string;
  }) => void;
};

export type SpringIncoming = {
  [K in BusinessName]: (input: unknown) => void;
} & { 'auth.invalidated': (input: unknown) => void };

export type SpringOutgoing = {
  [K in StoreName]: (
    input: { accessToken: string; command: StoreCommand },
    ack: (reply: unknown) => void,
  ) => void;
};

export type InterServerEvents = Record<never, never>;
