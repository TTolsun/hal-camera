import path from 'node:path';
import fs from 'node:fs';

export function ommCli() {
  if (process.env.DOCGEN_OMM_CLI) return path.resolve(process.env.DOCGEN_OMM_CLI);
  const local = process.platform === 'win32' && process.env.LOCALAPPDATA
    ? path.join(process.env.LOCALAPPDATA, 'HALCamera/docgen-tools/node_modules/oh-my-mermaid/dist/cli.js') : null;
  if (local && fs.existsSync(local)) return local;
  return path.join(import.meta.dirname, 'node_modules/oh-my-mermaid/dist/cli.js');
}
