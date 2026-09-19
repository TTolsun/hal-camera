#!/usr/bin/env node
// 공용 문서 엔진(@ttolsun/omm-doc-workflow) 호출 진입점.
//
//   node tools/docgen/docflow.mjs <command> [옵션]
//
// 이 저장소는 문서 파이프라인 코드를 두지 않습니다. 엔진은 `npm ci --prefix tools/docgen --ignore-scripts` 로
// tools/docgen/node_modules 에 설치되며, 개발 중인 엔진을 쓰려면 DOCFLOW_ENGINE_ROOT 에 그 경로를 지정합니다.
// 프로젝트 범위(코드 경로, 바인딩, 어댑터, 디자인)는 project.json 에 고정되어 있어 --project/--config/--source 는 받지 않습니다.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const REQUIRED_VERSION = '0.5.1';
const root = path.resolve(import.meta.dirname, '../..');
const args = process.argv.slice(2);
const command = args.shift();
if (!command || command === '--help') {
  console.log('node tools/docgen/docflow.mjs <check|sync|verify|generate|extract|coverage|site|design|brief|doctor|inspect> [옵션]');
  process.exit(command ? 0 : 1);
}
if (args.some(x => ['--project', '--config', '--source'].includes(x))) throw new Error('이 앱의 프로젝트와 코드 경로는 project.json으로 고정합니다.');

const engine = process.env.DOCFLOW_ENGINE_ROOT
  ? path.resolve(process.env.DOCFLOW_ENGINE_ROOT)
  : path.join(import.meta.dirname, 'node_modules', '@ttolsun', 'omm-doc-workflow');
const metadataFile = path.join(engine, 'package.json');
if (!fs.existsSync(metadataFile)) {
  throw new Error(`문서 엔진이 없습니다: ${engine}\n먼저 npm ci --prefix tools/docgen --ignore-scripts 를 실행하거나 DOCFLOW_ENGINE_ROOT 를 지정하세요.`);
}
const metadata = JSON.parse(fs.readFileSync(metadataFile, 'utf8'));
if (metadata.name !== '@ttolsun/omm-doc-workflow' || metadata.version !== REQUIRED_VERSION) {
  throw new Error(`검증된 공용 엔진 ${REQUIRED_VERSION}이 필요합니다. 현재: ${metadata.name}@${metadata.version} (${engine})`);
}

const result = spawnSync(process.execPath, [path.join(engine, 'bin', 'docflow.mjs'), command, '--project', root, '--config', 'tools/docgen/project.json', ...args], {
  cwd: root, stdio: 'inherit', windowsHide: true,
  env: { ...process.env, DOCFLOW_SOURCE_ROOT: root },
});
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
