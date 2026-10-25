import { MessageRevision } from './MessageRevision';
import { ResultPanel } from '@/features/inquiry';
import { useRef } from 'react';
import { Link } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { paths } from '@/shared/config/paths';
import { displayDate } from '@/shared/lib/date';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { useChat } from '../hooks/useChat';
import { MessageComposer } from './MessageComposer';
import { Button } from '@/shared/ui/Button';
import { useChatScroll } from '../hooks/useChatScroll';
import { RunRetry } from './RunRetry';
import styles from './ChatView.module.css';

export function ChatView({ inquiryId }: { inquiryId: string }) {
  const chat = useChat(inquiryId);
  const { log, hasNew, observe, remember, bottom, cancelPrepend } = useChatScroll(
    inquiryId,
    chat.inquiry.data
      ? chat.items.map((item) => `${item.id}:${String(item.revision)}`).join(',')
      : '',
    chat.live?.text,
  );
  const loadingOlder = useRef(false);
  async function loadOlder() {
    if (!chat.messages.hasNextPage || chat.messages.isFetching || loadingOlder.current) return;
    loadingOlder.current = true;
    remember();
    const result = await chat.messages.fetchNextPage();
    if (result.isFetchNextPageError) cancelPrepend();
    loadingOlder.current = false;
  }
  const { data, error } = chat.inquiry;
  if (chat.inquiry.isPending) return <LoadingState label="문의와 대화를 불러오는 중이에요" />;
  if (error || chat.denied)
    return (
      <ErrorState
        title={
          (error instanceof ApiError && error.status === 403) || chat.denied
            ? '이 문의를 볼 수 없어요'
            : error instanceof ApiError && error.status === 404
              ? '문의를 찾을 수 없어요'
              : '문의를 불러오지 못했어요'
        }
        description={
          error instanceof ApiError && error.status === 403
            ? '본인 문의 내역에서 접근 가능한 문의를 선택해 주세요.'
            : error instanceof ApiError && error.status === 404
              ? '문의 주소를 확인하거나 새 문의를 시작해 주세요.'
              : undefined
        }
        onRetry={chat.refresh}
      />
    );
  if (!data) return null;
  const title = data.problems[0]?.summary ?? chat.items[0]?.content ?? '새 문의';
  const runStatus = data.currentRun?.status;
  const lastAnswerId = [...chat.items].reverse().find((message) => message.sender === 'AI')?.id;
  return (
    <main className={styles.chat}>
      <header className={styles.head}>
        <div>
          <h1>
            {title}
            {data.problems.length > 1 ? ` 외 ${String(data.problems.length - 1)}건` : ''}
          </h1>
          <div className={styles.metadata}>
            <StatusBadge
              label={data.status === 'WAITING_USER' ? '추가 확인 중' : '진행 중'}
              tone="progress"
            />
            <span>
              티켓 {data.tickets.length} · 저장 실패 {data.creationFailures.length}
            </span>
          </div>
        </div>
        <Link className={styles.newInquiry} to={paths.home}>
          + 새 문의
        </Link>
      </header>
      <div
        className={styles.log}
        ref={log}
        aria-label="대화 내역"
        onScroll={() => {
          observe();
          if ((log.current?.scrollTop ?? Infinity) < 120 && !chat.messages.isFetchNextPageError)
            void loadOlder();
        }}
      >
        <p className={styles.divider}>이 문의의 대화 · 작성 내용은 저장 확인 후 반영돼요</p>
        {!lastAnswerId && <ResultPanel result={data} />}
        {chat.messages.isError && !chat.messages.isFetchNextPageError && (
          <ErrorState
            title="대화를 불러오지 못했어요"
            onRetry={() => {
              void (chat.messages.isFetchNextPageError
                ? chat.messages.fetchNextPage()
                : chat.messages.refetch());
            }}
          />
        )}
        {chat.messages.isFetchNextPageError && (
          <ErrorState
            title="이전 대화를 불러오지 못했어요"
            onRetry={() => {
              void loadOlder();
            }}
          />
        )}
        {chat.messages.hasNextPage && !chat.messages.isFetchNextPageError && (
          <Button
            variant="ghost"
            disabled={chat.messages.isFetching}
            onClick={() => {
              void loadOlder();
            }}
          >
            {chat.messages.isFetchingNextPage ? '이전 대화 불러오는 중' : '이전 대화 불러오기'}
          </Button>
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
            {message.sender === 'USER' && (
              <MessageRevision
                message={message}
                version={data.version}
                onStored={chat.refresh}
                disabled={!chat.historyReady}
              />
            )}
            {message.id === lastAnswerId && <ResultPanel result={data} />}
          </article>
        ))}
        {!chat.historyReady && !chat.messages.isError && (
          <p role="status">최신 대화를 불러오고 있어요. 확인이 끝나면 메시지를 보낼 수 있어요.</p>
        )}
        {chat.pending && (
          <div role="status" className={styles.aiRow}>
            <div className={styles.author}>
              <span>H</span>Hops · {chat.live?.text ? '답변 작성 중' : '분석 중'}
            </div>
            <p className={styles.progress}>
              {chat.live?.progress ?? '문의 내용을 확인하고 있어요.'}
            </p>
            {chat.live?.text && (
              <p className={styles.aiBubble}>
                {chat.live.text}
                <span className={styles.cursor} aria-hidden="true" />
              </p>
            )}
            <small className={styles.progress}>작성 중인 답변이에요. 저장된 결과가 아니에요.</small>
          </div>
        )}
        {(runStatus === 'TIMED_OUT' || runStatus === 'FAILED') && (
          <div className={styles.runError} role="alert">
            <strong>
              {runStatus === 'TIMED_OUT'
                ? '분석 시간이 초과됐어요'
                : '모델·통신 오류로 분석을 끝내지 못했어요'}
            </strong>
            <p>분류 결과나 해결 실패가 아니에요. 이미 저장된 메시지와 티켓은 유지돼요.</p>
            {data.currentRun && chat.historyReady && (
              <RunRetry
                key={`${data.currentRun.id}:${String(data.version)}`}
                inquiryId={inquiryId}
                version={data.version}
                run={data.currentRun}
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
      <div className={styles.dock}>
        <MessageComposer
          key={inquiryId}
          inquiryId={inquiryId}
          version={data.version}
          historyReady={chat.historyReady}
          onStored={chat.refresh}
          onRefresh={chat.refresh}
        />
      </div>
    </main>
  );
}
