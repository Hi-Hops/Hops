import type { ReactNode } from 'react';
import { parseBlocks } from '@/shared/lib/markdown';
import styles from './MarkdownText.module.css';

const INLINE = /(\*\*[^*]+\*\*|`[^`]+`)/g;

function inline(text: string): ReactNode[] {
  return text
    .split(INLINE)
    .filter(Boolean)
    .map((part, index) => {
      if (part.startsWith('**') && part.endsWith('**') && part.length > 4)
        return <strong key={index}>{part.slice(2, -2)}</strong>;
      if (part.startsWith('`') && part.endsWith('`') && part.length > 2)
        return <code key={index}>{part.slice(1, -1)}</code>;
      return part;
    });
}

/**
 * AI 답변·승인 안내 본문의 간단한 마크다운(제목, 순서/비순서 목록, 굵게, 인라인 코드)을 서식으로 표시한다.
 * HTML로 해석하지 않고 React 요소로만 만든다. 본문에 태그가 있어도 글자로 보인다.
 * 지원하지 않는 문법은 원래 글자 그대로 둔다.
 */
export function MarkdownText({ text, className }: { text: string; className?: string }) {
  return (
    <div className={`${styles.markdown} ${className ?? ''}`}>
      {parseBlocks(text).map((block, index) => {
        if (block.kind === 'heading') {
          // 대화 안의 제목이라 페이지 제목보다 낮은 단계로 둔다
          const Tag = block.level <= 2 ? 'h3' : 'h4';
          return <Tag key={index}>{inline(block.text)}</Tag>;
        }
        if (block.kind === 'list') {
          const items = block.items.map((item, i) => <li key={i}>{inline(item)}</li>);
          return block.ordered ? <ol key={index}>{items}</ol> : <ul key={index}>{items}</ul>;
        }
        return (
          <p key={index}>
            {block.lines.map((line, i) => (
              <span key={i}>
                {i > 0 && <br />}
                {inline(line)}
              </span>
            ))}
          </p>
        );
      })}
    </div>
  );
}
