import type { Tone } from '@/shared/ui/StatusBadge';
type Display = { label: string; tone: Tone };
const states: Record<string, Display> = {
  WAITING: { label: '처리 대기', tone: 'neutral' },
  IN_PROGRESS: { label: '진행 중', tone: 'progress' },
  WAITING_USER: { label: '사용자 응답 대기', tone: 'attention' },
  WAITING_SYSTEM: { label: '시스템 확인 대기', tone: 'attention' },
  ANALYZING: { label: '분석 중', tone: 'progress' },
  TICKET_PENDING: { label: '접수 결과 확인 중', tone: 'attention' },
  IN_SERVICE: { label: '담당자 처리 중', tone: 'progress' },
  WAITING_CONFIRMATION: { label: '해결 확인 대기', tone: 'attention' },
  RESOLVED: { label: '해결 완료', tone: 'success' },
  OUT_OF_SCOPE: { label: '지원 범위 밖', tone: 'neutral' },
  CLOSED: { label: '지원 범위 밖 안내 종료', tone: 'neutral' },
};
export function systemStatus(value: string): Display {
  return states[value] ?? { label: `상태 확인 필요 (${value})`, tone: 'neutral' };
}
export function editableInquiryStatus(value: string) {
  return ['IN_PROGRESS', 'WAITING_USER'].includes(value);
}
export const ticketStates = {
  WAITING: '처리 대기',
  IN_PROGRESS: '처리 중',
  WAITING_CONFIRMATION: '해결 확인 대기',
  RESOLVED: '해결 완료',
};
