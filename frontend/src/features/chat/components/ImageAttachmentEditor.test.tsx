import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { maskImage } from '../lib/maskImage';
import { ImageAttachmentEditor } from './ImageAttachmentEditor';

vi.mock('../lib/maskImage', () => ({ maskImage: vi.fn() }));

beforeEach(() => {
  let id = 0;
  class TestURL extends URL {
    static override createObjectURL = vi.fn(() => `blob:test-${String(id++)}`);
    static override revokeObjectURL = vi.fn();
  }
  vi.stubGlobal('URL', TestURL);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it('두 이미지의 가림 결과를 확인하기 전에는 첨부하지 않는다', async () => {
  const processed = [
    new Blob(['first'], { type: 'image/png' }),
    new Blob(['second'], { type: 'image/png' }),
  ] as const;
  vi.mocked(maskImage).mockResolvedValueOnce(processed[0]).mockResolvedValueOnce(processed[1]);
  const ready = vi.fn();
  const invalidate = vi.fn();
  const user = userEvent.setup();
  render(<ImageAttachmentEditor onReady={ready} onInvalidate={invalidate} />);
  const input = screen.getByLabelText(/이미지 선택/);
  await user.upload(input, [
    new File(['a'], 'first.png', { type: 'image/png' }),
    new File(['b'], 'second.jpg', { type: 'image/jpeg' }),
  ]);
  const previews = screen.getAllByAltText('첨부할 이미지 미리보기');
  for (const image of previews) {
    Object.defineProperty(image, 'naturalWidth', { configurable: true, value: 100 });
    Object.defineProperty(image, 'naturalHeight', { configurable: true, value: 80 });
    fireEvent.load(image);
  }
  const firstArticle = screen.getAllByRole('article')[0];
  if (!firstArticle) throw new Error('첫 이미지가 없습니다.');
  const first = within(firstArticle);
  await user.type(first.getByLabelText('왼쪽'), '2');
  await user.type(first.getByLabelText('위쪽'), '3');
  await user.type(first.getByLabelText('너비'), '4');
  await user.type(first.getByLabelText('높이'), '5');
  await user.click(first.getByRole('button', { name: '영역 추가' }));
  await user.click(screen.getByRole('button', { name: '가린 이미지 미리보기' }));
  const results = await screen.findAllByAltText('전송할 가림 이미지 크게 보기');
  expect(results).toHaveLength(2);
  const checkbox = screen.getByRole('checkbox', { name: /모든 전송 이미지/ });
  expect(checkbox).toBeDisabled();
  for (const image of results) fireEvent.load(image);
  expect(maskImage).toHaveBeenCalledWith(expect.any(File), [{ x: 2, y: 3, width: 4, height: 5 }]);
  expect(ready).not.toHaveBeenCalled();
  const attach = screen.getByRole('button', { name: '가린 이미지 첨부' });
  expect(attach).toBeDisabled();
  await user.click(checkbox);
  await user.click(attach);
  expect(ready).toHaveBeenCalledWith(processed);
  invalidate.mockClear();
  await user.click(first.getByRole('button', { name: '마지막 가림 취소' }));
  expect(invalidate).toHaveBeenCalledOnce();
  expect(screen.queryByRole('button', { name: '가린 이미지 첨부' })).not.toBeInTheDocument();
});

it('이미지 처리가 실패하면 첨부할 결과를 만들지 않는다', async () => {
  vi.mocked(maskImage).mockRejectedValueOnce(new Error('decode'));
  const ready = vi.fn();
  const user = userEvent.setup();
  render(<ImageAttachmentEditor onReady={ready} onInvalidate={vi.fn()} />);
  await user.upload(
    screen.getByLabelText(/이미지 선택/),
    new File(['x'], 'a.png', { type: 'image/png' }),
  );
  const image = screen.getByAltText('첨부할 이미지 미리보기');
  Object.defineProperty(image, 'naturalWidth', { configurable: true, value: 10 });
  Object.defineProperty(image, 'naturalHeight', { configurable: true, value: 10 });
  fireEvent.load(image);
  await user.click(screen.getByRole('button', { name: '가린 이미지 미리보기' }));
  await waitFor(() =>
    expect(screen.getByRole('alert')).toHaveTextContent('이미지를 처리하지 못했어요'),
  );
  expect(ready).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: '가린 이미지 첨부' })).not.toBeInTheDocument();
});

it('이미지 개수 한도를 넘으면 전체 선택을 거부한다', async () => {
  const ready = vi.fn();
  const user = userEvent.setup();
  render(<ImageAttachmentEditor onReady={ready} onInvalidate={vi.fn()} />);
  const files = Array.from(
    { length: 6 },
    (_, index) => new File(['x'], `${String(index)}.png`, { type: 'image/png' }),
  );
  await user.upload(screen.getByLabelText(/이미지 선택/), files);
  expect(screen.getByRole('alert')).toHaveTextContent('최대 5장');
  expect(screen.queryByAltText('첨부할 이미지 미리보기')).not.toBeInTheDocument();
  expect(ready).not.toHaveBeenCalled();
});

it('문의에 남은 장수보다 많이 고르면 전체 선택을 거부한다', async () => {
  const user = userEvent.setup();
  render(<ImageAttachmentEditor onReady={vi.fn()} onInvalidate={vi.fn()} maxImages={2} />);
  const files = Array.from(
    { length: 3 },
    (_, index) => new File(['x'], `${String(index)}.png`, { type: 'image/png' }),
  );
  await user.upload(screen.getByLabelText(/이미지 선택 \(최대 2장/), files);
  expect(screen.getByRole('alert')).toHaveTextContent('최대 2장');
  expect(screen.queryByAltText('첨부할 이미지 미리보기')).not.toBeInTheDocument();
});
