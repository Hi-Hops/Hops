import { vi } from 'vitest';

// 자동 테스트 전용 임시 값. 개인 .env나 실행 계정과 공유하지 않는다.
const password = crypto.randomUUID();
export const TEST_LOGINS = {
  requester: { loginId: crypto.randomUUID(), password },
  network: { loginId: crypto.randomUUID(), password },
  backend: { loginId: crypto.randomUUID(), password },
  admin: { loginId: crypto.randomUUID(), password },
};
export function setupMockCredentials() {
  vi.stubEnv('VITE_DATA_MODE', 'mock');
  vi.stubEnv('VITE_MOCK_PREVIEW_ID', '');
  vi.stubEnv('VITE_MOCK_REQUESTER_ID', TEST_LOGINS.requester.loginId);
  vi.stubEnv('VITE_MOCK_NETWORK_ID', TEST_LOGINS.network.loginId);
  vi.stubEnv('VITE_MOCK_BACKEND_ID', TEST_LOGINS.backend.loginId);
  vi.stubEnv('VITE_MOCK_ADMIN_ID', TEST_LOGINS.admin.loginId);
  vi.stubEnv('VITE_MOCK_PASSWORD', password);
}
