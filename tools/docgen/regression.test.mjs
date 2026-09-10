import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const source = path.resolve(import.meta.dirname, '../..');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'hal-docgen-test-'));
for (const dir of ['tools/docgen', 'docs/guide', '.omm', 'app', 'gradle']) {
  fs.mkdirSync(path.dirname(path.join(root, dir)), { recursive: true });
  fs.cpSync(path.join(source, dir), path.join(root, dir), { recursive: true, filter: p => !p.includes('omm-backup') && !['build', 'node_modules'].includes(path.basename(p)) });
}
fs.copyFileSync(path.join(source, 'settings.gradle.kts'), path.join(root, 'settings.gradle.kts'));
const file = p => path.join(root, p);
const run = (script, ...args) => spawnSync(process.execPath, [file('tools/docgen/' + script), ...args], { cwd: root, encoding: 'utf8' });
const pass = r => assert.equal(r.status, 0, r.stdout + r.stderr);
const snapshot = dir => {
  const result = {};
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, ent.name);
    if (ent.isDirectory()) Object.assign(result, snapshot(p));
    else result[path.relative(root, p)] = fs.readFileSync(p).toString('base64');
  }
  return result;
};
const change = (p, transform, body) => {
  const original = fs.readFileSync(file(p));
  try { fs.writeFileSync(file(p), transform(original.toString('utf8'))); body(); }
  finally { fs.writeFileSync(file(p), original); }
};
pass(run('extract.mjs')); pass(run('verify.mjs', '--accept')); pass(run('generate.mjs'));

test('reviewed LF and CRLF checkouts have identical evidence hashes', () => {
  const all = Object.keys(snapshot(root)).filter(p => /\.(kt|kts|md|mmd|yaml|json)$/.test(p));
  const saved = new Map(all.map(p => [p, fs.readFileSync(file(p))]));
  try {
    for (const eol of ['\r\n', '\n']) {
      for (const p of all) fs.writeFileSync(file(p), saved.get(p).toString('utf8').replace(/\r?\n/g, eol));
      pass(run('verify.mjs', '--check'));
    }
  } finally { for (const [p, bytes] of saved) fs.writeFileSync(file(p), bytes); }
});

test('writing style changes require manuscript review', () => {
  change('tools/docgen/style/fluent-korean.md', s => s + '\nUse complete sentences.\n', () => {
    const r = run('verify.mjs', '--check');
    assert.equal(r.status, 1);
    assert.match(r.stdout, /content:architecture.md\/overview.*검토 대기/);
  });
});

test('human decision edits invalidate manuscripts without changing IDs', () => {
  change('docs/guide/_inputs/decisions.md', s => s + '\nChanged decision rationale.\n', () => {
    const r = run('verify.mjs', '--check');
    assert.equal(r.status, 1); assert.match(r.stdout, /content:architecture.md\/overview.*검토 대기/);
  });
});

test('device evidence edits invalidate manuscripts without changing IDs', () => {
  change('docs/guide/_inputs/device-verification.yaml', s => s + '\n# Corrected verification result\n', () => assert.equal(run('verify.mjs', '--check').status, 1));
});

test('writing instructions and facts invalidate accepted manuscripts', () => {
  change('docs/guide/_bindings.yaml', s => s.replace('reader:', 'reader: revised #'), () => assert.equal(run('verify.mjs', '--check').status, 1));
  change('tools/docgen/state/facts.json', s => s.replace('versionName', 'changedVersionName'), () => assert.equal(run('verify.mjs', '--check').status, 1));
});

test('nested structure fields invalidate accepted evidence', () => {
  change('.omm/overall-architecture/benchmark/benchmark-evaluator/description.md', s => s + '\nUpdated nested evidence.\n', () => assert.equal(run('verify.mjs', '--check').status, 1));
});

test('dry-run is read-only even after source changes', () => {
  change('app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt', s => s + '\n// source changed\n', () => {
    const before = snapshot(root);
    const r = run('sync.mjs', '--dry-run'); pass(r);
    assert.match(r.stdout, /재스캔 대상 perspective/);
    assert.deepEqual(snapshot(root), before);
  });
});

test('missing cited files never retain a fresh observed state', () => {
  const p = 'app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt';
  const bytes = fs.readFileSync(file(p));
  try {
    fs.unlinkSync(file(p)); assert.equal(run('verify.mjs', '--check').status, 1);
    const evidence = JSON.parse(fs.readFileSync(file('tools/docgen/state/evidence.json')));
    assert.equal(evidence.entries['content:architecture.md/overview'].observed.state, 'missing');
  } finally { fs.writeFileSync(file(p), bytes); }
});

test('malformed markers cause no partial page writes', () => {
  pass(run('verify.mjs', '--accept')); pass(run('generate.mjs'));
  change('docs/guide/troubleshooting.md', s => s.replace('<!-- omm:end id=status -->', '<!-- omm:end id=wrong -->'), () => {
    const before = snapshot(file('docs/guide'));
    assert.equal(run('generate.mjs').status, 1);
    assert.deepEqual(snapshot(file('docs/guide')), before);
  });
});

test.after(() => {
  const parent = path.resolve(os.tmpdir());
  if (path.dirname(path.resolve(root)) !== parent || !path.basename(root).startsWith('hal-docgen-test-')) throw new Error('Unsafe fixture path');
  fs.rmSync(root, { recursive: true, force: true });
});
