import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

// YAML is already locked as an OMM CLI dependency of the engine; no runtime dependency is added.
const engine = process.env.DOCFLOW_ENGINE_ROOT
  ? path.resolve(process.env.DOCFLOW_ENGINE_ROOT)
  : path.join(import.meta.dirname, 'node_modules', '@ttolsun', 'omm-doc-workflow');
const { ommCli } = await import(pathToFileURL(path.join(engine, 'src', 'omm-cli.mjs')));
const { parse } = createRequire(ommCli())('yaml');
const directory = path.resolve(import.meta.dirname, '../../.github/workflows');
const workflow = parse(fs.readFileSync(path.join(directory, 'docs-sync.yml'), 'utf8'));

test('local runner accepts only main push or dispatch with the explicit repository gate', () => {
  assert.deepEqual(Object.keys(workflow.on).sort(), ['push', 'workflow_dispatch']);
  assert.deepEqual(workflow.on.push.branches, ['main']);
  for (const expected of ['app/**', '.omm/**', 'guide/_bindings.yaml', 'tools/docgen/**']) {
    assert.ok(workflow.on.push.paths.includes(expected));
  }
  const enabled = new Function('github', 'vars', `return Boolean(${workflow.jobs.sync.if});`);
  const github = { repository: 'TTolsun/hal-camera', ref: 'refs/heads/main', event_name: 'push' };
  const vars = { DOCGEN_LOCAL_RUNNER_ENABLED: 'true' };
  assert.equal(enabled(github, vars), true);
  assert.equal(enabled({ ...github, event_name: 'workflow_dispatch' }, vars), true);
  for (const event_name of ['pull_request', 'pull_request_target', 'workflow_run']) {
    assert.equal(enabled({ ...github, event_name }, vars), false);
  }
  assert.equal(enabled({ ...github, ref: 'refs/heads/feature' }, vars), false);
  assert.equal(enabled({ ...github, repository: 'someone/hal-camera' }, vars), false);
  assert.equal(enabled(github, {}), false);
  assert.equal(enabled(github, { DOCGEN_LOCAL_RUNNER_ENABLED: 'false' }), false);
});

test('only docs-sync uses the local label and it never accepts review automatically', () => {
  assert.deepEqual(workflow.jobs.sync['runs-on'], ['self-hosted', 'docgen-qwen']);
  assert.equal(workflow.jobs.sync['timeout-minutes'], 45);
  assert.equal(workflow.jobs.sync.defaults.run.shell,
    'powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File {0}');
  for (const file of fs.readdirSync(directory).filter(name => /\.ya?ml$/.test(name) && name !== 'docs-sync.yml')) {
    assert.ok(!fs.readFileSync(path.join(directory, file), 'utf8').includes('docgen-qwen'), file);
  }
  const steps = workflow.jobs.sync.steps;
  assert.ok(steps.every(step => !step.run?.includes('--accept')));
  const sync = steps.find(step => step.env?.DOCGEN_QWEN_MODEL);
  assert.ok(steps.every(step => step.shell === undefined));
  assert.equal(sync.env.DOCGEN_QWEN_CONTEXT, '49152');
  assert.equal(sync.env.DOCGEN_LLM_TIMEOUT_MS, '1800000');
  assert.equal(workflow.on.workflow_dispatch.inputs.force.default, false);
  assert.equal(workflow.on.workflow_dispatch.inputs.force.type, 'boolean');
  assert.match(sync.run, /\$env:DOCGEN_FORCE -eq 'true'/);
});

test('every run says whether there is anything to review, and an automatic docs PR waits for review', () => {
  const steps = workflow.jobs.sync.steps;
  const sync = steps.find(step => step.env?.DOCGEN_QWEN_MODEL);
  const pr = steps.find(step => step.uses?.startsWith('peter-evans/create-pull-request'));
  const summary = steps.at(-1);
  assert.equal(sync.id, 'sync');
  assert.equal(pr.id, 'pr');
  assert.deepEqual(pr.with.labels.trim().split('\n').map(label => label.trim()), ['documentation', 'needs-review']);
  assert.equal(summary.if, 'always()');
  assert.equal(summary.env.SYNC_OUTCOME, '${{ steps.sync.outcome }}');
  assert.equal(summary.env.PR_NUMBER, '${{ steps.pr.outputs.pull-request-number }}');
  assert.match(summary.run, /GITHUB_STEP_SUMMARY/);
  assert.match(summary.run, /::notice/);
  // Windows PowerShell 5.1 reads the BOM-less script in the ANSI code page, so Korean text lives in env, and step
  // outputs reach the script through env rather than being pasted into it.
  for (const step of steps.filter(step => step.run)) {
    assert.ok(!/[\uac00-\ud7a3]/.test(step.run), step.name);
    assert.ok(!step.run.includes('${{'), step.name);
  }
});

test('docs-check runs on every pull request so it can be a required check', () => {
  const check = parse(fs.readFileSync(path.join(directory, 'docs-check.yml'), 'utf8'));
  assert.ok('pull_request' in check.on);
  assert.equal(check.on.pull_request?.paths, undefined);
  assert.deepEqual(check.jobs.check.strategy.matrix.os, ['ubuntu-latest', 'windows-latest']);
});
