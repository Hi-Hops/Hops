const { test } = require('node:test');
const assert = require('node:assert/strict');
const { evaluate } = require('./ci-gate.cjs');

test('선택된 검사가 성공하고 나머지가 생략된 경우만 통과한다', () => {
  const needs = { changes: { result: 'success', outputs: { spring: 'false', frontend: 'true', ai: 'false', deployment: 'false' } },
    whitespace: { result: 'success' }, spring: { result: 'skipped' }, frontend: { result: 'success' }, ai: { result: 'skipped' }, 'deployment-checks': { result: 'skipped' } };
  assert.equal(evaluate(needs), true);
  assert.equal(evaluate({ ...needs, frontend: { result: 'failure' } }), false);
  assert.equal(evaluate({ ...needs, frontend: { result: 'skipped' } }), false);
  assert.equal(evaluate({ ...needs, changes: { result: 'failure' } }), false);
  assert.equal(evaluate({ ...needs, whitespace: { result: 'failure' } }), false);
  assert.equal(evaluate({ ...needs, whitespace: undefined }), false);
  assert.equal(evaluate({ ...needs, ai: { result: 'cancelled' } }), false);
  assert.equal(evaluate({ ...needs, changes: { result: 'success', outputs: {} } }), false);
});
