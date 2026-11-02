import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { MutationResult } from '@/shared/api/types';
import type { StoredCommand, StoredCommandName } from '@/shared/api/mutations';
import { createInquiry, sendMessage } from '../api/chatApi';
import { contentError, codePointLength } from '@/shared/lib/content';
import { Button } from '@/shared/ui/Button';
import { useSession } from '@/features/auth';
import { useConnection } from '@/features/realtime';
import { useDrafts } from '../model/draftStore';
import { useRetryAfter } from '@/shared/lib/useRetryAfter';
import styles from './MessageComposer.module.css';

type MessageComposerProps = {
  inquiryId?: string;
  version?: number;
  onStored: (ack: MutationResult) => void;
  onRefresh?: () => void;
  isHome?: boolean;
  focusKey?: string;
  historyReady?: boolean;
};

export function MessageComposer({
  inquiryId,
  version = 1,
  onStored,
  onRefresh,
  isHome = false,
  focusKey,
  historyReady = true,
}: MessageComposerProps) {
  const client = useQueryClient();
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
  const { remaining, wait } = useRetryAfter();
  async function send(retry = false) {
    if (!historyReady || remaining > 0 || status === 'sending' || (invalid && !retry)) return;
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
        ? await createInquiry(request.command)
        : 'inquiryId' in request.command
          ? await sendMessage(request.command)
          : { ok: 'unknown' as const };
    if (useSession.getState().user?.id !== actorId) return;
    if (reply.ok === true) {
      if (
        useDrafts.getState().drafts[key]?.pending?.command.requestId === request.command.requestId
      ) {
        useDrafts.getState().set(key, { content: '', pending: null });
      }
      void client.invalidateQueries({ queryKey: ['inquiries'] });
    }
    if (!mounted.current) return;
    if (reply.ok === 'unknown') {
      setStatus('unknown');
      setNotice('결과 확인 중이에요. 같은 요청으로 다시 확인할 수 있어요.');
      return;
    }
    if (!reply.ok) {
      wait(reply.retryAfterSeconds ?? 0);
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
              disabled={!historyReady || remaining > 0}
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
          disabled={!historyReady || status === 'sending'}
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
            disabled={!historyReady || remaining > 0 || Boolean(invalid)}
          >
            {status === 'sending' ? '전송 중' : isHome ? '질문하기 ↑' : '보내기 ↑'}
          </Button>
        </div>
      </form>
      {remaining > 0 && (
        <p role="status">{remaining}초 후 다시 전송할 수 있어요. 입력은 유지돼요.</p>
      )}
      {invalid && codePointLength(content) > 4000 && (
        <p className={styles.error} role="alert">
          {invalid}
        </p>
      )}
      {connection !== 'connected' && (
        <p className={styles.helper}>
          실시간 연결이 끊겨도 전송할 수 있어요. 저장 결과는 서버에서 확인해요.
        </p>
      )}
    </div>
  );
}
