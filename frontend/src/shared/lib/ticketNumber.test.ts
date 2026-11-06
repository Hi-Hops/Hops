import { expect, it } from 'vitest';
import { displayTicketNumber } from './ticketNumber';

it.each([
  ['T-0000000000000123', 'T-0123'],
  ['T-0000000000000001', 'T-0001'],
  ['T-0000000000012345', 'T-12345'],
  ['T-0000000000000000', 'T-0000'],
  ['TK-0013', 'TK-0013'],
])('%s는 앞자리 0을 줄여 %s로 표시한다', (value, expected) => {
  expect(displayTicketNumber(value)).toBe(expected);
});

it('자릿수가 달라도 다른 번호는 다르게 표시한다', () => {
  expect(displayTicketNumber('T-0000000000010123')).not.toBe(
    displayTicketNumber('T-0000000000000123'),
  );
});

it.each(['TK-TEST', 'T-12A', ''])('형식이 다른 값 "%s"는 그대로 표시한다', (value) => {
  expect(displayTicketNumber(value)).toBe(value);
});
