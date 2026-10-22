import type { AnswerDeltaEvent } from '@/shared/api/types';

export class StreamBuffer {
  private seen = new Set<string>();
  private chunks = new Map<string, Map<number, string>>();
  needsRecovery = false;
  constructor(
    readonly version: number,
    readonly runId: string,
  ) {}
  accept(event: AnswerDeltaEvent) {
    if (
      event.inquiryVersion !== this.version ||
      event.runId !== this.runId ||
      this.seen.has(event.eventId)
    )
      return false;
    this.seen.add(event.eventId);
    const { messageId, seq, text } = event.payload;
    const chunks = this.chunks.get(messageId) ?? new Map<number, string>();
    const existing = chunks.get(seq);
    if (existing !== undefined && existing !== text) {
      this.needsRecovery = true;
      return false;
    }
    chunks.set(seq, text);
    this.chunks.set(messageId, chunks);
    return true;
  }
  text(messageId: string) {
    const chunks = this.chunks.get(messageId);
    let seq = 1;
    let result = '';
    while (chunks?.has(seq)) {
      result += chunks.get(seq) ?? '';
      seq += 1;
    }
    return result;
  }
}
