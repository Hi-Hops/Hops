import { apiRequest } from '@/shared/api/client';
import type { TicketDetail, TicketPage } from '@/shared/api/types';
export const ticketKeys = {
  all: ['tickets'],
  list: (query: string, actor: string) => ['tickets', 'list', actor, query],
  detail: (id: string, actor: string) => ['tickets', 'detail', actor, id],
};
export function getTickets(query: string, signal?: AbortSignal) {
  return apiRequest<TicketPage>(`/api/tickets?${query}`, { signal });
}
export function getTicket(id: string, signal?: AbortSignal) {
  return apiRequest<TicketDetail>(`/api/tickets/${encodeURIComponent(id)}`, { signal });
}
