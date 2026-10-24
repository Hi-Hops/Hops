import mapping from '@fixtures/accounts.json';

/** Mock 전용 브라우저 환경 값. 실제 인증 비밀값을 보관하는 용도가 아니다. */
export function readMockAuth(env: Record<string, unknown> = import.meta.env) {
  const password = env[mapping.passwordEnv];
  if (typeof password !== 'string' || !password.trim()) return null;
  const accounts = mapping.accounts.map((account) => {
    const raw = env[account.loginIdEnv];
    return { userFixture: account.userFixture, loginId: typeof raw === 'string' ? raw.trim() : '' };
  });
  if (accounts.some((account) => !account.loginId)) return null;
  if (new Set(accounts.map((account) => account.loginId)).size !== accounts.length) return null;
  return { password, accounts };
}
