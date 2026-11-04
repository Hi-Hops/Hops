function evaluate(needs) {
  if (needs.changes?.result !== 'success' || needs.whitespace?.result !== 'success') return false;
  const outputs = needs.changes.outputs || {};
  for (const [output, job] of [['spring', 'spring'], ['frontend', 'frontend'], ['ai', 'ai'], ['deployment', 'deployment-checks']]) {
    const selected = outputs[output];
    const result = needs[job]?.result;
    if (!(selected === 'true' && result === 'success') && !(selected === 'false' && result === 'skipped')) return false;
  }
  return true;
}

module.exports = { evaluate };
if (require.main === module) {
  let passed = false;
  try { passed = evaluate(JSON.parse(process.env.NEEDS_JSON)); } catch (error) { /* fail closed */ }
  if (!passed) {
    console.error('필요한 검사 결과를 확인할 수 없거나 실패했습니다.');
    process.exitCode = 1;
  } else console.log('선택된 검사가 모두 통과했습니다.');
}
