import { useQuery } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { useSubscription } from '@/features/realtime';
import { getTicket, ticketKeys } from '../api/ticketApi';
export function useTicketDetail(id: string) {
  const actor = useSession((state) => state.user?.id ?? '');
  const query = useQuery({
    queryKey: ticketKeys.detail(id, actor),
    queryFn: ({ signal }) => getTicket(id, signal),
    refetchInterval: 5000,
  });
  useSubscription({ type: 'TICKET', id });
  return query;
}
