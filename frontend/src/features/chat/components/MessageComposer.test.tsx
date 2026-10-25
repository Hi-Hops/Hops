import { TEST_LOGINS } from '@/test/mockCredentials';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { useConnection } from '@/features/realtime';
import { mockBackend } from '@/shared/api/mockBackend';
import { mockWorkspace } from '@/shared/api/mockWorkspace';
import { fixture } from '@/shared/api/mockData';
import { realtime } from '@/shared/realtime/mockTransport';
import { contentError, codePointLength } from '@/shared/lib/content';
import { renderWithProviders } from '@/test/renderWithProviders';
import { MessageComposer } from './MessageComposer';
import { RecentInquiries } from '@/features/inquiry';
import '../api/chatApi';
import { useDrafts } from '../model/draftStore';

beforeEach(async () => {
  mockBackend.reset();
  useSession.getState().logout();
  realtime.disconnect();
  await login(TEST_LOGINS.requester);
  const token = useSession.getState().token;
  if (!token) throw new Error('테스트 로그인 실패');
  realtime.connect(token);
  useConnection.getState().setState('connected');
});

async function compose(content: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('문의 내용'), content);
  await user.click(screen.getByRole('button', { name: '질문하기 ↑' }));
}

describe('F003 메시지 전송', () => {
  it('F003-AC01 F006-AC10 이벤트·구독 없이 저장 ACK만으로 세션 목록을 갱신한다', async () => {
    const stored = vi.fn();
    renderWithProviders(
      <>
        <RecentInquiries />
        <MessageComposer isHome onStored={stored} />
      </>,
    );
    expect(await screen.findAllByRole('link')).toHaveLength(1);
    await compose('VPN ACK만 받은 새 문의');
    await waitFor(() => {
      expect(screen.getAllByRole('link')).toHaveLength(2);
    });
    expect(screen.getByRole('link', { name: /VPN ACK만 받은 새 문의/u })).toBeVisible();
    expect(stored).toHaveBeenCalledOnce();
  });
  it.each(['error', 'unknown'] as const)(
    'F003-AC03/04 %s ACK는 목록 갱신이나 화면 이동을 성공 처리하지 않는다',
    async (kind) => {
      const stored = vi.fn();
      const rendered = renderWithProviders(<MessageComposer isHome onStored={stored} />);
      const invalidate = vi.spyOn(rendered.client, 'invalidateQueries');
      if (kind === 'error') mockWorkspace.failNextCommand();
      else realtime.loseNextAck();
      await compose('ACK 확인 전 보존');
      await screen.findByRole('button', { name: '같은 요청으로 다시 확인' });
      expect(invalidate).not.toHaveBeenCalled();
      expect(stored).not.toHaveBeenCalled();
      expect(screen.getByLabelText('문의 내용')).toHaveValue('ACK 확인 전 보존');
    },
  );
  it('F003-AC15 화면 이동 뒤 늦은 저장 ACK가 이전 화면으로 이동시키지 않는다', async () => {
    const stored = vi.fn();
    const rendered = renderWithProviders(<MessageComposer isHome onStored={stored} />);
    const invalidate = vi.spyOn(rendered.client, 'invalidateQueries');
    await compose('이동 전에 전송');
    rendered.unmount();
    await waitFor(() => {
      expect(useDrafts.getState().drafts.new?.pending).toBeNull();
    });
    expect(stored).not.toHaveBeenCalled();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['inquiries'] });
  });
  it('F003-AC01 저장 ACK 전에는 전송 중이며 확인 뒤 문의 ID를 전달한다', async () => {
    const stored = vi.fn();
    renderWithProviders(<MessageComposer isHome onStored={stored} />);
    await compose('VPN 연결이 안 돼요');
    expect(screen.getByRole('button', { name: '전송 중' })).toBeDisabled();
    expect(stored).not.toHaveBeenCalled();
    await waitFor(() => {
      expect(stored).toHaveBeenCalledOnce();
    });
    expect(screen.getByLabelText('문의 내용')).toHaveValue('');
  });
  it('F003-AC02 코드 포인트를 세고 공백·초과 입력을 보존한다', async () => {
    expect(codePointLength('가😀')).toBe(2);
    expect(contentError('😀'.repeat(4000))).toBeNull();
    expect(contentError('\u3000\u00a0\t')).not.toBeNull();
    expect(contentError('\ud800')).not.toBeNull();
    renderWithProviders(<MessageComposer isHome onStored={vi.fn()} />);
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('문의 내용'));
    await user.paste('가'.repeat(4001));
    expect(screen.getByRole('alert')).toHaveTextContent('1자 넘었어요');
    expect(screen.getByRole('button', { name: '질문하기 ↑' })).toBeDisabled();
    expect(screen.getByLabelText('문의 내용')).toHaveValue('가'.repeat(4001));
  });
  it('F003-AC03 저장 오류 뒤 원문과 같은 요청을 유지해 재전송한다', async () => {
    const stored = vi.fn();
    renderWithProviders(<MessageComposer isHome onStored={stored} />);
    mockWorkspace.failNextCommand();
    await compose('VPN 오류');
    expect(await screen.findByRole('alert')).toHaveTextContent('저장하지 못했어요');
    expect(screen.getByLabelText('문의 내용')).toHaveValue('VPN 오류');
    await userEvent.click(screen.getByRole('button', { name: '같은 요청으로 다시 확인' }));
    await waitFor(() => {
      expect(stored).toHaveBeenCalledOnce();
    });
  });
  it('F003-AC04 ACK 유실은 결과 미확인이며 재전송해도 문의가 하나만 생긴다', async () => {
    const stored = vi.fn();
    renderWithProviders(<MessageComposer isHome onStored={stored} />);
    realtime.loseNextAck();
    await compose('VPN 오류');
    expect(await screen.findByText(/결과 확인 중이에요/u)).toBeVisible();
    expect(stored).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: '같은 요청으로 다시 확인' }));
    await waitFor(() => {
      expect(stored).toHaveBeenCalledOnce();
    });
    const user = useSession.getState().user;
    if (!user) throw new Error('로그인 필요');
    expect(mockWorkspace.listInquiries(user, 0, 20).totalElements).toBe(2);
  });
  it('F003-AC06 채팅 전송에는 문의 ID와 현재 버전을 전달한다', async () => {
    const seed = fixture('inquiry-result', 'InquiryResult');
    const stored = vi.fn();
    renderWithProviders(
      <MessageComposer inquiryId={seed.id} version={seed.version} onStored={stored} />,
    );
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('메시지 입력'), '추가 오류 정보');
    await user.click(screen.getByRole('button', { name: '보내기 ↑' }));
    await waitFor(() => {
      expect(stored).toHaveBeenCalledWith(
        expect.objectContaining({ inquiryId: seed.id, inquiryVersion: seed.version + 1 }),
      );
    });
  });
  it('F003-AC13 충돌 시 최신 조회를 요청하고 입력을 보존한다', async () => {
    const seed = fixture('inquiry-result', 'InquiryResult');
    const refresh = vi.fn();
    renderWithProviders(
      <MessageComposer inquiryId={seed.id} version={1} onStored={vi.fn()} onRefresh={refresh} />,
    );
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('메시지 입력'), '충돌해도 보존');
    await user.click(screen.getByRole('button', { name: '보내기 ↑' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('최신 대화');
    expect(refresh).toHaveBeenCalledOnce();
    expect(screen.getByLabelText('메시지 입력')).toHaveValue('충돌해도 보존');
  });
  it('F001-AC06 인증 만료 후 미확인 요청을 같은 키로 복원한다', async () => {
    const stored = vi.fn();
    const rendered = renderWithProviders(<MessageComposer isHome onStored={stored} />);
    realtime.loseNextAck();
    await compose('VPN 오류');
    await screen.findByText(/결과 확인 중이에요/u);
    useSession.getState().expire();
    rendered.unmount();
    await login(TEST_LOGINS.requester);
    const token = useSession.getState().token;
    if (!token) throw new Error('로그인 필요');
    realtime.connect(token);
    renderWithProviders(<MessageComposer isHome onStored={stored} />);
    await userEvent.click(screen.getByRole('button', { name: '질문하기 ↑' }));
    await waitFor(() => {
      expect(stored).toHaveBeenCalledOnce();
    });
    const user = useSession.getState().user;
    if (!user) throw new Error('로그인 필요');
    expect(mockWorkspace.listInquiries(user, 0, 20).totalElements).toBe(2);
  });
});
