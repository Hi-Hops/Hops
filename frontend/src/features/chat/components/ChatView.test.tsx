import { screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { login, useSession } from '@/features/auth';
import { RealtimeBoundary } from '@/features/realtime';
import { mockBackend } from '@/shared/api/mockBackend';
import { mockWorkspace } from '@/shared/api/mockWorkspace';
import { fixture } from '@/shared/api/mockData';
import { renderWithProviders } from '@/test/renderWithProviders';
import { ChatView } from './ChatView';
import type { StoredAck } from '@/shared/api/types';

beforeEach(async () => {
  mockBackend.reset();
  useSession.getState().logout();
  await login({ loginId: 'demo.requester', password: 'mock-only-password' });
});
function start(content: string) {
  const user = useSession.getState().user;
  if (!user) throw new Error('로그인 필요');
  const ack = mockWorkspace.command(user, 'inquiry.create', {
    requestId: crypto.randomUUID(),
    content,
  });
  if (!ack.ok) throw new Error('문의 생성 실패');
  return ack;
}

describe('F003 대화 조회·분석', () => {
  it('F003-AC05 대화를 사용자·AI로 구분하고 페이지 순서를 유지한다', async () => {
    const seed = fixture('inquiry-result', 'InquiryResult');
    renderWithProviders(
      <RealtimeBoundary>
        <ChatView inquiryId={seed.id} />
      </RealtimeBoundary>,
    );
    expect(await screen.findByLabelText('사용자 메시지')).toHaveTextContent(
      'VPN 접속 오류가 나요.',
    );
    expect(screen.getByLabelText('Hops 답변')).toHaveTextContent('오류 코드를 알려 주세요.');
    const user = useSession.getState().user;
    if (!user) throw new Error('로그인 필요');
    const first = mockWorkspace.messages(user, seed.id, 0, 1);
    const second = mockWorkspace.messages(user, seed.id, first.nextAfterSequenceNo ?? 0, 1);
    expect(first.items[0]?.sequenceNo).toBeLessThan(second.items[0]?.sequenceNo ?? 0);
  });
  it('F003-AC07 분석 중 임시 표시를 저장된 답변으로 바꾼다', async () => {
    const ack = start('VPN 연결 오류');
    renderWithProviders(
      <RealtimeBoundary>
        <ChatView inquiryId={ack.inquiryId} />
      </RealtimeBoundary>,
    );
    expect(await screen.findByText('작성 중인 답변이에요. 저장된 결과가 아니에요.')).toBeVisible();
    expect(await screen.findByText('오류 코드를 알려 주세요.')).toBeVisible();
    await waitFor(() => {
      expect(
        screen.queryByText('작성 중인 답변이에요. 저장된 결과가 아니에요.'),
      ).not.toBeInTheDocument();
    });
  });
  it('F003-AC08 추가 질문은 서버의 문제별 질문 횟수를 유지한다', () => {
    const ack = start('VPN 연결 오류');
    mockWorkspace.flushPending();
    const user = useSession.getState().user;
    if (!user) throw new Error('로그인 필요');
    const result = mockWorkspace.readInquiry(user, ack.inquiryId);
    expect(result.problems[0]?.questionCount).toBe(1);
    const cs = start('CS 검토 예시');
    mockWorkspace.flushPending();
    expect(mockWorkspace.readInquiry(user, cs.inquiryId).tickets[0]?.routingStatus).toBe(
      'CS_REVIEW',
    );
  });
  it('F003-AC09 범위 밖 질문은 티켓을 만들지 않는다', async () => {
    const ack = start('오늘 날씨 알려 주세요');
    mockWorkspace.flushPending();
    renderWithProviders(
      <RealtimeBoundary>
        <ChatView inquiryId={ack.inquiryId} />
      </RealtimeBoundary>,
    );
    expect(await screen.findByText('이 질문은 사내 IT 지원 범위 밖이에요.')).toBeVisible();
    const user = useSession.getState().user;
    if (!user) throw new Error('로그인 필요');
    expect(mockWorkspace.readInquiry(user, ack.inquiryId).tickets).toHaveLength(0);
  });
  it.each([
    ['시간 초과 예시', '분석 시간이 초과됐어요'],
    ['모델 오류 예시', '모델·통신 오류로 분석을 끝내지 못했어요'],
  ])('F003-AC10 F003-AC11 %s를 별도로 안내한다', async (content, expected) => {
    const ack = start(content);
    mockWorkspace.flushPending();
    renderWithProviders(
      <RealtimeBoundary>
        <ChatView inquiryId={ack.inquiryId} />
      </RealtimeBoundary>,
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(expected);
  });
  it('F003-AC12 이관 이후 메시지는 실행 없이 정보 보완으로 저장한다', () => {
    const ack = start('VPN 오류');
    const user = useSession.getState().user;
    if (!user) throw new Error('로그인 필요');
    mockWorkspace.flushPending();
    mockWorkspace.command(user, 'message.send', {
      requestId: crypto.randomUUID(),
      inquiryId: ack.inquiryId,
      expectedInquiryVersion: 1,
      content: '809 오류예요',
    });
    mockWorkspace.flushPending();
    const supplement = mockWorkspace.command(user, 'message.send', {
      requestId: crypto.randomUUID(),
      inquiryId: ack.inquiryId,
      expectedInquiryVersion: 2,
      content: '추가 발생 시각이에요',
    });
    expect(supplement).toMatchObject({ ok: true, runId: null, effect: 'SUPPLEMENT_SAVED' });
  });
  it('F003-AC14 F005-AC08 저장 변경 신호 뒤 문의 결과를 다시 조회한다', async () => {
    const ack: StoredAck = start('CS 검토 예시');
    renderWithProviders(
      <RealtimeBoundary>
        <ChatView inquiryId={ack.inquiryId} />
      </RealtimeBoundary>,
    );
    await screen.findByRole('heading', { level: 1 });
    mockWorkspace.flushPending();
    await waitFor(() => {
      expect(screen.getByText('티켓 1 · 저장 실패 0')).toBeVisible();
    });
  });
});
