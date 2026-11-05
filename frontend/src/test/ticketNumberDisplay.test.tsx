import { screen, within } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { SystemTicketBoard, SystemTicketDetail } from '@/features/ticket';
import { systemGet } from '@/shared/api/systemCommands';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { readTickets } from '@/shared/api/systemWire';
import type * as SystemWire from '@/shared/api/systemWire';
import { realtime } from '@/shared/realtime';
import { TEST_LOGINS } from './mockCredentials';
import { renderWithProviders } from './renderWithProviders';

const serverNumber = /^T-0{8,}\d+$/u;
const shownNumber = /^T-\d{4,}$/u;

// Spring이 보내는 T-와 16자리 숫자 형식으로 바꿔 화면이 받는 값을 실제 응답과 맞춘다.
vi.mock('@/shared/api/systemWire', async (original) => {
  const actual = await original<typeof SystemWire>();
  const stretch = <T extends { ticketNumber: string }>(ticket: T): T => ({
    ...ticket,
    ticketNumber: `T-${ticket.ticketNumber.replace(/\D/gu, '').padStart(16, '0')}`,
  });
  return {
    ...actual,
    readTickets: (value: unknown) => {
      const page = actual.readTickets(value);
      return { ...page, items: page.items.map(stretch) };
    },
    readTicket: (value: unknown) => stretch(actual.readTicket(value)),
    readManagementTicket: (value: unknown) => stretch(actual.readManagementTicket(value)),
  };
});

beforeEach(async () => {
  useSession.getState().logout();
  systemMockBackend.reset();
  await login(TEST_LOGINS.network);
});

function expectShort(cell: HTMLElement) {
  expect(cell.textContent).toMatch(shownNumber);
  expect(cell.getAttribute('title')).toMatch(serverNumber);
}

it('F009-CONTRACT-01 칸반 카드는 티켓 번호를 줄여 표시하고 전체 번호를 title에 남긴다', async () => {
  renderWithProviders(<SystemTicketBoard />, '/tickets?view=board&state=WAITING&page=0');
  const link = await screen.findByRole('link', { name: /전자결재에서 문서를 열 수 없어요/u });
  expectShort(within(link).getByText(shownNumber));
  expect(screen.queryByText(serverNumber)).not.toBeInTheDocument();
});

it('F009-CONTRACT-01 목록 표는 티켓 번호를 줄여 표시하고 전체 번호를 title에 남긴다', async () => {
  renderWithProviders(<SystemTicketBoard />, '/tickets?view=list&state=WAITING&page=0');
  const link = await screen.findByRole('link', { name: /전자결재에서 문서를 열 수 없어요/u });
  const row = link.closest('tr');
  if (!row) throw new Error('티켓 행 없음');
  expectShort(within(row).getByText(shownNumber));
  expect(screen.queryByText(serverNumber)).not.toBeInTheDocument();
});

it('F009-CONTRACT-01 티켓 상세 제목은 줄인 번호를 표시하고 전체 번호를 title에 남긴다', async () => {
  const page = await systemGet('/tickets?scope=ASSIGNED_SYSTEMS', readTickets);
  const ticket = page.items[0];
  if (!ticket) throw new Error('가상 티켓 없음');
  expect(ticket.ticketNumber).toMatch(serverNumber);
  realtime.connect(useSession.getState().token ?? '');
  renderWithProviders(
    <SystemTicketDetail id={ticket.id} onClose={vi.fn()} />,
    `/tickets/${ticket.id}`,
  );
  const dialog = await screen.findByRole('dialog', { name: new RegExp(`· ${ticket.title}$`, 'u') });
  const shown = `T-${ticket.ticketNumber.slice(2).replace(/^0+/u, '').padStart(4, '0')}`;
  const heading = within(dialog).getByRole('heading', { level: 2 });
  expect(heading.textContent).toContain(shown);
  expect(heading).toHaveAttribute('title', ticket.ticketNumber);
  expect(screen.queryByText(ticket.ticketNumber)).not.toBeInTheDocument();
  realtime.disconnect();
});
