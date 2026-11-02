import type { TicketCard } from '@/shared/api/types';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { processingDisplay, routingDisplay } from '../model/status';
export function TicketStatuses({ ticket }: { ticket: TicketCard }) {
  return (
    <>
      <StatusBadge {...processingDisplay(ticket.processingStatus)} />
      <StatusBadge {...routingDisplay(ticket.routingStatus)} />
    </>
  );
}
