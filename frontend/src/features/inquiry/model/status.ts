import type { ProcessingStatus } from '@/shared/api/types';
import type { Tone } from '@/shared/ui/StatusBadge';
type Display = { label: string; tone: Tone };
export const processing: Record<ProcessingStatus, Display> = {
  WAITING: { label: '처리 대기', tone: 'neutral' },
  IN_PROGRESS: { label: '처리 중', tone: 'progress' },
  WAITING_CONFIRM: { label: '해결 확인 대기', tone: 'attention' },
  RESOLVED: { label: '해결 완료', tone: 'success' },
  CLOSED_UNCONFIRMED: { label: '미확인 종료', tone: 'neutral' },
};
// Q-ALL-2: 운영 코드 합의 전 Mock 프로필 표시명이며 미등록 값도 보존한다.
const routing: Record<string, Display> = {
  ASSIGNED: { label: '부서 배정됨', tone: 'neutral' },
  CS_REVIEW: { label: 'CS 검토', tone: 'attention' },
  REASSIGNING: { label: '재배정 중', tone: 'progress' },
  EXCEPTION: { label: '배정 예외', tone: 'danger' },
  RECOVERY_PENDING: { label: '복구 대기', tone: 'attention' },
};
const problem: Record<string, Display> = {
  ANALYZING: { label: '분석 중', tone: 'progress' },
  WAITING_USER: { label: '추가 질문 중', tone: 'attention' },
  TICKET_CREATED: { label: '티켓 생성·배정됨', tone: 'success' },
  CREATION_FAILED: { label: '생성 실패', tone: 'danger' },
  OUT_OF_SCOPE: { label: '지원 범위 밖', tone: 'neutral' },
};
export function routingDisplay(value: string): Display {
  return routing[value] ?? { label: `확인되지 않은 배정 상태 (${value})`, tone: 'neutral' };
}
export function problemDisplay(value: string): Display {
  return problem[value] ?? { label: `확인되지 않은 문제 상태 (${value})`, tone: 'neutral' };
}
