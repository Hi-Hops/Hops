import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { SystemMessageComposer, SystemChatView } from '@/features/chat';
import { SystemTicketBoard, SystemTicketDetail } from '@/features/ticket';
import { useConnection } from '@/features/realtime';
import { apiRequest } from '@/shared/api/client';
import { savedResource, sendSystemCommand, systemGet } from '@/shared/api/systemCommands';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { systems, users } from '@/shared/api/systemMockData';
import {
  readInquiry,
  readInquiries,
  readManagementTicket,
  readTicket,
  readMessages,
  readReceipt,
  readTickets,
  readUser,
} from '@/shared/api/systemWire';
import { realtime } from '@/shared/realtime';
import { TEST_LOGINS } from './mockCredentials';
import { renderWithProviders } from './renderWithProviders';
import type { InquiryResult } from '@/shared/api/types/hopsSystem1';
import * as systemCommands from '@/shared/api/systemCommands';
import { ApiError } from '@/shared/api/client';
import { SystemCommandButton } from '@/features/inquiry';
import { confirmSystemCommand } from '@/features/inquiry/api/systemInquiryApi';

beforeEach(() => {
  useSession.getState().logout();
  systemMockBackend.reset();
  realtime.disconnect();
});
afterEach(() => {
  realtime.disconnect();
  systemMockBackend.reset();
  vi.restoreAllMocks();
});
async function create(content: string, reportedSystemId: string | null) {
  const request = {
    path: '/api/v2/inquiries',
    body: { requestId: crypto.randomUUID(), content, reportedSystemId },
  };
  return savedResource(await sendSystemCommand(request), 'INQUIRY').id;
}
async function settled(id: string) {
  let value: InquiryResult | undefined;
  await waitFor(async () => {
    value = await systemGet(`/inquiries/${id}`, readInquiry);
    expect(value.currentRun?.status).toBe('SUCCEEDED');
  });
  if (!value) throw new Error('문의 결과 없음');
  return value;
}
async function reply(inquiry: InquiryResult, content: string) {
  await sendSystemCommand({
    path: `/api/v2/inquiries/${inquiry.id}/messages`,
    body: {
      requestId: crypto.randomUUID(),
      expectedInquiryVersion: inquiry.inquiryVersion,
      content,
      problemIds: [],
    },
  });
  return settled(inquiry.id);
}

describe('hops-system-1 시스템 단위 전환', () => {
  it('F003-AC02 초과 입력을 자르지 않고 보존하며 코드 포인트 경계에 맞춰 전송을 허용한다', async () => {
    await login(TEST_LOGINS.requester);
    const user = userEvent.setup();
    renderWithProviders(<SystemMessageComposer onStored={vi.fn()} />);
    const input = screen.getByRole('textbox', { name: '문의 내용' });
    await user.click(input);
    await user.paste('가'.repeat(4001));
    expect(input).toHaveValue('가'.repeat(4001));
    expect(screen.getByRole('button', { name: '질문하기 ↑' })).toBeDisabled();
    await user.clear(input);
    await user.paste('😀'.repeat(4000));
    expect(input).toHaveValue('😀'.repeat(4000));
    expect(screen.getByRole('button', { name: '질문하기 ↑' })).toBeEnabled();
  });
  it('미확인 변경은 화면을 다시 열어도 원 명령을 유지하고 같은 요청으로 확인한다', async () => {
    await login(TEST_LOGINS.requester);
    const inquiryId = crypto.randomUUID();
    const problemId = crypto.randomUUID();
    const factory = vi.fn((requestId: string) =>
      confirmSystemCommand(inquiryId, problemId, {
        requestId,
        expectedInquiryVersion: 1,
        systemId: systems[0].id,
      }),
    );
    const send = vi
      .spyOn(systemCommands, 'sendSystemCommand')
      .mockImplementation((command, confirm) =>
        Promise.resolve({
          requestId: command.body.requestId,
          operationId: crypto.randomUUID(),
          state: confirm ? 'SUCCEEDED' : 'PENDING',
          resource: confirm ? { type: 'INQUIRY', id: inquiryId } : null,
          error: null,
        }),
      );
    const operationKey = `system:${inquiryId}:${problemId}`;
    const user = userEvent.setup();
    const first = renderWithProviders(
      <SystemCommandButton
        operationKey={operationKey}
        createCommand={factory}
        label="시스템 확인"
      />,
    );
    await user.click(screen.getByRole('button', { name: '시스템 확인' }));
    expect(await screen.findByRole('button', { name: '같은 요청 결과 확인' })).toBeEnabled();
    const original = send.mock.calls[0]?.[0];
    expect(original).toBeDefined();
    first.unmount();
    const changedFactory = vi.fn((requestId: string) =>
      confirmSystemCommand(inquiryId, problemId, {
        requestId,
        expectedInquiryVersion: 2,
        systemId: systems[1].id,
      }),
    );
    renderWithProviders(
      <SystemCommandButton
        operationKey={operationKey}
        createCommand={changedFactory}
        label="시스템 확인"
        disabled
      />,
    );
    await user.click(screen.getByRole('button', { name: '같은 요청 결과 확인' }));
    expect(await screen.findByText('저장했어요. 최신 상태를 확인해 주세요.')).toBeVisible();
    expect(changedFactory).not.toHaveBeenCalled();
    expect(send).toHaveBeenLastCalledWith(original, true);
  });
  it('서버 400의 사용자 입력 오류를 안내하고 작성 원문을 보존한다', async () => {
    await login(TEST_LOGINS.requester);
    vi.spyOn(systemCommands, 'sendSystemCommand').mockRejectedValue(
      new ApiError(400, {
        code: 'INVALID_INPUT',
        message: '입력을 확인해 주세요.',
        requestId: crypto.randomUUID(),
        retryable: false,
        fieldErrors: [
          { field: 'content', code: 'INVALID_CONTENT' },
          { field: 'internalField', code: 'INTERNAL' },
        ],
      }),
    );
    renderWithProviders(<SystemMessageComposer onStored={vi.fn()} />);
    const user = userEvent.setup();
    const input = screen.getByRole('textbox', { name: '문의 내용' });
    await user.type(input, '작성 중인 문의');
    await user.click(screen.getByRole('button', { name: '질문하기 ↑' }));
    expect(
      await screen.findByText('입력을 확인해 주세요. 내용 입력을 확인해 주세요.'),
    ).toBeVisible();
    expect(input).toHaveValue('작성 중인 문의');
    expect(screen.queryByRole('button', { name: '같은 요청 결과 확인' })).not.toBeInTheDocument();
    expect(screen.queryByText(/internalField/u)).not.toBeInTheDocument();
  });
  it('F001-CONTRACT-02 같은 부서라도 비담당자는 거부하고 복수 PRIMARY는 담당 시스템만 조회한다', async () => {
    const staff = await login(TEST_LOGINS.network);
    expect(staff.primarySystemIds).toEqual([systems[0].id, systems[1].id]);
    expect(staff.department).not.toHaveProperty('isRoutable');
    const page = await systemGet('/tickets?scope=ASSIGNED_SYSTEMS', readTickets);
    expect(page.items.map((t) => t.responsibleSystem.id)).toEqual([systems[0].id]);
    await expect(
      systemGet(`/tickets?scope=ASSIGNED_SYSTEMS&systemId=${systems[2].id}`, readTickets),
    ).rejects.toMatchObject({ status: 403 });
    await login(TEST_LOGINS.requester);
    expect(useSession.getState().user?.department.id).toBe(staff.department.id);
    await expect(systemGet('/tickets?scope=ASSIGNED_SYSTEMS', readTickets)).rejects.toMatchObject({
      status: 403,
    });
  });
  it('F007-CONTRACT-01 관리 상세는 원문·표준 요청을 반환하지 않고 담당자 상세 접근은 거부한다', async () => {
    await login(TEST_LOGINS.admin);
    const page = await systemGet('/tickets?scope=ADMIN', readTickets);
    expect(page.items).toHaveLength(2);
    const id = page.items[0]?.id ?? '';
    const detail = await systemGet(`/management/tickets/${id}`, readManagementTicket);
    expect(detail).not.toHaveProperty('standardRequest');
    expect(detail).not.toHaveProperty('workRecords');
    await expect(apiRequest(`/api/v2/tickets/${id}`)).rejects.toMatchObject({ status: 403 });
  });
  it('F007-CONTRACT-01 관리 응답에 여분 필드가 있어도 담당자 내부 영역을 표시하지 않는다', async () => {
    await login(TEST_LOGINS.network);
    const page = await systemGet('/tickets?scope=ASSIGNED_SYSTEMS', readTickets);
    const id = page.items[0]?.id ?? '';
    const full = await systemGet(`/tickets/${id}`, readTicket);
    await login(TEST_LOGINS.admin);
    const original = systemMockBackend.request.bind(systemMockBackend);
    vi.spyOn(systemMockBackend, 'request').mockImplementation((path, method, body, token, key) =>
      path === `/api/v2/management/tickets/${id}` ? full : original(path, method, body, token, key),
    );
    renderWithProviders(
      <SystemTicketDetail id={id} onClose={vi.fn()} renderCaseReview={() => null} />,
      `/admin/tickets/${id}`,
    );
    expect(await screen.findByRole('heading', { name: new RegExp(full.title, 'u') })).toBeVisible();
    expect(screen.queryByRole('heading', { name: '문의 요약' })).not.toBeInTheDocument();
    expect(screen.queryByText('원문 대화 보기')).not.toBeInTheDocument();
  });
  it('F003-CONTRACT-04 저장 후 응답 유실을 원 키로 확인하면 문의·메시지·실행이 중복 생성되지 않는다', async () => {
    await login(TEST_LOGINS.requester);
    const command = {
      path: '/api/v2/inquiries',
      body: {
        requestId: crypto.randomUUID(),
        content: '전자결재 문의',
        reportedSystemId: systems[0].id,
      },
    };
    systemMockBackend.loseNextResponse();
    await expect(sendSystemCommand(command)).rejects.toMatchObject({ status: 0 });
    const receipt = await sendSystemCommand(command, true);
    const id = savedResource(receipt, 'INQUIRY').id;
    const original = await settled(id);
    expect(await sendSystemCommand(command)).toEqual(receipt);
    const messages = await systemGet(`/inquiries/${id}/messages`, readMessages);
    expect(messages.items.filter((m) => m.sender === 'USER')).toHaveLength(1);
    expect(messages.items.filter((m) => m.sender === 'AI')).toHaveLength(1);
    expect((await systemGet(`/inquiries/${id}`, readInquiry)).currentRun?.id).toBe(
      original.currentRun?.id,
    );
    await expect(
      sendSystemCommand({ ...command, body: { ...command.body, content: '다른 요청' } }),
    ).rejects.toMatchObject({ status: 409 });
  });
  it('F003-CONTRACT-02 질문 5회와 시스템 대기를 보존하고 선택 후 같은 문제를 배정한다', async () => {
    await login(TEST_LOGINS.requester);
    const id = await create('어떤 화면인지 모르겠어요', null);
    let inquiry = await settled(id);
    for (let i = 0; i < 5; i++) inquiry = await reply(inquiry, '아직 시스템을 모르겠어요');
    const problem = inquiry.problems[0];
    expect(problem).toMatchObject({ questionCount: 5, state: 'WAITING_SYSTEM' });
    if (!problem) throw new Error('문제 없음');
    await sendSystemCommand({
      path: `/api/v2/inquiries/${id}/problems/${problem.id}/system-confirmations`,
      body: {
        requestId: crypto.randomUUID(),
        expectedInquiryVersion: inquiry.inquiryVersion,
        systemId: systems[0].id,
      },
    });
    const assigned = await settled(id);
    expect(assigned.problems[0]).toMatchObject({
      id: problem.id,
      questionCount: 5,
      state: 'IN_SERVICE',
    });
  });
  it('F004-CONTRACT-01 메시지 revision 충돌은 원문과 저장된 티켓을 보존한다', async () => {
    await login(TEST_LOGINS.requester);
    const id = await create('전자결재 문서 오류', systems[0].id);
    let inquiry = await settled(id);
    inquiry = await reply(inquiry, '오늘부터 문서를 열 수 없어요');
    const messages = await systemGet(`/inquiries/${id}/messages`, readMessages);
    const message = messages.items[0];
    if (!message) throw new Error('메시지 없음');
    const command = {
      path: `/api/v2/inquiries/${id}/messages/${message.id}/revisions`,
      body: {
        requestId: crypto.randomUUID(),
        expectedInquiryVersion: inquiry.inquiryVersion,
        expectedMessageRevision: message.revision,
        content: '오전부터 문서를 열 수 없어요',
      },
    };
    await sendSystemCommand(command);
    await expect(
      sendSystemCommand({ ...command, body: { ...command.body, requestId: crypto.randomUUID() } }),
    ).rejects.toMatchObject({ status: 409 });
    const updated = await systemGet(`/inquiries/${id}/messages`, readMessages);
    expect(updated.items[0]).toMatchObject({
      id: message.id,
      revision: 2,
      content: command.body.content,
    });
    expect(updated.items[0]?.revisions[0]?.content).toBe(message.content);
    expect((await systemGet(`/inquiries/${id}`, readInquiry)).problems[0]?.ticket?.id).toBe(
      inquiry.problems[0]?.ticket?.id,
    );
  });
  it('F009-CONTRACT-01 기존 칸반에서 시스템 필터와 독립 상세 링크를 제공한다', async () => {
    await login(TEST_LOGINS.network);
    renderWithProviders(<SystemTicketBoard />, '/tickets?view=board&state=WAITING&page=0');
    const link = await screen.findByRole('link', { name: /전자결재에서 문서를 열 수 없어요/u });
    expect(link.getAttribute('href')).toContain('/tickets/');
    expect(link.getAttribute('href')).toContain('view=board&state=WAITING&page=0');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: '책임 시스템' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: '파일 공유' })).not.toBeInTheDocument();
  });
  it('F001-CONTRACT-03 열린 담당자 상세의 권한을 회수하면 보호 내용이 사라진다', async () => {
    await login(TEST_LOGINS.network);
    realtime.connect(useSession.getState().token ?? '');
    const page = await systemGet('/tickets?scope=ASSIGNED_SYSTEMS', readTickets);
    const id = page.items[0]?.id ?? '';
    renderWithProviders(
      <SystemTicketDetail id={id} onClose={vi.fn()} renderCaseReview={() => null} />,
      `/tickets/${id}?view=list&systemId=${systems[0].id}`,
    );
    expect(await screen.findByRole('heading', { name: '문의 요약' })).toBeVisible();
    expect(screen.getByRole('button', { name: '상세 닫기' })).toBeVisible();
    act(() => {
      systemMockBackend.revokeSystem(users[1].id, systems[0].id);
    });
    expect(await screen.findByText('이 시스템 티켓을 볼 권한이 없어요')).toBeVisible();
    expect(screen.queryByRole('heading', { name: '문의 요약' })).not.toBeInTheDocument();
  });
  it('모달인 담당 티켓 상세에서도 연결이 끊기면 상세 안에서 다시 연결한다', async () => {
    await login(TEST_LOGINS.network);
    const token = useSession.getState().token ?? '';
    const page = await systemGet('/tickets?scope=ASSIGNED_SYSTEMS', readTickets);
    const id = page.items[0]?.id ?? '';
    vi.spyOn(realtime, 'subscribe').mockImplementation((_target, listener) => {
      listener?.('disconnected');
      return () => undefined;
    });
    const connect = vi.spyOn(realtime, 'connect').mockImplementation(() => undefined);
    renderWithProviders(
      <SystemTicketDetail id={id} onClose={vi.fn()} renderCaseReview={() => null} />,
      `/tickets/${id}?view=list&systemId=${systems[0].id}`,
    );
    expect(await screen.findByText('실시간 연결이 끊겼어요')).toBeVisible();
    expect(screen.queryByRole('heading', { name: '확인된 사실' })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }));
    expect(connect).toHaveBeenCalledWith(token);
  });
  it('관리자 티켓 상세는 내용을 유지하고 보드 연결이 끊기면 모달 안에서 다시 연결한다', async () => {
    await login(TEST_LOGINS.admin);
    const token = useSession.getState().token ?? '';
    const page = await systemGet('/tickets?scope=ADMIN', readTickets);
    const id = page.items[0]?.id ?? '';
    const connect = vi.spyOn(realtime, 'connect').mockImplementation(() => undefined);
    renderWithProviders(
      <SystemTicketDetail id={id} onClose={vi.fn()} renderCaseReview={() => null} />,
      `/admin/tickets/${id}`,
    );
    expect(await screen.findByRole('heading', { name: '업무 메타데이터' })).toBeVisible();
    expect(screen.queryByRole('button', { name: '다시 연결' })).not.toBeInTheDocument();
    act(() => {
      useConnection.getState().setState('disconnected');
    });
    await userEvent.setup().click(screen.getByRole('button', { name: '다시 연결' }));
    expect(connect).toHaveBeenCalledWith(token);
    expect(screen.getByRole('heading', { name: '업무 메타데이터' })).toBeVisible();
    act(() => {
      useConnection.getState().setState('idle');
    });
  });
  it('F003-CONTRACT-04 입력 UI에서 응답 유실 후 같은 요청을 확인하고 입력을 정리한다', async () => {
    await login(TEST_LOGINS.requester);
    const onStored = vi.fn();
    renderWithProviders(<SystemMessageComposer onStored={onStored} />);
    await userEvent.type(screen.getByRole('textbox', { name: '문의 내용' }), '파일 공유 권한 문의');
    systemMockBackend.loseNextResponse();
    await userEvent.click(screen.getByRole('button', { name: '질문하기 ↑' }));
    const confirm = await screen.findByRole('button', { name: '같은 요청 결과 확인' });
    await waitFor(() => {
      expect(confirm).toBeEnabled();
    });
    await userEvent.click(confirm);
    await waitFor(() => {
      expect(onStored).toHaveBeenCalledTimes(1);
    });
    expect(screen.getByRole('textbox', { name: '문의 내용' })).toHaveValue('');
    expect((await systemGet('/inquiries', readInquiries)).items).toHaveLength(3);
  });
  it('F002-CONTRACT-08 조각 없이 완료된 실행도 최종 답변과 필요한 행동을 표시한다', async () => {
    await login(TEST_LOGINS.requester);
    const id = await create('전자결재 문의', systems[0].id);
    renderWithProviders(<SystemChatView inquiryId={id} />, `/inquiries/${id}/chat`);
    expect(
      await screen.findByText(
        '어떤 시스템에서 언제 발생했는지, 기대한 동작과 실제 현상을 알려 주세요.',
        {},
        { timeout: 4000 },
      ),
    ).toBeVisible();
    expect(screen.getByText('답변 필요')).toBeVisible();
    expect(screen.queryByText('질문 1/5')).not.toBeInTheDocument();
  });
  it('F005-CONTRACT-01 잘못된 성공 응답·구 프로필 DTO는 신규 계약에서 거부한다', () => {
    expect(() => readUser({ ...users[0], primarySystemIds: undefined })).toThrow();
    expect(() =>
      readReceipt({
        requestId: crypto.randomUUID(),
        operationId: crypto.randomUUID(),
        state: 'SUCCEEDED',
        resource: null,
        error: null,
      }),
    ).toThrow();
  });
});
