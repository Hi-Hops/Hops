import type { StandardRequest } from '@/shared/api/types/hopsSystem1';

const detailFields = [
  ['taskOrScreen', '업무·화면', '하려던 업무·화면'],
  ['occurredAt', '발생 시점', '발생 시점'],
  ['requestedAccess', '요청 권한', '필요한 기능·권한'],
  ['businessPurpose', '업무 목적', '업무 목적'],
  ['desiredChange', '원하는 변경', '바라는 변경'],
  ['currentWorkflow', '진행 방식·조건', '현재 진행 방식'],
  ['expectedBehavior', '기대한 동작', '기대한 동작'],
  ['actualBehavior', '실제 현상', '실제 동작'],
] as const;

const factLabels = new Map<string, string>();
for (const [, label, sourceLabel] of detailFields) {
  factLabels.set(label, label);
  factLabels.set(sourceLabel, label);
}
factLabels.set('보고 시스템', '보고 시스템');

const normalize = (text: string) => text.trim().replace(/\s+/gu, ' ');

export function requestOverview(request: StandardRequest) {
  const summary = normalize(request.summary);
  const fields = detailFields.flatMap(([key, label]) => {
    const value = request.details[key]?.trim();
    return value ? [{ key, label, value }] : [];
  });
  const overview = fields
    .filter(
      ({ key, value }) =>
        key !== 'expectedBehavior' && key !== 'actualBehavior' && normalize(value) !== summary,
    )
    .slice(0, 3);
  const compactFields = overview.filter(
    ({ value }) => value.length <= 28 && !/[\r\n]|[.!?。！？]$/u.test(value),
  );
  const fieldValues = new Map<string, string>(
    fields.map(({ label, value }) => [label, normalize(value)]),
  );
  const groups = new Map<string, Map<string, string>>();

  for (const { text } of request.confirmedFacts) {
    const separator = text.indexOf(':');
    const label = separator < 0 ? undefined : factLabels.get(text.slice(0, separator).trim());
    const content = label ? text.slice(separator + 1).trim() : text.trim();
    const key = normalize(content);
    if (!key || (label && fieldValues.get(label) === key)) continue;

    const groupLabel = label ?? '기타 확인 내용';
    const group = groups.get(groupLabel) ?? new Map<string, string>();
    if (!group.has(key)) group.set(key, content);
    groups.set(groupLabel, group);
  }

  return {
    compactFields,
    narrativeFields: overview.filter((field) => !compactFields.includes(field)),
    additionalFields: fields.filter((field) => !overview.includes(field)),
    factGroups: [...groups].map(([label, group]) => ({ label, facts: [...group.values()] })),
  };
}

export function requestSentences(value: string) {
  return value.split(/\r?\n+|(?<=[.!?。！？])[^\S\r\n]+/u).filter((line) => line.trim());
}
