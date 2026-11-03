// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { systemMockBackend } from '@/shared/api/systemMockBackend';
import { systems, users } from '@/shared/api/systemMockData';
import { readLogin, readInquiries } from '@/shared/api/systemWire';
import { TEST_LOGINS } from '@/test/mockCredentials';
import type { AnswerDeltaEvent } from '@/shared/api/types';
import { SystemMockTransport } from './systemMockTransport';
import { StreamBuffer } from './streamBuffer';

let transport: SystemMockTransport;
beforeEach(() => {
  systemMockBackend.reset();
  transport = new SystemMockTransport();
});
afterEach(() => {
  transport.disconnect();
  systemMockBackend.reset();
});
function signIn(role: keyof typeof TEST_LOGINS = 'requester') {
  return readLogin(
    systemMockBackend.request('/api/v2/auth/login', 'POST', TEST_LOGINS[role], null),
  );
}

describe('F002 시스템 Mock 수신·권한', () => {
  it('F002-CONTRACT-01 인증된 세션만 연결한다', () => {
    expect(() => {
      transport.connect('invalid');
    }).toThrow();
    transport.connect(signIn().accessToken);
    expect(transport.getState()).toBe('connected');
  });
  it('F002-CONTRACT-01 본인 문의 구독을 동기화하고 다른 문의는 거부한다', async () => {
    const session = signIn();
    const own = readInquiries(
      systemMockBackend.request('/api/v2/inquiries', 'GET', undefined, session.accessToken),
    ).items[0];
    if (!own) throw new Error('본인 문의 필요');
    const sync = vi.fn(() => Promise.resolve());
    transport.onSync(sync);
    transport.connect(session.accessToken);
    const release = transport.subscribe({ type: 'INQUIRY', id: own.id });
    await vi.waitFor(() => {
      expect(sync).toHaveBeenCalledWith({ type: 'INQUIRY', id: own.id });
    });
    const denied = vi.fn();
    transport.subscribe({ type: 'INQUIRY', id: crypto.randomUUID() }, denied);
    expect(denied).toHaveBeenCalledWith('denied');
    release();
  });
  it('F002-CONTRACT-02 현재 PRIMARY 회수 시 열린 대상도 거부한다', () => {
    const session = signIn('network');
    transport.connect(session.accessToken);
    const state = vi.fn();
    transport.subscribe({ type: 'SYSTEM_TICKETS', id: systems[0].id }, state);
    expect(state).toHaveBeenLastCalledWith('connected');
    systemMockBackend.revokeSystem(users[1].id, systems[0].id);
    expect(state).toHaveBeenLastCalledWith('denied');
  });
  it('F002-CONTRACT-02 만료 세션을 다시 연결하지 못하며 해제 시 구독을 비운다', () => {
    const session = signIn('network');
    transport.connect(session.accessToken);
    const sync = vi.fn(() => Promise.resolve());
    transport.onSync(sync);
    transport.subscribe({ type: 'SYSTEM_TICKETS', id: systems[0].id });
    sync.mockClear();
    transport.disconnect();
    systemMockBackend.revokeSystem(users[1].id, systems[0].id);
    expect(sync).not.toHaveBeenCalled();
    systemMockBackend.expire(session.accessToken);
    expect(() => {
      transport.connect(session.accessToken);
    }).toThrow();
  });
});
const inquiryId = crypto.randomUUID();
const runId = crypto.randomUUID();
const messageId = crypto.randomUUID();
function delta(
  seq: number,
  text: string,
  extra: Partial<Omit<AnswerDeltaEvent, 'payload'>> = {},
): AnswerDeltaEvent {
  return {
    eventId: crypto.randomUUID(),
    inquiryId,
    inquiryVersion: 1,
    runId,
    ...extra,
    payload: { messageId, seq, text },
  };
}
describe('F002 답변 조각 버퍼', () => {
  it('F002-CONTRACT-01 같은 이벤트와 같은 순번은 한 번만 반영한다', () => {
    const event = delta(1, '시스템 ');
    const buffer = new StreamBuffer(1, runId);
    buffer.accept(event);
    buffer.accept(event);
    buffer.accept({ ...event, eventId: crypto.randomUUID() });
    expect(buffer.text(messageId)).toBe('시스템 ');
  });
  it('F002-CONTRACT-01 순서가 바뀐 조각은 보존하고 이전 버전·실행은 무시한다', () => {
    const buffer = new StreamBuffer(1, runId);
    for (const event of [
      delta(1, '시스템 '),
      delta(3, '확인 중'),
      delta(2, '정보 '),
      delta(4, '이전 버전', { inquiryVersion: 2 }),
      delta(4, '이전 실행', { runId: crypto.randomUUID() }),
    ])
      buffer.accept(event);
    expect(buffer.text(messageId)).toBe('시스템 정보 확인 중');
  });
  it('F002-CONTRACT-01 같은 순번의 다른 내용은 복구 대상으로 표시한다', () => {
    const buffer = new StreamBuffer(1, runId);
    buffer.accept(delta(1, '원문'));
    buffer.accept(delta(1, '충돌'));
    expect(buffer.needsRecovery).toBe(true);
  });
  it('F002-CONTRACT-01 순번 공백과 초기화를 구분한다', () => {
    const buffer = new StreamBuffer(1, runId);
    buffer.accept(delta(1, '첫 '));
    buffer.accept(delta(3, '셋'));
    expect(buffer.hasGaps).toBe(true);
    expect(buffer.text(messageId)).toBe('첫 ');
    buffer.accept(delta(2, '둘 '));
    expect(buffer.hasGaps).toBe(false);
    buffer.clear();
    expect(buffer.text(messageId)).toBe('');
    expect(buffer.needsRecovery).toBe(false);
  });
});
