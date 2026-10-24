import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { useSubscription } from '@/features/realtime';
import { getTicket, ticketKeys } from '../api/ticketApi';
import { canReadTickets } from '../model/permissions';
export function useTicketDetail(id: string) {
  const user = useSession((state) => state.user);
  const canRead = canReadTickets(user);
  const query = useQuery({
    queryKey: ticketKeys.detail(id, user?.id ?? ''),
    queryFn: ({ signal }) => getTicket(id, signal),
    enabled: canRead,
    refetchInterval: 5000,
  });
  useSubscription(canRead ? { type: 'TICKET', id } : null);
  return query;
}
