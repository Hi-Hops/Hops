import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import {
  workRecordCommand,
  assignmentCommand,
  processingCommand,
  getSystemTickets,
  getSystemTicket,
  getGeneration,
  getResolution,
  generationRetryCommand,
} from '@/features/ticket/api/systemTicketApi';
import { WorkRecordEditor } from '@/features/ticket/components/WorkRecordEditor';
import { ResolutionControls } from '@/features/inquiry/components/ResolutionControls';
import { CaseEditor } from '@/features/case/components/CaseEditor';
import {
  getCase,
  editCaseCommand,
  approveCaseCommand,
  indexCommand,
  getCases,
} from '@/features/case/api/caseApi';
import { searchableCase } from '@/features/case/model/publication';
import { apiRequest } from '@/shared/api/client';
import { sendSystemCommand, systemGet, savedResource } from '@/shared/api/systemCommands';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { systems } from '@/shared/api/systemMockData';
import { readInquiry } from '@/shared/api/systemWire';
import type {
  TicketDetail,
  WorkRecordKind,
  CaseDetail,
  ResolutionRequest,
} from '@/shared/api/types/hopsSystem1';
import { realtime } from '@/shared/realtime';
import { TEST_LOGINS } from './mockCredentials';
import { renderWithProviders } from './renderWithProviders';

beforeEach(() => {
  useSession.getState().logout();
  systemMockBackend.reset();
});
afterEach(() => {
  realtime.disconnect();
  systemMockBackend.reset();
  vi.restoreAllMocks();
});
async function firstTicket() {
  await login(TEST_LOGINS.network);
  const id = (await getSystemTickets('scope=ASSIGNED_SYSTEMS')).items[0]?.id;
  if (!id) throw new Error('담당 티켓 필요');
  return getSystemTicket(id);
}
function work(ticket: TicketDetail, kind: WorkRecordKind = 'VERIFIED_RESOLUTION') {
  return workRecordCommand(ticket.id, {
    requestId: crypto.randomUUID(),
    expectedTicketRevision: ticket.ticketRevision,
    kind,
    action: '접근 정책을 점검하고 복구했어요.',
    verification:
      kind === 'VERIFIED_RESOLUTION' || kind === 'IMPLEMENTED_VERIFIED'
        ? '같은 환경에서 재현되지 않음을 확인했어요.'
        : null,
    confirmedCause: null,
    appliesToSystemVersion: null,
  });
}
function response(request: ResolutionRequest, decision: 'RESOLVED' | 'UNRESOLVED') {
  return {
    path: '/api/v2/resolution-requests/' + request.id + '/responses',
    body: {
      requestId: crypto.randomUUID(),
      expectedRequestRevision: request.requestRevision,
      decision,
    },
    expectedResource: { type: 'RESOLUTION_REQUEST', id: request.id },
  };
}
async function generated(ticket: TicketDetail) {
  const id = ticket.caseGeneration?.id;
  if (!id) throw new Error('생성 작업 필요');
  const caseId = await waitFor(async () => {
    const value = await getGeneration(id);
    expect(value.state).toBe('SUCCEEDED');
    if (!value.caseId) throw new Error('사례 필요');
    return value.caseId;
  });
  return getCase(caseId);
}
async function draftCase() {
  const ticket = await firstTicket();
  await sendSystemCommand(work(ticket));
  return generated(await getSystemTicket(ticket.id));
}
async function confirm(detail: CaseDetail) {
  await login(TEST_LOGINS.requester);
  const request = await getResolution(detail.version.resolutionRequestId);
  await sendSystemCommand(response(request, 'RESOLVED'));
  await login(TEST_LOGINS.network);
}
function edit(detail: CaseDetail, title: string) {
  const v = detail.version;
  return editCaseCommand(v.id, {
    requestId: crypto.randomUUID(),
    expectedCaseVersion: v.caseVersion,
    title,
    problemStatement: v.problemStatement,
    action: v.action,
    verification: v.verification,
    applicability: v.applicability,
    limitations: v.limitations,
    appliesToSystemVersion: v.appliesToSystemVersion,
    accessScope: v.accessScope === 'EMPLOYEE' ? 'EMPLOYEE' : 'SYSTEM_STAFF',
  });
}
describe('F006/F007/F009/F011 처리·해결·사례 계약', () => {
  it('F009-CONTRACT-05 진행·실패는 이력만 저장하고 검증 기록은 확인 대기와 별도 초안 생성을 만든다', async () => {
    let ticket = await firstTicket();
    for (const kind of ['PROGRESS', 'FAILED_ATTEMPT'] as const) {
      await sendSystemCommand(work(ticket, kind));
      ticket = await getSystemTicket(ticket.id);
      expect(ticket.state).toBe('IN_PROGRESS');
      expect(ticket.activeResolutionRequest).toBeNull();
      expect(ticket.caseGeneration).toBeNull();
    }
    expect(ticket.workRecords).toHaveLength(2);
    const bad = work(ticket);
    bad.body.verification = null;
    await expect(sendSystemCommand(bad)).rejects.toMatchObject({ status: 400 });
    await sendSystemCommand(work(ticket));
    ticket = await getSystemTicket(ticket.id);
    expect(ticket.state).toBe('WAITING_CONFIRMATION');
    expect(ticket.workRecords).toHaveLength(3);
    expect(ticket.activeResolutionRequest).toMatchObject({
      source: 'WORK_RECORD',
      state: 'PENDING',
    });
    await expect(sendSystemCommand(work(ticket))).rejects.toMatchObject({ status: 409 });
    expect((await getSystemTicket(ticket.id)).activeResolutionRequest?.id).toBe(
      ticket.activeResolutionRequest?.id,
    );
  });
  it('F009-CONTRACT-02 기능 요청은 구현·검증 완료 기록으로만 확인 요청과 구현 사례를 만든다', async () => {
    await login(TEST_LOGINS.requester);
    const receipt = await sendSystemCommand({
      path: '/api/v2/inquiries',
      body: {
        requestId: crypto.randomUUID(),
        content: '일괄 다운로드 기능 추가',
        reportedSystemId: systems[0].id,
      },
    });
    const id = savedResource(receipt, 'INQUIRY').id;
    const inquiry = await waitFor(async () => {
      const value = await systemGet('/inquiries/' + id, readInquiry);
      expect(value.currentRun?.status).toBe('SUCCEEDED');
      return value;
    });
    await sendSystemCommand({
      path: '/api/v2/inquiries/' + id + '/messages',
      body: {
        requestId: crypto.randomUUID(),
        expectedInquiryVersion: inquiry.inquiryVersion,
        content: '목록에서 여러 문서를 선택해 다운로드하고 싶어요.',
        problemIds: [],
      },
    });
    const ticketId = await waitFor(async () => {
      const value = (await systemGet('/inquiries/' + id, readInquiry)).problems[0]?.ticket?.id;
      if (!value) throw new Error('기능 요청 티켓 대기');
      return value;
    });
    await login(TEST_LOGINS.network);
    let ticket = await getSystemTicket(ticketId);
    expect(ticket.processingType).toBe('CHANGE_REQUEST');
    await sendSystemCommand(work(ticket, 'PROGRESS'));
    ticket = await getSystemTicket(ticketId);
    expect(ticket.activeResolutionRequest).toBeNull();
    expect(ticket.caseGeneration).toBeNull();
    await expect(sendSystemCommand(work(ticket, 'VERIFIED_RESOLUTION'))).rejects.toMatchObject({
      status: 409,
    });
    await sendSystemCommand(work(ticket, 'IMPLEMENTED_VERIFIED'));
    ticket = await getSystemTicket(ticketId);
    expect(ticket.state).toBe('WAITING_CONFIRMATION');
    expect((await generated(ticket)).version.caseType).toBe('IMPLEMENTATION');
  });
  it('F009-CONTRACT-06 처리 유형 정정은 근거와 최신 버전이 필요하고 조사·장애 사이에서만 바꾼다', async () => {
    const ticket = await firstTicket();
    const command = processingCommand(ticket.id, {
      requestId: crypto.randomUUID(),
      expectedTicketRevision: ticket.ticketRevision,
      processingType: 'INCIDENT',
      reason: '실제 오류 재현 확인',
      evidenceMessageIds: [],
      evidenceNote: null,
    });
    await expect(sendSystemCommand(command)).rejects.toMatchObject({ status: 400 });
    const stored = {
      ...command,
      body: {
        ...command.body,
        requestId: crypto.randomUUID(),
        evidenceNote: '동일 환경에서 장애를 재현했어요.',
      },
    };
    await sendSystemCommand(stored);
    const latest = await getSystemTicket(ticket.id);
    expect(latest).toMatchObject({
      processingType: 'INCIDENT',
      state: ticket.state,
      ticketRevision: ticket.ticketRevision + 1,
    });
    await expect(
      sendSystemCommand({ ...stored, body: { ...stored.body, requestId: crypto.randomUUID() } }),
    ).rejects.toMatchObject({ status: 409 });
  });
  it('F006-CONTRACT-01 미해결 응답은 같은 티켓으로 돌아가며 다른 문제를 변경하지 않는다', async () => {
    let ticket = await firstTicket();
    await sendSystemCommand(work(ticket));
    ticket = await getSystemTicket(ticket.id);
    const request = ticket.activeResolutionRequest;
    if (!request) throw new Error('확인 요청 필요');
    await login(TEST_LOGINS.requester);
    const others = (await systemGet('/inquiries/' + ticket.inquiryId, readInquiry)).problems.filter(
      (p) => p.id !== ticket.problemId,
    );
    await sendSystemCommand(response(request, 'UNRESOLVED'));
    const inquiry = await systemGet('/inquiries/' + ticket.inquiryId, readInquiry);
    expect(inquiry.problems.filter((p) => p.id !== ticket.problemId)).toEqual(others);
    await expect(sendSystemCommand(response(request, 'RESOLVED'))).rejects.toMatchObject({
      status: 409,
    });
    await login(TEST_LOGINS.network);
    expect(await getSystemTicket(ticket.id)).toMatchObject({ id: ticket.id, state: 'IN_PROGRESS' });
  });
  it('F006-TARGET-04 GUIDE 해결 확인에는 티켓·사례를 생성하지 않는다', async () => {
    await login(TEST_LOGINS.requester);
    const receipt = await sendSystemCommand({
      path: '/api/v2/inquiries',
      body: {
        requestId: crypto.randomUUID(),
        content: '전자결재 사용법 안내만 필요해요',
        reportedSystemId: systems[0].id,
      },
    });
    const id = savedResource(receipt, 'INQUIRY').id;
    const request = await waitFor(async () => {
      const value =
        (await systemGet('/inquiries/' + id, readInquiry)).problems[0]?.activeResolutionRequest ??
        null;
      expect(value?.source).toBe('GUIDE');
      if (!value) throw new Error('GUIDE 확인 필요');
      return value;
    });
    await sendSystemCommand(response(request, 'RESOLVED'));
    const problem = (await systemGet('/inquiries/' + id, readInquiry)).problems[0];
    expect(problem).toMatchObject({ state: 'RESOLVED', ticket: null });
    await login(TEST_LOGINS.network);
    expect((await getCases('')).items).toEqual([]);
  });
  it('F009-DETAIL-08 응답 유실 후 화면을 닫아도 입력과 원 요청을 유지하여 처리 기록을 중복 생성하지 않는다', async () => {
    const ticket = await firstTicket();
    const user = userEvent.setup();
    const first = renderWithProviders(<WorkRecordEditor ticket={ticket} />);
    await user.type(screen.getByRole('textbox', { name: '실제 조치' }), '장애 점검 중');
    systemMockBackend.loseNextResponse();
    await user.click(screen.getByRole('button', { name: '처리 기록 저장' }));
    await screen.findByRole('button', { name: '같은 요청 결과 확인' });
    first.unmount();
    renderWithProviders(<WorkRecordEditor ticket={await getSystemTicket(ticket.id)} />);
    expect(screen.getByRole('textbox', { name: '실제 조치' })).toHaveValue('장애 점검 중');
    await user.click(screen.getByRole('button', { name: '같은 요청 결과 확인' }));
    await screen.findByText('저장했어요. 최신 상태를 확인해 주세요.');
    expect((await getSystemTicket(ticket.id)).workRecords).toHaveLength(1);
    expect(screen.getByRole('textbox', { name: '실제 조치' })).toHaveValue('');
  });
  it('F009-DETAIL-08 버전 충돌은 입력을 보존하고 최신 티켓 확인 전까지 재저장을 막는다', async () => {
    const ticket = await firstTicket();
    const user = userEvent.setup();
    const view = renderWithProviders(<WorkRecordEditor ticket={ticket} />);
    await user.type(screen.getByRole('textbox', { name: '실제 조치' }), '새 점검 내용');
    await sendSystemCommand(work(ticket, 'PROGRESS'));
    await user.click(screen.getByRole('button', { name: '처리 기록 저장' }));
    await screen.findByRole('alert');
    view.unmount();
    renderWithProviders(<WorkRecordEditor ticket={await getSystemTicket(ticket.id)} />);
    expect(screen.getByRole('textbox', { name: '실제 조치' })).toHaveValue('새 점검 내용');
    expect(screen.getByRole('button', { name: '처리 기록 저장' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '최신 티켓 확인 후 이어쓰기' }));
    expect(screen.getByRole('button', { name: '처리 기록 저장' })).toBeEnabled();
  });
  it('F006-CONTRACT-04 문의자 확인 UI는 응답 유실 시 반대 응답 대신 원 요청만 재확인한다', async () => {
    let ticket = await firstTicket();
    await sendSystemCommand(work(ticket));
    ticket = await getSystemTicket(ticket.id);
    const request = ticket.activeResolutionRequest;
    if (!request) throw new Error('확인 요청 필요');
    await login(TEST_LOGINS.requester);
    const user = userEvent.setup();
    renderWithProviders(<ResolutionControls request={request} />);
    systemMockBackend.loseNextResponse();
    await user.click(screen.getByRole('button', { name: '아직 해결되지 않았어요' }));
    await screen.findByRole('button', { name: '같은 요청 결과 확인' });
    expect(screen.queryByRole('button', { name: '해결됐어요' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '같은 요청 결과 확인' }));
    await screen.findByText('저장했어요. 최신 상태를 확인해 주세요.');
    expect((await getResolution(request.id)).state).toBe('UNRESOLVED');
  });
  it('F007-CONTRACT-01 관리자는 제한 상세로 배정을 정정할 수 있지만 처리 기록·처리 유형·사례에는 접근하지 못한다', async () => {
    const ticket = await firstTicket();
    await login(TEST_LOGINS.admin);
    await expect(sendSystemCommand(work(ticket, 'PROGRESS'))).rejects.toMatchObject({
      status: 403,
    });
    await expect(
      sendSystemCommand(
        processingCommand(ticket.id, {
          requestId: crypto.randomUUID(),
          expectedTicketRevision: ticket.ticketRevision,
          processingType: 'INVESTIGATION',
          reason: '원인 조사 필요',
          evidenceNote: '메타데이터 확인',
          evidenceMessageIds: [],
        }),
      ),
    ).rejects.toMatchObject({ status: 403 });
    await expect(getCases('')).rejects.toMatchObject({ status: 403 });
    await sendSystemCommand(
      assignmentCommand(ticket.id, {
        requestId: crypto.randomUUID(),
        expectedTicketRevision: ticket.ticketRevision,
        responsibleSystemId: systems[2].id,
        reason: '파일 공유 책임 확인',
        evidenceNote: '관련 업무를 담당자와 확인함',
        evidenceMessageIds: [],
      }),
    );
    await login(TEST_LOGINS.network);
    await expect(getSystemTicket(ticket.id)).rejects.toMatchObject({ status: 403 });
    await login(TEST_LOGINS.backend);
    const changed = await getSystemTicket(ticket.id);
    expect(changed.responsibleSystem.id).toBe(systems[2].id);
    expect(changed.currentPrimary.id).toBe(useSession.getState().user?.id);
    expect(changed.assignmentHistory.at(-1)?.reason).toBe('파일 공유 책임 확인');
  });
  it('F011-AC01 초안 생성 실패는 처리 기록·확인 요청을 보존하고 원 생성 작업에서 재실행한다', async () => {
    const ticket = await firstTicket();
    systemMockBackend.setNextGenerationOutcome('FAILED');
    await sendSystemCommand(work(ticket));
    const stored = await getSystemTicket(ticket.id);
    const id = stored.caseGeneration?.id;
    if (!id) throw new Error('작업 필요');
    await waitFor(async () => {
      expect((await getGeneration(id)).state).toBe('FAILED');
    });
    expect((await getSystemTicket(ticket.id)).workRecords).toHaveLength(1);
    expect(stored.activeResolutionRequest?.state).toBe('PENDING');
    await sendSystemCommand(generationRetryCommand(id, crypto.randomUUID()));
    await generated(stored);
    expect((await getSystemTicket(ticket.id)).caseGeneration?.id).toBe(id);
  });
  it('F011-AC02 사례 편집은 버전을 증가시키며 원 기록 확인 전 승인·승인 후 편집을 거부한다', async () => {
    let detail = await draftCase();
    await expect(
      sendSystemCommand(
        approveCaseCommand(detail.version.id, {
          requestId: crypto.randomUUID(),
          expectedCaseVersion: 1,
        }),
      ),
    ).rejects.toMatchObject({ status: 409 });
    const stale = edit(detail, '늦은 수정');
    await sendSystemCommand(edit(detail, '검토한 사례 제목'));
    detail = await getCase(detail.version.id);
    expect(detail.version).toMatchObject({ caseVersion: 2, title: '검토한 사례 제목' });
    await expect(sendSystemCommand(stale)).rejects.toMatchObject({ status: 409 });
    await confirm(detail);
    await sendSystemCommand(
      approveCaseCommand(detail.version.id, {
        requestId: crypto.randomUUID(),
        expectedCaseVersion: 2,
      }),
    );
    detail = await getCase(detail.version.id);
    expect(detail.version.state).toBe('APPROVED');
    await expect(sendSystemCommand(edit(detail, '승인 뒤 수정'))).rejects.toMatchObject({
      status: 409,
    });
    const ticket = await getSystemTicket(detail.version.ticketId);
    await expect(sendSystemCommand(work(ticket, 'PROGRESS'))).rejects.toMatchObject({
      status: 409,
    });
  });
  it('F011-AC03/04/05 부분 색인 실패는 양쪽 비공개이며 실패 저장소만 다음 attempt로 복구한다', async () => {
    let detail = await draftCase();
    await confirm(detail);
    systemMockBackend.failNextIndex('NEO4J');
    await sendSystemCommand(
      approveCaseCommand(detail.version.id, {
        requestId: crypto.randomUUID(),
        expectedCaseVersion: detail.version.caseVersion,
      }),
    );
    await waitFor(async () => {
      detail = await getCase(detail.version.id);
      expect(detail.indexes.find((i) => i.store === 'NEO4J')?.state).toBe('FAILED');
    });
    expect(searchableCase(detail)).toBe(false);
    expect(detail.publication.canSearch).toBe(false);
    const vector = detail.indexes.find((i) => i.store === 'VECTOR');
    const neo = detail.indexes.find((i) => i.store === 'NEO4J');
    await expect(
      sendSystemCommand(
        indexCommand(
          detail.version.id,
          {
            requestId: crypto.randomUUID(),
            expectedCaseVersion: detail.version.caseVersion,
            store: 'VECTOR',
          },
          true,
        ),
      ),
    ).rejects.toMatchObject({ status: 409 });
    const request = vi.spyOn(systemMockBackend, 'request');
    await getCase(detail.version.id);
    await getCase(detail.version.id);
    expect(request.mock.calls.every((call) => call[1] === 'GET')).toBe(true);
    await sendSystemCommand(
      indexCommand(
        detail.version.id,
        {
          requestId: crypto.randomUUID(),
          expectedCaseVersion: detail.version.caseVersion,
          store: 'NEO4J',
        },
        true,
      ),
    );
    await waitFor(async () => {
      detail = await getCase(detail.version.id);
      expect(searchableCase(detail)).toBe(true);
    });
    expect(detail.indexes.find((i) => i.store === 'VECTOR')).toEqual(vector);
    expect(detail.indexes.find((i) => i.store === 'NEO4J')).toMatchObject({
      jobId: neo?.jobId,
      attempt: 2,
    });
    for (const state of ['UNKNOWN', 'FAILED', 'RUNNING']) {
      const invalid = structuredClone(detail);
      const index = invalid.indexes[0];
      if (index) index.state = state;
      expect(searchableCase(invalid)).toBe(false);
    }
    const mismatch = structuredClone(detail);
    const index = mismatch.indexes[0];
    if (index) index.caseVersion++;
    expect(searchableCase(mismatch)).toBe(false);
    expect(
      searchableCase({ ...detail, publication: { ...detail.publication, canSearch: false } }),
    ).toBe(false);
  });
  it('F011-AC02 승인 UI는 원 기록·해결 확인 연결을 검증하고 체크와 저장된 버전을 요구한다', async () => {
    const detail = await draftCase();
    const user = userEvent.setup();
    const view = renderWithProviders(<CaseEditor detail={detail} />);
    await screen.findByText('문의자 해결 확인 전');
    await user.click(screen.getByRole('checkbox'));
    expect(screen.getByRole('button', { name: '확인한 버전 승인' })).toBeDisabled();
    view.unmount();
    await confirm(detail);
    renderWithProviders(<CaseEditor detail={detail} />);
    await screen.findByText('문의자 해결 확인됨');
    await user.click(screen.getByRole('checkbox'));
    expect(screen.getByRole('button', { name: '확인한 버전 승인' })).toBeEnabled();
    await user.type(screen.getByRole('textbox', { name: '사례 제목' }), ' 수정');
    expect(screen.getByRole('button', { name: '확인한 버전 승인' })).toBeDisabled();
  });
  it('F011-AC02 API는 구 경로로 폴백하지 않는다', async () => {
    await firstTicket();
    const request = vi.spyOn(systemMockBackend, 'request');
    await getCases('state=DRAFT');
    expect(request.mock.calls[0]?.[0]).toBe('/api/v2/cases?state=DRAFT');
    await expect(apiRequest('/api/cases')).rejects.toMatchObject({
      body: { code: 'INVALID_API_PATH' },
    });
  });
});
