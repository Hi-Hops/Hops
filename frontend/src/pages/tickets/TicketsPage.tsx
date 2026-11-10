import { CaseDetailView } from '@/features/case';
import { SystemTicketBoard, SystemTicketDetail } from '@/features/ticket';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
export function TicketsPage() {
  const { ticketId } = useParams();
  const [search] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const admin = location.pathname.startsWith('/admin/');
  const navigationState: unknown = location.state;
  const origin =
    navigationState && typeof navigationState === 'object' && 'ticketModalOrigin' in navigationState
      ? navigationState.ticketModalOrigin
      : null;
  return (
    <>
      <SystemTicketBoard />
      {ticketId && (
        <SystemTicketDetail
          key={ticketId}
          id={ticketId}
          renderCaseReview={(caseId, reviewTarget) => (
            <CaseDetailView key={caseId} id={caseId} reviewTarget={reviewTarget} />
          )}
          returnFocusId={origin === 'case' ? 'case-ticket-' + ticketId : 'ticket-' + ticketId}
          onClose={() => {
            if (origin === 'list' || origin === 'case') {
              void navigate(-1);
              return;
            }
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
