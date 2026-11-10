import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import type { AssistanceCase } from '@/shared/api/types/hopsSystem1';
import { renderWithProviders } from '@/test/renderWithProviders';
import { GroupedCases } from './GroupedCases';
import { TicketAssistance } from './TicketAssistance';
import { getTicketAssistance } from '../api/systemTicketApi';
import { login, useSession } from '@/features/auth';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { systemGet } from '@/shared/api/systemCommands';
import { readTicket, readTickets } from '@/shared/api/systemWire';
import { TEST_LOGINS } from '@/test/mockCredentials';

vi.mock('../api/systemTicketApi', () => ({ getTicketAssistance: vi.fn() }));
afterEach(() => {
  useSession.getState().logout();
  systemMockBackend.reset();
});

function member(title: string, number: string): AssistanceCase {
  return {
    evidence: {
      id: crypto.randomUUID(),
      documentId: crypto.randomUUID(),
      documentVersion: 2,
      kind: 'CASE',
      title,
      excerpt: '승인된 조치',
      applicability: '조회 상태: 승인',
      systemId: crypto.randomUUID(),
      appliesToSystemVersion: null,
      accessScope: 'SYSTEM_STAFF',
      origin: 'SYNTHETIC',
    },
    sourceCase: {
      caseNumber: 'C-' + number,
      ticketId: crypto.randomUUID(),
      ticketNumber: 'T-00000000000000' + number,
      approvedAt: '2026-11-10T15:22:08Z',
    },
    assessment: {
      relevance: 'MEDIUM',
      family: '조회',
      reasons: [],
      conditions: [],
      quotePairs: [],
      sections: [],
      nextChecks: [],
      matched: 0,
      unknown: 0,
      mismatched: 0,
    },
  };
}
describe('F009 묶인 승인 사례', () => {
  it('F009-CONTRACT-09 멤버를 선택하면 모달 안에서 해당 원문을 보여주고 대표로 복귀한다', async () => {
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      configurable: true,
      value: vi.fn(),
    });
    systemMockBackend.reset();
    await login(TEST_LOGINS.network);
    const page = await systemGet('/tickets?scope=ASSIGNED_SYSTEMS', readTickets);
    const ticket = page.items[0];
    if (!ticket) throw new Error('fixture');
    const detail = await systemGet('/tickets/' + ticket.id, readTicket);
    const first = member('대표 사례', '93');
    const latest = member('새 A 사례', '95');
    first.assessment.sections = [
      { label: '실제 조치', quote: '대표의 조치 원문', start: 0, end: 9 },
    ];
    latest.assessment.sections = [
      { label: '실제 조치', quote: '새 A의 조치 원문', start: 0, end: 10 },
    ];
    vi.mocked(getTicketAssistance).mockResolvedValue({
      ticketId: ticket.id,
      ticketRevision: 1,
      standardRequestId: detail.standardRequest.id,
      query: 'B 문의',
      sources: [],
      materials: [{ ...first, groupedCases: [first, latest] }],
    });
    renderWithProviders(<TicketAssistance ticket={detail} />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: '1위 대표 사례' }));
    await user.click(screen.getByText('묶인 사례 2건 펼쳐보기'));
    await user.click(screen.getByRole('button', { name: '묶인 사례 상세 보기: 새 A 사례' }));
    expect(screen.getByRole('heading', { name: '새 A 사례' })).toBeVisible();
    expect(screen.getByText('새 A의 조치 원문')).toBeVisible();
    expect(screen.getByText('1위 대표 그룹 · 묶인 사례')).toBeVisible();
    expect(screen.queryByText('대표의 조치 원문')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '1위 대표 사례' }));
    expect(screen.getByRole('heading', { name: '대표 사례' })).toBeVisible();
    expect(screen.queryByText('새 A의 조치 원문')).not.toBeInTheDocument();
  });
  it('F009-CONTRACT-09 목록을 펼쳐 출처·승인 버전을 확인하고 개별 사례를 선택한다', async () => {
    const first = member('이전 사례', '93');
    const latest = member('새 A 사례', '95');
    const onSelect = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <GroupedCases
        cases={[first, latest]}
        representativeId={first.evidence.id}
        selectedId={first.evidence.id}
        onSelect={onSelect}
      />,
    );
    expect(screen.getByText('새 A 사례')).not.toBeVisible();
    await user.click(screen.getByText('묶인 사례 2건 펼쳐보기'));
    const list = screen.getByRole('list', { name: '묶인 해결 사례 목록' });
    expect(within(list).getByText(/원 티켓 T-0095/)).toBeVisible();
    expect(within(list).getByText('C-95 · v2')).toBeVisible();
    await user.click(within(list).getByRole('button', { name: '묶인 사례 상세 보기: 새 A 사례' }));
    expect(onSelect).toHaveBeenCalledWith(latest.evidence.id);
    const link = within(list).getByRole('link', { name: /승인 사례 열기: 새 A 사례/ });
    expect(link).toHaveAttribute('href', '/cases/' + latest.evidence.documentId);
    expect(link).toHaveAttribute('target', '_blank');
  });
  it('F009-CONTRACT-09 구 서버나 단일 사례에는 펼치기를 표시하지 않는다', () => {
    renderWithProviders(
      <GroupedCases cases={[]} representativeId="none" selectedId={null} onSelect={vi.fn()} />,
    );
    expect(screen.queryByText(/묶인 사례/)).not.toBeInTheDocument();
  });
});
