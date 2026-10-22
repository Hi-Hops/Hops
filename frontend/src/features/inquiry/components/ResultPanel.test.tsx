import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { fixture } from '@/shared/api/mockData';
import { ResultPanel } from './ResultPanel';
describe('F005 결과 카드', () => {
  it('F005-AC01 문제별 상태와 추가 질문 횟수를 구분한다', () => {
    render(<ResultPanel result={fixture('inquiry-result', 'InquiryResult')} />);
    expect(screen.getByText('티켓 생성·배정됨')).toBeVisible();
    expect(screen.getByText('생성 실패')).toBeVisible();
    expect(screen.getByText('추가 질문 중')).toBeVisible();
    expect(screen.getByText(/질문 1 \/ 3회/u)).toBeVisible();
  });
  it('F005-AC02 저장된 티켓에 부서와 생성 시각을 표시한다', () => {
    const result = fixture('inquiry-result', 'InquiryResult');
    render(<ResultPanel result={result} />);
    for (const ticket of result.tickets) {
      const card = screen.getByLabelText(`저장된 티켓 ${ticket.ticketNumber}`);
      expect(within(card).getByText(ticket.department.name)).toBeVisible();
      expect(within(card).getByText(/^생성 /u)).toBeVisible();
    }
  });
  it('F005-AC03 처리 상태와 CS 배정 상태는 별개다', () => {
    const result = fixture('inquiry-result', 'InquiryResult');
    const cs = fixture('ticket-cs', 'TicketCard');
    result.tickets = [{ ...cs, problemId: result.problems[0]?.id ?? cs.problemId }];
    render(<ResultPanel result={result} />);
    expect(screen.getByText('CS 검토')).toBeVisible();
    expect(screen.getByText('처리 대기')).toBeVisible();
  });
  it('F005-AC04 일부 생성 실패에도 성공한 두 티켓을 유지한다', () => {
    render(<ResultPanel result={fixture('inquiry-result', 'InquiryResult')} />);
    expect(screen.getAllByLabelText(/^저장된 티켓 /u)).toHaveLength(2);
    expect(screen.getByText('티켓을 만들지 못했어요')).toBeVisible();
  });
  it('F005-AC05 실패 항목에 기술 재시도 버튼을 두지 않는다', () => {
    render(<ResultPanel result={fixture('inquiry-result', 'InquiryResult')} />);
    expect(screen.queryByRole('button', { name: /재시도/u })).not.toBeInTheDocument();
  });
  it('F005-AC06 티켓 생성 승인 조작을 두지 않는다', () => {
    render(<ResultPanel result={fixture('inquiry-result', 'InquiryResult')} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
  it('F005-AC07 같은 티켓 ID는 한 번만 표시한다', () => {
    const result = fixture('inquiry-result', 'InquiryResult');
    result.tickets = [...result.tickets, ...result.tickets];
    render(<ResultPanel result={result} />);
    expect(screen.getAllByLabelText(/^저장된 티켓 /u)).toHaveLength(2);
  });
  it('F005-AC09 현재 실행 중에도 기존 카드와 분석 중 표시를 유지한다', () => {
    const result = fixture('inquiry-result', 'InquiryResult');
    if (result.currentRun) result.currentRun.status = 'RUNNING';
    render(<ResultPanel result={result} />);
    expect(screen.getByText('분석 중')).toBeVisible();
    expect(screen.getAllByLabelText(/^저장된 티켓 /u)).toHaveLength(2);
  });
  it('F005-AC01 미등록 상태는 중립 문구로 남긴다', () => {
    const result = fixture('inquiry-result', 'InquiryResult');
    if (result.problems[0]) result.problems[0].status = 'FUTURE_STATUS';
    render(<ResultPanel result={result} />);
    expect(screen.getByText(/확인되지 않은 문제 상태/u)).toBeVisible();
  });
});
