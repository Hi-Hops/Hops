export type SessionEvent = 'logout' | 'expired' | 'switched';
const listeners = new Set<(event: SessionEvent) => void>();

export function onSessionEvent(listener: (event: SessionEvent) => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function emitSessionEvent(event: SessionEvent) {
  for (const listener of listeners) listener(event);
}
