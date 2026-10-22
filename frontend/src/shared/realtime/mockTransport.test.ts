import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fixture } from '@/shared/api/mockData';
import { mockBackend } from '@/shared/api/mockBackend';
import type { LoginResponse } from '@/shared/api/types';
import { MockTransport } from './mockTransport';
import { StreamBuffer } from './streamBuffer';

let transport: MockTransport;
let session: LoginResponse;
beforeEach(() => {
  mockBackend.reset();
  transport = new MockTransport();
  // Mock HTTP 경계의 로그인 응답은 공통 LoginResponse다.
  session = mockBackend.request(
    '/api/auth/login',
    'POST',
    { loginId: 'demo.requester', password: 'mock-only-password' },
    null,
  ) as LoginResponse;
});

describe('F002 실시간 통신', () => {
  it('F002-AC01 유효한 인증만 연결한다', () => {
    expect(() => {
      transport.connect('invalid');
    }).toThrow();
    transport.connect(session.accessToken);
    expect(transport.getState()).toBe('connected');
  });
  it('F002-AC02 구독 허용 후에만 이벤트를 전달하고 해제한다', async () => {
    transport.connect(session.accessToken);
    const received = vi.fn();
    transport.on('agent.progress', received);
    transport.publish('agent.progress', fixture('progress', 'AgentProgressEvent'));
    expect(received).not.toHaveBeenCalled();
    const command = fixture('subscribe-0', 'SubscriptionCommand');
    const ack = await transport.send('subscription.request', command);
    expect(ack.ok).toBe(true);
    transport.publish('agent.progress', fixture('progress', 'AgentProgressEvent'));
    expect(received).toHaveBeenCalledOnce();
    await transport.send('subscription.release', command);
    expect(transport.subscriptionCount()).toBe(0);
  });
  it('F002-AC03 타인 문의 구독을 거부한다', async () => {
    transport.connect(session.accessToken);
    const command = fixture('subscribe-0', 'SubscriptionCommand');
    command.target.id = crypto.randomUUID();
    expect((await transport.send('subscription.request', command)).ok).toBe(false);
  });
  it('F002-AC04 같은 eventId와 같은 조각 순번을 한 번만 반영한다', () => {
    const event = fixture('delta-1', 'AnswerDeltaEvent');
    const buffer = new StreamBuffer(event.inquiryVersion, event.runId);
    buffer.accept(event);
    buffer.accept(event);
    buffer.accept({ ...event, eventId: crypto.randomUUID() });
    expect(buffer.text(event.payload.messageId)).toBe('담당 ');
  });
  it('F002-AC05 순서가 바뀐 조각을 보존하고 이전 버전·실행을 무시한다', () => {
    const event = fixture('delta-1', 'AnswerDeltaEvent');
    const buffer = new StreamBuffer(event.inquiryVersion, event.runId);
    for (const key of ['delta-1', 'delta-3', 'delta-2', 'stale-delta', 'old-run-delta'])
      buffer.accept(fixture(key, 'AnswerDeltaEvent'));
    expect(buffer.text(event.payload.messageId)).toBe('담당 부서에 연결했어요.');
  });
  it('F002-AC05 같은 조각 키에 다른 내용이 오면 REST 복구 대상으로 표시한다', () => {
    const event = fixture('delta-1', 'AnswerDeltaEvent');
    const buffer = new StreamBuffer(event.inquiryVersion, event.runId);
    buffer.accept(event);
    buffer.accept({
      ...event,
      eventId: crypto.randomUUID(),
      payload: { ...event.payload, text: '충돌' },
    });
    expect(buffer.needsRecovery).toBe(true);
  });
  it('F002-AC06 연결 구간의 오류를 구분한다', () => {
    transport.connect(session.accessToken);
    transport.interrupt(true);
    expect(transport.getState()).toBe('relayError');
    transport.interrupt();
    expect(transport.getState()).toBe('disconnected');
  });
  it('F002-AC07 재접속 뒤 기존 구독을 자동 복원한 것으로 취급하지 않는다', async () => {
    transport.connect(session.accessToken);
    await transport.send('subscription.request', fixture('subscribe-0', 'SubscriptionCommand'));
    transport.interrupt();
    transport.connect(session.accessToken);
    expect(transport.subscriptionCount()).toBe(0);
  });
  it('F002-AC08 진행 이벤트와 저장 이벤트를 별도 전달한다', async () => {
    transport.connect(session.accessToken);
    await transport.send('subscription.request', fixture('subscribe-0', 'SubscriptionCommand'));
    const stored = vi.fn();
    transport.on('message.stored', stored);
    transport.publish('agent.progress', fixture('progress', 'AgentProgressEvent'));
    expect(stored).not.toHaveBeenCalled();
    transport.publish('message.stored', fixture('stored', 'MessageStoredEvent'));
    expect(stored).toHaveBeenCalledOnce();
  });
  it('F002-AC09 만료 토큰으로 재인증하지 못한다', () => {
    mockBackend.expire(session.accessToken);
    expect(() => {
      transport.connect(session.accessToken);
    }).toThrow();
  });
  it('F002-AC10 로그아웃 때 연결과 구독을 비운다', async () => {
    transport.connect(session.accessToken);
    await transport.send('subscription.request', fixture('subscribe-0', 'SubscriptionCommand'));
    transport.disconnect();
    expect(transport.getState()).toBe('disconnected');
    expect(transport.subscriptionCount()).toBe(0);
  });
  it('F002-AC11 권한을 잃은 대상은 재구독을 허용하지 않는다', async () => {
    transport.connect(session.accessToken);
    await transport.send('subscription.request', fixture('subscribe-0', 'SubscriptionCommand'));
    transport.interrupt();
    mockBackend.expire(session.accessToken);
    expect(() => {
      transport.connect(session.accessToken);
    }).toThrow();
    expect(transport.subscriptionCount()).toBe(0);
  });
});
