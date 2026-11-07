import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { parseBlocks } from '@/shared/lib/markdown';
import { MarkdownText } from './MarkdownText';

const GUIDE = [
  '## 절차',
  '1. 사내 포털 로그인 화면을 엽니다.',
  '2. **사번**과 비밀번호를 입력합니다.',
  '',
  '## 확인할 수 있는 결과',
  '- 로그인하면 `대상 시스템` 화면이 열립니다.',
  '- SSO 세션은 8시간 유지됩니다.',
  '',
  '참고 자료: 사내 포털 로그인과 SSO 세션 이용',
].join('\n');

describe('F003 AI 답변 서식', () => {
  it('F003-CONTRACT-12 제목·목록·굵게·코드를 기호 대신 서식으로 표시한다', () => {
    render(<MarkdownText text={GUIDE} />);

    expect(screen.getByRole('heading', { name: '절차' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '확인할 수 있는 결과' })).toBeVisible();
    expect(screen.getAllByRole('list').map((list) => list.tagName)).toEqual(['OL', 'UL']);
    expect(screen.getAllByRole('listitem')).toHaveLength(4);
    expect(screen.getByText('사번').tagName).toBe('STRONG');
    expect(screen.getByText('대상 시스템').tagName).toBe('CODE');
    expect(screen.getByText('참고 자료: 사내 포털 로그인과 SSO 세션 이용')).toBeVisible();
    expect(document.body.textContent).not.toContain('##');
    expect(document.body.textContent).not.toContain('**');
  });

  it('F003-CONTRACT-12 본문의 HTML 태그는 해석하지 않고 글자로 보인다', () => {
    const { container } = render(
      <MarkdownText text={'<img src=x onerror="alert(1)"> 안내\n<script>alert(1)</script>'} />,
    );

    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
    expect(container).toHaveTextContent('<img src=x onerror="alert(1)"> 안내');
  });

  it('F003-CONTRACT-12 줄바꿈과 질문 번호를 그대로 읽을 수 있게 나눈다', () => {
    expect(
      parseBlocks('문제 1\n처리에 필요한 내용을 알려 주시겠어요?\n1. 기대 동작\n2. 발생 시점'),
    ).toEqual([
      { kind: 'paragraph', lines: ['문제 1', '처리에 필요한 내용을 알려 주시겠어요?'] },
      { kind: 'list', ordered: true, items: ['기대 동작', '발생 시점'] },
    ]);
    expect(parseBlocks('#제목 아님\n- \n')).toEqual([
      { kind: 'paragraph', lines: ['#제목 아님', '- '] },
    ]);
  });
});
