import { useEffect, useState } from 'react';
import { realtime } from '@/shared/realtime/mockTransport';
import type { SubscriptionTarget } from '@/shared/api/types';
import { useConnection } from '../model/connectionStore';

export function useSubscription(target: SubscriptionTarget | null) {
  const connection = useConnection((state) => state.state);
  const [denied, setDenied] = useState(false);
  const type = target?.type;
  const id = target?.id;
  useEffect(() => {
    if (!type || !id || connection !== 'connected') return;
    const currentTarget = { type, id };
    let active = true;
    let subscribed = false;
    void realtime
      .send('subscription.request', { requestId: crypto.randomUUID(), target: currentTarget })
      .then((reply) => {
        subscribed = reply.ok === true;
        if (active) setDenied(reply.ok === false);
        else if (subscribed)
          void realtime.send('subscription.release', {
            requestId: crypto.randomUUID(),
            target: currentTarget,
          });
      });
    return () => {
      active = false;
      if (subscribed)
        void realtime.send('subscription.release', {
          requestId: crypto.randomUUID(),
          target: currentTarget,
        });
    };
  }, [connection, id, type]);
  return { denied };
}
