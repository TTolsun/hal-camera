import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { makeFixture, manuscript, runSync, probeFile } from './sync-fixture.mjs';
import { snapshot, changedFiles, prepareCommit, applyCommit, recover, writeBytes, acquireLock } from './transaction.mjs';
import { qwen } from './qwen.mjs';

async function server(t, handler) {
  const service = http.createServer(async (req, res) => {
    let text = ''; for await (const chunk of req) text += chunk;
    const data = JSON.parse(text);
    assert.equal(data.model, 'qwen3.5:4b'); assert.equal(data.think, false);
    assert.equal(data.tools, undefined); assert.equal(data.truncate, false);
    assert.equal(data.stream, true);
    handler(data, res);
  });
  await new Promise(r => service.listen(0, '127.0.0.1', r));
  t.after(() => { service.closeAllConnections(); service.close(); });
  return `http://127.0.0.1:${service.address().port}`;
}
const packet = data => JSON.stringify(data) + '\n';
const reply = (res, content, extra = {}) => {
  res.setHeader('Content-Type', 'application/x-ndjson');
  const text = JSON.stringify(content), middle = Math.floor(text.length / 2);
  res.write(packet({ done: false, message: { content: text.slice(0, middle) } }));
  res.write(packet({ done: false, message: { content: text.slice(middle) } }));
  res.end(packet({ done: true, done_reason: 'stop', eval_count: 42, message: { content: '' }, ...extra }));
};
const scan = { updates: [{ element: 'sync-probe', field: 'description', text: 'Probe.OBSERVE_MS는 12000ms입니다.' }] };
function fixture(t, n = 1) { const f = makeFixture(n); t.after(f.cleanup); return f; }

function transportEnv(t, url, extra = {}) {
  for (const [key, value] of Object.entries({ DOCGEN_OLLAMA_URL: url, DOCGEN_LLM_TIMEOUT_MS: '10000', DOCGEN_LLM_IDLE_MS: '2000', ...extra })) {
    const previous = process.env[key];
    process.env[key] = value;
    t.after(() => { if (previous === undefined) delete process.env[key]; else process.env[key] = previous; });
  }
}

test('stream reconstructs split UTF-8, split lines and an unterminated final line', async t => {
  const expected = { markdown: '한국어 원고입니다.\n두 번째 줄입니다.' };
  const url = await server(t, (_data, res) => {
    const wire = Buffer.from(packet({ done: false, message: { content: JSON.stringify(expected) } }) + '\r\n' +
      JSON.stringify({ done: true, done_reason: 'stop', eval_count: 12 }));
    const split = wire.indexOf(Buffer.from('한')) + 1;
    res.write(wire.subarray(0, split));
    setImmediate(() => { res.write(wire.subarray(split, split + 3)); setImmediate(() => res.end(wire.subarray(split + 3))); });
  });
  transportEnv(t, url);
  assert.deepEqual(await qwen('test', {}), expected);
});

test('stream accepts final-chunk content and stops without waiting for EOF', async t => {
  const url = await server(t, (data, res) => {
    assert.equal(data.options.num_predict, 1234);
    res.write(packet({ done: false, message: { content: '{"value":' } }));
    res.write(packet({ done: true, done_reason: 'stop', message: { content: '42}' } }));
  });
  transportEnv(t, url, { DOCGEN_QWEN_NUM_PREDICT: '1234' });
  assert.deepEqual(await qwen('test', {}), { value: 42 });
});

test('default output budget remains 8192', async t => {
  const url = await server(t, (data, res) => {
    assert.equal(data.options.num_predict, 8192);
    reply(res, { ok: true });
  });
  transportEnv(t, url, { DOCGEN_QWEN_NUM_PREDICT: '8192' });
  delete process.env.DOCGEN_QWEN_NUM_PREDICT;
  assert.deepEqual(await qwen('test', {}), { ok: true });
});

for (const completes of [true, false]) {
  test(`active stream ${completes ? 'refreshes idle deadline' : 'cannot extend total deadline'}`, async t => {
    const url = await server(t, (_data, res) => {
      res.write(packet({ done: false, message: { content: '{"ok":true}' } }));
      let chunks = 0;
      const timer = setInterval(() => {
        res.write(packet({ done: false, message: { content: '' } }));
        if (completes && ++chunks === 8) res.end(packet({ done: true, done_reason: 'stop' }));
      }, 100);
      res.on('close', () => clearInterval(timer));
    });
    transportEnv(t, url, { DOCGEN_LLM_IDLE_MS: '500', DOCGEN_LLM_TIMEOUT_MS: completes ? '10000' : '800' });
    if (completes) assert.deepEqual(await qwen('test', {}), { ok: true });
    else await assert.rejects(qwen('test', {}), /전체 시간 제한 초과/);
  });
}

test('idle deadline also covers waiting for headers', async t => {
  const url = await server(t, () => {});
  transportEnv(t, url, { DOCGEN_LLM_IDLE_MS: '100' });
  await assert.rejects(qwen('test', {}), /유휴 시간 제한 초과/);
});

test('stream survives the previous 300-second limit', { skip: process.env.DOCGEN_LONG_STREAM !== '1', timeout: 330000 }, async t => {
  const url = await server(t, (_data, res) => {
    res.write(packet({ done: false, message: { content: '{"ok":true}' } }));
    const heartbeat = setInterval(() => res.write(packet({ done: false, message: { content: '' } })), 1000);
    const finish = setTimeout(() => res.end(packet({ done: true, done_reason: 'stop' })), 305000);
    res.on('close', () => { clearInterval(heartbeat); clearTimeout(finish); });
  });
  transportEnv(t, url, { DOCGEN_LLM_TIMEOUT_MS: '1800000', DOCGEN_LLM_IDLE_MS: '120000' });
  delete process.env.DOCGEN_LLM_TIMEOUT_MS;
  delete process.env.DOCGEN_LLM_IDLE_MS;
  assert.deepEqual(await qwen('test', {}), { ok: true });
});

test('invalid transport settings fail before contacting Ollama', async t => {
  let requests = 0;
  const url = await server(t, (_data, res) => { requests++; reply(res, {}); });
  transportEnv(t, url);
  for (const key of ['DOCGEN_LLM_TIMEOUT_MS', 'DOCGEN_LLM_IDLE_MS', 'DOCGEN_QWEN_NUM_PREDICT']) {
    for (const value of ['0', '-1', '1.5', 'NaN']) {
      await t.test(`${key}=${value}`, async sub => {
        transportEnv(sub, url, { [key]: value });
        await assert.rejects(qwen('test', {}), new RegExp(key));
      });
    }
  }
  assert.equal(requests, 0);
});

test('timer overflow is rejected before a request and the maximum delay is accepted', async t => {
  let requests = 0;
  const url = await server(t, (_data, res) => { requests++; reply(res, { ok: true }); });
  for (const key of ['DOCGEN_LLM_TIMEOUT_MS', 'DOCGEN_LLM_IDLE_MS']) {
    for (const value of ['2147483648', String(Number.MAX_SAFE_INTEGER)]) {
      await t.test(`${key}=${value}`, async sub => {
        transportEnv(sub, url, { [key]: value });
        await assert.rejects(qwen('test', {}), new RegExp(`${key} must not exceed 2147483647ms`));
      });
    }
  }
  assert.equal(requests, 0);
  transportEnv(t, url, { DOCGEN_LLM_TIMEOUT_MS: '2147483647', DOCGEN_LLM_IDLE_MS: '2147483647' });
  assert.deepEqual(await qwen('test', {}), { ok: true });
  assert.equal(requests, 1);
});

test('local protocol completes scan/write/generate without accepting review', async t => {
  const f = fixture(t); const before = snapshot(f.root);
  const url = await server(t, (data, res) => reply(res, data.format.properties.updates ? scan : { markdown: manuscript('12000') }));
  const r = await runSync(f.root, { DOCGEN_OLLAMA_URL: url }); assert.equal(r.code, 0, r.out);
  assert.match(fs.readFileSync(path.join(f.root, 'docs/guide/probe.md'), 'utf8'), /12000ms/);
  const accepted = bytes => Object.fromEntries(Object.entries(JSON.parse(bytes).entries).map(([k, v]) => [k, v.accepted]));
  assert.deepEqual(accepted(snapshot(f.root).get('tools/docgen/state/evidence.json')), accepted(before.get('tools/docgen/state/evidence.json')));
  assert.ok(changedFiles(before, snapshot(f.root)).every(p => p.startsWith('.omm/sync-probe/') || p.startsWith('docs/guide/') || p.startsWith('tools/docgen/state/')));
});

for (const scenario of ['http', 'timeout', 'idle', 'json', 'truncated', 'incomplete', 'stream-error', 'path', 'validation', 'writer-empty', 'writer-source', 'second-writer', 'marker']) {
  test(`${scenario} failure preserves every original byte`, async t => {
    const f = fixture(t, 2); let writes = 0;
    if (scenario === 'marker') f.put('docs/guide/probe.md', '<!-- omm:begin id=status -->\n');
    const before = snapshot(f.root);
    const url = await server(t, (data, res) => {
      if (scenario === 'timeout') return;
      if (scenario === 'idle') return res.write(packet({ done: false, message: { content: '{' } }));
      if (scenario === 'http') { res.statusCode = 503; return res.end(); }
      if (scenario === 'json') return res.end('{bad');
      if (scenario === 'incomplete') return res.end(packet({ done: false, message: { content: JSON.stringify(scan) } }));
      if (scenario === 'stream-error') return res.end(packet({ error: 'model failed' }));
      if (scenario === 'truncated') return reply(res, scan, { done_reason: 'length' });
      if (data.format.properties.updates) {
        if (scenario === 'path') return reply(res, { updates: [{ ...scan.updates[0], element: '../app' }] });
        if (scenario === 'validation') return reply(res, { updates: [{ ...scan.updates[0], field: 'diagram', text: 'broken diagram [' }] });
        return reply(res, scan);
      }
      writes++;
      if (scenario === 'writer-empty' || (scenario === 'second-writer' && writes === 2)) return reply(res, { markdown: '' });
      return reply(res, { markdown: scenario === 'writer-source' ? manuscript('12000').replace(probeFile, 'missing.kt') : manuscript('12000') });
    });
    const r = await runSync(f.root, { DOCGEN_OLLAMA_URL: url, DOCGEN_LLM_TIMEOUT_MS: scenario === 'timeout' ? '100' : '30000', DOCGEN_LLM_IDLE_MS: '1000' });
    assert.equal(r.code, 1, r.out); assert.deepEqual(changedFiles(before, snapshot(f.root)), [], r.out);
    if (scenario === 'idle') assert.match(r.out, /유휴 시간 제한 초과/);
    if (scenario === 'timeout') assert.match(r.out, /전체 시간 제한 초과/);
    if (scenario === 'second-writer') assert.equal(writes, 2);
  });
}

test('concurrent original edit prevents publication and preserves user text', async t => {
  const f = fixture(t); const before = snapshot(f.root);
  const url = await server(t, (data, res) => {
    f.put(probeFile, '// user edit\n');
    reply(res, data.format.properties.updates ? scan : { markdown: manuscript('12000') });
  });
  const r = await runSync(f.root, { DOCGEN_OLLAMA_URL: url }); assert.equal(r.code, 1, r.out);
  assert.deepEqual(changedFiles(before, snapshot(f.root)), [probeFile]);
});

test('publish write failure rolls back already written files', t => {
  const f = fixture(t); const before = snapshot(f.root); const after = new Map(before);
  after.set('.omm/sync-probe/description.md', Buffer.from('new')); after.set('docs/guide/new.md', Buffer.from('new'));
  const allowed = p => p.startsWith('.omm/') || p.startsWith('docs/');
  const journal = prepareCommit(f.root, before, after, allowed); let count = 0;
  assert.throws(() => applyCommit(f.root, journal, allowed, (...args) => { if (++count === 2) throw new Error('disk'); writeBytes(...args); }), /disk/);
  assert.deepEqual(changedFiles(before, snapshot(f.root)), []);
});

test('interrupted publish can recover; newer edits block recovery before any writes', t => {
  const f = fixture(t); const before = snapshot(f.root); const after = new Map(before);
  after.set('.omm/sync-probe/description.md', Buffer.from('new')); after.set('docs/guide/new.md', Buffer.from('new'));
  const allowed = p => p.startsWith('.omm/') || p.startsWith('docs/');
  prepareCommit(f.root, before, after, allowed);
  writeBytes(f.root, '.omm/sync-probe/description.md', Buffer.from('new'));
  writeBytes(f.root, 'docs/guide/new.md', Buffer.from('user'));
  const interrupted = snapshot(f.root);
  assert.throws(() => recover(f.root, allowed), /사용자가 수정/);
  assert.deepEqual(changedFiles(interrupted, snapshot(f.root)), []);
  writeBytes(f.root, 'docs/guide/new.md', Buffer.from('new'));
  assert.equal(recover(f.root, allowed), true);
  assert.deepEqual(changedFiles(before, snapshot(f.root)), []);
  assert.equal(recover(f.root, allowed), false);
});

test('recover command handles an interrupted commit and stale lock', async t => {
  const f = fixture(t); const before = snapshot(f.root); const after = new Map(before);
  const target = '.omm/sync-probe/description.md'; after.set(target, Buffer.from('new'));
  prepareCommit(f.root, before, after, p => p === target);
  writeBytes(f.root, target, Buffer.from('new'));
  f.put('tools/docgen/state/.sync-lock', '2147483647');
  const blocked = await runSync(f.root); assert.equal(blocked.code, 1, blocked.out);
  const r = await runSync(f.root, {}, ['--recover']); assert.equal(r.code, 0, r.out);
  assert.deepEqual(changedFiles(before, snapshot(f.root)), []);
  assert.equal(fs.existsSync(path.join(f.root, 'tools/docgen/state/.sync-lock')), false);
});

test('live lock rejects concurrent sync and recovery', async t => {
  const f = fixture(t); const unlock = acquireLock(f.root);
  try {
    for (const args of [[], ['--recover']]) {
      const r = await runSync(f.root, {}, args); assert.equal(r.code, 1, r.out); assert.match(r.out, /다른 동기화/);
    }
  } finally { unlock(); }
});

test('up-to-date repository makes no model request', async t => {
  const f = fixture(t); f.put(probeFile, 'package dev.halcamera\nobject Probe { const val OBSERVE_MS = 10000L }\n');
  const before = snapshot(f.root);
  const r = await runSync(f.root, { DOCGEN_OLLAMA_URL: 'http://127.0.0.1:1' });
  assert.equal(r.code, 0, r.out); assert.deepEqual(changedFiles(before, snapshot(f.root)), []);
});

test('remote endpoint and oversized prompt fail without writes', async t => {
  const f = fixture(t); const before = snapshot(f.root);
  for (const env of [{ DOCGEN_OLLAMA_URL: 'https://example.com' }, { DOCGEN_MAX_PROMPT_CHARS: '10' }]) {
    const r = await runSync(f.root, env); assert.equal(r.code, 1, r.out);
    assert.deepEqual(changedFiles(before, snapshot(f.root)), []);
  }
});

test('real installed Qwen completes the same pipeline', { skip: process.env.DOCGEN_REAL_QWEN !== '1', timeout: 3660000 }, async t => {
  const f = fixture(t); const before = snapshot(f.root);
  const failed = await runSync(f.root, { DOCGEN_LLM_TIMEOUT_MS: '1' });
  assert.equal(failed.code, 1, failed.out);
  assert.deepEqual(changedFiles(before, snapshot(f.root)), []);
  const r = await runSync(f.root);
  console.log(r.out); assert.equal(r.code, 0, r.out);
  const model = fs.readFileSync(path.join(f.root, '.omm/sync-probe/description.md'), 'utf8');
  const page = fs.readFileSync(path.join(f.root, 'docs/guide/probe.md'), 'utf8');
  assert.match(model, /12000|12,000|12\s*초/); assert.doesNotMatch(model, /10000|10,000|10\s*초/);
  assert.match(fs.readFileSync(path.join(f.root, '.omm/sync-probe/timer/description.md'), 'utf8'), /12000|12,000|12\s*초/);
  assert.match(page, /12000|12,000|12\s*초/); assert.doesNotMatch(page, /10000|10,000|10\s*초/);
});

test('real installed Qwen writes one manuscript without scanning', { skip: process.env.DOCGEN_REAL_QWEN !== '1', timeout: 1860000 }, async t => {
  const f = fixture(t); const before = snapshot(f.root);
  const r = await runSync(f.root, {}, ['--write-only']);
  console.log(r.out); assert.equal(r.code, 0, r.out);
  assert.ok(changedFiles(before, snapshot(f.root)).every(p => !p.startsWith('.omm/')));
  const page = fs.readFileSync(path.join(f.root, 'docs/guide/probe.md'), 'utf8');
  assert.match(page, /12000|12,000|12\s*초/); assert.doesNotMatch(page, /10000|10,000|10\s*초/);
});
