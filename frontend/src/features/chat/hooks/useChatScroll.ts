import { useLayoutEffect, useRef } from 'react';

/** 새 메시지·진행·답변은 최신으로 이동하고, 이전 대화 추가 시에는 읽던 위치를 보존한다. */
export function useChatScroll(id: string, signature: string, activity: string) {
  const log = useRef<HTMLDivElement>(null);
  const view = useRef({ id, signature: '' });
  const prepend = useRef<{ height: number; top: number } | null>(null);
  useLayoutEffect(() => {
    const node = log.current;
    if (!node) return;
    if (view.current.id !== id) {
      view.current = { id, signature: '' };
      prepend.current = null;
    }
    const previous = view.current.signature;
    const addedOlder =
      prepend.current && previous && signature !== previous && signature.endsWith(`,${previous}`);
    if (addedOlder && prepend.current) {
      node.scrollTop = prepend.current.top + node.scrollHeight - prepend.current.height;
      prepend.current = null;
    } else {
      node.scrollTop = node.scrollHeight;
      // 과거 조회 중 도착한 새 내용도 따라간 뒤, 실제 과거 페이지가 붙을 때의 기준을 갱신한다.
      if (prepend.current) prepend.current = { height: node.scrollHeight, top: node.scrollTop };
    }
    view.current.signature = signature;
  }, [id, signature, activity]);
  function remember() {
    const node = log.current;
    if (node) prepend.current = { height: node.scrollHeight, top: node.scrollTop };
  }
  function observe() {
    if (prepend.current) remember();
  }
  function bottom() {
    if (log.current) log.current.scrollTop = log.current.scrollHeight;
  }
  return {
    log,
    observe,
    remember,
    bottom,
    cancelPrepend: () => {
      prepend.current = null;
    },
  };
}
