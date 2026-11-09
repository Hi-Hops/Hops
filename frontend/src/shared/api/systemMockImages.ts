import { mockError } from './mockError';
import { isUuid } from './wire';
import type { ImageAttachment, ImageAttachmentMetadata } from './types/hopsSystem1';

type MockImage = {
  id: string;
  ownerId: string;
  blob: Blob;
  createdAt: string;
  inquiryId: string | null;
  messageId: string | null;
};

/** 개발 화면을 위한 메모리 대역. 실제 저장소·이미지 보안 검증을 대신하지 않는다. */
export class SystemMockImages {
  private images = new Map<string, MockImage>();

  reset() {
    this.images.clear();
  }

  upload(ownerId: string, mediaType: unknown, dataBase64: unknown): string {
    if (
      mediaType !== 'image/png' ||
      typeof dataBase64 !== 'string' ||
      dataBase64.length === 0 ||
      dataBase64.length > Math.ceil((8 * 1024 * 1024) / 3) * 4
    )
      mockError(400, 'INVALID_INPUT', 'PNG 이미지와 크기를 확인해 주세요.');
    let decoded: string;
    try {
      decoded = atob(dataBase64);
    } catch {
      mockError(400, 'INVALID_INPUT', 'PNG 이미지 형식을 확인해 주세요.');
    }
    if (
      decoded.length === 0 ||
      decoded.length > 8 * 1024 * 1024 ||
      !decoded.startsWith('\x89PNG\r\n\x1a\n')
    )
      mockError(400, 'INVALID_INPUT', 'PNG 이미지 형식을 확인해 주세요.');
    const bytes = Uint8Array.from(decoded, (character) => character.charCodeAt(0));
    const id = crypto.randomUUID();
    this.images.set(id, {
      id,
      ownerId,
      blob: new Blob([bytes.buffer], { type: 'image/png' }),
      createdAt: new Date().toISOString(),
      inquiryId: null,
      messageId: null,
    });
    return id;
  }

  validate(ownerId: string, ids: unknown, existingCount: number): string[] {
    if (ids === undefined) return [];
    if (
      !Array.isArray(ids) ||
      ids.length > 5 ||
      !ids.every(isUuid) ||
      new Set(ids).size !== ids.length ||
      existingCount + ids.length > 5
    )
      mockError(400, 'INVALID_INPUT', '첨부 이미지 목록과 개수를 확인해 주세요.');
    for (const id of ids) {
      const image = this.images.get(id);
      if (image?.ownerId !== ownerId) mockError(404, 'NOT_FOUND', '이미지를 찾을 수 없어요.');
      if (image.messageId !== null) mockError(409, 'INVALID_STATE', '이미 연결된 이미지예요.');
    }
    return ids;
  }

  bind(ids: string[], inquiryId: string, messageId: string): ImageAttachment[] {
    return ids.map((id) => {
      const image = this.images.get(id);
      if (!image) throw new Error('검증된 이미지가 없어요.');
      image.inquiryId = inquiryId;
      image.messageId = messageId;
      return { id, mediaType: 'image/png', byteSize: image.blob.size };
    });
  }

  metadata(ownerId: string, id: string): ImageAttachmentMetadata {
    const image = this.owned(ownerId, id);
    if (!image.messageId) mockError(404, 'NOT_FOUND', '연결된 이미지를 찾을 수 없어요.');
    return {
      id,
      mediaType: 'image/png',
      byteSize: image.blob.size,
      state: 'BOUND',
      createdAt: image.createdAt,
      expiresAt: null,
      messageId: image.messageId,
      linkRevision: 0,
      problemIds: [],
    };
  }

  content(ownerId: string, inquiryId: string, id: string): Blob {
    const image = this.owned(ownerId, id);
    if (image.inquiryId !== inquiryId || !image.messageId)
      mockError(404, 'NOT_FOUND', '이미지를 찾을 수 없어요.');
    return image.blob;
  }

  private owned(ownerId: string, id: string): MockImage {
    const image = this.images.get(id);
    if (image?.ownerId !== ownerId) mockError(404, 'NOT_FOUND', '이미지를 찾을 수 없어요.');
    return image;
  }
}
