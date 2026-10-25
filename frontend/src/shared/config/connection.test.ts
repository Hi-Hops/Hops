import { describe, expect, it, vi } from 'vitest';
import { connectionMode, liveConnection } from './connection';

describe('F001·F002 서버 연결 설정', () => {
  it('F001-AC01 명시하지 않으면 기존 Mock 모드를 유지한다', () => {
    vi.stubEnv('VITE_DATA_MODE', '');
    expect(connectionMode()).toBe('mock');
  });
  it('F001-AC01 live와 잘못된 모드를 구분한다', () => {
    vi.stubEnv('VITE_DATA_MODE', 'live');
    expect(connectionMode()).toBe('live');
    vi.stubEnv('VITE_DATA_MODE', 'typo');
    expect(connectionMode).toThrow('mock 또는 live');
  });
  it('F002-AC01 서버 주소와 Socket.IO 접속 경로를 적용한다', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080/');
    vi.stubEnv('VITE_SOCKET_URL', 'http://localhost:3001');
    vi.stubEnv('VITE_SOCKET_PATH', '/chat/socket.io');
    expect(liveConnection()).toEqual({
      apiBaseUrl: 'http://localhost:8080',
      socketUrl: 'http://localhost:3001',
      socketPath: '/chat/socket.io',
    });
  });
  it.each([
    '',
    'http://localhost:8080/api',
    'ftp://localhost',
    'http://user:secret@localhost',
    'http://localhost?token=value',
  ])('F001-AC01 잘못된 주소를 거부하고 Mock으로 바꾸지 않는다: %s', (url) => {
    vi.stubEnv('VITE_API_BASE_URL', url);
    vi.stubEnv('VITE_SOCKET_URL', 'http://localhost:3001');
    vi.stubEnv('VITE_SOCKET_PATH', '/socket.io');
    expect(liveConnection).toThrow();
  });
});
