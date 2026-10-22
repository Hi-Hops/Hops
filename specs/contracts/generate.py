"""공통 JSON Schema에서 언어별 전송 DTO와 필드 표를 생성한다(표준 라이브러리만 사용)."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def render() -> dict[str, str]:
    definitions = json.loads((ROOT / 'contract.schema.json').read_text(encoding='utf-8'))['$defs']
    memberships: dict[str, list[str]] = {}
    for name, schema in definitions.items():
        for variant in schema.get('oneOf', []):
            memberships.setdefault(variant['$ref'].split('/')[-1], []).append(name)

    def kind(schema: dict, language: str) -> str:
        if 'anyOf' in schema:
            base = kind(schema['anyOf'][0], language)
            return {'ts': base + ' | null', 'py': 'Optional[' + repr(base) + ']', 'java': base}[language]
        if '$ref' in schema:
            return schema['$ref'].split('/')[-1]
        if 'oneOf' in schema:
            parts = [kind(item, language) for item in schema['oneOf']]
            return ' | '.join(parts) if language == 'ts' else 'Union[' + ', '.join(parts) + ']'
        value = schema.get('type')
        if 'enum' in schema and language in ('ts', 'py'):
            values = schema['enum']
            if language == 'ts':
                return ' | '.join(json.dumps(v, ensure_ascii=False) for v in values)
            return 'Literal[' + ', '.join(repr(v) for v in values) + ']'
        if isinstance(value, list):
            base = kind({**schema, 'type': next(t for t in value if t != 'null')}, language)
            return {'ts': base + ' | null', 'py': 'Optional[' + base + ']', 'java': base}[language]
        if 'const' in schema:
            value = schema['const']
            if language == 'ts':
                return json.dumps(value, ensure_ascii=False)
            if language == 'py':
                return 'Literal[' + repr(value) + ']'
        if value == 'array':
            item = kind(schema['items'], language)
            return {'ts': 'Array<' + item + '>', 'py': 'list[' + item + ']', 'java': 'List<' + item + '>'}[language]
        return {
            'ts': {'string': 'string', 'integer': 'number', 'boolean': 'boolean', 'null': 'null'},
            'py': {'string': 'str', 'integer': 'int', 'boolean': 'bool', 'null': 'None'},
            'java': {'string': 'String', 'integer': 'Integer', 'boolean': 'Boolean', 'null': 'Void'},
        }[language][schema['type']]

    ts = ['// 생성 파일. 수정은 contract.schema.json에서 한다. Mock 계약 초안.',
          '// 프론트와 Node는 동일한 파일을 사용한다. 런타임 검증은 별도다.']
    py = ['# 생성 파일. Mock 계약 초안. dataclass 자체는 JSON 런타임 검증기가 아니다.',
          'from __future__ import annotations', 'from dataclasses import dataclass',
          'from typing import Literal, Optional, Union', '']
    java = ['// 생성 파일. Java 21용 Mock 전송 DTO. JSON 경계의 Schema 검증은 별도다.',
            'package hops.contracts.mock;', 'import java.util.List;',
            'public final class HopsContracts {', '    private HopsContracts() {}']
    fields = ['# 공통 데이터 필드', '', '생성 원본: `contract.schema.json` · 상태: Mock 계약 초안.', '',
              '모든 객체 필드는 필수다. nullable은 키를 생략하지 않고 null을 보낸다. 배열은 []를 보낸다.', '']
    for name, schema in definitions.items():
        if schema.get('type') == 'object':
            props = schema['properties']
            ts += [f'export type {name} = {{'] + [f'  {key}: {kind(value, "ts")};' for key, value in props.items()] + ['};', '']
            py += ['@dataclass(frozen=True)', f'class {name}:'] + [f'    {key}: {kind(value, "py")}' for key, value in props.items()] + ['']
            interfaces = memberships.get(name, [])
            suffix = ' implements ' + ', '.join(interfaces) if interfaces else ''
            java += [f'    public record {name}(' + ', '.join(kind(value, 'java') + ' ' + key for key, value in props.items()) + ')' + suffix + ' {}']
            fields += [f'## {name}', '', '| 필드 | JSON / TypeScript | 조건 |', '|---|---|---|']
            for key, value in props.items():
                details = {k: v for k, v in value.items() if k not in ('type', '$ref', 'items')}
                display_type = kind(value, 'ts').replace('|', '\\|')
                fields.append(f'| `{key}` | `{display_type}` | {json.dumps(details, ensure_ascii=False) if details else "필수"} |')
            fields += ['']
        elif 'oneOf' in schema:
            refs = [item['$ref'].split('/')[-1] for item in schema['oneOf']]
            ts += [f'export type {name} = ' + ' | '.join(refs) + ';', '']
            py += [f'{name} = Union[' + ', '.join(repr(ref) for ref in refs) + ']', '']
            java += [f'    public sealed interface {name} permits ' + ', '.join(refs) + ' {}']
        else:
            # 원시 별칭은 언어별 기본 타입으로 참조를 풀어서 생성한다.
            ts += [f'export type {name} = {kind(schema, "ts")};', '']
            py += [f'{name} = {kind(schema, "py")}', '']
    java += ['}']
    # Java에는 원시 type alias가 없어 해당 별칭만 기본 타입으로 치환한다.
    java_text = '\n'.join(java) + '\n'
    import re
    for name, schema in definitions.items():
        if schema.get('type') not in ('object', None):
            java_text = re.sub(r'\b' + re.escape(name) + r'\b', kind(schema, 'java'), java_text)
    return {'types.ts': '\n'.join(ts) + '\n', 'contracts_dto.py': '\n'.join(py) + '\n',
            'HopsContracts.java': java_text, 'DATA_TYPES.md': '\n'.join(fields) + '\n'}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    outputs = render()
    for name, content in outputs.items():
        path = ROOT / name
        if args.check:
            if not path.exists() or path.read_text(encoding='utf-8') != content:
                raise SystemExit(f'생성 결과 불일치: {path.name}')
        else:
            path.write_text(content, encoding='utf-8', newline='\n')
    print(f'언어별 DTO·필드 표 {len(outputs)}개 ' + ('일치' if args.check else '생성'))


if __name__ == '__main__':
    main()
