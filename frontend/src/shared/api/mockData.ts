import data from '@fixtures/fixtures.json';
import type {
  AgentProgressEvent,
  AnswerDeltaEvent,
  ChatMessage,
  CurrentUser,
  Department,
  InquiryResult,
  LoginResponse,
  MessagePage,
  MessageStoredEvent,
  SubscriptionTarget,
  TicketCard,
  TicketDetail,
} from './types';

type MockFixtureTypes = {
  AgentProgressEvent: AgentProgressEvent;
  AnswerDeltaEvent: AnswerDeltaEvent;
  ChatMessage: ChatMessage;
  CurrentUser: CurrentUser;
  Department: Department;
  InquiryResult: InquiryResult;
  LoginResponse: LoginResponse;
  MessagePage: MessagePage;
  MessageStoredEvent: MessageStoredEvent;
  SubscriptionTarget: SubscriptionTarget;
  TicketCard: TicketCard;
  TicketDetail: TicketDetail;
};

/** 공용 Mock JSON의 타입명과 프론트 타입을 연결하는 외부 데이터 경계다. */
export function fixture<K extends keyof MockFixtureTypes>(
  id: string,
  type: K,
): MockFixtureTypes[K] {
  const found = data.cases.find((row) => row.id === id);
  if (!found) throw new Error(`공용 Mock 데이터가 없어요: ${id}`);
  if (found.type !== type) throw new Error(`공용 Mock 타입이 달라요: ${id}`);
  return structuredClone(found.value) as MockFixtureTypes[K];
}
