import { SystemTicketBoard, SystemTicketDetail } from '@/features/ticket';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
export function TicketsPage() {
  const { ticketId } = useParams();
  const [search] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const admin = location.pathname.startsWith('/admin/');
  if (ticketId && search.get('tab') === 'work' && !admin)
    return <SystemTicketDetail key={ticketId} id={ticketId} />;
  return (
    <>
      <SystemTicketBoard />
      {ticketId && (
        <SystemTicketDetail
          key={ticketId}
          id={ticketId}
          onClose={() => {
            const next = new URLSearchParams(search);
            next.delete('tab');
            void navigate(
              { pathname: admin ? '/admin/tickets' : '/tickets', search: next.toString() },
              { replace: true },
            );
          }}
        />
      )}
    </>
  );
}
