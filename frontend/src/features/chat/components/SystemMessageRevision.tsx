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
        <Button
          variant="ghost"
          disabled={disabled}
          onClick={() => {
            setEditing(true);
          }}
        >
          내용 정정
        </Button>
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
