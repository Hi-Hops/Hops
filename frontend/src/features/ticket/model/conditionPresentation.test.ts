import { describe, expect, it } from 'vitest';
import { distinctStatements, statementParts } from './conditionPresentation';
describe('F009 서술형 조건의 읽기 표시', () => {
  it('F009-CONTRACT-08 중복만 줄이고 다른 값과 쉼표가 포함된 문장은 보존한다', () => {
    const values = [
      '업무·화면: 문서함',
      '업무·화면:  문서함',
      '조회 상태: 승인, 오늘 기안',
      '조회 상태: 전체',
    ];
    expect(distinctStatements(values)).toEqual([values[0], values[2], values[3]]);
  });
  it('F009-CONTRACT-08 라벨만 분리하고 본문의 콜론과 물음표는 보존한다', () => {
    expect(statementParts('확인 내용: 주소는 https://example.test/a:b, 왜 안 보일까요?')).toEqual({
      label: '확인 내용',
      value: '주소는 https://example.test/a:b, 왜 안 보일까요?',
    });
  });
});
