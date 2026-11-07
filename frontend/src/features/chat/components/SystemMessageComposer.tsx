import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { editableInquiryStatus } from '@/features/inquiry';
import { ApiError } from '@/shared/api/client';
import { apiErrorMessage } from '@/shared/api/errorMessage';
import { savedResource, sendSystemCommand } from '@/shared/api/systemCommands';
import { contentError, codePointLength } from '@/shared/lib/content';
import { useRetryAfter } from '@/shared/lib/useRetryAfter';
import { Button } from '@/shared/ui/Button';
import type { ChatMessage, InquiryResult } from '@/shared/api/types/hopsSystem1';
import {
  createInquiryCommand,
  getChatSystems,
  reviseMessageCommand,
  sendMessageCommand,
} from '../api/systemChatApi';
import { useSystemDrafts } from '../model/systemDraftStore';
import styles from './MessageComposer.module.css';

type SystemMessageComposerProps = {
  inquiry?: InquiryResult;
  revise?: ChatMessage;
  onStored: (inquiryId: string) => void;
  disabled?: boolean;
  focusKey?: string;
};
export function SystemMessageComposer({
  inquiry,
  revise,
  onStored,
  disabled = false,
  focusKey,
}: SystemMessageComposerProps) {
  const actor = useSession((s) => s.user?.id);
  const key = `${actor ?? ''}:${revise ? `revision:${revise.id}` : (inquiry?.id ?? 'new')}`;
  const draft = useSystemDrafts((s) => s.drafts[key]);
  const content = draft?.content ?? revise?.content ?? '';
  const systemId = draft?.systemId ?? null;
  const pending = draft?.pending ?? null;
  const locked = useRef(false);
  const input = useRef<HTMLTextAreaElement>(null);
  const [busy, setBusy] = useState(false);
  const [confirmedKey, setConfirmedKey] = useState<string | null>(null);
  const privacyConfirmed = confirmedKey === key;
  const [notice, setNotice] = useState('');
  const { remaining, wait } = useRetryAfter();
  const client = useQueryClient();
  const catalog = useQuery({
    queryKey: ['hops-system-1', 'systems', actor],
    queryFn: ({ signal }) => getChatSystems(signal),
    enabled: !inquiry && Boolean(actor),
  });
  useEffect(() => {
    if (!inquiry || revise) input.current?.focus();
  }, [focusKey, inquiry, revise]);
  const invalid = contentError(content);
  const blocked = disabled || Boolean(inquiry && !editableInquiryStatus(inquiry.status));
  async function submit(confirm = false) {
    if (
      locked.current ||
      blocked ||
      remaining > 0 ||
      (!confirm && (invalid || pending || !privacyConfirmed))
    )
      return;
    const request =
      pending ??
      (!inquiry
        ? createInquiryCommand({
            requestId: crypto.randomUUID(),
            content,
            reportedSystemId: systemId,
          })
        : revise
          ? reviseMessageCommand(inquiry.id, revise.id, {
              requestId: crypto.randomUUID(),
              expectedInquiryVersion: inquiry.inquiryVersion,
              expectedMessageRevision: revise.revision,
              content,
            })
          : sendMessageCommand(inquiry.id, {
              requestId: crypto.randomUUID(),
              expectedInquiryVersion: inquiry.inquiryVersion,
              content,
              problemIds: [],
            }));
    useSystemDrafts.getState().set(key, { content, systemId, pending: request });
    locked.current = true;
    setBusy(true);
    setNotice('');
    try {
      const resource = savedResource(await sendSystemCommand(request, confirm), 'INQUIRY');
      if (useSession.getState().user?.id !== actor) return;
      useSystemDrafts.getState().set(key, { content: '', systemId: null, pending: null });
      setConfirmedKey(null);
      void client.invalidateQueries({ queryKey: ['inquiries'] });
      void client.invalidateQueries({ queryKey: ['hops-system-1'] });
      onStored(resource.id);
    } catch (error) {
      if (useSession.getState().user?.id !== actor) return;
      if (error instanceof ApiError) {
        wait(error.retryAfterSeconds);
        if (error.status === 409 || error.status === 400) {
          useSystemDrafts.getState().set(key, { content, systemId, pending: null });
          void client.invalidateQueries({ queryKey: ['hops-system-1'] });
        }
      }
      setNotice(apiErrorMessage(error, '저장 결과를 확인하지 못했어요.'));
    } finally {
      locked.current = false;
      setBusy(false);
    }
  }
  return (
    <div className={`${styles.container} ${!inquiry ? styles.home : ''}`}>
      <form
        className={styles.composer}
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <label className={styles.label} htmlFor={`system-message-${key}`}>
          {revise ? '정정 내용' : !inquiry ? '문의 내용' : '메시지 입력'}
        </label>
        {revise && <p>서버의 현재 원문: {revise.content}</p>}
        <textarea
          id={`system-message-${key}`}
          ref={input}
          value={content}
          disabled={blocked || busy || Boolean(pending)}
          placeholder={
            !inquiry ? '예: 전자결재에서 문서를 열 수 없어요' : '추가 정보나 새 질문을 입력하세요'
          }
          onChange={(e) => {
            setConfirmedKey(null);
            useSystemDrafts
              .getState()
              .set(key, { content: e.target.value, systemId, pending: null });
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              void submit();
            }
          }}
          aria-invalid={Boolean(invalid && content)}
          aria-describedby={`privacy-notice-${key}`}
        />
        <p id={`privacy-notice-${key}`} className={styles.helper}>
          비밀번호, 인증번호와 개인정보를 입력하지 마세요. 자동 감지는 개인정보를 놓칠 수 있어요.
        </p>
        <label className={styles.helper}>
          <input
            type="checkbox"
            checked={privacyConfirmed}
            disabled={blocked || busy || Boolean(pending)}
            onChange={(event) => {
              setConfirmedKey(event.target.checked ? key : null);
            }}
          />{' '}
          보낼 내용에 비밀번호와 불필요한 개인정보가 없는지 확인했어요.
        </label>
        <div className={styles.bar}>
          {!inquiry && (
            <>
              <label htmlFor="reported-system">보고 시스템</label>
              <select
                id="reported-system"
                value={systemId ?? ''}
                disabled={busy || Boolean(pending)}
                onChange={(e) => {
                  useSystemDrafts
                    .getState()
                    .set(key, { content, systemId: e.target.value || null, pending: null });
                }}
              >
                <option value="">선택하지 않고 문의</option>
                {catalog.data?.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </>
          )}
          <span>Enter 전송 · Shift+Enter 줄바꿈</span>
          <span>{codePointLength(content).toLocaleString()} / 4,000</span>
          <Button
            type="submit"
            isLoading={busy}
            disabled={
              blocked ||
              busy ||
              remaining > 0 ||
              Boolean(invalid) ||
              Boolean(pending) ||
              !privacyConfirmed
            }
          >
            {revise ? '정정 저장' : !inquiry ? '질문하기 ↑' : '보내기 ↑'}
          </Button>
        </div>
      </form>
      {catalog.isError && !inquiry && (
        <p role="alert">
          시스템 목록을 불러오지 못했어요. 시스템을 선택하지 않고 문의할 수 있어요.
        </p>
      )}
      {invalid && content && <p role="alert">{invalid}</p>}
      {notice && <p role="status">{notice}</p>}
      {pending && (
        <Button
          variant="secondary"
          disabled={busy || remaining > 0 || blocked}
          onClick={() => {
            void submit(true);
          }}
        >
          같은 요청 결과 확인
        </Button>
      )}
      {remaining > 0 && <p role="status">{remaining}초 후 다시 요청할 수 있어요.</p>}
    </div>
  );
}
