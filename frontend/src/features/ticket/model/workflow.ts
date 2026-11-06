import type { TicketManagementDetail, WorkRecordKind } from '@/shared/api/types/hopsSystem1';
export const processingLabels: Record<string, string> = {
  KNOWLEDGE_QA: '지식 안내',
  SERVICE_REQUEST: '신청·작업',
  INVESTIGATION: '조사',
  INCIDENT: '장애',
  CHANGE_REQUEST: '기능 추가·개선',
  REVIEW_REQUIRED: '검토 필요',
  OUT_OF_SCOPE: '지원 범위 밖',
};
export const workKinds: Record<WorkRecordKind, string> = {
  PROGRESS: '진행 기록',
  FAILED_ATTEMPT: '실패한 시도',
  VERIFIED_RESOLUTION: '처리 완료',
  IMPLEMENTED_VERIFIED: '구현 완료',
};
export function isWorkKind(value: string): value is WorkRecordKind {
  return Object.hasOwn(workKinds, value);
}
export function mutableTicket(ticket: TicketManagementDetail) {
  return ['WAITING', 'IN_PROGRESS', 'WAITING_CONFIRMATION'].includes(ticket.state);
}
export function nullableInput(value: string) {
  return value === '' ? null : value;
}
