// 서버 값의 앞자리 0만 줄여 화면에 보여준다. 최소 4자리를 남기고, 숫자는 잘라내지 않아 번호가 겹치지 않는다.
// API 요청과 응답에는 서버 값을 그대로 쓴다.
export function displayTicketNumber(value: string) {
  const match = /^([A-Za-z]+-)(\d+)$/.exec(value);
  if (!match) return value;
  const [, prefix = '', digits = ''] = match;
  return prefix + digits.replace(/^0+/, '').padStart(4, '0');
}
