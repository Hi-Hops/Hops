import { expect, it } from 'vitest';
import { readImageMetadata, readMessages, readTicketImages } from './systemWire';

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

it('이미지 공개 대상과 담당자 목록의 형식을 검증한다', () => {
  const id = crypto.randomUUID();
  const metadata = {
    id,
    state: 'BOUND',
    mediaType: 'image/png',
    byteSize: 24,
    createdAt: '2026-11-08T00:00:00Z',
    expiresAt: null,
    messageId: crypto.randomUUID(),
    linkRevision: 1,
    problemIds: [crypto.randomUUID()],
  };
  expect(readImageMetadata(metadata)).toEqual(metadata);
  expect(() =>
    readImageMetadata({
      ...metadata,
      problemIds: [metadata.problemIds[0], metadata.problemIds[0]],
    }),
  ).toThrow();
  expect(() => readImageMetadata({ ...metadata, messageId: null })).toThrow();
  const attachment = { id, mediaType: 'image/png', byteSize: 24 };
  expect(readTicketImages([attachment])).toEqual([attachment]);
  expect(() => readTicketImages([attachment, attachment])).toThrow();
});

it('이미지 관찰 확인 요청의 형식과 메시지 대상을 검증한다', () => {
  const message = {
    id: crypto.randomUUID(),
    inquiryId: crypto.randomUUID(),
    requestId: null,
    revision: 1,
    sequenceNo: 1,
    sender: 'AI',
    content: '오류 화면인가요?',
    problemIds: [],
    revisions: [],
    createdAt: '2026-11-08T00:00:00Z',
  };
  const observation = {
    observationId: crypto.randomUUID(),
    observationRevision: 1,
    attachmentId: crypto.randomUUID(),
    problemId: crypto.randomUUID(),
    text: '오류 화면',
    state: 'UNCONFIRMED',
  };
  expect(
    readMessages({
      items: [{ ...message, imageObservationQuestions: [observation] }],
      nextBeforeSequenceNo: null,
    }).items[0]?.imageObservationQuestions,
  ).toEqual([observation]);
  for (const bad of [
    [{ ...observation, state: 'RESOLVED' }],
    [{ ...observation, observationRevision: 0 }],
    [observation, observation],
    [{ ...observation, text: '가'.repeat(401) }],
  ]) {
    expect(() =>
      readMessages({
        items: [{ ...message, imageObservationQuestions: bad }],
        nextBeforeSequenceNo: null,
      }),
    ).toThrow();
  }
  expect(() =>
    readMessages({
      items: [{ ...message, sender: 'USER', imageObservationQuestions: [observation] }],
      nextBeforeSequenceNo: null,
    }),
  ).toThrow();
});
