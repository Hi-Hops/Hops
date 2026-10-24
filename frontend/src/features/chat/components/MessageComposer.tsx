import { useEffect, useRef, useState } from 'react';
import type { StoredAck } from '@/shared/api/types';
import type { StoredCommand, StoredCommandName } from '@/shared/realtime/types';
import { realtime } from '@/shared/realtime';
import { contentError, codePointLength } from '@/shared/lib/content';
import { Button } from '@/shared/ui/Button';
import { useSession } from '@/features/auth';
import { useConnection } from '@/features/realtime';
import { useDrafts } from '../model/draftStore';
import styles from './MessageComposer.module.css';

type MessageComposerProps = {
  inquiryId?: string;
  version?: number;
  onStored: (ack: StoredAck) => void;
  onRefresh?: () => void;
  isHome?: boolean;
  focusKey?: string;
};

export function MessageComposer({
  inquiryId,
  version = 1,
  onStored,
  onRefresh,
  isHome = false,
  focusKey,
}: MessageComposerProps) {
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    if (isHome) inputRef.current?.focus();
  }, [isHome, focusKey]);
  const key = inquiryId ?? 'new';
  const draft = useDrafts((state) => state.drafts[key]);
  const content = draft?.content ?? '';
  const pending = draft?.pending ?? null;
  const [status, setStatus] = useState<'idle' | 'sending' | 'unknown' | 'error'>(
    pending ? 'unknown' : 'idle',
  );
  const [notice, setNotice] = useState('');
  const connection = useConnection((state) => state.state);
  const invalid = contentError(content);
  async function send(retry = false) {
    if (status === 'sending' || (invalid && !retry)) return;
    const event: StoredCommandName = inquiryId ? 'message.send' : 'inquiry.create';
    const command: StoredCommand = inquiryId
      ? { requestId: crypto.randomUUID(), inquiryId, expectedInquiryVersion: version, content }
      : { requestId: crypto.randomUUID(), content };
    const actorId = useSession.getState().user?.id;
    const request = pending?.command.content === content ? pending : { event, command };
    useDrafts.getState().set(key, { content, pending: request });
    setStatus('sending');
    setNotice('');
    const reply =
      request.event === 'inquiry.create'
        ? await realtime.send('inquiry.create', request.command)
        : 'inquiryId' in request.command
          ? await realtime.send('message.send', request.command)
          : { ok: 'unknown' as const };
    if (useSession.getState().user?.id !== actorId) return;
    if (
      reply.ok === true &&
      useDrafts.getState().drafts[key]?.pending?.command.requestId === request.command.requestId
    ) {
      useDrafts.getState().set(key, { content: '', pending: null });
    }
    if (!mounted.current) return;
    if (reply.ok === 'unknown') {
      setStatus('unknown');
      setNotice('결과 확인 중이에요. 같은 요청으로 다시 확인할 수 있어요.');
      return;
    }
    if (!reply.ok) {
      setStatus('error');
      setNotice(reply.error.message);
      if (reply.error.code === 'UNAUTHENTICATED') useSession.getState().expire();
      if (reply.error.code === 'INQUIRY_VERSION_CONFLICT') {
        useDrafts.getState().set(key, { content, pending: null });
        onRefresh?.();
      }
      return;
    }
    setStatus('idle');
    setNotice(
      reply.effect === 'SUPPLEMENT_SAVED'
        ? '정보 보완으로 저장했어요. 담당자가 확인할 수 있어요.'
        : '메시지를 저장했어요.',
    );
    onStored(reply);
  }
  return (
    <div className={`${styles.container} ${isHome ? styles.home : ''}`}>
      {notice && (
        <div
          className={`${styles.notice} ${status === 'error' ? styles.error : ''}`}
          role={status === 'error' ? 'alert' : 'status'}
        >
          {notice}
          {(status === 'unknown' || (status === 'error' && pending)) && (
            <Button
              variant="secondary"
              onClick={() => {
                void send(true);
              }}
            >
              같은 요청으로 다시 확인
            </Button>
          )}
        </div>
      )}
      <form
        className={styles.composer}
        onSubmit={(event) => {
          event.preventDefault();
          void send();
        }}
      >
        <label className={styles.label} htmlFor={`message-${key}`}>
          {isHome ? '문의 내용' : '메시지 입력'}
        </label>
        <textarea
          ref={inputRef}
          id={`message-${key}`}
          value={content}
          placeholder={
            isHome
              ? '예: 재택근무 중 VPN에 연결하면 809 오류가 나요'
              : '추가 정보나 새 질문을 입력하세요. 분석 중에도 보낼 수 있어요'
          }
          onChange={(event) => {
            useDrafts.getState().set(key, { content: event.target.value, pending: null });
            setStatus('idle');
            setNotice('');
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
              event.preventDefault();
              void send();
            }
          }}
          aria-invalid={Boolean(invalid && content)}
          aria-describedby={`composer-help-${key}`}
          disabled={status === 'sending'}
        />
        <div className={styles.bar}>
          <span id={`composer-help-${key}`}>Enter 전송 · Shift+Enter 줄바꿈</span>
          {content && (
            <span className={invalid ? styles.error : undefined}>
              {codePointLength(content).toLocaleString()} / 4,000
            </span>
          )}
          <Button
            type="submit"
            isLoading={status === 'sending'}
            disabled={Boolean(invalid) || connection !== 'connected'}
          >
            {status === 'sending' ? '전송 중' : isHome ? '질문하기 ↑' : '보내기 ↑'}
          </Button>
        </div>
      </form>
      {invalid && codePointLength(content) > 4000 && (
        <p className={styles.error} role="alert">
          {invalid}
        </p>
      )}
      {connection !== 'connected' && (
        <p className={styles.helper}>연결을 복구한 뒤 보낼 수 있어요. 작성 중인 내용은 유지돼요.</p>
      )}
    </div>
  );
}
