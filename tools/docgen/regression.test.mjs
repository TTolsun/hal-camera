import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const source = path.resolve(import.meta.dirname, '../..');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'hal-docgen-test-'));
for (const dir of ['tools/docgen', 'tools/halcam/halcam', 'docs/guide', '.omm', 'app', 'gradle']) {
  fs.mkdirSync(path.dirname(path.join(root, dir)), { recursive: true });
  fs.cpSync(path.join(source, dir), path.join(root, dir), { recursive: true, filter: p => !p.includes('omm-backup') && !['build', 'node_modules'].includes(path.basename(p)) });
}
fs.copyFileSync(path.join(source, 'settings.gradle.kts'), path.join(root, 'settings.gradle.kts'));
const file = p => path.join(root, p);
const run = (script, ...args) => spawnSync(process.execPath, [file('tools/docgen/' + script), ...args], { cwd: root, encoding: 'utf8' });
const pass = r => assert.equal(r.status, 0, r.stdout + r.stderr);
const runCode = code => spawnSync(process.execPath, ['--input-type=module', '-e', code], { cwd: root, encoding: 'utf8' });
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
// A fixture that starts out stale (e.g. a manuscript citing a deleted file) would fail every later test with an
// unrelated message; check it here so the real cause is the first thing reported.
pass(run('verify.mjs', '--check'));

test('must_link files outside perspective globs participate in manuscript freshness', () => {
  pass(runCode(`
    import fs from 'node:fs';
    import assert from 'node:assert/strict';
    import { readBindings } from './tools/docgen/lib.mjs';
    import { collectKeys, computeHashes } from './tools/docgen/model.mjs';
    fs.writeFileSync('MustLinkOnly.kt', 'object MustLinkOnly { val value = 1 }');
    try {
      const b = readBindings();
      const k = collectKeys(b).find(k => k.kind === 'content');
      k.block.brief.must_link = ['MustLinkOnly'];
      const before = computeHashes(b, k);
      fs.writeFileSync('MustLinkOnly.kt', 'object MustLinkOnly { val value = 2 }');
      assert.notEqual(computeHashes(b, k).codeHash, before.codeHash);
    } finally { fs.unlinkSync('MustLinkOnly.kt'); }
  `));
});

test('nested element YAML and nearest-parent inheritance preserve perspective hashes', () => {
  const r = runCode(`
    import assert from 'node:assert/strict';
    import { parseYaml } from './tools/docgen/yaml-lite.mjs';
    import { readBindings } from './tools/docgen/lib.mjs';
    import { collectElements, collectKeys, computeHashes } from './tools/docgen/model.mjs';
    const b = readBindings(), source = 'overall-architecture';
    const before = computeHashes(b, collectKeys(b)[0]);
    b.sources[source].elements = parseYaml('elements:\\n  .:\\n    evidence: [app/src/main/AndroidManifest.xml]\\n  benchmark:\\n    evidence: [app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt]\\n  benchmark/run-validity:\\n    evidence: [app/src/main/java/dev/halcamera/benchmark/RunValidity.kt]\\n').elements;
    const elements = new Map(collectElements(b, source).map(e => [e.path, e]));
    assert.deepEqual(elements.get(source + '/camera-engines/engine-interface').evidence, ['app/src/main/AndroidManifest.xml']);
    assert.deepEqual(elements.get(source + '/benchmark/run-assembler').evidence, ['app/src/main/java/dev/halcamera/benchmark/BenchmarkRunner.kt']);
    assert.deepEqual(elements.get(source + '/benchmark/run-validity').evidence, ['app/src/main/java/dev/halcamera/benchmark/RunValidity.kt']);
    assert.equal(elements.get(source + '/benchmark/run-assembler').parent, source + '/benchmark');
    assert.equal(elements.get(source).parent, null);
    assert.deepEqual(computeHashes(b, collectKeys(b)[0]), before);
  `);
  pass(r);
});

test('element evidence outside the perspective fails before freshness records are written', () => {
  for (const eol of ['\n', '\r\n']) change('docs/guide/_bindings.yaml', text => text.replace(/\r?\n/g, eol).replace(/(      \.:\r?\n        evidence:)/, '$1\n          - docs/guide/architecture.md'), () => {
    const before = snapshot(file('tools/docgen/state'));
    const r = run('verify.mjs', '--check');
    assert.equal(r.status, 1); assert.match(r.stderr, /overall-architecture.*부분집합.*architecture.md/);
    assert.deepEqual(snapshot(file('tools/docgen/state')), before);
  });
});

test('misspelled element mapping cannot silently inherit broader evidence', () => {
  change('docs/guide/_bindings.yaml', text => text.replace('      benchmark/run-validity:', '      benchmark/missing-element:'), () => {
    const r = run('verify.mjs', '--check');
    assert.equal(r.status, 1); assert.match(r.stderr, /존재하지 않는 elements 경로.*missing-element/);
  });
});

test('all configured elements fit the prompt budget and exclude descendant fields', () => {
  pass(runCode(`
    import assert from 'node:assert/strict';
    import { readBindings } from './tools/docgen/lib.mjs';
    import { collectElements } from './tools/docgen/model.mjs';
    import { elementInput } from './tools/docgen/scan-prompt.mjs';
    const b = readBindings(); let count = 0;
    for (const [name, source] of Object.entries(b.sources)) if (source.kind === 'omm') {
      for (const e of collectElements(b, name)) {
        const input = elementInput(e); assert.ok(input.prompt.length <= 60000, e.path);
        assert.ok(input.files.length > 0); count++;
      }
    }
    assert.equal(count, 47);
  `));
});

test('oversized scan reports element and size without writing or contacting the model', () => {
  const before = snapshot(root);
  const r = spawnSync(process.execPath, [file('tools/docgen/sync.mjs'), '--dry-run', '--scan-only', '--force'], {
    cwd: root, encoding: 'utf8', env: { ...process.env, DOCGEN_MAX_PROMPT_CHARS: '10', DOCGEN_OLLAMA_URL: 'http://127.0.0.1:1' },
  });
  assert.equal(r.status, 1); assert.match(r.stderr, /overall-architecture: Qwen 입력 \d+자가 한도 10자를/);
  assert.deepEqual(snapshot(root), before);
});

test('reviewed LF and CRLF checkouts have identical evidence hashes', () => {
  const all = Object.keys(snapshot(root)).filter(p => /\.(kt|kts|py|md|mmd|yaml|json)$/.test(p));
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

test('CLI transport changes invalidate the runtime guide', () => {
  change('tools/halcam/halcam/cli.py', source => source + '\n# changed protocol\n', () => {
    const result = run('verify.mjs', '--check');
    assert.equal(result.status, 1);
    assert.match(result.stdout, /content:architecture.md\/runtime-flow.*관련 소스 변경됨/);
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
