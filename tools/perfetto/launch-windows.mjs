#!/usr/bin/env node
// Generate cycle windows for a BOOTTIME Perfetto trace from exported benchmark JSON.
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

export function windowsSql(runs) {
  const rows = [];
  const quote = value => `'${String(value).replaceAll("'", "''")}'`;
  for (const run of runs) {
    for (const cycle of run.raw?.launch_cycles ?? []) {
      if (cycle.warmup || cycle.failed || !Number.isFinite(cycle.first_started_ms)) continue;
      const marks = cycle.timestamps_ns ?? {};
      for (const [stage, begin, end] of [
        ['open', 'open_call', 'opened'],
        ['configure', 'configure_call', 'configured'],
        ['first_started', 'repeating_call', 'first_started'],
      ]) {
        const start = marks[begin], stop = marks[end];
        if (typeof start !== 'string' || typeof stop !== 'string' ||
            !/^\d+$/.test(start) || !/^\d+$/.test(stop)) continue;
        if (BigInt(stop) <= BigInt(start)) continue;
        if (BigInt(stop) > 9223372036854775807n) throw new Error('Timestamp exceeds SQLite integer range');
        if (!Number.isSafeInteger(cycle.iteration)) throw new Error('Invalid iteration');
        rows.push(`(${quote(run.run_id)}, ${cycle.iteration}, ${quote(cycle.first_started_ms < 220 ? 'fast' : 'slow')}, ${quote(stage)}, ${start}, ${stop})`);
      }
    }
  }
  if (!rows.length) throw new Error('No complete launch windows');
  return `-- Requires trace clock BOOTTIME: clock_snapshot.clock_id=6 must have ts=clock_value.
-- Threshold 220 ms is exploratory (#162), not a regression rule.
DROP TABLE IF EXISTS launch_windows;
CREATE PERFETTO TABLE launch_windows AS
WITH input(run_id, iteration, mode, stage, ts, end_ts) AS (VALUES
${rows.join(',\n')}
)
SELECT *, end_ts-ts AS dur FROM input
WHERE ts >= (SELECT start_ts FROM trace_bounds)
  AND end_ts <= (SELECT end_ts FROM trace_bounds)
  AND EXISTS (SELECT 1 FROM clock_snapshot WHERE clock_id=6)
  AND NOT EXISTS (SELECT 1 FROM clock_snapshot WHERE clock_id=6 AND ts!=clock_value);
SELECT run_id, iteration, mode, stage, ts, end_ts, dur/1e6 AS duration_ms
FROM launch_windows ORDER BY ts, stage;
`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const files = process.argv.slice(2);
  if (!files.length) throw new Error('Usage: node tools/perfetto/launch-windows.mjs run.json [run2.json ...] > windows.sql');
  const runs = files.flatMap(file => {
    const text = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
    const value = JSON.parse(text.includes('HALCAM_REPORTS=') ? text.split('HALCAM_REPORTS=')[1].split(/\r?\n/)[0] : text);
    return Array.isArray(value) ? value : [value];
  });
  process.stdout.write(windowsSql(runs));
}
