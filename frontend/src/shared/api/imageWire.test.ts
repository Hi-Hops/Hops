import { expect, it } from 'vitest';
import { readMessages } from './systemWire';

it('F003-CONTRACT 기존 대화와 정상 첨부를 수용하고 잘못된 이미지 메타데이터는 거부한다', () => {
  const message = {
    id: crypto.randomUUID(),
    inquiryId: crypto.randomUUID(),
    requestId: crypto.randomUUID(),
    revision: 1,
    sequenceNo: 1,
    sender: 'USER',
    content: '화면 오류',
    problemIds: [],
    revisions: [],
    createdAt: '2026-11-08T00:00:00Z',
  };
  expect(readMessages({ items: [message], nextBeforeSequenceNo: null }).items).toHaveLength(1);
  const attachment = { id: crypto.randomUUID(), mediaType: 'image/png', byteSize: 1024 };
  expect(
    readMessages({ items: [{ ...message, attachments: [attachment] }], nextBeforeSequenceNo: null })
      .items[0]?.attachments,
  ).toEqual([attachment]);
  for (const bad of [
    [{ ...attachment, mediaType: 'image/svg+xml' }],
    [{ ...attachment, byteSize: 8 * 1024 * 1024 + 1 }],
    Array.from({ length: 6 }, () => attachment),
    null,
  ]) {
    expect(() =>
      readMessages({ items: [{ ...message, attachments: bad }], nextBeforeSequenceNo: null }),
    ).toThrow();
  }
});
