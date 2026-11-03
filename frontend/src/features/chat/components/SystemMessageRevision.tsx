import { useState } from 'react';
import type { ChatMessage, InquiryResult } from '@/shared/api/types/hopsSystem1';
import { Button } from '@/shared/ui/Button';
import { SystemMessageComposer } from './SystemMessageComposer';
import styles from './MessageRevision.module.css';

export function SystemMessageRevision({
  message,
  inquiry,
  disabled,
}: {
  message: ChatMessage;
  inquiry: InquiryResult;
  disabled: boolean;
}) {
  const [editing, setEditing] = useState(false);
  return (
    <div className={styles.wrapper}>
      {editing ? (
        <>
          <SystemMessageComposer
            inquiry={inquiry}
            revise={message}
            disabled={disabled}
            onStored={() => {
              setEditing(false);
            }}
          />
          <Button
            variant="ghost"
            onClick={() => {
              setEditing(false);
            }}
          >
            편집 닫기
          </Button>
        </>
      ) : (
        <button
          type="button"
          className={styles.editButton}
          aria-label="내용 정정"
          title="내용 정정"
          disabled={disabled}
          onClick={() => {
            setEditing(true);
          }}
        >
          <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
            <path
              d="M11.1 2.6a1.5 1.5 0 0 1 2.1 0l.2.2a1.5 1.5 0 0 1 0 2.1L6 12.3l-3 .7.7-3z"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.4"
              strokeLinejoin="round"
            />
          </svg>
        </button>
      )}
      {message.revision > 1 && <span>정정됨 · {message.revision}차</span>}
      {message.revisions.length > 0 && (
        <details className={styles.history}>
          <summary>이전 내용 {message.revisions.length}개</summary>
          {message.revisions.map((r) => (
            <div key={r.revisionNo}>
              <strong>{r.revisionNo}차 원문</strong>
              <p>{r.content}</p>
            </div>
          ))}
        </details>
      )}
    </div>
  );
}
