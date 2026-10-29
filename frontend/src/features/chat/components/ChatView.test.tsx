import { TEST_LOGINS } from '@/test/mockCredentials';
import { act, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { RealtimeBoundary } from '@/features/realtime';
import { mockBackend } from '@/shared/api/mockBackend';
import { mockWorkspace } from '@/shared/api/mockWorkspace';
import { fixture } from '@/shared/api/mockData';
import { renderWithProviders } from '@/test/renderWithProviders';
import { ChatView } from './ChatView';
import type { MutationResult } from '@/shared/api/types';
import * as chatApi from '../api/chatApi';
import { realtime } from '@/shared/realtime/mockTransport';

afterEach(() => {
  vi.restoreAllMocks();
});

beforeEach(async () => {
  mockBackend.reset();
  useSession.getState().logout();
  await login(TEST_LOGINS.requester);
});
function start(content: string) {
  const user = useSession.getState().user;
  if (!user) throw new Error('로그인 필요');
  const ack = mockWorkspace.command(user, 'inquiry.create', {
    requestId: crypto.randomUUID(),
    content,
  });

  return ack;
}

describe('F003 대화 조회·분석', () => {
  it('F003-AC07 QUEUED는 실행 중 답변 대신 분석 대기로 안내한다', async () => {
    const result = fixture('inquiry-result', 'InquiryResult');
    result.currentRun = {
      id: crypto.randomUUID(),
      inquiryVersion: result.version,
      status: 'QUEUED',
      error: null,
    };
    vi.spyOn(chatApi, 'getInquiry').mockResolvedValue(result);
    renderWithProviders(
      <RealtimeBoundary>
        <ChatView inquiryId={result.id} />
      </RealtimeBoundary>,
    );
    expect(await screen.findByText('분석 순서를 기다리고 있어요.')).toBeVisible();
    expect(screen.queryByText('답변 작성 중')).not.toBeInTheDocument();
  });
  it('F003-AC07 F004-AC03 실행 교체 후 새 진행 이벤트에 이전 임시 답변을 섞지 않는다', async () => {
    const result = fixture('inquiry-result', 'InquiryResult');
    const oldRun = crypto.randomUUID();
    const newRun = crypto.randomUUID();
    result.currentRun = {
      id: oldRun,
      inquiryVersion: result.version,
      status: 'RUNNING',
      error: null,
    };
    const request = vi.spyOn(chatApi, 'getInquiry').mockResolvedValue(result);
    const { client } = renderWithProviders(
      <RealtimeBoundary>
        <ChatView inquiryId={result.id} />
      </RealtimeBoundary>,
    );
    await screen.findByRole('heading', { level: 1 });
    await waitFor(() => {
      expect(realtime.getState()).toBe('connected');
    });
    act(() => {
      realtime.publish('answer.delta', {
        eventId: crypto.randomUUID(),
        inquiryId: result.id,
        inquiryVersion: result.version,
        runId: oldRun,
        payload: { messageId: crypto.randomUUID(), seq: 1, text: '이전 실행의 임시 답변' },
      });
    });
    expect(await screen.findByText('이전 실행의 임시 답변')).toBeVisible();
    const next = {
      ...result,
      version: result.version + 1,
      currentRun: {
        id: newRun,
        inquiryVersion: result.version + 1,
        status: 'RUNNING',
        error: null,
      },
    };
    request.mockResolvedValue(next);
    await act(async () => {
      await client.invalidateQueries({ queryKey: chatApi.chatKeys.inquiry(result.id) });
    });
    await waitFor(() => {
      expect(screen.queryByText('이전 실행의 임시 답변')).not.toBeInTheDocument();
    });
    act(() => {
      realtime.publish('agent.progress', {
        eventId: crypto.randomUUID(),
        inquiryId: result.id,
        inquiryVersion: next.version,
        runId: newRun,
        payload: {
          stepNo: 1,
          stepType: 'ANALYSIS',
          displaySummary: '새 입력 분석 중',
          status: 'RUNNING',
        },
      });
    });
    expect(await screen.findByText('새 입력 분석 중')).toBeVisible();
    expect(screen.queryByText('이전 실행의 임시 답변')).not.toBeInTheDocument();
  });
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
  ])('F003-AC10 F003-AC11 %s를 별도로 안내하고 다시 분석을 제공한다', async (content, expected) => {
    const ack = start(content);
    mockWorkspace.flushPending();
    renderWithProviders(
      <RealtimeBoundary>
        <ChatView inquiryId={ack.inquiryId} />
      </RealtimeBoundary>,
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(expected);
    expect(await screen.findByRole('button', { name: '다시 분석' })).toBeEnabled();
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
    expect(supplement).toMatchObject({ runId: null, effect: 'SUPPLEMENT_SAVED' });
  });
  it('F003-AC14 F005-AC08 저장 변경 신호 뒤 문의 결과를 다시 조회한다', async () => {
    const ack: MutationResult = start('CS 검토 예시');
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
