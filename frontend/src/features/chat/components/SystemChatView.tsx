import { useRef } from 'react';
import { useSession } from '@/features/auth';
import { ConnectionIndicator } from '@/features/realtime';
import {
  SystemCommandButton,
  systemStatus,
  editableInquiryStatus,
  inquiryTitle,
  resultMetaParts,
  useResultNotice,
} from '@/features/inquiry';
import { ApiError } from '@/shared/api/client';
import { displayChatTime, isToday } from '@/shared/lib/date';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { MarkdownText } from '@/shared/ui/MarkdownText';
import { Button } from '@/shared/ui/Button';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { useSystemChat } from '../hooks/useSystemChat';
import { retryRunCommand } from '../api/systemChatApi';
import { useChatScroll } from '../hooks/useChatScroll';
import { ProblemStatusBar } from './ProblemStatusBar';
import { ProblemStatusContent } from './ProblemStatusContent';
import { ProgressSteps } from './ProgressSteps';
import { SystemMessageComposer } from './SystemMessageComposer';
import { SystemMessageRevision } from './SystemMessageRevision';
import { MessageImage } from './MessageImage';
import { ImageObservationConfirmation } from './ImageObservationConfirmation';
import { ImageProblemLinkEditor } from './ImageProblemLinkEditor';
import { ImageAnalysisTargetEditor } from './ImageAnalysisTargetEditor';
import styles from './ChatView.module.css';

export function SystemChatView({ inquiryId }: { inquiryId: string }) {
  const chat = useSystemChat(inquiryId);
  const userName = useSession((s) => s.user?.name) ?? '나';
  const actor = useSession((s) => s.user?.id) ?? '';
  const { noticeOpen, closeNotice } = useResultNotice(actor, chat.inquiry.data);
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
  const title = inquiryTitle(inquiry, chat.items.find((m) => m.sender === 'USER')?.content);
  // 연결 상태 점은 가장 최근 Hops 라벨 하나에만 둔다. 분석 중에는 진행 표시의 라벨에 둔다.
  const lastAiId = chat.items.filter((m) => m.sender === 'AI').at(-1)?.id;
  const meta = [
    ...resultMetaParts(inquiry),
    `${isToday(inquiry.createdAt) ? '오늘 ' : ''}${displayChatTime(inquiry.createdAt)} 시작`,
  ];
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
      <div className={styles.workspace}>
        <section className={styles.conversation} aria-label="대화">
          <header className={styles.head}>
            <h1 id="chat-title" tabIndex={-1} title={`${inquiry.inquiryNumber} · ${title}`}>
              {title}
            </h1>
            <StatusBadge {...systemStatus(inquiry.status)} />
            <span className={styles.metadata}>{meta.join(' · ')}</span>
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
                <article
                  key={message.id}
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
                    {message.sender === 'AI' ? (
                      <MarkdownText text={message.content} />
                    ) : (
                      <p>{message.content}</p>
                    )}
                    {message.attachments?.map((attachment, position) => (
                      <div key={`${actor}:${attachment.id}`}>
                        <MessageImage
                          actor={actor}
                          inquiryId={inquiryId}
                          attachmentId={attachment.id}
                          position={position}
                        />
                        {import.meta.env.VITE_CHAT_IMAGE_ATTACHMENTS === 'true' &&
                          message.sender === 'USER' && (
                            <>
                              <ImageProblemLinkEditor
                                inquiry={inquiry}
                                messageId={message.id}
                                attachmentId={attachment.id}
                                disabled={!chat.ready}
                              />
                              <ImageAnalysisTargetEditor
                                inquiry={inquiry}
                                messageId={message.id}
                                attachmentId={attachment.id}
                                disabled={!chat.ready || closed}
                              />
                            </>
                          )}
                      </div>
                    ))}
                    {import.meta.env.VITE_CHAT_IMAGE_ATTACHMENTS === 'true' &&
                      message.imageObservationQuestions?.map((observation) => (
                        <ImageObservationConfirmation
                          key={`${observation.observationId}:${observation.observationRevision}`}
                          inquiryId={inquiryId}
                          inquiryVersion={inquiry.inquiryVersion}
                          observation={observation}
                          disabled={!chat.ready || closed}
                        />
                      ))}
                  </div>
                  {message.sender === 'USER' && !closed && (
                    <SystemMessageRevision
                      message={message}
                      inquiry={inquiry}
                      disabled={!chat.ready}
                    />
                  )}
                </article>
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
          <ProblemStatusBar inquiry={inquiry} analyzing={chat.active} />
          {!closed && (
            <div className={styles.dock}>
              <SystemMessageComposer
                inquiry={inquiry}
                disabled={!chat.ready}
                // 불러온 메시지 기준이라 이전 페이지가 남아 있으면 적게 셀 수 있다. 서버가 최종 거부한다.
                usedImages={chat.items.reduce(
                  (count, message) => count + (message.attachments?.length ?? 0),
                  0,
                )}
                onStored={() => {
                  bottom();
                }}
              />
            </div>
          )}
        </section>
        <aside className={styles.rail} id="problem-confirmations" aria-labelledby="status-title">
          <h2 id="status-title">처리 현황</h2>
          <ProblemStatusContent
            inquiry={inquiry}
            analyzing={chat.active}
            noticeOpen={noticeOpen}
            onNoticeClose={closeNotice}
          />
        </aside>
      </div>
    </main>
  );
}
