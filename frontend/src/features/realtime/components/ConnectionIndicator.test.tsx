import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { realtime } from '@/shared/realtime';
import { useSession } from '@/features/auth';
import { ConnectionIndicator } from './ConnectionIndicator';
import { useConnection } from '../model/connectionStore';

afterEach(() => {
  vi.restoreAllMocks();
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

  it('연결되면 상태만 알리고 버튼을 두지 않는다', () => {
    useConnection.getState().setState('connected');
    render(<ConnectionIndicator />);
    expect(screen.getByRole('status', { name: '실시간 연결됨' })).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('끊기면 눌러서 다시 연결한다', async () => {
    useSession.setState({ token: 'token-1' });
    const connect = vi.spyOn(realtime, 'connect').mockImplementation(() => undefined);
    useConnection.getState().setState('disconnected');
    render(<ConnectionIndicator />);
    await userEvent.setup().click(screen.getByRole('button', { name: /연결이 끊겼어요/ }));
    expect(connect).toHaveBeenCalledWith('token-1');
  });
});
