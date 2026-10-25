import { useLayoutEffect, useRef, useState } from 'react';

/** 이전 대화 추가 시 위치를 보존하고, 하단을 보고 있을 때만 새 답변을 따라간다. */
export function useChatScroll(id: string, signature: string, stream: string | undefined) {
  const log = useRef<HTMLDivElement>(null);
  const view = useRef({ id, initialized: false, bottom: true, signature: '', stream: '' });
  const prepend = useRef<{ height: number; top: number } | null>(null);
  const anchor = useRef<{ id: string; top: number } | null>(null);
  const [hasNew, setHasNew] = useState(false);
  useLayoutEffect(() => {
    const node = log.current;
    if (!node || !signature) return;
    if (view.current.id !== id) {
      view.current = { id, initialized: false, bottom: true, signature: '', stream: '' };
      prepend.current = null;
      anchor.current = null;
    }
    if (prepend.current) {
      node.scrollTop = prepend.current.top + node.scrollHeight - prepend.current.height;
      prepend.current = null;
    } else if (!view.current.initialized || view.current.bottom) {
      node.scrollTop = node.scrollHeight;
    } else if (view.current.signature !== signature || view.current.stream !== (stream ?? '')) {
      const currentAnchor = [...node.querySelectorAll<HTMLElement>('[data-message-id]')].find(
        (item) => item.dataset.messageId === anchor.current?.id,
      );
      if (currentAnchor && anchor.current)
        node.scrollTop +=
          currentAnchor.getBoundingClientRect().top -
          node.getBoundingClientRect().top -
          anchor.current.top;
      setHasNew(true);
    }
    view.current.initialized = true;
    view.current.signature = signature;
    view.current.stream = stream ?? '';
  }, [id, signature, stream]);
  function observe() {
    const node = log.current;
    if (!node) return;
    view.current.bottom = node.scrollHeight - node.scrollTop - node.clientHeight < 80;
    const bounds = node.getBoundingClientRect();
    const visible = [...node.querySelectorAll<HTMLElement>('[data-message-id]')].find((item) => {
      const rect = item.getBoundingClientRect();
      return rect.height > 0 && rect.bottom > bounds.top && rect.top < bounds.bottom;
    });
    anchor.current = visible?.dataset.messageId
      ? { id: visible.dataset.messageId, top: visible.getBoundingClientRect().top - bounds.top }
      : null;
    if (view.current.bottom) setHasNew(false);
  }
  function remember() {
    const node = log.current;
    if (node) prepend.current = { height: node.scrollHeight, top: node.scrollTop };
  }
  function bottom() {
    if (log.current) log.current.scrollTop = log.current.scrollHeight;
    view.current.bottom = true;
    setHasNew(false);
  }
  return {
    log,
    hasNew,
    observe,
    remember,
    bottom,
    cancelPrepend: () => {
      prepend.current = null;
    },
  };
}
