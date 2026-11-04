import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ConnectionIndicator } from './ConnectionIndicator';
import { useConnection } from '../model/connectionStore';

afterEach(() => {
  act(() => {
    useConnection.getState().setState('disconnected');
  });
});

describe('실시간 연결 표시', () => {
  it('구독이 없으면 표시하지 않는다', () => {
    useConnection.getState().setState('idle');
    const { container } = render(<ConnectionIndicator />);
    expect(container).toBeEmptyDOMElement();
  });

  it.each([
    ['connected', '실시간 연결됨'],
    ['connecting', '실시간 연결 중'],
    ['disconnected', '실시간 연결 끊김'],
  ] as const)('%s 상태를 버튼 없이 알린다', (state, label) => {
    useConnection.getState().setState(state);
    render(<ConnectionIndicator />);
    expect(screen.getByRole('status', { name: label })).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
