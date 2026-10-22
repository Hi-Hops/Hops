import type { TicketCard } from '@/shared/api/types';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { processing, routingDisplay } from '../model/status';
export function TicketStatuses({ ticket }: { ticket: TicketCard }) {
  return (
    <>
      <StatusBadge {...processing[ticket.processingStatus]} />
      <StatusBadge {...routingDisplay(ticket.routingStatus)} />
    </>
  );
}
