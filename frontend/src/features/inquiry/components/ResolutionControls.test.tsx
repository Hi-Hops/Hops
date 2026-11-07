import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ResolutionRequest } from '@/shared/api/types/hopsSystem1';
import { renderWithProviders } from '@/test/renderWithProviders';
import { ResolutionControls } from './ResolutionControls';

function request(source: string, resultSummary: string): ResolutionRequest {
  return {
    id: '00000000-0000-0000-0000-000000000001',
    inquiryId: '00000000-0000-0000-0000-000000000002',
    problemId: '00000000-0000-0000-0000-000000000003',
    ticketId: source === 'GUIDE' ? null : '00000000-0000-0000-0000-000000000004',
    source,
    sourceId: '00000000-0000-0000-0000-000000000005',
    sourceVersion: 1,
    requestRevision: 1,
    state: 'PENDING',
    resultSummary,
    confirmationOrigin: 'HOPS_USER',
    createdAt: '2026-11-07T00:00:00Z',
    respondedAt: null,
  };
}

describe('F003 안내 해결 확인', () => {
  it('F003-CONTRACT-12 안내 해결 확인은 채팅의 안내 본문을 다시 펼치지 않고 접어 두며 서식으로 보인다', () => {
    renderWithProviders(
      <ResolutionControls
        request={request('GUIDE', '## 절차\n1. 문서함을 엽니다.\n\n참고 자료: 문서 찾기')}
      />,
    );

    expect(screen.getByText('채팅에서 받은 안내로 해결됐는지 알려 주세요.')).toBeVisible();
    const summary = screen.getByText('안내 다시 보기');
    expect(summary.closest('details')).not.toHaveAttribute('open');
    expect(screen.getByRole('heading', { name: '절차', hidden: true })).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('##');
    expect(screen.getByRole('button', { name: '해결됐어요' })).toBeVisible();
  });

  it('F003-CONTRACT-12 담당자 처리 기록 요약은 펼친 채로 보인다', () => {
    renderWithProviders(
      <ResolutionControls request={request('WORK_RECORD', '권한을 다시 부여했습니다.')} />,
    );

    expect(screen.getByText('권한을 다시 부여했습니다.')).toBeVisible();
    expect(screen.queryByText('안내 다시 보기')).not.toBeInTheDocument();
  });
});
