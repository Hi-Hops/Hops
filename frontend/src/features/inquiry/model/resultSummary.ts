import type { InquiryResult, Problem } from '@/shared/api/types/hopsSystem1';

export const QUESTION_LIMIT = 5;

export const isCreationFailed = (p: Problem) =>
  Boolean(p.creationResult && !p.ticket && p.creationResult.outcome !== 'STORED');
export const needsAnswer = (p: Problem) => ['WAITING_USER', 'WAITING_SYSTEM'].includes(p.state);

export function inquiryTitle(inquiry: InquiryResult, firstMessage?: string) {
  const [first, ...rest] = inquiry.problems;
  // 문제 분리 전에는 첫 사용자 메시지의 첫 줄을 제목으로 쓴다.
  const line = firstMessage?.trim().split('\n')[0];
  if (!first) return line?.length ? line : '새 문의';
  return rest.length ? `${first.summary} 외 ${rest.length}건` : first.summary;
}

// 서버가 확인한 문제별 결과만 집계한다. 티켓 생성은 해결 완료와 구분한다.
export function resultCounts(inquiry: InquiryResult) {
  const tickets = inquiry.problems.filter((p) => p.ticket);
  return {
    tickets: tickets.length,
    resolved: tickets.filter((p) => p.ticket?.state === 'RESOLVED').length,
    notCreated: inquiry.problems.filter(
      (p) => isCreationFailed(p) && p.creationResult?.outcome === 'NOT_CREATED',
    ).length,
    checking: inquiry.problems.filter(
      (p) => isCreationFailed(p) && p.creationResult?.outcome !== 'NOT_CREATED',
    ).length,
    asking: inquiry.problems.filter(needsAnswer).length,
  };
}

export function resultMetaParts(inquiry: InquiryResult) {
  const c = resultCounts(inquiry);
  const parts: string[] = [];
  if (c.tickets) parts.push(`티켓 ${c.resolved}/${c.tickets}건 해결`);
  if (c.notCreated) parts.push(`저장 실패 ${c.notCreated}`);
  if (c.checking) parts.push(`접수 확인 중 ${c.checking}`);
  if (c.asking) parts.push(`추가 확인 ${c.asking}`);
  return parts;
}
