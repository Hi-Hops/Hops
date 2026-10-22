"""공통 계약·fixture·생성 DTO를 검사한다. 외부 패키지 없는 저장소 전용 검사기.

JSON Schema 전체 구현이 아니다. 사용한 키워드만 지원하고 모르는 키워드는 실패한다.
서비스 통합 시 Draft 2020-12 구현체로도 검증해야 한다.
"""
from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import re
import sys
from uuid import UUID

from generate import render

ROOT = Path(__file__).resolve().parent
MOCK = ROOT.parent / 'mocks'
SCHEMA = json.loads((ROOT / 'contract.schema.json').read_text(encoding='utf-8'))
DEFS = SCHEMA['$defs']


def check_schema(node: dict) -> None:
    allowed = {'$ref', 'type', 'properties', 'required', 'additionalProperties', 'items',
               'oneOf', 'anyOf', 'enum', 'const', 'minimum', 'maximum', 'minLength',
               'maxLength', 'pattern', 'format', 'description'}
    assert not (set(node) - allowed), f'검사 미지원 키워드: {set(node) - allowed}'
    if '$ref' in node:
        assert node['$ref'].startswith('#/$defs/') and node['$ref'].split('/')[-1] in DEFS
    for value in node.get('properties', {}).values():
        check_schema(value)
    for value in node.get('oneOf', []) + node.get('anyOf', []):
        check_schema(value)
    if 'items' in node:
        check_schema(node['items'])
    if node.get('type') == 'object':
        assert node['additionalProperties'] is False
        assert set(node['properties']) == set(node['required'])


def validate(value, schema: dict, path: str = '$') -> None:
    def require(ok: bool, message: str) -> None:
        if not ok:
            raise ValueError(f'{path}: {message}')

    if '$ref' in schema:
        validate(value, DEFS[schema['$ref'].split('/')[-1]], path)
        return
    for key in ('oneOf', 'anyOf'):
        if key in schema:
            matches = 0
            for branch in schema[key]:
                try:
                    validate(value, branch, path)
                    matches += 1
                except ValueError:
                    pass
            require(matches == 1 if key == 'oneOf' else matches >= 1, f'{key} 불일치')
            return
    kinds = schema.get('type')
    if kinds is not None:
        kinds = kinds if isinstance(kinds, list) else [kinds]
        tests = {'null': value is None, 'string': isinstance(value, str),
                 'integer': type(value) is int or (type(value) is float and value.is_integer()),
                 'boolean': type(value) is bool, 'object': type(value) is dict, 'array': type(value) is list}
        require(any(tests[k] for k in kinds), 'JSON 타입 불일치')
    if 'const' in schema:
        require(type(value) is type(schema['const']) and value == schema['const'], 'const 불일치')
    if 'enum' in schema:
        require(any(type(value) is type(v) and value == v for v in schema['enum']), 'enum 불일치')
    if value is None:
        return
    if isinstance(value, str):
        require(not any(0xD800 <= ord(c) <= 0xDFFF for c in value), '단독 surrogate 금지')
        require(len(value) >= schema.get('minLength', 0), '문자열 너무 짧음')
        require(len(value) <= schema.get('maxLength', sys.maxsize), '문자열 너무 김')
        if 'pattern' in schema:
            require(re.search(schema['pattern'], value) is not None, 'pattern 불일치')
        if schema.get('format') == 'uuid':
            try:
                require(str(UUID(value)) == value, '정규 UUID 형식 필요')
            except (ValueError, AttributeError):
                raise ValueError(f'{path}: UUID 불일치') from None
        if schema.get('format') == 'date-time':
            try:
                require(value.endswith('Z'), 'UTC Z 필요')
                datetime.fromisoformat(value[:-1] + '+00:00')
            except ValueError:
                raise ValueError(f'{path}: 시각 불일치') from None
    if type(value) in (int, float):
        require(value >= schema.get('minimum', -float('inf')), '최솟값 미달')
        require(value <= schema.get('maximum', float('inf')), '최댓값 초과')
    if isinstance(value, dict):
        props = schema.get('properties', {})
        require(set(schema.get('required', [])) <= set(value), '필수 필드 누락')
        if schema.get('additionalProperties') is False:
            require(set(value) <= set(props), '정의하지 않은 필드')
        for key, item in value.items():
            if key in props:
                validate(item, props[key], path + '.' + key)
    if isinstance(value, list):
        for index, item in enumerate(value):
            validate(item, schema['items'], f'{path}[{index}]')


def main() -> None:
    for schema in DEFS.values():
        check_schema(schema)
    for name, expected in render().items():
        assert (ROOT / name).read_text(encoding='utf-8') == expected, f'생성 파일 불일치: {name}'
    fixture_doc = json.loads((MOCK / 'fixtures.json').read_text(encoding='utf-8'))
    rows = fixture_doc['cases']
    fixtures = {row['id']: row for row in rows}
    assert len(fixtures) == len(rows), 'fixture ID 중복'
    for row in rows:
        validate(row['value'], {'$ref': '#/$defs/' + row['type']}, row['id'])
    values = {key: row['value'] for key, row in fixtures.items()}
    scenarios = json.loads((MOCK / 'scenarios.json').read_text(encoding='utf-8'))['scenarios']
    for scenario in scenarios:
        assert scenario['fixtureSequence'] and scenario['expected']
        for fixture_id in scenario['fixtureSequence']:
            assert fixture_id in fixtures, f'시나리오 참조 누락: {fixture_id}'
        for ac in scenario['acceptanceCriteria']:
            feature = next(ROOT.parent.glob(ac[:4] + '-*/spec.md'))
            assert ac in feature.read_text(encoding='utf-8'), f'인수 조건 없음: {ac}'
    operations = json.loads((ROOT / 'operations.json').read_text(encoding='utf-8'))['operations']
    for op in operations:
        for field in ('request', 'response'):
            assert op[field] is None or op[field] in DEFS
        for name in op.get('events', {}).values():
            assert name in DEFS
    # JSON 관계와 일관성: DB나 서버 실행 검증을 대신하지 않는다.
    inquiry = values['inquiry-result']
    problems = {p['id'] for p in inquiry['problems']}
    tickets = inquiry['tickets']
    assert len({t['id'] for t in tickets}) == len(tickets)
    assert len({t['workItemId'] for t in tickets}) == len(tickets)
    for t in tickets:
        assert t['inquiryId'] == inquiry['id'] and t['problemId'] in problems
        assert t['department']['isRoutable']
    assert len(tickets) == 2 and tickets[0]['problemId'] == tickets[1]['problemId']
    assert values['tickets-network']['items'] == [values['ticket-network']]
    assert values['tickets-backend']['items'] == [values['ticket-backend']]
    assert values['ticket-detail']['id'] == values['ticket-network']['id']
    assert set(f['workItemId'] for f in inquiry['creationFailures']).isdisjoint(t['workItemId'] for t in tickets)
    for failure in inquiry['creationFailures']:
        assert failure['problemId'] in problems
    for key in ('messages', 'messages-page-1', 'messages-page-2'):
        seq = [m['sequenceNo'] for m in values[key]['items']]
        assert seq == sorted(set(seq))
    for req_key, result_key in [('ai-request', 'ai-ask'), ('ai-request-tickets', 'ai-tickets'), ('ai-request-cs', 'ai-cs-review')]:
        req, result = values[req_key], values[result_key]
        for field in ('runId', 'inquiryId', 'inquiryVersion', 'schemaVersion'):
            assert req[field] == result[field]
        evidence = {e['id'] for e in req['evidence']}
        problem_refs = {p['ref'] for p in result['problems']}
        for p in result['problems']:
            assert set(p['evidenceIds']) <= evidence
            assert (p['action'] == 'ASK_USER') == (p['question'] is not None)
            assert p['action'] != 'ASK_USER' or p['questionCount'] < 3
        for proposal in result['ticketProposals']:
            assert proposal['problemRef'] in problem_refs
            assert proposal['departmentCode'] in {d['code'] for d in req['departments']}
            assert set(proposal['evidenceIds']) <= evidence
            assert proposal['purpose'] != 'CS_REVIEW' or proposal['departmentCode'] == 'CS'
    assert values['supplement-ack']['runId'] is None
    assert values['supplement-ack']['effect'] == 'SUPPLEMENT_SAVED'
    # 음성 예제: 다른 언어에서 자주 생기는 coercion·null·단위 오류를 실제로 거부한다.
    rejected = []
    def reject(label, typ, value):
        try:
            validate(value, {'$ref': '#/$defs/' + typ})
        except ValueError:
            rejected.append(label)
            return
        raise AssertionError('잘못된 데이터가 통과함: ' + label)

    send = values['send']
    reject('버전 문자열', 'MessageSendCommand', {**send, 'expectedInquiryVersion': '2'})
    reject('버전에 boolean', 'MessageSendCommand', {**send, 'expectedInquiryVersion': True})
    reject('버전에 소수', 'MessageSendCommand', {**send, 'expectedInquiryVersion': 1.5})
    reject('int 범위 초과', 'MessageSendCommand', {**send, 'expectedInquiryVersion': 2147483648})
    reject('공백 입력', 'MessageSendCommand', {**send, 'content': '\u3000\u00a0\t\n'})
    reject('초과 입력', 'MessageSendCommand', {**send, 'content': '가' * 4001})
    validate({**send, 'content': '😀' * 4000}, DEFS['MessageSendCommand'])
    reject('단독 surrogate', 'MessageSendCommand', {**send, 'content': '\ud800'})
    reject('snake_case', 'MessageSendCommand', {**send, 'inquiry_id': send['inquiryId']})
    reject('UUID 아닌 표시번호', 'MessageSendCommand', {**send, 'inquiryId': 'Q-001'})
    ack = values['create-ack']
    reject('null 대신 필드 누락', 'CommandAck', {k:v for k,v in ack.items() if k != 'runId'})
    reject('구독 ACK와 저장 ACK 혼용', 'CommandAck', values['subscribe-ack-0'])
    reject('구독 성공에 문의 필드 강제', 'SubscriptionAck', ack)
    reject('local 시각', 'LoginResponse', {**values['login-0'], 'expiresAt': '2026-10-23T09:30:00+09:00'})
    reject('없는 날짜', 'LoginResponse', {**values['login-0'], 'expiresAt': '2026-02-30T00:00:00Z'})
    reject('목록 null', 'TicketPage', {**values['tickets-empty'], 'items': None})
    reject('질문 4회', 'Problem', {**inquiry['problems'][0], 'questionCount': 4})
    reject('AI 응답에 JWT 추가', 'AiResult', {**values['ai-ask'], 'accessToken': 'mock-not-allowed'})
    reject('HTTP 상태를 ACK에 삽입', 'CommandAck', {**ack, 'httpStatus': 200})
    print(f'PASS: 정의 {len(DEFS)}개, 생성 파일 4개 일치, fixture {len(rows)}개, 작업 {len(operations)}개, 시나리오 {len(scenarios)}개, 음성 사례 {len(rejected)}개')
    print('범위: Schema 사용 키워드·JSON 참조·시나리오 선언 검사. 앱/소켓/DB/실제 AI/E2E 실행 아님.')


if __name__ == '__main__':
    main()
