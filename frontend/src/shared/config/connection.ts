export function connectionMode() {
  const mode = import.meta.env.VITE_DATA_MODE?.trim() ?? 'mock';
  if (mode === '') return 'mock';
  if (mode !== 'mock' && mode !== 'live')
    throw new Error('VITE_DATA_MODE는 mock 또는 live로 설정해 주세요.');
  return mode;
}

function serverUrl(value: string | undefined, name: string) {
  if (!value) throw new Error(`${name}에 서버 주소를 입력해 주세요.`);
  const url = new URL(value);
  if (
    !['http:', 'https:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== '/'
  )
    throw new Error(`${name}에는 경로·인증 정보가 없는 http(s) 서버 주소를 입력해 주세요.`);
  return url.origin;
}

export function liveConnection() {
  return { apiBaseUrl: serverUrl(import.meta.env.VITE_API_BASE_URL, 'VITE_API_BASE_URL') };
}
