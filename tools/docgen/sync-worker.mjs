#!/usr/bin/env node
// Internal worker: all mutations occur in the supervisor's disposable copy.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { readBindings, readState, writeState, REPO_ROOT, globFiles, readOmmField } from './lib.mjs';
import { collectKeys, collectElements, contentPath, splitFrontMatter, computeHashes, stateOf, OMM_FIELDS, citedFiles, readContentBlock } from './model.mjs';
import { snapshot, changedFiles } from './transaction.mjs';
import { qwen } from './qwen.mjs';
import { elementInput } from './scan-prompt.mjs';

const args = new Set(process.argv.slice(2));
const dryRun = args.has('--dry-run');
if (!dryRun && process.env.DOCGEN_STAGED_WORKER !== '1') throw new Error('sync.mjs를 통해 실행하세요.');
const runNode = (name, ...extra) => {
  const r = spawnSync(process.execPath, [path.join(import.meta.dirname, name), ...extra],
    { cwd: REPO_ROOT, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  if (r.status !== 0) throw new Error(`${name} 실패: ${r.stderr || r.error?.message || r.stdout}`);
  return r.stdout;
};
const omm = (...extra) => {
  const cli = process.env.DOCGEN_OMM_CLI;
  if (!cli || !fs.existsSync(cli)) throw new Error('OMM CLI가 없습니다. npm ci --prefix tools/docgen을 실행하거나 DOCGEN_OMM_CLI를 지정하세요.');
  const r = spawnSync(process.execPath, [cli, ...extra], { cwd: REPO_ROOT, encoding: 'utf8', timeout: 30000 });
  if (r.status !== 0) throw new Error(`omm ${extra[0]} 실패: ${r.stderr || r.stdout || r.error?.message}`);
};
const sourceText = files => files.map(file => `\n## 파일: ${file}\n${fs.readFileSync(path.join(REPO_ROOT, file), 'utf8')}`).join('\n');
const schema = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false });

try {
  console.log('1/4 추출과 최신성 검사');
  runNode('extract.mjs', ...(dryRun ? ['--dry-run'] : []));
  runNode('verify.mjs', ...(dryRun ? ['--dry-run'] : []));
  const bindings = readBindings();
  const evidence = readState('evidence.json', { entries: {} }).entries;
  const keys = collectKeys(bindings);
  const needs = key => {
    const current = computeHashes(bindings, key);
    return args.has('--force') || !current.exists || current.missingCited?.length || stateOf(current, evidence[key.key]?.accepted) !== 'fresh';
  };
  const scans = keys.filter(k => k.kind === 'omm' && needs(k));
  const scanState = readState('scan.json', { schema: 1, entries: {} });
  if (scanState?.schema !== 1 || !scanState.entries || typeof scanState.entries !== 'object' || Array.isArray(scanState.entries)) {
    throw new Error('scan.json 형식이 유효하지 않습니다.');
  }
  let scanned = 0;
  console.log(`  재스캔 대상 perspective: ${scans.map(k => k.source).join(', ') || '(없음)'}`);
  console.log('2/4 Qwen 구조 갱신');
  if (!args.has('--write-only')) for (const k of scans) {
    const elements = collectElements(bindings, k.source);
    if (!elements.length) throw new Error(`기존 OMM 구조가 없습니다: ${k.source}`);
    for (const element of elements) {
      const input = elementInput(element);
      const cached = scanState.entries[element.path];
      if (!args.has('--force') && cached?.codeHash === input.codeHash && Number.isFinite(Date.parse(cached.scannedAt))) {
        console.log(`  - ${element.path}: 근거 변경 없음, 건너뜀`);
        continue;
      }
      console.log(`  - ${element.path}: 입력 ${input.prompt.length}자, 근거 ${input.files.length}개`);
      if (dryRun) continue;
      const started = Date.now();
      const before = snapshot(REPO_ROOT);
      const result = await qwen(input.prompt, schema({ updates: { type: 'array', items: schema({
        element: { type: 'string', enum: [element.path] }, field: { type: 'string', enum: OMM_FIELDS }, text: { type: 'string' },
      }) } }));
      if (!Array.isArray(result.updates)) throw new Error('Qwen 구조 응답에 updates가 없습니다.');
      const seen = new Set();
      for (const update of result.updates) {
        if (!update || update.element !== element.path || !OMM_FIELDS.includes(update.field) || typeof update.text !== 'string' || !update.text.trim()) {
          throw new Error('Qwen 구조 응답의 경로·필드·내용이 유효하지 않습니다.');
        }
        const key = `${update.element}/${update.field}`;
        if (seen.has(key)) throw new Error(`중복 OMM 수정: ${key}`);
        seen.add(key);
      }
      for (const update of result.updates) {
        if (readOmmField(update.element, update.field) === update.text.replace(/\r\n/g, '\n').trim()) continue;
        console.log(`    수정: ${update.element}/${update.field}`);
        omm('write', update.element, update.field, update.text);
      }
      // OMM CLI may register an existing child in its parent's metadata.
      const parentMeta = element.parent && `.omm/${element.parent}/meta.yaml`;
      const outside = changedFiles(before, snapshot(REPO_ROOT)).filter(p => path.posix.dirname(p) !== `.omm/${element.path}` && p !== parentMeta);
      if (outside.length) throw new Error(`구조 갱신 범위 위반: ${outside.join(', ')}`);
      omm('validate', element.path);
      scanState.entries[element.path] = { codeHash: input.codeHash, scannedAt: new Date().toISOString() };
      scanned++;
      console.log(`    완료: ${element.path}, ${((Date.now() - started) / 1000).toFixed(1)}초`);
    }
  }
  if (scanned) writeState('scan.json', scanState);
  console.log('3/4 Qwen 원고 갱신');
  if (!args.has('--scan-only')) for (const k of keys.filter(k => k.kind === 'content' && needs(k))) {
    console.log(`  - ${k.page}/${k.block.id}`);
    if (dryRun) continue;
    const sourceFiles = [...new Set([
      ...globFiles((k.block.based_on ?? []).flatMap(name => bindings.sources[name]?.evidence ?? [])),
      ...citedFiles(readContentBlock(bindings, k.page, k.block)?.meta),
    ])];
    const prompt = runNode('brief.mjs', k.page, k.block.id) + '\n다음 원본 코드를 근거로 사용하세요. 파일 도구는 없습니다.\n' + sourceText(sourceFiles) +
      '\n응답은 {"markdown":"front matter를 포함한 전체 원고"} JSON입니다.';
    const result = await qwen(prompt, schema({ markdown: { type: 'string' } }));
    if (typeof result.markdown !== 'string' || !result.markdown.startsWith('---\n')) throw new Error('Qwen 원고에 front matter가 없습니다.');
    const { meta, body } = splitFrontMatter(result.markdown);
    if (!body.trim() || !Array.isArray(meta.based_on) || meta.confidence !== k.block.confidence ||
        JSON.stringify([...meta.based_on].sort()) !== JSON.stringify([...(k.block.based_on ?? [])].sort())) throw new Error('Qwen 원고 계약이 일치하지 않습니다.');
    if (!Array.isArray(meta.sources) || (meta.confidence === 'code' && !meta.sources.length) ||
        citedFiles(meta).some(p => !sourceFiles.includes(p))) throw new Error('원고가 제공되지 않은 코드 근거를 인용했습니다.');
    if (meta.confidence !== 'device' && meta.verifications?.length) throw new Error('코드 원고에 기기 검증 기록을 추가할 수 없습니다.');
    const target = contentPath(bindings, k.page, k.block.id);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, result.markdown.trimEnd() + '\n');
  }
  console.log('4/4 검증과 문서 생성');
  if (!dryRun) {
    console.log(runNode('verify.mjs'));
    console.log(runNode('generate.mjs'));
  } else console.log('[dry-run] 실제 변경 없음');
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
