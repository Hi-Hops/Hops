import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { realtime } from '@/shared/realtime';
import { useSession } from '@/features/auth';
import { ReconnectNotice } from './ReconnectNotice';
import { useConnection } from '../model/connectionStore';

afterEach(() => {
  vi.restoreAllMocks();
  act(() => {
    useConnection.getState().setState('disconnected');
  });
});

describe('실시간 재연결 안내', () => {
  it.each(['idle', 'connecting', 'connected'] as const)('%s 상태에서는 띄우지 않는다', (state) => {
    useConnection.getState().setState(state);
    const { container } = render(<ReconnectNotice />);
    expect(container).toBeEmptyDOMElement();
  });

  it('끊기면 다시 연결 버튼으로 재연결한다', async () => {
    useSession.setState({ token: 'token-1' });
    const connect = vi.spyOn(realtime, 'connect').mockImplementation(() => undefined);
    useConnection.getState().setState('disconnected');
    render(<ReconnectNotice />);
    expect(screen.getByRole('status')).toHaveTextContent('실시간 연결이 끊겼어요');
    await userEvent.setup().click(screen.getByRole('button', { name: '다시 연결' }));
    expect(connect).toHaveBeenCalledWith('token-1');
  });
});
