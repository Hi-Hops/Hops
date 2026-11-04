// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { combineStates } from './sseTransport';

describe('F002 화면 전체 연결 상태 집계', () => {
  it.each([
    [[], 'idle'],
    [['connected', 'connected'], 'connected'],
    [['connected', 'connecting'], 'connecting'],
    // 재시도가 멈춘 대상은 다른 대상이 재시도 중이어도 수동 재연결 안내가 보여야 한다.
    [['connecting', 'disconnected'], 'disconnected'],
    [['disconnected', 'connected'], 'disconnected'],
    [['connected', 'denied'], 'disconnected'],
    [['connecting', 'denied'], 'connecting'],
  ] as const)('%j → %s', (states, expected) => {
    expect(combineStates([...states])).toBe(expected);
  });
});
