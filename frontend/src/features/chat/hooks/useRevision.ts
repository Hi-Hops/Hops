import { useState } from 'react';
import type { ChatMessage, MessageReviseCommand } from '@/shared/api/types';
import { realtime } from '@/shared/realtime/mockTransport';
import { useSession } from '@/features/auth';
import { contentError } from '@/shared/lib/content';
import { useDrafts } from '../model/draftStore';
export function useRevision(message: ChatMessage, version: number, onStored: () => void) {
  const key = `revise:${message.id}`;
  const draft = useDrafts((state) => state.drafts[key]);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const content = draft?.content ?? message.content;
  function change(value: string) {
    useDrafts.getState().set(key, { content: value, pending: null });
    setNotice('');
  }
  function cancel() {
    useDrafts.getState().set(key, { content: message.content, pending: null });
    setEditing(false);
    setNotice('');
  }
  async function save() {
    if (busy || contentError(content)) return;
    const pending = draft?.pending;
    const actor = useSession.getState().user?.id;
    const command: MessageReviseCommand =
      pending?.event === 'message.revise' &&
      'messageId' in pending.command &&
      pending.command.content === content
        ? pending.command
        : {
            requestId: crypto.randomUUID(),
            inquiryId: message.inquiryId,
            expectedInquiryVersion: version,
            messageId: message.id,
            content,
          };
    useDrafts.getState().set(key, { content, pending: { event: 'message.revise', command } });
    setBusy(true);
    setNotice('');
    const reply = await realtime.send('message.revise', command);
    if (useSession.getState().user?.id !== actor) return;
    setBusy(false);
    if (reply.ok === 'unknown') {
      setNotice('결과 확인 중이에요. 같은 요청으로 다시 저장해 확인할 수 있어요.');
      return;
    }
    if (!reply.ok) {
      setNotice(reply.error.message);
      if (reply.error.code === 'INQUIRY_VERSION_CONFLICT') {
        useDrafts.getState().set(key, { content, pending: null });
        onStored();
      }
      if (reply.error.code === 'UNAUTHENTICATED') useSession.getState().expire();
      return;
    }
    useDrafts.getState().set(key, { content: '', pending: null });
    setEditing(false);
    setNotice(
      reply.effect === 'SUPPLEMENT_SAVED'
        ? '정보 보완으로 저장했어요. 기존 티켓은 유지돼요.'
        : '정정 내용을 저장했어요. 최신 내용으로 분석해요.',
    );
    onStored();
  }
  return {
    editing,
    start: () => {
      if (!draft || draft.content === '') change(message.content);
      setEditing(true);
      setNotice('');
    },
    cancel,
    content,
    change,
    save,
    busy,
    notice,
    invalid: contentError(content),
  };
}
