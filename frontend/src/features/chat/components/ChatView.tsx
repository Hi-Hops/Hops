import { ResultPanel } from '@/features/inquiry';
import { useEffect, useRef } from 'react';
import { Link } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { paths } from '@/shared/config/paths';
import { displayDate } from '@/shared/lib/date';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { Button } from '@/shared/ui/Button';
import { useChat } from '../hooks/useChat';
import { MessageComposer } from './MessageComposer';
import styles from './ChatView.module.css';

export function ChatView({ inquiryId }: { inquiryId: string }) {
  const chat = useChat(inquiryId);
  const log = useRef<HTMLDivElement>(null);
  const streamText = chat.live?.text;
  useEffect(() => {
    if (log.current) log.current.scrollTop = log.current.scrollHeight;
  }, [chat.items.length, streamText]);
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
        onRetry={chat.refresh}
      />
    );
  if (!data) return null;
  const title = data.problems[0]?.summary ?? chat.items[0]?.content ?? '새 문의';
  const runStatus = data.currentRun?.status;
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
      <div className={styles.log} ref={log} aria-label="대화 내역">
        <p className={styles.divider}>이 문의의 대화 · 작성 내용은 저장 확인 후 반영돼요</p>
        {chat.messages.isError && (
          <ErrorState
            title="대화를 불러오지 못했어요"
            onRetry={() => {
              void chat.messages.refetch();
            }}
          />
        )}
        {chat.items.map((message) => (
          <article
            key={message.id}
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
          </article>
        ))}
        {chat.messages.hasNextPage && (
          <Button
            variant="ghost"
            onClick={() => {
              void chat.messages.fetchNextPage();
            }}
          >
            대화 더 불러오기
          </Button>
        )}
        {chat.pending && (
          <div role="status" className={styles.aiRow}>
            <div className={styles.author}>
              <span>H</span>Hops · 분석 중
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
        <ResultPanel result={data} />
        {(runStatus === 'TIMED_OUT' || runStatus === 'FAILED') && (
          <div className={styles.runError} role="alert">
            <strong>
              {runStatus === 'TIMED_OUT'
                ? '분석 시간이 초과됐어요'
                : '모델·통신 오류로 분석을 끝내지 못했어요'}
            </strong>
            <p>분류 결과나 해결 실패가 아니에요. 이미 저장된 메시지와 티켓은 유지돼요.</p>
          </div>
        )}
      </div>
      <div className={styles.dock}>
        <MessageComposer
          inquiryId={inquiryId}
          version={data.version}
          onStored={chat.refresh}
          onRefresh={chat.refresh}
        />
      </div>
    </main>
  );
}
