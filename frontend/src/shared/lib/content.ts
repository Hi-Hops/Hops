import schema from '@contracts/contract.schema.json';
const nonWhitespace = new RegExp(schema.$defs.UserContent.pattern, 'u');
export function codePointLength(value: string) {
  return Array.from(value).length;
}
export function contentError(value: string) {
  const length = codePointLength(value);
  if (length > 4000)
    return `4,000자를 ${String(length - 4000)}자 넘었어요. 원문을 줄이거나 나눠 보내 주세요.`;
  if (!nonWhitespace.test(value)) return '내용을 입력해 주세요.';
  if (/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value))
    return '올바르지 않은 문자가 있어요.';
  return null;
}
