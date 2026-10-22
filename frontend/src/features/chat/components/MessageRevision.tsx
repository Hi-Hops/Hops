import type { ChatMessage } from '@/shared/api/types';
import { codePointLength } from '@/shared/lib/content';
import { Button } from '@/shared/ui/Button';
import { useRevision } from '../hooks/useRevision';
import styles from './MessageRevision.module.css';
type MessageRevisionProps = { message: ChatMessage; version: number; onStored: () => void };
export function MessageRevision({ message, version, onStored }: MessageRevisionProps) {
  const editor = useRevision(message, version, onStored);
  if (message.sender !== 'USER') return null;
  return (
    <div className={styles.wrapper}>
      {editor.editing ? (
        <form
          className={styles.editor}
          onSubmit={(event) => {
            event.preventDefault();
            void editor.save();
          }}
        >
          <label htmlFor={`revision-${message.id}`}>메시지 정정</label>
          <p>기존 티켓은 유지돼요. 분석 중인 문제에는 최신 내용을 반영해요.</p>
          <textarea
            autoFocus
            id={`revision-${message.id}`}
            value={editor.content}
            disabled={editor.busy}
            onChange={(event) => {
              editor.change(event.target.value);
            }}
            aria-invalid={Boolean(editor.invalid)}
          />
          <div className={styles.actions}>
            <span>{codePointLength(editor.content).toLocaleString()} / 4,000</span>
            <Button variant="secondary" disabled={editor.busy} onClick={editor.cancel}>
              취소
            </Button>
            <Button type="submit" isLoading={editor.busy} disabled={Boolean(editor.invalid)}>
              {editor.busy ? '정정 중' : '정정 저장'}
            </Button>
          </div>
          {editor.invalid && <p role="alert">{editor.invalid}</p>}
        </form>
      ) : (
        <div className={styles.meta}>
          <Button variant="ghost" onClick={editor.start}>
            내용 정정
          </Button>
          {message.revision > 1 && <span>정정됨 · {message.revision}차</span>}
        </div>
      )}
      {editor.notice && (
        <p className={styles.notice} role="status">
          {editor.notice}
        </p>
      )}
      {message.revisions.length > 0 && (
        <details className={styles.history}>
          <summary>이전 내용 {message.revisions.length}개</summary>
          {message.revisions.map((revision) => (
            <div key={revision.revisionNo}>
              <strong>{revision.revisionNo}차 원문</strong>
              <p>{revision.content}</p>
            </div>
          ))}
        </details>
      )}
    </div>
  );
}
