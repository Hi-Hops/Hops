import type { InquiryStatus, ProcessingStatus } from '@/shared/api/types';
import type { Tone } from '@/shared/ui/StatusBadge';
type Display = { label: string; tone: Tone };
export const inquiryStatus: Record<InquiryStatus, Display> = {
  IN_PROGRESS: { label: '진행 중', tone: 'progress' },
  WAITING_USER: { label: '추가 확인 중', tone: 'attention' },
  RESOLVED: { label: '해결 완료', tone: 'success' },
  CLOSED_UNCONFIRMED: { label: '미확인 종료', tone: 'neutral' },
  CLOSED: { label: '종료', tone: 'neutral' },
};
export const processing: Record<ProcessingStatus, Display> = {
  WAITING: { label: '처리 대기', tone: 'neutral' },
  IN_PROGRESS: { label: '처리 중', tone: 'progress' },
  WAITING_CONFIRM: { label: '해결 확인 대기', tone: 'attention' },
  RESOLVED: { label: '해결 완료', tone: 'success' },
  CLOSED_UNCONFIRMED: { label: '미확인 종료', tone: 'neutral' },
};
// DATA_TYPES의 개발 계약 표시명. 미등록 값은 성공으로 추정하지 않는다.
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
export function processingDisplay(value: string): Display {
  const known: Readonly<Record<string, Display | undefined>> = processing;
  return (
    known[value] ?? {
      label: `확인되지 않은 처리 상태 (${value})`,
      tone: 'neutral',
    }
  );
}
export function problemDisplay(value: string): Display {
  return problem[value] ?? { label: `확인되지 않은 문제 상태 (${value})`, tone: 'neutral' };
}

const run: Record<string, Display> = {
  QUEUED: { label: '분석 대기', tone: 'attention' },
  RUNNING: { label: '분석 중', tone: 'progress' },
  SUCCEEDED: { label: '분석 완료', tone: 'neutral' },
  FAILED: { label: '분석 실패', tone: 'danger' },
  TIMED_OUT: { label: '분석 시간 초과', tone: 'danger' },
  SUPERSEDED: { label: '최신 입력으로 대체됨', tone: 'neutral' },
};
export function runDisplay(value: string): Display {
  return run[value] ?? { label: `확인되지 않은 실행 상태 (${value})`, tone: 'neutral' };
}
