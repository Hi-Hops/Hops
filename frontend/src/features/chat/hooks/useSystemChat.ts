import { useEffect, useMemo, useRef, useState } from 'react';
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { useSubscription } from '@/features/realtime';
import { realtime } from '@/shared/realtime';
import { StreamBuffer } from '@/shared/realtime/streamBuffer';
import type { ChatMessage } from '@/shared/api/types/hopsSystem1';

export type ProgressStep = { stepNo: number; text: string };
const idle = { runId: '', text: '', steps: [] as ProgressStep[] };
import {
  getSystemInquiry,
  getSystemMessages,
  getSystemRun,
  systemChatKeys,
} from '../api/systemChatApi';

const isActive = (status: string | undefined) => ['QUEUED', 'RUNNING'].includes(status ?? '');

export function useSystemChat(id: string) {
  const actor = useSession((s) => s.user?.id) ?? '';
  const client = useQueryClient();
  const inquiry = useQuery({
    queryKey: systemChatKeys.inquiry(actor, id),
    queryFn: ({ signal }) => getSystemInquiry(id, signal),
    refetchInterval: (q) => (q.state.status === 'error' ? false : 5000),
  });
  const messages = useInfiniteQuery({
    queryKey: systemChatKeys.messages(actor, id),
    initialPageParam: null as number | null,
    queryFn: ({ pageParam, signal }) => getSystemMessages(id, pageParam, signal),
    getNextPageParam: (page) => page.nextBeforeSequenceNo ?? undefined,
  });
  const current = inquiry.data?.currentRun;
  const run = useQuery({
    queryKey: systemChatKeys.run(actor, current?.id ?? ''),
    queryFn: ({ signal }) => getSystemRun(current?.id ?? '', signal),
    enabled: Boolean(current && isActive(current.status)),
    refetchInterval: (q) => (isActive(q.state.data?.status ?? 'QUEUED') ? 1000 : false),
  });
  // 문의 GET의 종료 상태가 이전에 캐시된 RUNNING보다 우선한다.
  const displayedRun = current && !isActive(current.status) ? current : (run.data ?? current);
  const observed = useRef('');
  useEffect(() => {
    const value = `${actor}:${id}:${current?.id ?? ''}:${displayedRun?.status ?? ''}`;
    if (observed.current === value) return;
    const previous = observed.current;
    observed.current = value;
    void client.invalidateQueries({ queryKey: systemChatKeys.messages(actor, id) });
    // 종료·실행 교체 모두 직전에 저장된 답변까지 REST로 다시 읽는다.
    if (previous && current)
      void client.invalidateQueries({ queryKey: systemChatKeys.inquiry(actor, id) });
  }, [actor, client, current, displayedRun?.status, id]);
  const subscription = useSubscription({ type: 'INQUIRY', id });
  const version = inquiry.data?.inquiryVersion ?? 0;
  const runId = current?.id ?? '';
  const buffer = useMemo(() => new StreamBuffer(version, runId), [version, runId]);
  const [live, setLive] = useState(idle);
  useEffect(() => {
    const offDelta = realtime.on('answer.delta', (event) => {
      if (event.inquiryId !== id || event.payload.messageId !== current?.answerMessageId) return;
      const accepted = buffer.accept(event);
      if (buffer.needsRecovery) {
        buffer.clear();
        setLive(idle);
        void client.invalidateQueries({ queryKey: systemChatKeys.inquiry(actor, id) });
        void client.invalidateQueries({ queryKey: systemChatKeys.messages(actor, id) });
        return;
      }
      if (accepted)
        setLive((s) => ({
          runId,
          text: buffer.text(event.payload.messageId),
          steps: s.runId === runId ? s.steps : [],
        }));
    });
    const offProgress = realtime.on('agent.progress', (event) => {
      if (event.inquiryId === id && event.runId === runId && event.inquiryVersion === version)
        setLive((s) => {
          // 공개 진행 요약을 단계 번호별로 쌓아 실행 중에만 체크 목록으로 보여준다.
          const steps = (s.runId === runId ? s.steps : []).filter(
            (step) => step.stepNo !== event.payload.stepNo,
          );
          steps.push({ stepNo: event.payload.stepNo, text: event.payload.displaySummary });
          return {
            runId,
            text: s.runId === runId ? s.text : '',
            steps: steps.sort((a, b) => a.stepNo - b.stepNo),
          };
        });
    });
    const offReset = realtime.on('stream.reset', (event) => {
      if (event.target.type === 'INQUIRY' && event.target.id === id) {
        buffer.clear();
        setLive(idle);
      }
    });
    return () => {
      offDelta();
      offProgress();
      offReset();
    };
  }, [actor, buffer, client, current?.answerMessageId, id, runId, version]);
  useEffect(() => {
    if (!buffer.hasGaps) return;
    const timer = setTimeout(() => {
      if (!buffer.hasGaps) return;
      buffer.clear();
      setLive(idle);
      void client.invalidateQueries({ queryKey: systemChatKeys.inquiry(actor, id) });
      void client.invalidateQueries({ queryKey: systemChatKeys.messages(actor, id) });
    }, 2000);
    return () => {
      clearTimeout(timer);
    };
  }, [actor, buffer, client, id, live]);
  const items = useMemo(() => {
    const unique = new Map<string, ChatMessage>();
    for (const message of messages.data?.pages.flatMap((p) => p.items) ?? []) {
      if ((unique.get(message.id)?.revision ?? 0) < message.revision)
        unique.set(message.id, message);
    }
    return [...unique.values()].sort((a, b) => a.sequenceNo - b.sequenceNo);
  }, [messages.data]);
  const active = isActive(displayedRun?.status);
  return {
    inquiry,
    messages,
    run: displayedRun,
    runQuery: run,
    items,
    subscription,
    active,
    live:
      active && live.runId === runId && !items.some((m) => m.id === current?.answerMessageId)
        ? live
        : null,
    ready: inquiry.isSuccess && messages.isSuccess && !messages.isRefetching,
  };
}
