import type { CurrentUser, SpringClient, SpringConfig } from '../contracts/types.js';
import { apiError, failure, date, uuid, sameTarget, keys } from '../contracts/validation.js';

function currentUser(user: unknown): user is CurrentUser {
  const text = (v: unknown) => typeof v === 'string' && v.length > 0;

  return (
    keys(user, ['id', 'employeeNo', 'name', 'department', 'roles']) &&
    uuid(user.id) &&
    text(user.employeeNo) &&
    text(user.name) &&
    keys(user.department, ['id', 'code', 'name', 'isRoutable']) &&
    uuid(user.department.id) &&
    text(user.department.code) &&
    text(user.department.name) &&
    typeof user.department.isRoutable === 'boolean' &&
    Array.isArray(user.roles) &&
    user.roles.every(
      (role) => typeof role === 'string' && ['BIZ_ADMIN', 'AGENT_OPS_ADMIN'].includes(role),
    )
  );
}

export function createSpringClient({ springUrl, nodeCredential }: SpringConfig): SpringClient {
  async function post(path: string, body: unknown): Promise<unknown> {
    try {
      const response = await fetch(new URL(path, springUrl), {
        method: 'POST',
        redirect: 'error',
        signal: AbortSignal.timeout(3000),
        headers: {
          Authorization: `Bearer ${nodeCredential}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
      });
      const value: unknown = await response.json();

      if (!response.ok) throw apiError(value) ? value : failure('SERVICE_UNAVAILABLE');

      return value;
    } catch (error) {
      throw apiError(error) ? error : failure('SERVICE_UNAVAILABLE');
    }
  }

  return {
    async authenticate(accessToken) {
      const value = await post('/internal/realtime/authenticate', {
        accessToken,
      });

      if (!keys(value, ['user', 'expiresAt']) || !currentUser(value.user) || !date(value.expiresAt))
        throw failure('SERVICE_UNAVAILABLE');

      if (Date.parse(value.expiresAt) <= Date.now()) throw failure('ACCESS_TOKEN_EXPIRED');

      return { user: value.user, expiresAt: value.expiresAt };
    },
    async authorize(accessToken, target) {
      const value = await post('/internal/realtime/authorize-subscription', {
        accessToken,
        target,
      });

      if (!keys(value, ['target']) || !sameTarget(value.target, target))
        throw failure('SERVICE_UNAVAILABLE');
    },
  };
}
