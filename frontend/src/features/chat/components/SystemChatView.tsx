import { Fragment, useRef, useState } from 'react';
import { Link } from 'react-router';
import { useSession } from '@/features/auth';
import { ConnectionIndicator } from '@/features/realtime';
import {
  SystemCommandButton,
  SystemResultPanel,
  SystemConfirmations,
  systemStatus,
  editableInquiryStatus,
  inquiryTitle,
  resultMetaParts,
} from '@/features/inquiry';
import { ApiError } from '@/shared/api/client';
import { displayChatTime, isToday } from '@/shared/lib/date';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { Button } from '@/shared/ui/Button';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { useSystemChat } from '../hooks/useSystemChat';
import { retryRunCommand } from '../api/systemChatApi';
import { useChatScroll } from '../hooks/useChatScroll';
import { ProgressSteps } from './ProgressSteps';
import { SystemMessageComposer } from './SystemMessageComposer';
import { SystemMessageRevision } from './SystemMessageRevision';
import styles from './ChatView.module.css';

export function SystemChatView({ inquiryId }: { inquiryId: string }) {
  const chat = useSystemChat(inquiryId);
  const userName = useSession((s) => s.user?.name) ?? '나';
  const { log, hasNew, observe, remember, bottom, cancelPrepend } = useChatScroll(
    inquiryId,
    chat.items.map((m) => `${m.id}:${m.revision}`).join(','),
    chat.live?.text,
  );
  const loading = useRef(false);
  const [dismissedRevision, setDismissedRevision] = useState(0);
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
  const title = inquiryTitle(inquiry, chat.items.find((m) => m.sender === 'USER')?.content);
  // 연결 상태 점은 가장 최근 Hops 라벨 하나에만 둔다. 분석 중에는 진행 표시의 라벨에 둔다.
  const lastAiId = chat.items.filter((m) => m.sender === 'AI').at(-1)?.id;
  const meta = [
    ...resultMetaParts(inquiry),
    `${isToday(inquiry.createdAt) ? '오늘 ' : ''}${displayChatTime(inquiry.createdAt)} 시작`,
  ];
  // 문제별 결과 카드는 문제를 참조한 마지막 AI 답변 아래에 붙인다. 분석 중에는 진행 표시 아래로,
  // 참조한 답변이 없으면 대화 끝에 둔다.
  const problemIds = new Set(inquiry.problems.map((p) => p.id));
  const anchorId = chat.active
    ? undefined
    : [...chat.items]
        .reverse()
        .find((m) => m.sender === 'AI' && m.problemIds.some((id) => problemIds.has(id)))?.id;
  const results =
    inquiry.problems.length > 0 ? (
      <SystemResultPanel
        result={inquiry}
        dismissedRevision={dismissedRevision}
        onDismiss={setDismissedRevision}
      />
    ) : null;
  // 답변이 끝난 실행의 진행 단계는 사라지지 않고 해당 답변 위에 완료 기록으로 남는다.
  const trailOf = (message: (typeof chat.items)[number]) =>
    message.sender === 'AI' && !(chat.active && message.id === run?.answerMessageId)
      ? chat.trails[message.id]
      : undefined;
  const steps = chat.trails[run?.answerMessageId ?? ''] ?? [
    { stepNo: 0, text: '문의 내용을 확인하고 있어요' },
  ];
  return (
    <main className={styles.chat}>
      <header className={styles.head}>
        <div className={styles.titleBlock}>
          <h1 id="chat-title" tabIndex={-1} title={`${inquiry.inquiryNumber} · ${title}`}>
            {title}
          </h1>
          <div className={styles.metadata}>
            <StatusBadge {...systemStatus(inquiry.status)} />
            <span>{meta.join(' · ')}</span>
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
        {chat.items.map((message) => {
          const trail = trailOf(message);
          return (
            <Fragment key={message.id}>
              <article
                data-message-id={message.id}
                className={message.sender === 'USER' ? styles.userRow : styles.aiRow}
                aria-label={message.sender === 'USER' ? '사용자 메시지' : 'Hops 답변'}
              >
                <div className={styles.author}>
                  {message.sender === 'AI' && (
                    <span className={styles.avatar} aria-hidden="true">
                      H
                    </span>
                  )}
                  <strong>{message.sender === 'USER' ? userName : 'Hops'}</strong>
                  {!chat.active && message.id === lastAiId && <ConnectionIndicator />}
                  <time dateTime={message.createdAt}>{displayChatTime(message.createdAt)}</time>
                </div>
                {trail && <ProgressSteps steps={trail} done />}
                <div className={message.sender === 'USER' ? styles.userBubble : styles.aiBubble}>
                  <p>{message.content}</p>
                </div>
                {message.sender === 'USER' && !closed && (
                  <SystemMessageRevision
                    message={message}
                    inquiry={inquiry}
                    disabled={!chat.ready}
                  />
                )}
              </article>
              {message.id === anchorId && results && (
                <div className={styles.attached}>{results}</div>
              )}
            </Fragment>
          );
        })}
        {chat.active && (
          <div role="status" className={styles.aiRow}>
            <div className={styles.author}>
              <span className={styles.avatar} aria-hidden="true">
                H
              </span>
              <strong>Hops</strong>
              <ConnectionIndicator />
              <span>{chat.run?.status === 'QUEUED' ? '분석 대기' : '분석 중'}</span>
            </div>
            <ProgressSteps key={chat.run?.id} steps={steps} />
            {chat.live?.text && <p className={styles.aiBubble}>{chat.live.text}</p>}
          </div>
        )}
        {!anchorId && results && (
          <div className={chat.active ? styles.attached : undefined}>{results}</div>
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
      </div>
      {hasNew && (
        <Button variant="secondary" onClick={bottom}>
          새 메시지 ↓
        </Button>
      )}
      {!closed && (
        <div className={styles.dock}>
          {!chat.active && <SystemConfirmations inquiry={inquiry} />}
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
