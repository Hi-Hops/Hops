import { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useSession } from '@/features/auth';
import { ApiError } from '@/shared/api/client';
import { apiErrorMessage } from '@/shared/api/errorMessage';
import { savedResource, sendSystemCommand } from '@/shared/api/systemCommands';
import type { SystemCommand } from '@/shared/api/systemCommands';
import { useRetryAfter } from '@/shared/lib/useRetryAfter';
import { Button } from '@/shared/ui/Button';
import { useSystemOperations } from '../model/systemOperationStore';

type SystemCommandButtonProps = {
  operationKey: string;
  createCommand: (requestId: string) => SystemCommand;
  label: string;
  disabled?: boolean;
};
export function SystemCommandButton({
  operationKey,
  createCommand,
  label,
  disabled = false,
}: SystemCommandButtonProps) {
  const actorId = useSession((s) => s.user?.id);
  const key = `${actorId ?? ''}:${operationKey}`;
  const pending = useSystemOperations((s) => s.pending[key]);
  const locked = useRef(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const { remaining, wait } = useRetryAfter();
  const client = useQueryClient();
  async function act() {
    if (locked.current || (disabled && !pending) || remaining > 0) return;
    const actor = useSession.getState().user?.id;
    const confirm = pending !== undefined;
    const command = pending ?? createCommand(crypto.randomUUID());
    useSystemOperations.getState().set(key, command);
    locked.current = true;
    setBusy(true);
    try {
      savedResource(await sendSystemCommand(command, confirm), command.expectedResource.type);
      if (useSession.getState().user?.id !== actor) return;
      useSystemOperations.getState().set(key, null);
      setNotice('저장했어요. 최신 상태를 확인해 주세요.');
      await client.invalidateQueries({ queryKey: ['hops-system-1'] });
    } catch (error) {
      if (useSession.getState().user?.id !== actor) return;
      if (error instanceof ApiError) {
        wait(error.retryAfterSeconds);
        if (error.status === 409 || error.status === 400) {
          useSystemOperations.getState().set(key, null);
          void client.invalidateQueries({ queryKey: ['hops-system-1'] });
        }
      }
      setNotice(apiErrorMessage(error, '결과를 확인하지 못했어요.'));
    } finally {
      locked.current = false;
      setBusy(false);
    }
  }
  return (
    <div>
      <Button
        variant="secondary"
        disabled={(disabled && !pending) || busy || remaining > 0}
        onClick={() => {
          void act();
        }}
      >
        {busy ? '요청 중' : pending ? '같은 요청 결과 확인' : label}
      </Button>
      {notice && <p role="status">{notice}</p>}
      {remaining > 0 && <p role="status">{remaining}초 후 다시 요청할 수 있어요.</p>}
    </div>
  );
}
