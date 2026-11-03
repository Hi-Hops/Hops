// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { codePointLength, contentError } from './content';

describe('F003 신규 계약의 사용자 입력 경계', () => {
  it('F003-AC02 이모지를 코드 포인트로 세며 4,000자 경계를 지킨다', () => {
    expect(codePointLength('가😀')).toBe(2);
    expect(contentError('😀'.repeat(4000))).toBeNull();
    expect(contentError('😀'.repeat(4001))).toContain('4,000자');
  });
  it.each(['', '\u3000\u00a0\t', '\ufeff\n', '\ud800', '\udc00'])(
    'F003-AC02 공백·잘못된 Unicode 입력을 거부한다',
    (content) => {
      expect(contentError(content)).not.toBeNull();
    },
  );
  it('F003-AC02 내용이 있는 앞뒤 공백과 줄바꿈은 허용한다', () => {
    expect(contentError('  문의 원문\n\t')).toBeNull();
  });
});
