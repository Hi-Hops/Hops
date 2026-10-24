import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useSubscription } from '@/features/realtime';
import { realtime } from '@/shared/realtime';
import { StreamBuffer } from '@/shared/realtime/streamBuffer';
import type { ChatMessage } from '@/shared/api/types';
import { chatKeys, getInquiry, getMessages } from '../api/chatApi';

export function useChat(id: string) {
  const client = useQueryClient();
  const inquiry = useQuery({
    queryKey: chatKeys.inquiry(id),
    queryFn: () => getInquiry(id),
    refetchInterval: (query) => (query.state.data?.currentRun?.status === 'RUNNING' ? 1000 : false),
  });
  const messages = useInfiniteQuery({
    queryKey: chatKeys.messages(id),
    initialPageParam: 0,
    queryFn: ({ pageParam, signal }) => getMessages(id, pageParam, signal),
    getNextPageParam: (page) => page.nextAfterSequenceNo ?? undefined,
  });
  const { fetchNextPage, hasNextPage, isFetching, isSuccess } = messages;
  useEffect(() => {
    if (isSuccess && hasNextPage && !isFetching) void fetchNextPage();
  }, [fetchNextPage, hasNextPage, isFetching, isSuccess]);
  const historyReady = inquiry.isSuccess && isSuccess && !hasNextPage && !isFetching;
  const subscription = useSubscription({ type: 'INQUIRY', id });
  const version = inquiry.data?.version ?? 0;
  const runId = inquiry.data?.currentRun?.id ?? '';
  const buffer = useMemo(() => new StreamBuffer(version, runId), [version, runId]);
  const [transient, setTransient] = useState({ runId: '', messageId: '', text: '', progress: '' });
  useEffect(() => {
    const seen = new Set<string>();
    const offProgress = realtime.on('agent.progress', (event) => {
      if (
        event.inquiryId !== id ||
        event.runId !== runId ||
        event.inquiryVersion !== version ||
        seen.has(event.eventId)
      )
        return;
      seen.add(event.eventId);
      setTransient((current) => ({ ...current, runId, progress: event.payload.displaySummary }));
    });
    const offDelta = realtime.on('answer.delta', (event) => {
      if (event.inquiryId !== id) return;
      const accepted = buffer.accept(event);
      if (buffer.needsRecovery) {
        void client.invalidateQueries({ queryKey: chatKeys.messages(id) });
        return;
      }
      if (!accepted) return;
      setTransient((current) => ({
        ...current,
        runId,
        messageId: event.payload.messageId,
        text: buffer.text(event.payload.messageId),
      }));
    });
    return () => {
      offProgress();
      offDelta();
    };
  }, [buffer, client, id, runId, version]);
  const items = useMemo(() => {
    const unique = new Map<string, ChatMessage>();
    for (const message of messages.data?.pages.flatMap((page) => page.items) ?? []) {
      if ((unique.get(message.id)?.revision ?? 0) < message.revision)
        unique.set(message.id, message);
    }
    return [...unique.values()].sort((a, b) => a.sequenceNo - b.sequenceNo);
  }, [messages.data]);
  const pending = inquiry.data?.currentRun?.status === 'RUNNING';
  const live =
    pending &&
    transient.runId === runId &&
    !items.some((message) => message.id === transient.messageId)
      ? transient
      : null;
  function refresh() {
    void client.invalidateQueries({ queryKey: chatKeys.all });
    void client.invalidateQueries({ queryKey: ['inquiries'] });
  }
  return {
    inquiry,
    messages,
    items,
    live,
    pending,
    refresh,
    historyReady,
    denied: subscription.denied,
  };
}
