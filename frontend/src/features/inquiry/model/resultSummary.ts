import type { InquiryResult, Problem } from '@/shared/api/types/hopsSystem1';

export const isCreationFailed = (p: Problem) =>
  Boolean(p.creationResult && !p.ticket && p.creationResult.outcome !== 'STORED');
export const isResolvedProblem = (p: Problem) =>
  p.state === 'RESOLVED' || p.ticket?.state === 'RESOLVED';

export const needsAnswer = (p: Problem) => ['WAITING_USER', 'WAITING_SYSTEM'].includes(p.state);

export const needsAction = (p: Problem) =>
  needsAnswer(p) || isCreationFailed(p) || p.activeResolutionRequest?.state === 'PENDING';
// 문의자가 해야 할 일이 있는 문제를 먼저 보여주고, 나머지는 서버 순서를 유지한다.
export const actionFirst = (problems: Problem[]) =>
  [...problems].sort((a, b) => Number(needsAction(b)) - Number(needsAction(a)));

// 좁은 화면의 처리 현황 요약 줄에 표시할, 가장 먼저 처리할 일이다.
export function problemActionLabel(inquiry: InquiryResult) {
  if (inquiry.pendingTargetMessageIds.length) return '답변을 연결할 문제를 선택해 주세요';
  const target = actionFirst(inquiry.problems)[0];
  if (!target || !needsAction(target)) return null;
  if (target.state === 'WAITING_SYSTEM') return '시스템 선택이 필요해요';
  if (target.state === 'WAITING_USER') return 'Hops 질문에 답해 주세요';
  if (isCreationFailed(target)) return '접수 결과 확인이 필요해요';
  return '해결 확인이 필요해요';
}

export function inquiryTitle(inquiry: InquiryResult) {
  const [first, ...rest] = inquiry.problems.filter((problem) => problem.state !== 'OUT_OF_SCOPE');
  if (!first) return inquiry.problems.length ? '지원 범위 안내' : '새 문의';
  return rest.length ? `${first.summary} 외 ${rest.length}건` : first.summary;
}

// 서버가 확인한 문제별 결과만 집계한다. 티켓 생성은 해결 완료와 구분한다.
export function resultCounts(inquiry: InquiryResult) {
  const tickets = inquiry.problems.filter((p) => p.ticket);
  return {
    tickets: tickets.length,
    resolvedProblems: inquiry.problems.filter(isResolvedProblem).length,
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
  if (c.resolvedProblems && inquiry.problems.length > 1)
    parts.push(`문제 ${c.resolvedProblems}/${inquiry.problems.length}건 해결`);
  if (c.notCreated) parts.push(`저장 실패 ${c.notCreated}`);
  if (c.checking) parts.push(`접수 확인 중 ${c.checking}`);
  if (c.asking) parts.push(`응답 필요 ${c.asking}건`);
  return parts;
}
