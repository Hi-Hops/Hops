// Node와 Python이 공용 fixture를 읽고 다시 쓸 때 JSON 구조·문자·정수값이 같은지 확인한다.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const fixturePath = fileURLToPath(new URL('../mocks/fixtures.json', import.meta.url));
const parsed = JSON.parse(readFileSync(fixturePath, 'utf8'));
const nodeHash = createHash('sha256').update(JSON.stringify(parsed), 'utf8').digest('hex');
// 경로를 쉘 문자열로 합치지 않고 argv로 전달한다.
const python = process.env.HOPS_CONTRACT_PYTHON ?? 'python';
const result = spawnSync(python, [
  '-c',
  'import hashlib,json,pathlib,sys; data=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")); print(hashlib.sha256(json.dumps(data,ensure_ascii=False,separators=(",",":")).encode("utf-8")).hexdigest())',
  fixturePath,
], { encoding: 'utf8', shell: false });
if (result.error || result.status !== 0) {
  throw new Error('Python 실행 실패: HOPS_CONTRACT_PYTHON에 실행 파일 경로를 지정하세요.');
}
if (nodeHash !== result.stdout.trim()) {
  throw new Error('Node/Python JSON 왕복 결과 불일치');
}
console.log(`PASS: Node/Python fixture ${parsed.cases.length}개 JSON 왕복 SHA-256 일치`);
console.log('런타임 DTO 검증·TypeScript typecheck·Java 직렬화 검사를 대신하지 않습니다.');
