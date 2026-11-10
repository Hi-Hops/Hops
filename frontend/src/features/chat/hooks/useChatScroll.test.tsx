import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useChatScroll } from './useChatScroll';

function ChatLog({ id, messages, activity }: { id: string; messages: string; activity: string }) {
  const { log, observe, remember, cancelPrepend } = useChatScroll(id, messages, activity);
  return (
    <>
      <div ref={log} aria-label="대화 내역" onScroll={observe}>
        {messages} {activity}
      </div>
      <button onClick={remember}>이전 대화 불러오기</button>
      <button onClick={cancelPrepend}>조회 실패</button>
    </>
  );
}

function setup(initialMessages = 'a:1') {
  let props = { id: 'inquiry-1', messages: initialMessages, activity: '' };
  const view = render(<ChatLog {...props} />);
  const log = screen.getByLabelText('대화 내역');
  let height = 1000;
  Object.defineProperties(log, {
    scrollHeight: { configurable: true, get: () => height },
    clientHeight: { configurable: true, value: 300 },
  });
  function update(changes: Partial<typeof props>, nextHeight = height) {
    height = nextHeight;
    props = { ...props, ...changes };
    view.rerender(<ChatLog {...props} />);
  }
  function scrollUp(top = 200) {
    log.scrollTop = top;
    fireEvent.scroll(log);
  }
  return { log, update, scrollUp };
}

describe('F003 채팅 최신 내용 자동 스크롤', () => {
  it('F003-DETAIL-12 초기 메시지를 조회하면 최신 위치로 이동한다', () => {
    const { log, update } = setup('');
    update({ messages: 'a:1,b:1' });
    expect(log.scrollTop).toBe(1000);
  });

  it('F003-DETAIL-12 위를 읽고 있어도 새 사용자·AI 메시지와 정정을 따라간다', () => {
    const { log, update, scrollUp } = setup();
    for (const messages of ['a:1,user:1', 'a:1,user:1,ai:1', 'a:1,user:2,ai:1']) {
      scrollUp();
      update({ messages }, 1500);
      expect(log.scrollTop).toBe(1500);
    }
  });

  it('F003-DETAIL-12 빈 대화의 분석 시작과 진행·답변 조각·완료·해결 확인을 따라간다', () => {
    const { log, update, scrollUp } = setup('');
    for (const activity of [
      '분석 대기',
      '분석 중',
      '진행 단계',
      '답변',
      '답변 조각',
      '완료',
      '해결 확인',
    ]) {
      scrollUp();
      update({ activity }, 1500);
      expect(log.scrollTop).toBe(1500);
    }
  });

  it('F003-DETAIL-12 내용 변화 없는 재조회는 읽던 위치를 유지한다', () => {
    const { log, update, scrollUp } = setup();
    scrollUp();
    update({ messages: 'a:1' });
    expect(log.scrollTop).toBe(200);
  });

  it('F003-DETAIL-03 이전 대화 높이만큼 위치를 보정하고 다음 새 메시지는 최신으로 이동한다', () => {
    const { log, update, scrollUp } = setup();
    scrollUp(40);
    fireEvent.click(screen.getByRole('button', { name: '이전 대화 불러오기' }));
    update({ messages: 'older:1,a:1' }, 1600);
    expect(log.scrollTop).toBe(640);
    update({ messages: 'older:1,a:1,new:1' }, 1900);
    expect(log.scrollTop).toBe(1900);
  });

  it('F003-DETAIL-03 과거 조회 중 답변 도착·사용자 스크롤 후에도 보정 기준을 유지한다', () => {
    const { log, update, scrollUp } = setup();
    scrollUp(40);
    fireEvent.click(screen.getByRole('button', { name: '이전 대화 불러오기' }));
    update({ activity: '답변 조각' }, 1100);
    expect(log.scrollTop).toBe(1100);
    scrollUp(100);
    update({ messages: 'older:1,a:1' }, 1600);
    expect(log.scrollTop).toBe(600);
  });

  it('F003-DETAIL-03 과거 조회 실패 이후 새 메시지는 최신으로 이동한다', () => {
    const { log, update, scrollUp } = setup();
    scrollUp(40);
    fireEvent.click(screen.getByRole('button', { name: '이전 대화 불러오기' }));
    fireEvent.click(screen.getByRole('button', { name: '조회 실패' }));
    update({ messages: 'a:1,new:1' }, 1600);
    expect(log.scrollTop).toBe(1600);
  });

  it('F003-DETAIL-12 문의를 전환하면 이전 대화 조회 기준을 지우고 최신으로 이동한다', () => {
    const { log, update, scrollUp } = setup();
    scrollUp(40);
    fireEvent.click(screen.getByRole('button', { name: '이전 대화 불러오기' }));
    update({ id: 'inquiry-2', messages: 'other:1,a:1' }, 1600);
    expect(log.scrollTop).toBe(1600);
  });
});
