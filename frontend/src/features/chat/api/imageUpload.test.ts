import { afterEach, expect, it, vi } from 'vitest';
import * as commands from '@/shared/api/systemCommands';
import { uploadDraftImage } from './imageUpload';

afterEach(() => {
  vi.restoreAllMocks();
});

it('F003-CONTRACT 접수 중인 이미지는 같은 요청 키와 같은 가림 바이트로 다시 확인한다', async () => {
  const requestId = crypto.randomUUID();
  const attachmentId = crypto.randomUUID();
  const blob = new Blob(['masked'], { type: 'image/png' });
  Object.defineProperty(blob, 'arrayBuffer', {
    value: () => Promise.resolve(new Uint8Array([1, 2, 3]).buffer),
  });
  const send = vi
    .spyOn(commands, 'sendSystemCommand')
    .mockResolvedValueOnce({
      requestId,
      operationId: crypto.randomUUID(),
      state: 'PENDING',
      resource: null,
      error: null,
    })
    .mockResolvedValueOnce({
      requestId,
      operationId: crypto.randomUUID(),
      state: 'SUCCEEDED',
      resource: { type: 'IMAGE_ATTACHMENT', id: attachmentId },
      error: null,
    });
  await expect(
    uploadDraftImage({ blob, requestId, attempted: true, attachmentId: null }),
  ).resolves.toBe(attachmentId);
  expect(send).toHaveBeenCalledTimes(2);
  expect(send.mock.calls[0]?.[1]).toBe(true);
  expect(send.mock.calls[1]?.[1]).toBeUndefined();
  expect(send.mock.calls[0]?.[0]).toEqual(send.mock.calls[1]?.[0]);
  expect(send.mock.calls[1]?.[0].body).toMatchObject({
    requestId,
    mediaType: 'image/png',
    dataBase64: 'AQID',
  });
});
