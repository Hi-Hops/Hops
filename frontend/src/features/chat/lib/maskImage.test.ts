import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { maskImage } from './maskImage';

describe('maskImage', () => {
  const close = vi.fn();
  const drawImage = vi.fn();
  const fillRect = vi.fn();
  const context = { drawImage, fillRect, fillStyle: '' };
  const encode = vi.fn((callback: BlobCallback) => {
    callback(new Blob(['masked'], { type: 'image/png' }));
  });

  beforeEach(() => {
    vi.stubGlobal(
      'createImageBitmap',
      vi.fn().mockResolvedValue({ width: 100, height: 60, close }),
    );
    context.fillStyle = '';
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(
      context as unknown as CanvasRenderingContext2D,
    );
    encode.mockClear();
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(encode);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    close.mockClear();
    drawImage.mockClear();
    fillRect.mockClear();
  });

  it('원본 이미지를 그리고 지정 영역 전체를 불투명하게 가린 뒤 새 PNG를 만든다', async () => {
    const file = new File(['original'], 'image.jpg', { type: 'image/jpeg' });
    const result = await maskImage(file, [{ x: 1.2, y: 2.4, width: 4, height: 5 }]);
    expect(drawImage).toHaveBeenCalledOnce();
    expect(fillRect).toHaveBeenCalledWith(1, 2, 5, 6);
    expect(context.fillStyle).toBe('#000000');
    expect(encode).toHaveBeenCalledWith(expect.any(Function), 'image/png');
    expect(result.type).toBe('image/png');
    expect(close).toHaveBeenCalledOnce();
  });

  it('이미지 밖 가림 영역은 출력하지 않는다', async () => {
    const file = new File(['original'], 'image.png', { type: 'image/png' });
    await expect(maskImage(file, [{ x: 99, y: 1, width: 2, height: 1 }])).rejects.toThrow();
    expect(encode).not.toHaveBeenCalled();
    expect(close).toHaveBeenCalledOnce();
  });

  it('매우 작은 양의 영역도 최소 한 픽셀을 가린다', async () => {
    const file = new File(['original'], 'image.png', { type: 'image/png' });
    await maskImage(file, [{ x: 10, y: 20, width: Number.MIN_VALUE, height: Number.MIN_VALUE }]);
    expect(fillRect).toHaveBeenCalledWith(10, 20, 1, 1);
  });

  it('지원하지 않는 형식과 빈 파일은 디코딩 전에 거부한다', async () => {
    await expect(
      maskImage(new File(['x'], 'image.svg', { type: 'image/svg+xml' }), []),
    ).rejects.toThrow();
    await expect(maskImage(new File([], 'image.png', { type: 'image/png' }), [])).rejects.toThrow();
    expect(createImageBitmap).not.toHaveBeenCalled();
  });
});
