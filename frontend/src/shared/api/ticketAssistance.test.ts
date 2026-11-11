import { describe, expect, it } from 'vitest';
import { readTicketAssistance } from './systemWire';
import type { TicketAssistance } from './types/hopsSystem1';
function fixture(): TicketAssistance {
  const messageId = crypto.randomUUID();
  const current = '📄 조회 상태: 승인';
  const source = '조회 상태: 승인';
  return {
    ticketId: crypto.randomUUID(),
    ticketRevision: 1,
    standardRequestId: crypto.randomUUID(),
    query: '현재 문의',
    sources: [
      {
        id: messageId,
        revision: 1,
        sender: 'USER',
        content: current,
        revisions: [],
        createdAt: new Date().toISOString(),
      },
    ],
    materials: [
      {
        evidence: {
          id: crypto.randomUUID(),
          documentId: crypto.randomUUID(),
          documentVersion: 1,
          kind: 'CASE',
          title: '조회 조건 사례',
          excerpt: source,
          systemId: crypto.randomUUID(),
          appliesToSystemVersion: null,
          applicability: source,
          accessScope: 'SYSTEM_STAFF',
          origin: 'SYNTHETIC',
        },
        assessment: {
          relevance: 'MEDIUM',
          family: '조회 상태',
          reasons: ['조회 상태가 같습니다.'],
          conditions: [
            {
              key: 'state',
              label: '조회 상태',
              status: 'MATCHED',
              currentEvidence: ['승인'],
              caseEvidence: source,
            },
          ],
          quotePairs: [
            {
              label: '조회 상태',
              status: 'MATCHED',
              messageId,
              currentQuote: source,
              currentStart: 3,
              currentEnd: current.length,
              sourceQuote: source,
              sourceStart: 0,
              sourceEnd: source.length,
            },
          ],
          sections: [{ label: '절차', quote: source, start: 0, end: source.length }],
          nextChecks: ['문서 상태를 확인하세요.'],
          matched: 1,
          unknown: 0,
          mismatched: 0,
        },
      },
    ],
  };
}
describe('F009 담당자 근거·원문 전송', () => {
  it('F009-CONTRACT-09 묶인 사례도 각 원문·판정·승인 출처를 검증한다', () => {
    const value = fixture();
    const parent = value.materials[0];
    if (!parent) throw new Error('fixture');
    const child = structuredClone(parent);
    child.evidence.id = crypto.randomUUID();
    child.evidence.documentId = crypto.randomUUID();
    child.sourceCase = {
      caseNumber: 'C-new',
      ticketId: crypto.randomUUID(),
      ticketNumber: 'T-0095',
      approvedAt: '2026-11-10T15:22:08Z',
    };
    parent.groupedCases = [structuredClone(parent), child];
    expect(
      readTicketAssistance(value).materials[0]?.groupedCases?.[1]?.sourceCase?.ticketNumber,
    ).toBe('T-0095');
    const section = child.assessment.sections[0];
    if (!section) throw new Error('fixture');
    section.quote = '다른 사례의 원문';
    expect(() => readTicketAssistance(value)).toThrow('문서 원문 구간 불일치');
  });
  it('F009-CONTRACT-09 다른 시스템·중복 멤버·대표 누락 그룹은 거부한다', () => {
    for (const mode of ['system', 'duplicate', 'missing']) {
      const value = fixture();
      const parent = value.materials[0];
      if (!parent) throw new Error('fixture');
      const child = structuredClone(parent);
      child.evidence.id = crypto.randomUUID();
      parent.groupedCases = [structuredClone(parent), child];
      if (mode === 'system') child.evidence.systemId = crypto.randomUUID();
      if (mode === 'duplicate') child.evidence.id = parent.evidence.id;
      if (mode === 'missing') parent.evidence.id = crypto.randomUUID();
      expect(() => readTicketAssistance(value)).toThrow('묶인 사례 범위 불일치');
    }
  });
  it('F009-CONTRACT-08 UTF-16 원문 위치와 문서 버전의 실제 구간만 읽는다', () => {
    expect(readTicketAssistance(fixture()).materials).toHaveLength(1);
  });
  it('F009-CONTRACT-08 조작한 인용·범위 초과·다른 문의 원문은 거부한다', () => {
    for (const patch of [
      { currentQuote: '없는 원문' },
      { sourceEnd: 9999 },
      { messageId: crypto.randomUUID() },
      { status: 'MISMATCHED' },
    ]) {
      const value = fixture();
      const pair = value.materials[0]?.assessment.quotePairs[0];
      if (!pair) throw new Error('fixture');
      Object.assign(pair, patch);
      expect(() => readTicketAssistance(value)).toThrow();
    }
  });
  it('F009-CONTRACT-08 조건 부족을 높은 일치로 표시하지 않는다', () => {
    const value = fixture();
    if (value.materials[0]) value.materials[0].assessment.relevance = 'HIGH';
    expect(() => readTicketAssistance(value)).toThrow();
  });
});
