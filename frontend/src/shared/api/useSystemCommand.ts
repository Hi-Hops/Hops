import { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { create } from 'zustand';
import { onSessionEvent } from '@/shared/lib/sessionEvents';
import { useRetryAfter } from '@/shared/lib/useRetryAfter';
import { ApiError } from './client';
import { apiErrorMessage } from './errorMessage';
import { savedResource, sendSystemCommand } from './systemCommands';
import type { SystemCommand } from './systemCommands';

type PendingState = {
  pending: Partial<Record<string, SystemCommand>>;
  set: (key: string, command: SystemCommand | null) => void;
};
const useCommands = create<PendingState>((set) => ({
  pending: {},
  set: (key, command) => {
    set((state) => ({
      pending: command
        ? { ...state.pending, [key]: command }
        : Object.fromEntries(Object.entries(state.pending).filter(([name]) => name !== key)),
    }));
  },
}));
onSessionEvent((event) => {
  if (event === 'logout' || event === 'switched') useCommands.setState({ pending: {} });
});

export function useSystemCommand(actorId: string, operationKey: string) {
  const key = actorId + ':' + operationKey;
  const pending = useCommands((state) => state.pending[key]);
  const locked = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [expired, setExpired] = useState(false);
  const { remaining, wait } = useRetryAfter();
  const client = useQueryClient();

  async function execute(factory: (requestId: string) => SystemCommand, onSaved?: () => void) {
    if (locked.current || remaining > 0 || expired || !actorId) return;
    const original = useCommands.getState().pending[key];
    const command = original ?? structuredClone(factory(crypto.randomUUID()));
    useCommands.getState().set(key, command);
    const stillCurrent = () =>
      useCommands.getState().pending[key]?.body.requestId === command.body.requestId;
    locked.current = true;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const receipt = await sendSystemCommand(command, Boolean(original));
      savedResource(receipt, command.expectedResource.type);
      if (!stillCurrent()) return;
      useCommands.getState().set(key, null);
      onSaved?.();
      setNotice('저장했어요. 최신 상태를 확인해 주세요.');
      await client.invalidateQueries({ queryKey: ['hops-system-1'] });
    } catch (cause) {
      if (!stillCurrent()) return;
      if (cause instanceof ApiError) {
        wait(cause.retryAfterSeconds);
        if ([400, 403, 409].includes(cause.status)) {
          useCommands.getState().set(key, null);
          void client.invalidateQueries({ queryKey: ['hops-system-1'] });
        }
        if (cause.status === 410) setExpired(true);
      }
      setError(apiErrorMessage(cause, '저장 결과를 확인하지 못했어요.'));
    } finally {
      locked.current = false;
      setBusy(false);
    }
  }
  return {
    execute,
    busy,
    pending: Boolean(pending),
    error,
    notice,
    remaining,
    expired,
    locked: busy || Boolean(pending) || expired,
    label: busy ? '요청 중' : pending ? '같은 요청 결과 확인' : null,
  };
}
