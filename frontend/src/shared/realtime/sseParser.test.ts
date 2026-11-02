// @vitest-environment node
import { describe, expect, it, vi } from 'vitest';
import { readSse } from './sseParser';
function bytes(text: string, step: number) {
  const data = new TextEncoder().encode(text);
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (let i = 0; i < data.length; i += step) controller.enqueue(data.slice(i, i + step));
      controller.close();
    },
  });
}
describe('F002 SSE 파서', () => {
  it('F002-AC01 CR만 있는 줄 구분도 완성된 빈 줄에서 전달한다', async () => {
    const receive = vi.fn();
    await readSse(
      bytes('id: e1\revent: state.changed\rdata: {}\r\r', 1),
      receive,
      new AbortController().signal,
    );
    expect(receive).toHaveBeenCalledExactlyOnceWith({
      id: 'e1',
      event: 'state.changed',
      data: '{}',
    });
  });
  it.each([1, 2, 7, 1000])(
    'F002-AC01 UTF-8·CRLF 경계와 여러 data 줄·heartbeat를 처리한다 (%i bytes)',
    async (step) => {
      const receive = vi.fn();
      await readSse(
        bytes(
          ': heartbeat\r\nid: e1\r\nevent: answer.delta\r\ndata: {"text":\r\ndata: "한글😀"}\r\n\r\n',
          step,
        ),
        receive,
        new AbortController().signal,
      );
      expect(receive).toHaveBeenCalledExactlyOnceWith({
        id: 'e1',
        event: 'answer.delta',
        data: '{"text":\n"한글😀"}',
      });
    },
  );
  it('F002-AC06 빈 줄 전에 끊긴 이벤트를 완료 처리하지 않는다', async () => {
    const receive = vi.fn();
    await readSse(
      bytes('id: e1\nevent: state.changed\ndata: {}', 1),
      receive,
      new AbortController().signal,
    );
    expect(receive).not.toHaveBeenCalled();
  });
  it('F002-AC10 AbortSignal이 reader 대기를 취소한다', async () => {
    const cancel = vi.fn();
    const controller = new AbortController();
    const promise = readSse(new ReadableStream<Uint8Array>({ cancel }), vi.fn(), controller.signal);
    controller.abort();
    await promise;
    expect(cancel).toHaveBeenCalledOnce();
  });
});
