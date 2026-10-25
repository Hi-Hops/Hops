import { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { ApiError } from '@/shared/api/client';
import type { RunRetryRequest, RunSummary } from '@/shared/api/types';
import { Button } from '@/shared/ui/Button';
import { chatKeys, retryRun } from '../api/chatApi';

export function RunRetry({
  inquiryId,
  version,
  run,
}: {
  inquiryId: string;
  version: number;
  run: RunSummary;
}) {
  const client = useQueryClient();
  const pending = useRef<RunRetryRequest | null>(null);
  const locked = useRef(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  if (!run.error?.retryable || !['TIMED_OUT', 'FAILED'].includes(run.status)) return null;
  async function retry() {
    if (locked.current) return;
    locked.current = true;
    setBusy(true);
    setNotice('');
    const actor = useSession.getState().user?.id;
    pending.current ??= { requestId: crypto.randomUUID(), expectedInquiryVersion: version };
    try {
      await retryRun(run.id, pending.current);
      if (useSession.getState().user?.id !== actor) return;
      pending.current = null;
      setNotice('재시도를 접수했어요. 분석 결과를 기다려 주세요.');
      await client.invalidateQueries({ queryKey: chatKeys.inquiry(inquiryId) });
    } catch (error) {
      if (useSession.getState().user?.id !== actor) return;
      if (error instanceof ApiError && error.status === 409) {
        pending.current = null;
        void client.invalidateQueries({ queryKey: chatKeys.all });
      }
      setNotice(
        error instanceof ApiError
          ? error.body.message
          : '요청 결과를 확인하지 못했어요. 같은 요청으로 다시 확인할 수 있어요.',
      );
    } finally {
      locked.current = false;
      setBusy(false);
    }
  }
  return (
    <div>
      <Button
        disabled={busy}
        onClick={() => {
          void retry();
        }}
      >
        {busy ? '재시도 요청 중' : '다시 분석'}
      </Button>
      {notice && <p role="status">{notice}</p>}
    </div>
  );
}
