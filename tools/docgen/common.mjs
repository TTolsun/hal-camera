#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

const engine = process.env.DOCFLOW_ENGINE_ROOT;
if (!engine) throw new Error('DOCFLOW_ENGINE_ROOT에 별도로 설치한 omm-doc-workflow 경로를 지정하세요.');
const root = path.resolve(import.meta.dirname, '../..');
const entry = path.resolve(engine, 'bin/docflow.mjs');
const metadata = JSON.parse(fs.readFileSync(path.resolve(engine, 'package.json'), 'utf8'));
if (metadata.name !== '@ttolsun/omm-doc-workflow' || metadata.version !== '0.1.0') throw new Error('검증된 공용 엔진 0.1.0이 필요합니다.');
const [command = 'sync', ...args] = process.argv.slice(2);
if (args.some(x=>['--project','--config','--source'].includes(x))) throw new Error('이 앱의 프로젝트와 코드 경로는 project.json으로 고정합니다.');
const result = spawnSync(process.execPath, [entry, command, '--project', root, '--config', 'tools/docgen/project.json', ...args], {
  cwd: root, stdio:'inherit', windowsHide:true,
  env: {...process.env, DOCFLOW_SOURCE_ROOT:root},
});
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
