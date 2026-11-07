/**
 * AI 답변·승인 안내 본문을 줄 단위로 나눠 제목, 순서/비순서 목록, 문단으로 묶는다.
 * HTML은 해석하지 않는다. 표시는 shared/ui/MarkdownText가 React 요소로 한다.
 */
export type Block =
  | { kind: 'heading'; level: number; text: string }
  // start: 순서 목록 첫 항목의 원문 번호. 발췌가 절차 중간부터 시작해도 원문 번호를 지킨다
  | { kind: 'list'; ordered: boolean; start: number; items: string[] }
  | { kind: 'paragraph'; lines: string[] };

const HEADING = /^(#{1,6})\s+(.+)$/;
const BULLET = /^\s*[-*]\s+(.+)$/;
const NUMBERED = /^\s*(\d+)[.)]\s+(.+)$/;

export function parseBlocks(text: string): Block[] {
  const blocks: Block[] = [];
  let paragraph: string[] = [];
  const flush = () => {
    if (paragraph.length) blocks.push({ kind: 'paragraph', lines: paragraph });
    paragraph = [];
  };
  for (const line of text.replace(/\r\n/g, '\n').split('\n')) {
    const heading = HEADING.exec(line);
    const bullet = BULLET.exec(line);
    const numbered = bullet ? null : NUMBERED.exec(line);
    if (!line.trim()) {
      flush();
    } else if (heading) {
      flush();
      const [, marks = '', title = ''] = heading;
      blocks.push({ kind: 'heading', level: marks.length, text: title.trim() });
    } else if (bullet || numbered) {
      flush();
      const ordered = numbered !== null;
      const item = ((numbered ? numbered[2] : bullet?.[1]) ?? '').trim();
      const last = blocks.at(-1);
      if (last?.kind === 'list' && last.ordered === ordered) last.items.push(item);
      else blocks.push({ kind: 'list', ordered, start: Number(numbered?.[1] ?? 1), items: [item] });
    } else {
      paragraph.push(line);
    }
  }
  flush();
  return blocks;
}
