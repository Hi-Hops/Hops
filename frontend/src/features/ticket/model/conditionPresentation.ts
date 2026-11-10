import type { CaseConditionComparison } from '@/shared/api/types/hopsSystem1';

/** APPLICABILITY는 서버가 자유서술 전체를 자동 판정하지 않고 남기는 대조 항목이다. */
export const isNarrativeCondition = (condition: CaseConditionComparison) =>
  condition.key === 'APPLICABILITY' && condition.status === 'UNKNOWN';

export function conditionValue(condition: CaseConditionComparison): string {
  return condition.caseEvidence.startsWith(condition.label + ':')
    ? condition.caseEvidence.slice(condition.label.length + 1).trim()
    : condition.caseEvidence;
}

/** 표시상 중복만 제거하며 서로 다른 값이나 문장 내부의 쉼표는 그대로 보존한다. */
export function distinctStatements(statements: string[]): string[] {
  const seen = new Set<string>();
  return statements.filter((text) => {
    const key = text.trim().replace(/\s+/gu, ' ');
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function statementParts(text: string): { label: string; value: string } | null {
  const match = /^([^:：\n]{1,28})[:：]\s*([\s\S]+)$/u.exec(text);
  return match?.[1] && match[2] ? { label: match[1].trim(), value: match[2] } : null;
}
