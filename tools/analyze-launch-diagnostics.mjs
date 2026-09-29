#!/usr/bin/env node
// Accept exported run JSON, a JSON array, or CliStoreInstrumentation export_reports output.
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

const median = values => {
  const sorted = values.filter(Number.isFinite).sort((a, b) => a - b);
  const n = sorted.length;
  return n ? (sorted[(n - 1) >> 1] + sorted[n >> 1]) / 2 : null;
};

export function analyze(runs, thresholdMs = 220) {
  const groups = { fast: [], slow: [] };
  const summaries = runs.map(run => {
    const cycles = (run.raw?.launch_cycles ?? []).filter(c => !c.warmup && !c.failed &&
      Number.isFinite(c.first_started_ms));
    const transitions = [];
    let previous;
    const modes = cycles.map(cycle => {
      const mode = cycle.first_started_ms < thresholdMs ? 'fast' : 'slow';
      const entry = { run_id: run.run_id, iteration: cycle.iteration, mode,
        open_ms: cycle.open_ms, first_started_ms: cycle.first_started_ms,
        diagnostics: cycle.diagnostics ?? null };
      groups[mode].push(entry);
      // A failed/missing cycle between samples cannot locate the switching cycle.
      if (previous && previous.mode !== mode && previous.iteration + 1 === cycle.iteration) {
        transitions.push({ from: previous, to: entry });
      }
      previous = entry;
      return mode;
    });
    return { run_id: run.run_id, validity: run.validity, env: run.env, measured_cycles: cycles.length,
      fast: modes.filter(m => m === 'fast').length, slow: modes.filter(m => m === 'slow').length,
      open_p50_ms: median(cycles.map(c => c.open_ms)),
      first_started_p50_ms: median(cycles.map(c => c.first_started_ms)), transitions };
  });
  const groupStats = Object.fromEntries(Object.entries(groups).map(([mode, cycles]) => {
    const thermal = new Set();
    const cpu = {};
    const duration = [];
    let unavailable = 0;
    for (const cycle of cycles) {
      for (const phase of ['before_open', 'after_close']) {
        const sample = cycle.diagnostics?.[phase];
        if (!sample) { unavailable++; continue; }
        if (Number.isInteger(sample.thermal_status)) thermal.add(sample.thermal_status);
        if (sample.start_ns && sample.end_ns) duration.push(Number(BigInt(sample.end_ns) - BigInt(sample.start_ns)) / 1e6);
        if (sample.cpu_status !== 'available') unavailable++;
        for (const policy of sample.cpu_policies ?? []) {
          if (!Number.isFinite(policy.scaling_cur_freq_khz)) continue;
          const key = `${phase}/${policy.policy}/${policy.related_cpus ?? '?'}`;
          (cpu[key] ??= []).push(policy.scaling_cur_freq_khz);
        }
      }
    }
    return [mode, { cycles: cycles.length, thermal_statuses: [...thermal].sort(),
      unavailable_cpu_snapshots: unavailable, snapshot_duration_p50_ms: median(duration),
      snapshot_duration_max_ms: duration.length ? Math.max(...duration) : null,
      cpu_khz: Object.fromEntries(Object.entries(cpu).map(([key, values]) => [key,
        { n: values.length, min: Math.min(...values), median: median(values), max: Math.max(...values) }])) }];
  }));
  return { threshold_ms: thresholdMs, threshold_source: 'issue 162 diagnostic 1; exploratory, not a comparison rule',
    runs: summaries, groups: groupStats };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const files = process.argv.slice(2);
  if (!files.length) throw new Error('Usage: node tools/analyze-launch-diagnostics.mjs run.json [run2.json ...]');
  const runs = files.flatMap(file => {
    const text = fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
    const payload = text.includes('HALCAM_REPORTS=') ? text.split('HALCAM_REPORTS=')[1].split(/\r?\n/)[0] : text;
    const value = JSON.parse(payload);
    return Array.isArray(value) ? value : [value];
  });
  console.log(JSON.stringify(analyze(runs), null, 2));
}
