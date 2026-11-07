export type ImageMask = { x: number; y: number; width: number; height: number };

const maxBytes = 5 * 1024 * 1024;
const maxDimension = 8192;
const maxPixels = 16_000_000;

/** 이미지는 브라우저 메모리에서만 읽고, 가린 픽셀을 새 PNG에 그려 원본 메타데이터를 전달하지 않는다. */
export async function maskImage(file: File, masks: readonly ImageMask[]): Promise<Blob> {
  if (!['image/png', 'image/jpeg'].includes(file.type) || file.size === 0 || file.size > maxBytes)
    throw new Error('PNG 또는 JPEG 이미지를 5MB 이하로 선택해 주세요.');
  const bitmap = await createImageBitmap(file);
  try {
    if (
      !bitmap.width ||
      !bitmap.height ||
      bitmap.width > maxDimension ||
      bitmap.height > maxDimension ||
      bitmap.width * bitmap.height > maxPixels
    )
      throw new Error('이미지의 가로·세로 크기가 허용 범위를 넘었어요.');
    const canvas = document.createElement('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('이미지 가림 처리를 시작하지 못했어요.');
    context.drawImage(bitmap, 0, 0);
    context.fillStyle = '#000000';
    for (const mask of masks) {
      const { x, y, width, height } = mask;
      if (
        ![x, y, width, height].every(Number.isFinite) ||
        x < 0 ||
        y < 0 ||
        x >= bitmap.width ||
        y >= bitmap.height ||
        width <= 0 ||
        height <= 0 ||
        x + width > bitmap.width ||
        y + height > bitmap.height
      )
        throw new Error('이미지 가림 영역을 다시 지정해 주세요.');
      const left = Math.floor(x);
      const top = Math.floor(y);
      const right = Math.min(bitmap.width, Math.max(left + 1, Math.ceil(x + width)));
      const bottom = Math.min(bitmap.height, Math.max(top + 1, Math.ceil(y + height)));
      context.fillRect(left, top, right - left, bottom - top);
    }
    const blob = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob((result) => {
        if (result?.type !== 'image/png' || !result.size || result.size > maxBytes) {
          reject(new Error('가린 이미지를 5MB 이하로 만들지 못했어요.'));
          return;
        }
        resolve(result);
      }, 'image/png');
    });
    return blob;
  } finally {
    bitmap.close();
  }
}
