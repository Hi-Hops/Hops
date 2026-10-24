// 공통 문서 DATA_TYPES의 UserContent 공백 집합을 따른다.
const whitespace = new Set([
  ...Array.from({ length: 5 }, (_, index) => 0x0009 + index),
  ...Array.from({ length: 11 }, (_, index) => 0x2000 + index),
  0x0020,
  0x0085,
  0x00a0,
  0x1680,
  0x2028,
  0x2029,
  0x202f,
  0x205f,
  0x3000,
  0xfeff,
]);
export function codePointLength(value: string) {
  return Array.from(value).length;
}
export function contentError(value: string) {
  const length = codePointLength(value);
  if (length > 4000)
    return `4,000자를 ${String(length - 4000)}자 넘었어요. 원문을 줄이거나 나눠 보내 주세요.`;
  if (!Array.from(value).some((character) => !whitespace.has(character.codePointAt(0) ?? 0)))
    return '내용을 입력해 주세요.';
  if (/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value))
    return '올바르지 않은 문자가 있어요.';
  return null;
}
