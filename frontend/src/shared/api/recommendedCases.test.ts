import { describe, expect, it } from 'vitest';
import { readRecommendedCases } from './systemWire';
import type { RecommendedCase } from './types/hopsSystem1';

function result(): RecommendedCase {
  return {
    id: crypto.randomUUID(),
    documentId: crypto.randomUUID(),
    documentVersion: 2,
    kind: 'CASE',
    title: '상신 문서 조회 조건 조정',
    excerpt: '승인된 사례 원문',
    systemId: crypto.randomUUID(),
    appliesToSystemVersion: null,
    applicability: '조회 상태가 승인인 경우',
    accessScope: 'SYSTEM_STAFF',
    origin: 'SYNTHETIC',
    recommendation: {
      query: '상신 문서가 안 보여요',
      score: 0.8 / 11 + 1 / 12,
      scoreMethod: 'WEIGHTED_RRF',
      keywordRank: 1,
      semanticRank: 2,
      keywordScore: 4,
      semanticSimilarity: 0.8,
      matchedKeywords: ['문서'],
      reasons: ['문서라는 검색어가 일치합니다.'],
      comparisons: [
        {
          key: 'APPLICABILITY',
          label: '적용 조건',
          status: 'UNKNOWN',
          currentEvidence: [],
          caseEvidence: '조회 상태가 승인인 경우',
        },
      ],
    },
  };
}

describe('F009 추천 사례 설명 전송 계약', () => {
  it('F009-CONTRACT-07 실제 결합 점수·근거·미확인 조건을 읽는다', () => {
    const item = result();
    expect(readRecommendedCases([item])).toEqual([item]);
  });

  it('F009-CONTRACT-07 구 서버의 설명 없는 사례와 빈 결과를 허용한다', () => {
    const item = result();
    delete item.recommendation;
    expect(readRecommendedCases([item])[0]?.recommendation).toBeUndefined();
    expect(readRecommendedCases([{ ...item, recommendation: null }])).toHaveLength(1);
    expect(readRecommendedCases([])).toEqual([]);
  });

  it.each([NaN, Infinity, -1, 95])(
    'F009-CONTRACT-07 점수 %s를 검색 점수로 신뢰하지 않는다',
    (score) => {
      const item = result();
      expect(() =>
        readRecommendedCases([{ ...item, recommendation: { ...item.recommendation, score } }]),
      ).toThrow();
    },
  );

  it('F009-CONTRACT-07 순위 없는 경로의 점수와 알 수 없는 조건 판정은 거부한다', () => {
    const item = result();
    expect(() =>
      readRecommendedCases([
        { ...item, recommendation: { ...item.recommendation, keywordRank: null } },
      ]),
    ).toThrow();
    expect(() =>
      readRecommendedCases([
        {
          ...item,
          recommendation: {
            ...item.recommendation,
            comparisons: [
              {
                key: 'test',
                label: '조건',
                status: 'CERTAIN',
                currentEvidence: [],
                caseEvidence: '조건',
              },
            ],
          },
        },
      ]),
    ).toThrow();
  });
});
