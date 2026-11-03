import { useRef } from 'react';
import { Link } from 'react-router';
import {
  SystemCommandButton,
  SystemResultPanel,
  systemStatus,
  editableInquiryStatus,
} from '@/features/inquiry';
import { ApiError } from '@/shared/api/client';
import { displayDate } from '@/shared/lib/date';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { Button } from '@/shared/ui/Button';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { useSystemChat } from '../hooks/useSystemChat';
import { retryRunCommand } from '../api/systemChatApi';
import { useChatScroll } from '../hooks/useChatScroll';
import { SystemMessageComposer } from './SystemMessageComposer';
import { SystemMessageRevision } from './SystemMessageRevision';
import styles from './ChatView.module.css';

export function SystemChatView({ inquiryId }: { inquiryId: string }) {
  const chat = useSystemChat(inquiryId);
  const { log, hasNew, observe, remember, bottom, cancelPrepend } = useChatScroll(
    inquiryId,
    chat.items.map((m) => `${m.id}:${m.revision}`).join(','),
    chat.live?.text,
  );
  const loading = useRef(false);
  async function older() {
    if (!chat.messages.hasNextPage || chat.messages.isFetching || loading.current) return;
    loading.current = true;
    remember();
    const result = await chat.messages.fetchNextPage();
    if (result.isFetchNextPageError) cancelPrepend();
    loading.current = false;
  }
  const inquiry = chat.inquiry.data;
  const run = chat.run;
  if (chat.inquiry.isPending) return <LoadingState />;
  const readDenied = [chat.messages.error, chat.runQuery.error].some(
    (error) => error instanceof ApiError && error.status === 403,
  );
  if (chat.inquiry.isError || chat.subscription.denied || readDenied)
    return (
      <ErrorState
        title={
          chat.subscription.denied ||
          readDenied ||
          (chat.inquiry.error instanceof ApiError && chat.inquiry.error.status === 403)
            ? '이 문의를 볼 수 없어요'
            : '문의를 불러오지 못했어요'
        }
        onRetry={() => {
          void chat.inquiry.refetch();
          if (chat.messages.isError) void chat.messages.refetch();
          if (chat.runQuery.isError) void chat.runQuery.refetch();
        }}
      />
    );
  if (!inquiry) return null;
  const closed = ['RESOLVED', 'CLOSED'].includes(inquiry.status);
  return (
    <main className={styles.chat}>
      <header className={styles.head}>
        <div>
          <h1 id="chat-title" tabIndex={-1}>
            {inquiry.inquiryNumber} · {inquiry.problems[0]?.summary ?? '문의'}
          </h1>
          <div className={styles.metadata}>
            <StatusBadge {...systemStatus(inquiry.status)} />
            <span>티켓 {inquiry.problems.filter((p) => p.ticket).length}개</span>
          </div>
        </div>
        <Link className={styles.newInquiry} to="/">
          + 새 문의
        </Link>
      </header>
      <div
        ref={log}
        className={styles.log}
        aria-label="대화 내역"
        onScroll={() => {
          observe();
          if ((log.current?.scrollTop ?? Infinity) < 120 && !chat.messages.isFetchNextPageError)
            void older();
        }}
      >
        <p className={styles.divider}>이 문의의 대화 · 작성 내용은 저장 확인 후 반영돼요</p>
        {chat.messages.hasNextPage && (
          <Button
            variant="ghost"
            disabled={chat.messages.isFetching}
            onClick={() => {
              void older();
            }}
          >
            이전 대화 불러오기
          </Button>
        )}
        {chat.messages.isError && (
          <ErrorState
            title="대화를 동기화하지 못했어요"
            onRetry={() => {
              void (chat.messages.isFetchNextPageError
                ? chat.messages.fetchNextPage()
                : chat.messages.refetch());
            }}
          />
        )}
        {chat.items.map((message) => (
          <article
            key={message.id}
            data-message-id={message.id}
            className={message.sender === 'USER' ? styles.userRow : styles.aiRow}
            aria-label={message.sender === 'USER' ? '사용자 메시지' : 'Hops 답변'}
          >
            {message.sender === 'AI' && (
              <div className={styles.author}>
                <span>H</span>Hops · {displayDate(message.createdAt)}
              </div>
            )}
            <div className={message.sender === 'USER' ? styles.userBubble : styles.aiBubble}>
              {message.sender === 'USER' && <small>나 · {displayDate(message.createdAt)}</small>}
              <p>{message.content}</p>
            </div>
            {message.sender === 'USER' && !closed && (
              <SystemMessageRevision message={message} inquiry={inquiry} disabled={!chat.ready} />
            )}
          </article>
        ))}
        {chat.active && (
          <div role="status" className={styles.aiRow}>
            <div className={styles.author}>
              <span>H</span>Hops · {chat.run?.status === 'QUEUED' ? '분석 대기' : '분석 중'}
            </div>
            <p className={styles.progress}>
              {chat.live?.progress ?? '문의 내용을 확인하고 있어요.'}
            </p>
            {chat.live?.text && <p className={styles.aiBubble}>{chat.live.text}</p>}
            <small>작성 중인 답변이에요. 저장된 결과가 아니에요.</small>
          </div>
        )}
        {chat.active && chat.runQuery.isError && (
          <ErrorState
            title="분석 상태를 확인하지 못했어요"
            onRetry={() => {
              void chat.runQuery.refetch();
            }}
          />
        )}
        {run?.error && (
          <div className={styles.runError} role="alert">
            <strong>{run.error.message}</strong>
            <p>이미 저장된 메시지와 티켓은 유지돼요.</p>
            {run.error.retryable && ['FAILED', 'TIMED_OUT'].includes(run.status) && (
              <SystemCommandButton
                key={run.id}
                operationKey={`run:${run.id}`}
                createCommand={(requestId) =>
                  retryRunCommand(run.id, {
                    requestId,
                    expectedInquiryVersion: inquiry.inquiryVersion,
                  })
                }
                label="다시 분석"
                disabled={!editableInquiryStatus(inquiry.status)}
              />
            )}
          </div>
        )}
        {inquiry.problems.length > 0 && <SystemResultPanel result={inquiry} />}
      </div>
      {hasNew && (
        <Button variant="secondary" onClick={bottom}>
          새 메시지 ↓
        </Button>
      )}
      {!closed && (
        <div className={styles.dock}>
          <SystemMessageComposer
            inquiry={inquiry}
            disabled={!chat.ready}
            onStored={() => {
              bottom();
            }}
          />
        </div>
      )}
    </main>
  );
}
