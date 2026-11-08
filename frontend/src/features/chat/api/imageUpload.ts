import { savedResource, sendSystemCommand } from '@/shared/api/systemCommands';
import type { DraftImage } from '../model/systemDraftStore';

/** 확인된 PNG만 메모리에서 인코딩한다. 재시도에는 같은 바이트와 요청 키를 사용한다. */
export async function uploadDraftImage(image: DraftImage): Promise<string> {
  const bytes = new Uint8Array(await image.blob.arrayBuffer());
  const chunks: string[] = [];
  for (let i = 0; i < bytes.length; i += 32768) {
    chunks.push(String.fromCharCode(...bytes.subarray(i, i + 32768)));
  }
  const command = {
    path: '/api/v2/image-attachments',
    expectedResource: { type: 'IMAGE_ATTACHMENT' },
    body: { requestId: image.requestId, mediaType: 'image/png', dataBase64: btoa(chunks.join('')) },
  };
  let receipt = await sendSystemCommand(command, image.attempted);
  if (image.attempted && (receipt.state === 'PENDING' || receipt.state === 'UNKNOWN')) {
    receipt = await sendSystemCommand(command);
  }
  return savedResource(receipt, 'IMAGE_ATTACHMENT').id;
}
