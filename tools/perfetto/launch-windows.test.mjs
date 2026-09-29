import { test } from 'node:test';
import assert from 'node:assert/strict';
import { windowsSql } from './launch-windows.mjs';

test('preserves integer clock precision, excludes invalid cycles and escapes labels', () => {
  const valid = { iteration: 2, first_started_ms: 170, timestamps_ns: {
    repeating_call: '9007199254740993', first_started: '9007199424740993' } };
  const sql = windowsSql([{ run_id: "run'1", raw: { launch_cycles: [
    valid, { ...valid, warmup: true }, { ...valid, failed: true },
    { ...valid, first_started_ms: null },
    { ...valid, timestamps_ns: { repeating_call: '2', first_started: '1' } },
  ] } }]);
  assert.equal(sql.match(/\('run''1'/g).length, 1);
  assert.match(sql, /'fast', 'first_started', 9007199254740993, 9007199424740993/);
  assert.match(sql, /end_ts <= \(SELECT end_ts FROM trace_bounds\)/);
});

test('refuses empty windows and unsafe iteration or timestamp', () => {
  assert.throws(() => windowsSql([]), /No complete/);
  const cycle = { iteration: '1);DROP TABLE x', first_started_ms: 260,
    timestamps_ns: { repeating_call: '1', first_started: '2' } };
  assert.throws(() => windowsSql([{ raw: { launch_cycles: [cycle] } }]), /Invalid iteration/);
  cycle.iteration = 1;
  cycle.timestamps_ns.first_started = '9223372036854775808';
  assert.throws(() => windowsSql([{ raw: { launch_cycles: [cycle] } }]), /integer range/);
});
