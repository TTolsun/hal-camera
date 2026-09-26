// 이 저장소의 바인딩·어댑터·상태 파일이 공용 엔진과 함께 동작하는지 확인하는 회귀 검사입니다.
// 엔진 자체의 검사는 엔진 저장소(@ttolsun/omm-doc-workflow)의 test/ 에 있습니다.
// 저장소를 임시 폴더에 복사해 실행하므로 작업본은 바뀌지 않습니다.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';

const source = path.resolve(import.meta.dirname, '../..');
const engine = process.env.DOCFLOW_ENGINE_ROOT
  ? path.resolve(process.env.DOCFLOW_ENGINE_ROOT)
  : path.join(import.meta.dirname, 'node_modules', '@ttolsun', 'omm-doc-workflow');
if (!fs.existsSync(path.join(engine, 'bin', 'docflow.mjs'))) {
  throw new Error(`문서 엔진이 없습니다: ${engine}. npm ci --prefix tools/docgen --ignore-scripts 를 먼저 실행하세요.`);
}
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'hal-docgen-test-'));
for (const dir of ['tools/docgen', 'tools/halcam/halcam', 'guide', '.omm', 'app', 'ctsvendor/src/main/java/dev/halcamera/ctsvendor', 'gradle']) {
  fs.mkdirSync(path.dirname(path.join(root, dir)), { recursive: true });
  fs.cpSync(path.join(source, dir), path.join(root, dir), { recursive: true, filter: p => !p.includes('omm-backup') && !['build', 'node_modules'].includes(path.basename(p)) });
}
fs.copyFileSync(path.join(source, 'settings.gradle.kts'), path.join(root, 'settings.gradle.kts'));
const file = p => path.join(root, p);
const env = { ...process.env, DOCFLOW_ENGINE_ROOT: engine, DOCFLOW_PROJECT_ROOT: root, DOCFLOW_SOURCE_ROOT: root, DOCFLOW_CONFIG: 'tools/docgen/project.json' };
const run = (command, ...args) => spawnSync(process.execPath, [file('tools/docgen/docflow.mjs'), command, ...args], { cwd: root, encoding: 'utf8', env });
const pass = r => assert.equal(r.status, 0, r.stdout + r.stderr);
// 엔진 모듈을 직접 불러오는 검사. config.mjs 가 환경 변수로 프로젝트를 찾도록 env 를 함께 넘깁니다.
const engineModule = name => JSON.stringify(pathToFileURL(path.join(engine, 'src', name)).href);
const runCode = code => spawnSync(process.execPath, ['--input-type=module', '-e', code], { cwd: root, encoding: 'utf8', env });
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
pass(run('extract')); pass(run('verify', '--accept')); pass(run('generate'));
// A fixture that starts out stale (e.g. a manuscript citing a deleted file) would fail every later test with an
// unrelated message; check it here so the real cause is the first thing reported.
pass(run('verify', '--check'));

test('must_link files outside perspective globs participate in manuscript freshness', () => {
  pass(runCode(`
    import fs from 'node:fs';
    import assert from 'node:assert/strict';
    import { readBindings } from ${engineModule('lib.mjs')};
    import { collectKeys, computeHashes } from ${engineModule('model.mjs')};
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
  pass(runCode(`
    import assert from 'node:assert/strict';
    import { parseYaml } from ${engineModule('yaml-lite.mjs')};
    import { readBindings } from ${engineModule('lib.mjs')};
    import { collectElements, collectKeys, computeHashes } from ${engineModule('model.mjs')};
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
  `));
});

test('element evidence outside the perspective fails before freshness records are written', () => {
  for (const eol of ['\n', '\r\n']) change('guide/_bindings.yaml', text => text.replace(/\r?\n/g, eol).replace(/(      \.:\r?\n        evidence:)/, '$1\n          - guide/architecture.md'), () => {
    const before = snapshot(file('tools/docgen/state'));
    const r = run('verify', '--check');
    assert.equal(r.status, 1); assert.match(r.stderr, /overall-architecture.*부분집합.*architecture.md/);
    assert.deepEqual(snapshot(file('tools/docgen/state')), before);
  });
});

test('misspelled element mapping cannot silently inherit broader evidence', () => {
  change('guide/_bindings.yaml', text => text.replace('      benchmark/run-validity:', '      benchmark/missing-element:'), () => {
    const r = run('verify', '--check');
    assert.equal(r.status, 1); assert.match(r.stderr, /존재하지 않는 elements 경로.*missing-element/);
  });
});

test('all configured elements fit the prompt budget and exclude descendant fields', () => {
  pass(runCode(`
    import assert from 'node:assert/strict';
    import { readBindings } from ${engineModule('lib.mjs')};
    import { collectElements } from ${engineModule('model.mjs')};
    import { elementInput } from ${engineModule('scan-prompt.mjs')};
    const b = readBindings(); let count = 0;
    for (const [name, source] of Object.entries(b.sources)) if (source.kind === 'omm') {
      for (const e of collectElements(b, name)) {
        const input = elementInput(e); assert.ok(input.prompt.length <= 60000, e.path);
        assert.ok(input.files.length > 0); count++;
      }
    }
    assert.equal(count, 74);
  `));
});

test('oversized scan reports element and size without writing or contacting the model', () => {
  const before = snapshot(root);
  const r = spawnSync(process.execPath, [file('tools/docgen/docflow.mjs'), 'sync', '--dry-run', '--scan-only', '--force'], {
    cwd: root, encoding: 'utf8', env: { ...env, DOCGEN_MAX_PROMPT_CHARS: '10', DOCGEN_OLLAMA_URL: 'http://127.0.0.1:1' },
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
      pass(run('verify', '--check'));
    }
  } finally { for (const [p, bytes] of saved) fs.writeFileSync(file(p), bytes); }
});

test('CLI transport changes invalidate the runtime guide', () => {
  change('tools/halcam/halcam/cli.py', source => source + '\n# changed protocol\n', () => {
    const result = run('verify', '--check');
    assert.equal(result.status, 1);
    assert.match(result.stdout, /content:architecture.md\/runtime-flow.*관련 소스 변경됨/);
  });
});

test('human decision edits invalidate manuscripts without changing IDs', () => {
  change('guide/_inputs/decisions.md', s => s + '\nChanged decision rationale.\n', () => {
    const r = run('verify', '--check');
    assert.equal(r.status, 1); assert.match(r.stdout, /content:architecture.md\/overview.*검토 대기/);
  });
});

test('device evidence edits invalidate manuscripts without changing IDs', () => {
  change('guide/_inputs/device-verification.yaml', s => s + '\n# Corrected verification result\n', () => assert.equal(run('verify', '--check').status, 1));
});

test('writing instructions and facts invalidate accepted manuscripts', () => {
  change('guide/_bindings.yaml', s => s.replace('reader:', 'reader: revised #'), () => assert.equal(run('verify', '--check').status, 1));
  change('tools/docgen/state/facts.json', s => s.replace('versionName', 'changedVersionName'), () => assert.equal(run('verify', '--check').status, 1));
});

test('nested structure fields invalidate accepted evidence', () => {
  change('.omm/overall-architecture/benchmark/benchmark-evaluator/description.md', s => s + '\nUpdated nested evidence.\n', () => assert.equal(run('verify', '--check').status, 1));
});

test('dry-run is read-only even after source changes', () => {
  change('app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt', s => s + '\n// source changed\n', () => {
    const before = snapshot(root);
    const r = run('sync', '--dry-run'); pass(r);
    assert.match(r.stdout, /재스캔 대상 perspective/);
    assert.deepEqual(snapshot(root), before);
  });
});

test('missing cited files never retain a fresh observed state', () => {
  const p = 'app/src/main/java/dev/halcamera/telemetry/FlightRecorder.kt';
  const bytes = fs.readFileSync(file(p));
  try {
    fs.unlinkSync(file(p)); assert.equal(run('verify', '--check').status, 1);
    const evidence = JSON.parse(fs.readFileSync(file('tools/docgen/state/evidence.json')));
    assert.equal(evidence.entries['content:architecture.md/overview'].observed.state, 'missing');
  } finally { fs.writeFileSync(file(p), bytes); }
});

test('malformed markers cause no partial page writes', () => {
  pass(run('verify', '--accept')); pass(run('generate'));
  change('guide/troubleshooting.md', s => s.replace('<!-- omm:end id=status -->', '<!-- omm:end id=wrong -->'), () => {
    const before = snapshot(file('guide'));
    assert.equal(run('generate').status, 1);
    assert.deepEqual(snapshot(file('guide')), before);
  });
});

test('a screen without an owning element fails coverage until evidence or an ignore entry names it', () => {
  pass(run('coverage', '--check'));
  const scratch = 'app/src/main/java/dev/halcamera/ScratchActivity.kt';
  const ignoring = (entry) => s => s.replace(/coverage:\r?\n  ignore: \[\]/, 'coverage:\n  ignore:\n' + entry);
  fs.writeFileSync(file(scratch), 'class ScratchActivity');
  try {
    const missing = run('coverage', '--check');
    assert.equal(missing.status, 1, missing.stdout + missing.stderr);
    assert.match(missing.stdout, /ScratchActivity\.kt\s+누락/);
    // Naming the file in an element's evidence is the normal fix.
    change('guide/_bindings.yaml', s => s.replace(/(      screens\/look-tokens:\r?\n        evidence:\r?\n)/, '$1          - ' + scratch + '\n'), () => {
      pass(run('coverage', '--check'));
    });
    // Documenting the exclusion is the other; a reason is mandatory.
    change('guide/_bindings.yaml', ignoring('    - path: ' + scratch + '\n      reason: 테스트용 화면'), () => {
      pass(run('coverage', '--check'));
    });
    change('guide/_bindings.yaml', ignoring('    - path: ' + scratch), () => {
      assert.equal(run('coverage', '--check').status, 1);
    });
  } finally { fs.unlinkSync(file(scratch)); }
  // An ignore entry that no longer matches anything is itself a failure, so the list cannot rot.
  change('guide/_bindings.yaml', ignoring('    - path: app/src/main/java/dev/halcamera/Gone.kt\n      reason: 없는 파일'), () => {
    const unused = run('coverage', '--check');
    assert.equal(unused.status, 1);
    assert.match(unused.stdout, /불필요한 제외/);
  });
});

// APP-UI.md sits outside guide/, so the generator cannot place .omm diagrams there. Its copies are marked with
// <!-- omm-copy: <source> --> and must match the .omm diagram, or a redrawn .omm figure leaves a stale design doc.
test('design doc diagram copies match their .omm source', () => {
  const doc = fs.readFileSync(path.join(source, 'docs/design/APP-UI.md'), 'utf8').replace(/\r\n/g, '\n');
  const copies = [...doc.matchAll(/<!-- omm-copy: ([a-z0-9-]+) -->\n```mermaid\n([\s\S]*?)```/g)];
  assert.deepEqual(copies.map(m => m[1]).sort(), ['ui-camera-label', 'ui-tool-handoff', 'ui-zoom']);
  for (const [, name, copy] of copies) {
    const original = fs.readFileSync(path.join(source, '.omm', name, 'diagram.mmd'), 'utf8').replace(/\r\n/g, '\n');
    assert.equal(copy.trim(), original.trim(), `docs/design/APP-UI.md 의 ${name} 사본을 .omm/${name}/diagram.mmd 와 같게 고치세요.`);
  }
});

test.after(() => {
  const parent = path.resolve(os.tmpdir());
  if (path.dirname(path.resolve(root)) !== parent || !path.basename(root).startsWith('hal-docgen-test-')) throw new Error('Unsafe fixture path');
  fs.rmSync(root, { recursive: true, force: true });
});
