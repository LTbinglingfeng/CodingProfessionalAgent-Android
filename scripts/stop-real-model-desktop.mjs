#!/usr/bin/env node
// Stop only our isolated host, then remove its copied credentials and transient app data.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const pointer = path.join(root, '.local/model-e2e-current.json');
const { run } = JSON.parse(fs.readFileSync(pointer));
if (path.dirname(run) !== path.join(root, '.local') || !path.basename(run).startsWith('model-e2e-')) {
  throw Error('Refusing cleanup outside generated isolated run');
}
const controlPath = path.join(run, 'control.json');
const c = JSON.parse(fs.readFileSync(controlPath));
let command = '';
try { command = execFileSync('ps', ['-p', String(c.pid), '-o', 'command='], { encoding: 'utf8' }); } catch {}
if (command) {
  if (!command.includes('/Electron.app/Contents/MacOS/Electron') || !command.includes(run)) {
    throw Error('PID no longer belongs to this isolated host; refusing to signal it');
  }
  process.kill(c.pid, 'SIGTERM');
  const deadline = Date.now() + 8_000;
  while (Date.now() < deadline) {
    try { process.kill(c.pid, 0); } catch { command = ''; break; }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  if (command) throw Error('Isolated host did not exit; keep data intact and investigate');
}
// This strict allowlist contains only directories this launcher created, not user config/source.
for (const dir of ['home', 'appData', 'userData', 'sessionData', 'crashDumps', 'tmp', 'logs']) {
  fs.rmSync(path.join(run, dir), { recursive: true, force: true });
}
fs.writeFileSync(path.join(run, 'run-summary.json'), JSON.stringify({ revision: c.revision,
  isolatedHome: true, loopbackOnly: true, cleaned: true }, null, 2) + '\n', { mode: 0o600 });
fs.rmSync(controlPath);
fs.rmSync(pointer);
console.log('Isolated host stopped; copied credentials and transient app data removed. Evidence retained.');
