import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { ommCli } from './omm-cli.mjs';

// YAML is already locked as an OMM CLI dependency; no runtime dependency is added.
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
