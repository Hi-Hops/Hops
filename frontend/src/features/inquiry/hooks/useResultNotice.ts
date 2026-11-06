import { useEffect, useState } from 'react';
import type { InquiryResult } from '@/shared/api/types/hopsSystem1';

const storageKey = (actor: string, inquiryId: string) => `hops:result-notice:${actor}:${inquiryId}`;

function readCreationKey(key: string) {
  try {
    return localStorage.getItem(key) ?? '';
  } catch {
    return '';
  }
}
function writeCreationKey(key: string, creationKey: string) {
  try {
    localStorage.setItem(key, creationKey);
  } catch {
    // 저장소를 쓸 수 없는 브라우저에서는 현재 화면의 상태만으로 안내를 유지한다.
  }
}

// 해결 응답·처리 기록도 resultRevision을 올리므로 revision 대신 접수 결과 내용을 비교한다.
function creationKeyOf(result: InquiryResult | undefined) {
  const created = result?.problems.filter((problem) => problem.creationResult) ?? [];
  if (created.length === 0) return '';
  return JSON.stringify(
    created
      .map((problem) => [
        problem.id,
        problem.creationResult?.workItemId,
        problem.creationResult?.outcome,
        problem.creationResult?.ticketId,
        problem.creationResult?.error?.code,
      ])
      .sort((a, b) => String(a[0]).localeCompare(String(b[0]))),
  );
}

// 접수 결과 모달은 소유자·문의·접수 결과별로 한 번만 안내한다. 모달을 띄운 시점에 기록해
// 닫지 않고 새로고침·재진입해도 같은 결과를 다시 띄우지 않는다. 결과 카드는 계속 남는다.
export function useResultNotice(actor: string, result: InquiryResult | undefined) {
  const key = actor && result ? storageKey(actor, result.id) : '';
  const creationKey = creationKeyOf(result);
  // 저장값은 문의를 열 때 한 번만 읽어, 표시 중 기록해도 열린 모달이 닫히지 않게 한다.
  const [seen, setSeen] = useState({ key: '', creationKey: '' });
  if (seen.key !== key) setSeen({ key, creationKey: key ? readCreationKey(key) : '' });
  const noticeOpen =
    Boolean(key) && seen.key === key && Boolean(creationKey) && creationKey !== seen.creationKey;
  useEffect(() => {
    if (noticeOpen) writeCreationKey(key, creationKey);
  }, [creationKey, key, noticeOpen]);
  return {
    noticeOpen,
    closeNotice: () => {
      setSeen({ key, creationKey });
    },
  };
}
