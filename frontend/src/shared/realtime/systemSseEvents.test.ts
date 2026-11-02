// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { decodeSystemEvent } from './systemSseEvents';

const target = { type: 'INQUIRY' as const, id: crypto.randomUUID() };
const common = () => ({
  eventId: crypto.randomUUID(),
  inquiryId: target.id,
  inquiryVersion: 1,
  runId: crypto.randomUUID(),
});
describe('F002 단일 계약의 flat SSE 경계', () => {
  it('F002-CONTRACT-01 flat 답변 조각을 현재 대상의 내부 알림으로 변환한다', () => {
    const event = { ...common(), messageId: crypto.randomUUID(), seq: 1, text: '임시 답변' };
    expect(decodeSystemEvent('answer.delta', event, target)).toEqual({
      name: 'answer.delta',
      value: {
        eventId: event.eventId,
        inquiryId: target.id,
        inquiryVersion: 1,
        runId: event.runId,
        payload: { messageId: event.messageId, seq: 1, text: '임시 답변' },
      },
    });
  });
  it('F002-CONTRACT-01 이전 payload envelope를 신규 wire로 받아들이지 않는다', () => {
    expect(
      decodeSystemEvent(
        'answer.delta',
        { ...common(), payload: { messageId: crypto.randomUUID(), seq: 1, text: '이전 형식' } },
        target,
      ),
    ).toBeNull();
  });
  it('F002-CONTRACT-01 저장 알림의 nullable requestId도 필수 키로 검사한다', () => {
    const event = {
      eventId: crypto.randomUUID(),
      inquiryId: target.id,
      messageId: crypto.randomUUID(),
    };
    expect(
      decodeSystemEvent('message.stored', { ...event, requestId: null }, target),
    ).toMatchObject({ name: 'message.stored', value: { requestId: null } });
    expect(decodeSystemEvent('message.stored', event, target)).toBeNull();
  });
  it('F002-CONTRACT-01 직원 문의에 CURATOR 내부 진행을 전달하지 않는다', () => {
    const event = { ...common(), stepNo: 1, role: 'INQUIRY', displaySummary: '문의 확인 중' };
    expect(decodeSystemEvent('agent.progress', event, target)).toMatchObject({
      name: 'agent.progress',
      value: { payload: { displaySummary: '문의 확인 중' } },
    });
    expect(decodeSystemEvent('agent.progress', { ...event, role: 'CURATOR' }, target)).toBeNull();
  });
  it('F002-CONTRACT-02 다른 대상·잘못된 식별자는 전달하지 않는다', () => {
    const event = { ...common(), messageId: crypto.randomUUID(), seq: 1, text: '조각' };
    expect(
      decodeSystemEvent('answer.delta', { ...event, inquiryId: crypto.randomUUID() }, target),
    ).toBeNull();
    expect(decodeSystemEvent('answer.delta', { ...event, eventId: 'invalid' }, target)).toBeNull();
  });
});
