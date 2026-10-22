import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { mockBackend } from '@/shared/api/mockBackend';
import { mockWorkspace } from '@/shared/api/mockWorkspace';
import { fixture } from '@/shared/api/mockData';
import { realtime } from '@/shared/realtime/mockTransport';
import { renderWithProviders } from '@/test/renderWithProviders';
import type { ChatMessage } from '@/shared/api/types';
import '../api/chatApi';
import { MessageRevision } from './MessageRevision';
beforeEach(async () => {
  mockBackend.reset();
  useSession.getState().logout();
  await login({ loginId: 'demo.requester', password: 'mock-only-password' });
  const token = useSession.getState().token;
  if (!token) throw new Error('토큰 필요');
  realtime.connect(token);
});
function actor() {
  const user = useSession.getState().user;
  if (!user) throw new Error('인증 필요');
  return user;
}
const message = () => fixture('message-user', 'ChatMessage');
async function edit() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: '내용 정정' }));
  await user.clear(screen.getByLabelText('메시지 정정'));
  await user.type(screen.getByLabelText('메시지 정정'), '사내 포털 오류로 정정해요');
  return user;
}
describe('F004 메시지 정정', () => {
  it('F004-AC01 저장 ACK 전 정정 중을 표시하고 입력을 검증한다', async () => {
    const updated = vi.fn();
    renderWithProviders(<MessageRevision message={message()} version={2} onStored={updated} />);
    const user = await edit();
    await user.click(screen.getByRole('button', { name: '정정 저장' }));
    expect(screen.getByRole('button', { name: '정정 중' })).toBeDisabled();
    await waitFor(() => {
      expect(updated).toHaveBeenCalledOnce();
    });
  });
  it('F004-AC02 원문 이력과 정정됨을 표시한다', async () => {
    const msg = message();
    const ack = mockWorkspace.command(actor(), 'message.revise', {
      requestId: crypto.randomUUID(),
      inquiryId: msg.inquiryId,
      messageId: msg.id,
      expectedInquiryVersion: 2,
      content: '정정 내용',
    });
    expect(ack.ok).toBe(true);
    const changed = mockWorkspace.messages(actor(), msg.inquiryId, 0, 20).items[0];
    if (!changed) throw new Error('메시지 없음');
    renderWithProviders(<MessageRevision message={changed} version={3} onStored={vi.fn()} />);
    expect(screen.getByText('정정됨 · 2차')).toBeVisible();
    await userEvent.click(screen.getByText('이전 내용 1개'));
    expect(screen.getByText(msg.content)).toBeVisible();
  });
  it('F004-AC03 이전 실행 완료가 최신 정정을 덮지 않고 티켓을 유지한다', () => {
    const msg = message();
    const tickets = mockWorkspace.readInquiry(actor(), msg.inquiryId).tickets;
    mockWorkspace.command(actor(), 'message.revise', {
      requestId: crypto.randomUUID(),
      inquiryId: msg.inquiryId,
      messageId: msg.id,
      expectedInquiryVersion: 2,
      content: '첫 정정',
    });
    mockWorkspace.command(actor(), 'message.revise', {
      requestId: crypto.randomUUID(),
      inquiryId: msg.inquiryId,
      messageId: msg.id,
      expectedInquiryVersion: 3,
      content: '최신 정정',
    });
    mockWorkspace.flushPending();
    expect(mockWorkspace.readInquiry(actor(), msg.inquiryId).version).toBe(4);
    expect(mockWorkspace.readInquiry(actor(), msg.inquiryId).tickets).toEqual(tickets);
    expect(
      mockWorkspace
        .messages(actor(), msg.inquiryId, 0, 20)
        .items.filter((item) => item.sender === 'AI'),
    ).toHaveLength(2);
  });
  it('F004-AC04 이관 이후 정정은 보완으로만 저장한다', () => {
    const ack = mockWorkspace.command(actor(), 'inquiry.create', {
      requestId: crypto.randomUUID(),
      content: 'CS 검토 예시',
    });
    if (!ack.ok) throw new Error('저장 실패');
    mockWorkspace.flushPending();
    const msg = mockWorkspace.messages(actor(), ack.inquiryId, 0, 20).items[0];
    if (!msg) throw new Error('메시지 없음');
    const reply = mockWorkspace.command(actor(), 'message.revise', {
      requestId: crypto.randomUUID(),
      inquiryId: ack.inquiryId,
      messageId: msg.id,
      expectedInquiryVersion: 1,
      content: '보완',
    });
    expect(reply).toMatchObject({ ok: true, runId: null, effect: 'SUPPLEMENT_SAVED' });
    expect(mockWorkspace.readInquiry(actor(), ack.inquiryId).tickets).toHaveLength(1);
  });
  it('F004-AC05 충돌해도 편집 내용을 유지하고 최신 조회를 요청한다', async () => {
    const refresh = vi.fn();
    renderWithProviders(<MessageRevision message={message()} version={1} onStored={refresh} />);
    const user = await edit();
    await user.click(screen.getByRole('button', { name: '정정 저장' }));
    expect(await screen.findByRole('status')).toHaveTextContent('최신 대화');
    expect(screen.getByLabelText('메시지 정정')).toHaveValue('사내 포털 오류로 정정해요');
    expect(refresh).toHaveBeenCalledOnce();
  });
  it('F004-AC06 ACK 유실 뒤 같은 키로 확인해 이력이 중복되지 않는다', async () => {
    renderWithProviders(<MessageRevision message={message()} version={2} onStored={vi.fn()} />);
    const user = await edit();
    realtime.loseNextAck();
    await user.click(screen.getByRole('button', { name: '정정 저장' }));
    expect(await screen.findByRole('status')).toHaveTextContent('결과 확인 중');
    await user.click(screen.getByRole('button', { name: '정정 저장' }));
    expect(await screen.findByRole('status')).toHaveTextContent('정정 내용을 저장');
    expect(
      mockWorkspace.messages(actor(), message().inquiryId, 0, 20).items[0]?.revisions,
    ).toHaveLength(1);
  });
  it('F004-AC07 AI 메시지에는 정정 조작이 없다', () => {
    const ai: ChatMessage = fixture('message-ai', 'ChatMessage');
    renderWithProviders(<MessageRevision message={ai} version={2} onStored={vi.fn()} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
