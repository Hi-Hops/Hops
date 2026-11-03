const accounts = [
  { role: 'REQUESTER', loginIdEnv: 'VITE_MOCK_REQUESTER_ID' },
  { role: 'SYSTEM_STAFF', loginIdEnv: 'VITE_MOCK_NETWORK_ID' },
  { role: 'FILE_STAFF', loginIdEnv: 'VITE_MOCK_BACKEND_ID' },
  { role: 'ADMIN', loginIdEnv: 'VITE_MOCK_ADMIN_ID' },
] as const;

/** Mock 전용 브라우저 환경 값. 실제 인증 비밀값을 보관하는 용도가 아니다. */
export function readMockAuth(env: Record<string, unknown> = import.meta.env) {
  const password = env.VITE_MOCK_PASSWORD;
  if (typeof password !== 'string' || !password.trim()) return null;
  const previewId = env.VITE_MOCK_PREVIEW_ID;
  if (typeof previewId === 'string' && previewId.trim())
    return { password, accounts: [{ role: 'PREVIEW' as const, loginId: previewId.trim() }] };
  const configured = accounts.map((account) => {
    const raw = env[account.loginIdEnv];
    return { role: account.role, loginId: typeof raw === 'string' ? raw.trim() : '' };
  });
  if (configured.some((account) => !account.loginId)) return null;
  if (new Set(configured.map((account) => account.loginId)).size !== configured.length) return null;
  return { password, accounts: configured };
}
