"""네트워크 없는 Mock 의미 검사. 실제 서버 구현/인증/E2E 검사가 아니다."""
from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ROWS = json.loads((ROOT / 'fixtures.json').read_text(encoding='utf-8'))['cases']
FIXTURES = {row['id']: row['value'] for row in ROWS}


class MockCommands:
    def __init__(self):
        self.results = {}
        self.version = 0
        self.created = 0

    def execute(self, actor, event, command):
        key = actor, event, command['requestId']
        canonical = json.dumps(command, sort_keys=True, ensure_ascii=False)
        if key in self.results:
            old_body, result = self.results[key]
            if old_body != canonical:
                return 'IDEMPOTENCY_KEY_REUSED'
            return deepcopy(result)
        if event != 'inquiry.create' and command['expectedInquiryVersion'] != self.version:
            return 'INQUIRY_VERSION_CONFLICT'
        if event == 'inquiry.create':
            result = deepcopy(FIXTURES['create-ack'])
            self.created += 1
        else:
            result = deepcopy(FIXTURES['send-ack' if event == 'message.send' else 'revise-ack'])
        self.version = result['inquiryVersion']
        self.results[key] = canonical, result
        return result


class DeltaBuffer:
    def __init__(self, version, run_id):
        self.version, self.run_id = version, run_id
        self.seen_events = set()
        self.chunks = {}

    def receive(self, event):
        if event['inquiryVersion'] != self.version or event['runId'] != self.run_id:
            return
        if event['eventId'] in self.seen_events:
            return
        self.seen_events.add(event['eventId'])
        payload = event['payload']
        key = payload['messageId'], payload['seq']
        if key in self.chunks and self.chunks[key] != payload['text']:
            raise ValueError('동일 조각 키의 내용 충돌: REST 저장 메시지로 복구 필요')
        self.chunks[key] = payload['text']

    def contiguous(self, message_id):
        seq, parts = 1, []
        while (message_id, seq) in self.chunks:
            parts.append(self.chunks[message_id, seq])
            seq += 1
        return ''.join(parts)


def visible_tickets(user):
    items = FIXTURES['inquiry-completed-routing']['tickets']
    return [t for t in items if 'BIZ_ADMIN' in user['roles'] or (
        user['department']['isRoutable'] and t['department']['id'] == user['department']['id'])]


def main():
    commands = MockCommands()
    actor = FIXTURES['user-0']['id']
    request = FIXTURES['create']
    first = commands.execute(actor, 'inquiry.create', request)
    # 최초 ACK가 유실돼도 서버 대역의 저장은 유지된다.
    again = commands.execute(actor, 'inquiry.create', request)
    assert first == again and commands.created == 1
    changed = {**request, 'content': '변경한 새 문의'}
    assert commands.execute(actor, 'inquiry.create', changed) == 'IDEMPOTENCY_KEY_REUSED'
    sent = commands.execute(actor, 'message.send', FIXTURES['send'])
    assert commands.version == 2
    # 현재 version이 2여도 동일 requestId의 expectedVersion=1 재전송은 성공 결과를 재사용한다.
    assert commands.execute(actor, 'message.send', FIXTURES['send']) == sent
    conflict = {**FIXTURES['send'], 'requestId': '00000000-0000-4000-8000-000000000999'}
    assert commands.execute(actor, 'message.send', conflict) == 'INQUIRY_VERSION_CONFLICT'
    revised = commands.execute(actor, 'message.revise', FIXTURES['revise'])
    assert revised == FIXTURES['revise-ack'] and commands.version == 3

    run = FIXTURES['run']
    buffer = DeltaBuffer(run['inquiryVersion'], run['id'])
    mid = FIXTURES['delta-1']['payload']['messageId']
    buffer.receive(FIXTURES['delta-1'])
    buffer.receive(FIXTURES['delta-3'])
    assert buffer.contiguous(mid) == '담당 '
    buffer.receive(FIXTURES['delta-2'])
    buffer.receive(FIXTURES['delta-2'])
    buffer.receive(FIXTURES['old-run-delta'])
    buffer.receive(FIXTURES['stale-delta'])
    assert buffer.contiguous(mid) == '담당 부서에 연결했어요.'
    same_seq_new_event = deepcopy(FIXTURES['delta-2'])
    same_seq_new_event['eventId'] = '00000000-0000-4000-8000-000000000998'
    buffer.receive(same_seq_new_event)
    assert buffer.contiguous(mid) == '담당 부서에 연결했어요.'
    same_seq_new_event['eventId'] = '00000000-0000-4000-8000-000000000997'
    same_seq_new_event['payload']['text'] = '다른 조각'
    try:
        buffer.receive(same_seq_new_event)
    except ValueError:
        pass
    else:
        raise AssertionError('조각 내용 충돌을 검출하지 못함')

    for user_index, expected in [(0, []), (1, FIXTURES['tickets-network']['items']),
                                 (2, FIXTURES['tickets-backend']['items']),
                                 (3, FIXTURES['tickets-admin']['items'])]:
        assert visible_tickets(FIXTURES[f'user-{user_index}']) == expected
    network_ticket_id = FIXTURES['ticket-network']['id']
    assert network_ticket_id not in {t['id'] for t in visible_tickets(FIXTURES['user-2'])}
    # 계정 전환 시 클라이언트 캐시·초안·구독 초기화에 대한 최소 예제.
    client = dict(user=actor, cache={'inquiry': first['inquiryId']}, draft='가상 초안', subscriptions=['INQUIRY'])
    next_user = FIXTURES['user-1']['id']
    if client['user'] != next_user:
        client = dict(user=next_user, cache={}, draft=None, subscriptions=[])
    assert not client['cache'] and client['draft'] is None and not client['subscriptions']
    print('PASS: ACK 유실·동일 재전송·키 재사용 충돌·버전 충돌·정정·역순/중복/이전 실행 조각·부서/관리자 조회·계정 전환')
    print('범위: 메모리 대역 의미 검사. 실제 Spring/Node/Python 서비스나 브라우저를 실행하지 않음.')


if __name__ == '__main__':
    main()
