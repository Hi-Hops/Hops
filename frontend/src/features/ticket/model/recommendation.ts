import type { CaseConditionComparison } from '@/shared/api/types/hopsSystem1';

export const comparisonLabels: Record<CaseConditionComparison['status'], string> = {
  MATCHED: '일치',
  MISMATCHED: '불일치',
  UNKNOWN: '추가 확인',
};

export const comparisonTones = {
  MATCHED: 'success',
  MISMATCHED: 'danger',
  UNKNOWN: 'attention',
} as const;
