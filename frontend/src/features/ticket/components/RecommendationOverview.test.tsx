import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import type { AssistanceMaterial } from '@/shared/api/types/hopsSystem1';
import { RecommendationOverview } from './RecommendationOverview';
function material(score: number | null): AssistanceMaterial {
  return {
    evidence: {
      id: crypto.randomUUID(),
      documentId: crypto.randomUUID(),
      documentVersion: 1,
      kind: 'CASE',
      title: '문서 조회 사례',
      excerpt: '자료 원문',
      systemId: crypto.randomUUID(),
      appliesToSystemVersion: null,
      applicability: '조회 조건',
      accessScope: 'SYSTEM_STAFF',
      origin: 'SYNTHETIC',
      recommendation: {
        query: '문의',
        score: 0.8 / 14 + (score === null ? 0 : 1 / 27),
        scoreMethod: 'WEIGHTED_RRF',
        keywordRank: 4,
        semanticRank: score === null ? null : 17,
        keywordScore: 1,
        semanticSimilarity: score,
        matchedKeywords: [],
        reasons: ['키워드·의미 검색 후보입니다.'],
        comparisons: [],
      },
    },
    assessment: {
      relevance: 'MEDIUM',
      family: '조회 조건',
      reasons: [],
      conditions: [
        {
          key: 'state',
          label: '조회 상태',
          status: 'MATCHED',
          currentEvidence: ['승인'],
          caseEvidence: '조회 상태: 승인',
        },
        {
          key: 'document',
          label: '문서 상태',
          status: 'UNKNOWN',
          currentEvidence: [],
          caseEvidence: '문서 상태: 상신',
        },
      ],
      quotePairs: [],
      sections: [],
      nextChecks: [],
      matched: 1,
      unknown: 1,
      mismatched: 0,
    },
  };
}
describe('F009 추천 즉시 시각화', () => {
  it.each([-0.5, 0, 0.633, 1])(
    'F009-CONTRACT-08 의미 유사도 %s를 확률로 바꾸지 않고 원점수로 표시한다',
    (score) => {
      render(<RecommendationOverview item={material(score)} />);
      expect(screen.getByText(score.toFixed(3))).toBeVisible();
      const meter = screen.getByRole('meter', { name: 'RAG 의미 유사도' });
      expect(meter).toHaveAttribute('aria-valuemin', '-1');
      expect(meter).toHaveAttribute('aria-valuemax', '1');
      expect(meter).toHaveAttribute('aria-valuenow', String(score));
    },
  );
  it('F009-CONTRACT-08 의미 점수 미제공을 0이나 성공률로 채우지 않는다', () => {
    render(<RecommendationOverview item={material(null)} />);
    expect(screen.queryByRole('meter')).not.toBeInTheDocument();
    expect(screen.getAllByText('미제공').length).toBeGreaterThan(0);
    expect(screen.queryByText('0.000')).not.toBeInTheDocument();
  });
  it('F009-CONTRACT-08 이름·현재 값·판정으로 조건별 의미를 설명한다', () => {
    render(<RecommendationOverview item={material(0.633)} />);
    const list = within(screen.getByRole('list', { name: '조건별 일치 현황' }));
    const rows = list.getAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('조회 상태');
    expect(rows[0]).toHaveTextContent('일치');
    expect(rows[0]).toHaveTextContent('승인');
    expect(rows[1]).toHaveTextContent('문서 상태');
    expect(rows[1]).toHaveTextContent('미확인');
    expect(rows[1]).not.toHaveTextContent('?');
  });
  it('F009-CONTRACT-08 서술형 조건은 긴 문의 나열 대신 직접 대조 안내와 상세 진입을 제공한다', async () => {
    const item = material(0.649);
    const paragraph = '현재 진행 방식: 오늘 기안하고 상신했으며 조회 기간에는 오늘이 포함됩니다.';
    item.assessment = {
      ...item.assessment,
      relevance: 'REFERENCE',
      matched: 0,
      unknown: 1,
      conditions: [
        {
          key: 'APPLICABILITY',
          label: '나머지 적용 조건',
          status: 'UNKNOWN',
          currentEvidence: [paragraph, paragraph],
          caseEvidence: '기안자·결재선·참조에 포함되어 있는지 기록을 확인한다.',
        },
      ],
    };
    const onCompare = vi.fn();
    render(<RecommendationOverview item={item} onCompare={onCompare} />);
    expect(screen.getByText('직접 대조 필요')).toBeVisible();
    expect(screen.getByText('서술형 적용 조건')).toBeVisible();
    expect(screen.queryByText(paragraph)).not.toBeInTheDocument();
    expect(screen.queryByText(/1개 일치/)).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: /비교 근거 보기/ }));
    expect(onCompare).toHaveBeenCalledOnce();
  });
});
