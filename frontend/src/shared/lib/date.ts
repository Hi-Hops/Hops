export function displayDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}

export function isToday(value: string, now = new Date()) {
  return new Date(value).toDateString() === now.toDateString();
}

// 대화 시각은 오늘이면 시:분만, 이전 날짜면 날짜까지 표시한다.
export function displayChatTime(value: string) {
  if (!isToday(value)) return displayDate(value);
  return new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}
