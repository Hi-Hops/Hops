import type { CaseDetail, IndexStore } from '@/shared/api/types/hopsSystem1';
export function currentIndex(detail: CaseDetail, store: IndexStore) {
  const matches = detail.indexes.filter(
    (item) =>
      item.store === store &&
      item.caseId === detail.version.id &&
      item.caseVersion === detail.version.caseVersion,
  );
  return matches.length === 1 ? matches[0] : undefined;
}
export function searchableCase(detail: CaseDetail) {
  const version = detail.version;
  return (
    version.state === 'APPROVED' &&
    detail.approval?.caseId === version.id &&
    detail.approval.caseVersion === version.caseVersion &&
    detail.publication.caseId === version.id &&
    detail.publication.caseVersion === version.caseVersion &&
    detail.publication.state === 'SEARCHABLE' &&
    detail.publication.canSearch &&
    currentIndex(detail, 'VECTOR')?.state === 'SUCCEEDED' &&
    currentIndex(detail, 'NEO4J')?.state === 'SUCCEEDED'
  );
}
