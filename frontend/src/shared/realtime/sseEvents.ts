import type { EventMap } from './types';

/** flat hops-system-1 SSE를 검증한 뒤 전달하는 내부 이벤트의 판별 유니언. */
export type PublicEvent = {
  [K in Exclude<keyof EventMap, 'resync.required' | 'stream.reset'>]: {
    name: K;
    value: EventMap[K];
  };
}[Exclude<keyof EventMap, 'resync.required' | 'stream.reset'>];
