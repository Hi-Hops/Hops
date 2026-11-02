import { useEffect, useState } from 'react';
import { realtime } from '@/shared/realtime';
import { useSession } from '@/features/auth';
import type { SubscriptionTarget } from '@/shared/api/types';
export function useSubscription(target: SubscriptionTarget | null) {
  const token = useSession((state) => state.token);
  const [deniedKey, setDeniedKey] = useState('');
  const type = target?.type;
  const id = target?.id;
  useEffect(() => {
    if (!type || !id || !token) return;
    return realtime.subscribe({ type, id }, (state) => {
      setDeniedKey(state === 'denied' ? type + ':' + id : '');
    });
  }, [type, id, token]);
  return { denied: Boolean(type && id && deniedKey === type + ':' + id) };
}
