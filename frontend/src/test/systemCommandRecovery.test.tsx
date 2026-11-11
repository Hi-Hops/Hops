import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login, useSession } from '@/features/auth';
import { SystemMessageComposer } from '@/features/chat';
import { getSystemTicket, getSystemTickets } from '@/features/ticket/api/systemTicketApi';
import { WorkRecordEditor } from '@/features/ticket/components/WorkRecordEditor';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { systems } from '@/shared/api/systemMockData';
import type { ResourceRef } from '@/shared/api/types/hopsSystem1';
import { TEST_LOGINS } from './mockCredentials';
import { renderWithProviders } from './renderWithProviders';

beforeEach(() => {
  useSession.getState().logout();
  systemMockBackend.reset();
});
afterEach(() => {
  useSession.getState().logout();
  systemMockBackend.reset();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

function commandBody(body: BodyInit | null | undefined) {
  if (typeof body !== 'string') throw new Error('JSON 명령 본문 필요');
  return JSON.parse(body) as { requestId: string };
}

/** 저장은 끝났지만 프록시가 비계약 오류를 반환하는 HTTP 경계 대역이다. */
function storedWithProxyError(status: number, resource: ResourceRef) {
  let requestId = '';
  const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation((url, options) => {
    if (typeof url !== 'string') return Promise.reject(new Error('문자열 URL 필요'));
    const path = new URL(url).pathname;
    if (path === '/api/v2/systems') return Promise.resolve(Response.json(systems));
    if (options?.method === 'POST') {
      const body = commandBody(options.body);
      requestId = body.requestId;
      return Promise.resolve(new Response('<html>proxy error</html>', { status }));
    }
    if (requestId && path === `/api/v2/operations/by-request/${requestId}`)
      return Promise.resolve(
        Response.json({
          requestId,
          operationId: crypto.randomUUID(),
          state: 'SUCCEEDED',
          resource,
          error: null,
        }),
      );
    return Promise.reject(new Error('예상하지 못한 HTTP 요청: ' + path));
  });
  vi.stubEnv('VITE_DATA_MODE', 'live');
  vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.test');
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

describe('F003·F009 비계약 4xx 이후 원 명령 복구', () => {
  it.each([400, 403, 409])(
    'F009-TARGET-04 처리 기록은 HTTP %s 이후 원 요청을 재확인한다',
    async (status) => {
      await login(TEST_LOGINS.network);
      const id = (await getSystemTickets('scope=ASSIGNED_SYSTEMS')).items[0]?.id;
      if (!id) throw new Error('담당 티켓 필요');
      const ticket = await getSystemTicket(id);
      const fetch = storedWithProxyError(status, { type: 'TICKET', id });
      const user = userEvent.setup();
      renderWithProviders(<WorkRecordEditor ticket={ticket} />);
      const input = screen.getByRole('textbox', { name: '처리 내용' });
      await user.type(input, '장애 점검 중');
      await user.click(screen.getByRole('button', { name: '진행 기록 저장' }));
      const confirm = await screen.findByRole('button', { name: '같은 요청 결과 확인' });
      expect(input).toHaveValue('장애 점검 중');
      expect(input).toBeDisabled();
      const original = fetch.mock.calls.find(([, options]) => options?.method === 'POST');
      const body = commandBody(original?.[1]?.body);
      await user.click(confirm);
      await screen.findByText('저장했어요. 최신 상태를 확인해 주세요.');
      expect(fetch).toHaveBeenCalledWith(
        `https://api.example.test/api/v2/operations/by-request/${body.requestId}`,
        expect.objectContaining({ method: 'GET' }),
      );
      expect(fetch.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
      expect(input).toHaveValue('');
    },
  );

  it.each([400, 403, 409])(
    'F003-CONTRACT-04 문의 입력은 HTTP %s 이후 원 요청을 재확인한다',
    async (status) => {
      await login(TEST_LOGINS.requester);
      const id = crypto.randomUUID();
      const fetch = storedWithProxyError(status, { type: 'INQUIRY', id });
      const onStored = vi.fn();
      const user = userEvent.setup();
      renderWithProviders(<SystemMessageComposer onStored={onStored} />);
      const input = screen.getByRole('textbox', { name: '문의 내용' });
      await user.type(input, '파일 공유 권한 문의');
      await user.click(screen.getByRole('button', { name: '질문하기 ↑' }));
      const confirm = await screen.findByRole('button', { name: '같은 요청 결과 확인' });
      expect(input).toHaveValue('파일 공유 권한 문의');
      expect(input).toBeDisabled();
      const original = fetch.mock.calls.find(([, options]) => options?.method === 'POST');
      const body = commandBody(original?.[1]?.body);
      await user.click(confirm);
      await waitFor(() => {
        expect(onStored).toHaveBeenCalledExactlyOnceWith(id);
      });
      expect(fetch).toHaveBeenCalledWith(
        `https://api.example.test/api/v2/operations/by-request/${body.requestId}`,
        expect.objectContaining({ method: 'GET' }),
      );
      expect(fetch.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
      expect(input).toHaveValue('');
    },
  );
});
