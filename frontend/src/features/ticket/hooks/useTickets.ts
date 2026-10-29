import { useQuery } from '@tanstack/react-query';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { useSession } from '@/features/auth';
import { useSubscription } from '@/features/realtime';
import { paths } from '@/shared/config/paths';
import { getTickets, ticketKeys } from '../api/ticketApi';
import { canReadTickets } from '../model/permissions';
export function useTickets() {
  const user = useSession((state) => state.user);
  const [search, setSearch] = useSearchParams();
  const { ticketId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const base = location.pathname.startsWith('/admin/') ? paths.adminTickets : paths.tickets;
  const canRead = canReadTickets(user);
  const view =
    search.get('view') === 'list' || (!search.has('view') && base === paths.adminTickets)
      ? 'list'
      : 'board';
  // view는 프론트 표시 상태다. 계약에 정의된 조회 조건만 서버로 보낸다.
  const query = new URLSearchParams();
  for (const key of ['page', 'size', 'processingStatus']) {
    const value = search.get(key);
    if (value !== null) query.set(key, value);
  }
  const queryString = query.toString();
  const tickets = useQuery({
    queryKey: ticketKeys.list(queryString, user?.id ?? ''),
    queryFn: ({ signal }) => getTickets(queryString, signal),
    enabled: canRead,
    refetchInterval: 5000,
  });
  useSubscription(canRead && user ? { type: 'DEPARTMENT_TICKETS', id: user.department.id } : null);
  function change(key: string, value: string) {
    const next = new URLSearchParams(search);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== 'page' && key !== 'view') next.delete('page');
    setSearch(next);
  }
  return {
    user,
    base,
    query: tickets,
    view,
    canRead,
    ticketId,
    search,
    change,
    close: () => {
      void navigate({ pathname: base, search: search.toString() }, { replace: true });
    },
  };
}
