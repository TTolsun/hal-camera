import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {test} from 'node:test';
import {generateCustomDesign} from './design.mjs';

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'hal-design-'));
  t.after(() => {
    assert.equal(path.dirname(root), path.resolve(os.tmpdir()));
    fs.rmSync(root, {recursive: true, force: true});
  });
  fs.mkdirSync(path.join(root, 'docs/design'), {recursive: true});
  const design = {preset: 'custom', stylesheet: 'docs/design/editorial.css'};
  const source = path.join(root, design.stylesheet);
  const target = path.join(root, 'docs/guide/assets/docflow-design.css');
  fs.writeFileSync(source, '/* Source */\r\nbody { color: black; }\r\n');
  return {root, design, source, target};
}

test('custom CSS generation records provenance and is reproducible', t => {
  const {root, design, source, target} = fixture(t);
  const original = fs.readFileSync(source);
  generateCustomDesign(root, design, []);
  const first = fs.readFileSync(target);
  assert.match(first.toString(), /^\/\* Generated from docs\/design\/editorial.css/);
  assert.match(first.toString(), /node tools\/docgen\/common.mjs design/);
  generateCustomDesign(root, design, []);
  assert.deepEqual(fs.readFileSync(target), first);
  generateCustomDesign(root, design, ['--check']);
  assert.deepEqual(fs.readFileSync(source), original);
});

test('check detects source and output drift without modifying files', t => {
  const {root, design, source, target} = fixture(t);
  assert.throws(() => generateCustomDesign(root, design, ['--check']), /differs/);
  assert.equal(fs.existsSync(target), false);
  generateCustomDesign(root, design, []);
  fs.appendFileSync(source, 'p { color: blue; }\n');
  const before = fs.readFileSync(target);
  assert.throws(() => generateCustomDesign(root, design, ['--check']), /differs/);
  assert.deepEqual(fs.readFileSync(target), before);
  generateCustomDesign(root, design, []);
  fs.appendFileSync(target, '/* manual edit */');
  const edited = fs.readFileSync(target);
  assert.throws(() => generateCustomDesign(root, design, ['--check']), /differs/);
  assert.deepEqual(fs.readFileSync(target), edited);
});

test('invalid options and paths do not write output', t => {
  const {root, design, target} = fixture(t);
  assert.throws(() => generateCustomDesign(root, design, ['--source', 'elsewhere']), /only --check/);
  assert.throws(() => generateCustomDesign(root, {...design, stylesheet: '../outside.css'}, []), /within the project/);
  assert.equal(fs.existsSync(target), false);
});
