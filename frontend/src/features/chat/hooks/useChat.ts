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
    refetchInterval: (query) =>
      ['RUNNING', 'QUEUED'].includes(query.state.data?.currentRun?.status ?? '') ? 1000 : false,
  });
  const messages = useInfiniteQuery({
    queryKey: chatKeys.messages(id),
    initialPageParam: null as number | null,
    queryFn: ({ pageParam, signal }) => getMessages(id, pageParam, signal),
    getNextPageParam: (page) => page.nextBeforeSequenceNo ?? undefined,
  });
  const historyReady =
    inquiry.isSuccess &&
    Boolean(messages.data) &&
    !messages.isRefetchError &&
    !messages.isRefetching;
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
      setTransient((current) => ({
        runId,
        messageId: current.runId === runId ? current.messageId : '',
        text: current.runId === runId ? current.text : '',
        progress: event.payload.displaySummary,
      }));
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
        runId,
        messageId: event.payload.messageId,
        text: buffer.text(event.payload.messageId),
        progress: current.runId === runId ? current.progress : '',
      }));
    });
    const offResync = realtime.on('stream.reset', (event) => {
      if (event.target.type !== 'INQUIRY' || event.target.id !== id) return;
      buffer.clear();
      setTransient({ runId: '', messageId: '', text: '', progress: '' });
    });
    return () => {
      offProgress();
      offDelta();
      offResync();
    };
  }, [buffer, client, id, runId, version]);
  useEffect(() => {
    if (!buffer.hasGaps) return;
    const timer = setTimeout(() => {
      if (buffer.hasGaps) {
        buffer.clear();
        setTransient({ runId: '', messageId: '', text: '', progress: '' });
        void client.invalidateQueries({ queryKey: chatKeys.messages(id) });
      }
    }, 2000);
    return () => {
      clearTimeout(timer);
    };
  }, [buffer, client, id, transient]);
  const items = useMemo(() => {
    const unique = new Map<string, ChatMessage>();
    for (const message of messages.data?.pages.flatMap((page) => page.items) ?? []) {
      if ((unique.get(message.id)?.revision ?? 0) < message.revision)
        unique.set(message.id, message);
    }
    return [...unique.values()].sort((a, b) => a.sequenceNo - b.sequenceNo);
  }, [messages.data]);
  const pending = ['RUNNING', 'QUEUED'].includes(inquiry.data?.currentRun?.status ?? '');
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
