import { test } from 'node:test';
import assert from 'node:assert/strict';
import { analyze } from './analyze-launch-diagnostics.mjs';

test('excludes warmup and failures, locates adjacent transitions, keeps absent data unknown', () => {
  const cycles = [
    { iteration: 0, warmup: true, first_started_ms: 170 },
    { iteration: 1, first_started_ms: 260 },
    { iteration: 2, first_started_ms: 170 },
    { iteration: 3, failed: true, first_started_ms: 170 },
    { iteration: 4, first_started_ms: 260 },
    { iteration: 5, first_started_ms: null },
  ];
  const result = analyze([{ run_id: 'test', raw: { launch_cycles: cycles } }]);
  assert.equal(result.runs[0].measured_cycles, 3);
  assert.equal(result.runs[0].transitions.length, 1);
  assert.equal(result.runs[0].transitions[0].to.iteration, 2);
  assert.equal(result.groups.fast.unavailable_cpu_snapshots, 2);
  assert.equal(result.groups.fast.snapshot_duration_p50_ms, null);
  assert.deepEqual(result.groups.fast.thermal_statuses, []);
});

test('preserves nanosecond precision when calculating snapshot overhead', () => {
  const snapshot = { start_ns: '123456789012345678', end_ns: '123456789012345680',
    thermal_status: 0, cpu_status: 'available', cpu_policies: [
      { policy: 'policy6', related_cpus: '6 7', scaling_cur_freq_khz: 1000000 }
    ] };
  const result = analyze([{ raw: { launch_cycles: [{ iteration: 1, first_started_ms: 220,
    diagnostics: { before_open: snapshot, after_close: snapshot } }] } }]);
  assert.equal(result.groups.slow.snapshot_duration_p50_ms, 0.000002);
  assert.deepEqual(result.groups.slow.thermal_statuses, [0]);
  assert.equal(result.groups.slow.cpu_khz['before_open/policy6/6 7'].median, 1000000);
});
