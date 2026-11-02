export type SseMessage = { id: string; event: string; data: string };

/** UTF-8·CRLF 청크 경계를 보존하고 빈 줄에서 완성된 이벤트만 전달한다. */
export async function readSse(
  stream: ReadableStream<Uint8Array>,
  receive: (message: SseMessage) => void,
  signal: AbortSignal,
) {
  const stopped = () => signal.aborted;
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let pending = '';
  let event = '';
  let id = '';
  let data: string[] = [];
  const line = (value: string) => {
    if (value === '') {
      if (data.length) receive({ id, event: event || 'message', data: data.join('\n') });
      event = '';
      id = '';
      data = [];
      return;
    }
    if (value.startsWith(':')) return;
    const colon = value.indexOf(':');
    const field = colon < 0 ? value : value.slice(0, colon);
    let content = colon < 0 ? '' : value.slice(colon + 1);
    if (content.startsWith(' ')) content = content.slice(1);
    if (field === 'data') data.push(content);
    else if (field === 'event') event = content;
    else if (field === 'id' && !content.includes('\0')) id = content;
  };
  const abort = () => {
    void reader.cancel().catch(() => undefined);
  };
  signal.addEventListener('abort', abort, { once: true });
  try {
    if (stopped()) return;
    while (!stopped()) {
      const part = await reader.read();
      if (part.done) break;
      pending += decoder.decode(part.value, { stream: true });
      let offset = 0;
      for (let index = 0; index < pending.length; index++) {
        const char = pending[index];
        if (char !== '\r' && char !== '\n') continue;
        if (char === '\r' && index === pending.length - 1) break;
        line(pending.slice(offset, index));
        if (char === '\r' && pending[index + 1] === '\n') index++;
        offset = index + 1;
        if (stopped()) return;
      }
      pending = pending.slice(offset);
      if (pending.length + data.reduce((size, item) => size + item.length, 0) > 1_048_576)
        throw new Error('SSE 이벤트 크기 제한');
    }
    if (!stopped() && pending === '\r') line('');
  } finally {
    signal.removeEventListener('abort', abort);
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}
