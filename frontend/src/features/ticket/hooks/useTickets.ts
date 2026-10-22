import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams, useSearchParams } from 'react-router';
import { useSession } from '@/features/auth';
import { useSubscription } from '@/features/realtime';
import { paths } from '@/shared/config/paths';
import { getTickets, ticketKeys } from '../api/ticketApi';
export function useTickets() {
  const user = useSession((state) => state.user);
  const [search, setSearch] = useSearchParams();
  const { ticketId } = useParams();
  const navigate = useNavigate();
  const canRead = user ? user.department.isRoutable || user.roles.includes('BIZ_ADMIN') : false;
  const query = useQuery({
    queryKey: ticketKeys.list(search.toString(), user?.id ?? ''),
    queryFn: ({ signal }) => getTickets(search.toString(), signal),
    enabled: canRead,
    refetchInterval: 5000,
  });
  useSubscription(canRead && user ? { type: 'DEPARTMENT_TICKETS', id: user.department.id } : null);
  function change(key: string, value: string) {
    const next = new URLSearchParams(search);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== 'page') next.delete('page');
    setSearch(next);
  }
  return {
    user,
    query,
    canRead,
    ticketId,
    search,
    change,
    close: () => {
      void navigate({ pathname: paths.tickets, search: search.toString() }, { replace: true });
    },
  };
}
