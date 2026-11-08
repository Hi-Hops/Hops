import { useEffect, useRef, useState } from 'react';
import { maskImage } from '../lib/maskImage';
import type { ImageMask } from '../lib/maskImage';
import styles from './ImageAttachmentEditor.module.css';

type Draft = {
  id: string;
  file: File;
  url: string;
  width: number;
  height: number;
  masks: ImageMask[];
};
type Prepared = { blob: Blob; url: string };
type Props = {
  onReady: (images: Blob[]) => void;
  onInvalidate: () => void;
  onSelectionChange?: (count: number) => void;
  disabled?: boolean;
  /** 이 메시지에 더 고를 수 있는 장수. 문의 전체 한도에서 이미 첨부한 장수를 뺀 값이다. */
  maxImages?: number;
};

function MaskCoordinates({
  width,
  height,
  disabled,
  onAdd,
}: {
  width: number;
  height: number;
  disabled: boolean;
  onAdd: (mask: ImageMask) => void;
}) {
  const [values, setValues] = useState({ x: '', y: '', width: '', height: '' });
  const inputs = [
    ['x', '왼쪽'],
    ['y', '위쪽'],
    ['width', '너비'],
    ['height', '높이'],
  ] as const;
  const parsed = Object.fromEntries(inputs.map(([key]) => [key, Number(values[key])])) as ImageMask;
  const valid =
    inputs.every(([key]) => values[key] !== '' && Number.isInteger(parsed[key])) &&
    parsed.x >= 0 &&
    parsed.y >= 0 &&
    parsed.width > 0 &&
    parsed.height > 0 &&
    parsed.x + parsed.width <= width &&
    parsed.y + parsed.height <= height;
  return (
    <fieldset disabled={disabled}>
      <legend>가림 영역 직접 입력 (픽셀)</legend>
      {inputs.map(([key, label]) => (
        <label key={key}>
          {label}
          <input
            type="number"
            min="0"
            value={values[key]}
            onChange={(event) => {
              setValues((old) => ({ ...old, [key]: event.target.value }));
            }}
          />
        </label>
      ))}
      <button
        type="button"
        disabled={!valid}
        onClick={() => {
          onAdd(parsed);
        }}
      >
        영역 추가
      </button>
    </fieldset>
  );
}

/** 원본은 브라우저 메모리에만 두고 확인한 가림 결과만 전달한다. */
export function ImageAttachmentEditor({
  onReady,
  onInvalidate,
  onSelectionChange,
  disabled = false,
  maxImages = 5,
}: Props) {
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [prepared, setPrepared] = useState<Prepared[]>([]);
  const [loadedResults, setLoadedResults] = useState<Set<string>>(() => new Set());
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const allResultsLoaded =
    prepared.length > 0 && prepared.every((image) => loadedResults.has(image.url));
  const urls = useRef(new Set<string>());
  const start = useRef<{ id: string; x: number; y: number } | null>(null);
  const alive = useRef(false);
  const preparing = useRef(false);

  useEffect(() => {
    alive.current = true;
    const active = urls.current;
    return () => {
      alive.current = false;
      for (const url of active) URL.revokeObjectURL(url);
      active.clear();
    };
  }, []);

  function clearPrepared() {
    onInvalidate();
    for (const image of prepared) {
      URL.revokeObjectURL(image.url);
      urls.current.delete(image.url);
    }
    setPrepared([]);
    setLoadedResults(new Set());
    setConfirmed(false);
  }

  function select(files: File[]) {
    if (disabled || preparing.current || !files.length) return;
    if (
      drafts.length + files.length > maxImages ||
      files.some(
        (file) =>
          !['image/png', 'image/jpeg'].includes(file.type) ||
          !file.size ||
          file.size > 5 * 1024 * 1024,
      )
    ) {
      setNotice(
        `PNG 또는 JPEG 이미지를 장당 5MB 이하, 최대 ${String(maxImages)}장까지 선택해 주세요.`,
      );
      return;
    }
    const created: string[] = [];
    let next: Draft[];
    try {
      next = files.map((file) => {
        const id = crypto.randomUUID();
        const url = URL.createObjectURL(file);
        urls.current.add(url);
        created.push(url);
        return { id, file, url, width: 0, height: 0, masks: [] };
      });
    } catch {
      for (const url of created) {
        URL.revokeObjectURL(url);
        urls.current.delete(url);
      }
      setNotice('이미지를 열지 못했어요. 다시 선택해 주세요.');
      return;
    }
    clearPrepared();
    onSelectionChange?.(drafts.length + next.length);
    setDrafts((current) => [...current, ...next]);
    setNotice('');
  }

  function update(id: string, change: (draft: Draft) => Draft) {
    if (disabled || preparing.current) return;
    clearPrepared();
    setDrafts((current) => current.map((draft) => (draft.id === id ? change(draft) : draft)));
  }

  function remove(id: string) {
    if (disabled || preparing.current) return;
    const item = drafts.find((draft) => draft.id === id);
    if (item) {
      URL.revokeObjectURL(item.url);
      urls.current.delete(item.url);
    }
    clearPrepared();
    onSelectionChange?.(drafts.length - 1);
    setDrafts((current) => current.filter((draft) => draft.id !== id));
  }

  async function prepare() {
    if (
      preparing.current ||
      disabled ||
      busy ||
      !drafts.length ||
      drafts.some((item) => !item.width || !item.height)
    )
      return;
    preparing.current = true;
    clearPrepared();
    setBusy(true);
    setNotice('');
    const created: string[] = [];
    try {
      const blobs: Blob[] = [];
      for (const item of drafts) {
        blobs.push(await maskImage(item.file, item.masks));
      }
      if (!alive.current) return;
      const next = blobs.map((blob) => {
        const url = URL.createObjectURL(blob);
        urls.current.add(url);
        created.push(url);
        return { blob, url };
      });
      setPrepared(next);
    } catch {
      for (const url of created) {
        URL.revokeObjectURL(url);
        urls.current.delete(url);
      }
      if (alive.current) setNotice('이미지를 처리하지 못했어요. 파일과 가림 영역을 확인해 주세요.');
    } finally {
      preparing.current = false;
      if (alive.current) setBusy(false);
    }
  }

  return (
    <section aria-label="이미지 첨부" className={styles.editor}>
      <fieldset disabled={disabled}>
        <label>
          이미지 선택 (최대 {maxImages}장, 장당 5MB)
          <input
            type="file"
            accept="image/png,image/jpeg"
            multiple
            disabled={busy}
            onChange={(event) => {
              select(Array.from(event.target.files ?? []));
              event.target.value = '';
            }}
          />
        </label>
        <p>가릴 부분을 드래그하세요. 자동으로 개인정보를 찾지 않으므로 직접 확인해 주세요.</p>
        {drafts.map((item) => (
          <article key={item.id} className={styles.item}>
            <div
              className={styles.preview}
              onPointerDown={(event) => {
                if (disabled || busy || !item.width || !item.height) return;
                const rect = event.currentTarget.getBoundingClientRect();
                if (!rect.width || !rect.height) return;
                const x = Math.max(
                  0,
                  Math.min(item.width, ((event.clientX - rect.left) / rect.width) * item.width),
                );
                const y = Math.max(
                  0,
                  Math.min(item.height, ((event.clientY - rect.top) / rect.height) * item.height),
                );
                start.current = { id: item.id, x, y };
                event.currentTarget.setPointerCapture(event.pointerId);
              }}
              onPointerUp={(event) => {
                const origin = start.current;
                start.current = null;
                if (origin?.id !== item.id || disabled || busy) return;
                const rect = event.currentTarget.getBoundingClientRect();
                if (!rect.width || !rect.height) return;
                const x = Math.max(
                  0,
                  Math.min(item.width, ((event.clientX - rect.left) / rect.width) * item.width),
                );
                const y = Math.max(
                  0,
                  Math.min(item.height, ((event.clientY - rect.top) / rect.height) * item.height),
                );
                const mask = {
                  x: Math.min(origin.x, x),
                  y: Math.min(origin.y, y),
                  width: Math.abs(x - origin.x),
                  height: Math.abs(y - origin.y),
                };
                if (mask.width >= 1 && mask.height >= 1)
                  update(item.id, (draft) => ({ ...draft, masks: [...draft.masks, mask] }));
              }}
              onPointerCancel={() => {
                start.current = null;
              }}
            >
              <img
                src={item.url}
                alt="첨부할 이미지 미리보기"
                draggable={false}
                onLoad={(event) => {
                  const image = event.currentTarget;
                  update(item.id, (draft) => ({
                    ...draft,
                    width: image.naturalWidth,
                    height: image.naturalHeight,
                  }));
                }}
                onError={() => {
                  setNotice('이미지를 열지 못했어요. 파일을 삭제하고 다시 선택해 주세요.');
                }}
              />
              {item.masks.map((mask, index) => (
                <span
                  key={index}
                  aria-hidden="true"
                  className={styles.mask}
                  style={{
                    left: `${(mask.x / item.width) * 100}%`,
                    top: `${(mask.y / item.height) * 100}%`,
                    width: `${(mask.width / item.width) * 100}%`,
                    height: `${(mask.height / item.height) * 100}%`,
                  }}
                />
              ))}
            </div>
            <MaskCoordinates
              width={item.width}
              height={item.height}
              disabled={busy}
              onAdd={(mask) => {
                update(item.id, (draft) => ({ ...draft, masks: [...draft.masks, mask] }));
              }}
            />
            <div className={styles.actions}>
              <button
                type="button"
                disabled={busy || !item.masks.length}
                onClick={() => {
                  update(item.id, (draft) => ({ ...draft, masks: draft.masks.slice(0, -1) }));
                }}
              >
                마지막 가림 취소
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  remove(item.id);
                }}
              >
                이미지 삭제
              </button>
            </div>
          </article>
        ))}
        {drafts.length > 0 && (
          <button
            type="button"
            disabled={busy || drafts.some((item) => !item.width || !item.height)}
            onClick={() => {
              void prepare();
            }}
          >
            가린 이미지 미리보기
          </button>
        )}
        {prepared.length > 0 && (
          <div className={styles.results}>
            {prepared.map((image) => (
              <a key={image.url} href={image.url} target="_blank" rel="noreferrer">
                <img
                  src={image.url}
                  alt="전송할 가림 이미지 크게 보기"
                  onLoad={() => {
                    setLoadedResults((current) => new Set([...current, image.url]));
                  }}
                  onError={() => {
                    setLoadedResults((current) => {
                      const next = new Set(current);
                      next.delete(image.url);
                      return next;
                    });
                    setConfirmed(false);
                    setNotice('가린 이미지를 표시하지 못했어요. 다시 미리보기 해 주세요.');
                  }}
                />
              </a>
            ))}
            <label>
              <input
                type="checkbox"
                checked={confirmed}
                disabled={busy || !allResultsLoaded}
                onChange={(event) => {
                  setConfirmed(event.target.checked);
                }}
              />{' '}
              모든 전송 이미지에 비밀번호와 불필요한 개인정보가 없는지 확인했어요.
            </label>
            <button
              type="button"
              disabled={!confirmed || busy || !allResultsLoaded}
              onClick={() => {
                onReady(prepared.map((image) => image.blob));
              }}
            >
              가린 이미지 첨부
            </button>
          </div>
        )}
        {notice && <p role="alert">{notice}</p>}
      </fieldset>
    </section>
  );
}
