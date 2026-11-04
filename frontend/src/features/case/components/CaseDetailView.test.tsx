import { screen } from '@testing-library/react';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { useSession } from '@/features/auth';
import { useSubscription } from '@/features/realtime';
import { ApiError } from '@/shared/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import type * as CaseApi from '../api/caseApi';
import { getCase } from '../api/caseApi';
import { CaseDetailView } from './CaseDetailView';

vi.mock('../api/caseApi', async (original) => ({
  ...(await original<typeof CaseApi>()),
  getCase: vi.fn(),
}));
vi.mock('@/features/realtime', () => ({ useSubscription: vi.fn() }));

beforeEach(() => {
  useSession.setState({
    user: {
      id: 'synthetic-owner',
      name: '가상 담당자',
      employeeNo: 'TEST',
      roles: [],
      primarySystemIds: ['synthetic-system'],
      department: { id: 'test', code: 'TEST', name: '가상 부서' },
    },
  });
  vi.mocked(useSubscription).mockReturnValue({ denied: true, state: 'denied' });
});
afterEach(() => {
  vi.clearAllMocks();
});

it('F011-CONTRACT-05 사례 HTTP 404와 구독 거부가 함께 오면 조회 불가를 안내하고 내용을 숨긴다', async () => {
  vi.mocked(getCase).mockRejectedValue(
    new ApiError(404, {
      code: 'NOT_FOUND',
      message: '대상 없음',
      requestId: 'test',
      retryable: false,
      fieldErrors: [],
    }),
  );
  renderWithProviders(<CaseDetailView id="missing-case" />);
  expect(await screen.findByText('사례를 찾을 수 없어요')).toBeVisible();
  expect(screen.queryByText('이 사례를 검토할 권한이 없어요')).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: '원 티켓 보기' })).not.toBeInTheDocument();
});

it('F011-CONTRACT-05 실제 HTTP 403은 권한 오류를 유지한다', async () => {
  vi.mocked(useSubscription).mockReturnValue({ denied: false, state: 'connected' });
  vi.mocked(getCase).mockRejectedValue(
    new ApiError(403, {
      code: 'FORBIDDEN',
      message: '권한 없음',
      requestId: 'test',
      retryable: false,
      fieldErrors: [],
    }),
  );
  renderWithProviders(<CaseDetailView id="forbidden-case" />);
  expect(await screen.findByText('이 사례를 검토할 권한이 없어요')).toBeVisible();
  expect(screen.queryByText('사례를 찾을 수 없어요')).not.toBeInTheDocument();
});
