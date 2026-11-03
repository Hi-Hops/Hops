/** 프론트엔드는 hops-system-1의 /api/v2 계약만 사용한다. */
export function apiPath(path: string) {
  return `/api/v2${path}`;
}
