#!/usr/bin/env node
// Opt-in isolated full desktop host. No business fixtures or model credentials in argv/env.
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import crypto from 'node:crypto';
import { once } from 'node:events';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const desktop = path.resolve(root, '../CodingProfessionalAgent');
const pointer = path.join(root, '.local/model-e2e-current.json');
if (fs.existsSync(pointer)) throw Error('An isolated run is already registered; stop/clean it before starting another');
let config;
try { config = JSON.parse(fs.readFileSync(path.join(root, '.local/test-model.json'), 'utf8')); }
catch { throw Error('Dedicated test configuration is unreadable/invalid; contents suppressed'); }
for (const name of ['baseUrl', 'apiKey', 'modelId']) {
  if (typeof config[name] !== 'string' || !config[name].trim()) throw Error(`Missing ${name}`);
}
let url;
try { url = new URL(config.baseUrl); }
catch { throw Error('Invalid dedicated baseUrl; contents suppressed'); }
if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
  throw Error('Expected HTTP(S) baseUrl without userinfo/query/fragment');
}
// Refuse inherited reference changes; rebuilding ignored artifacts is allowed, editing is not.
if (execFileSync('git', ['status', '--porcelain'], { cwd: desktop, encoding: 'utf8' }).trim()) {
  throw Error('Desktop reference has source changes; inspect before using');
}
const revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: desktop, encoding: 'utf8' }).trim();
const run = fs.mkdtempSync(path.join(root, '.local/model-e2e-'));
let pointerFd, child, log;
try {
  fs.chmodSync(run, 0o700);
  pointerFd = fs.openSync(pointer, 'wx', 0o600);
  fs.writeFileSync(pointerFd, JSON.stringify({ run }) + '\n');
  await launch();
} catch {
  if (child?.pid && child.exitCode === null && child.signalCode === null) {
    const exited = once(child, 'exit');
    child.kill('SIGKILL');
    await exited;
  }
  log?.destroy();
  fs.rmSync(run, { recursive: true, force: true });
  if (pointerFd !== undefined) fs.rmSync(pointer, { force: true });
  throw Error('Isolated desktop startup failed; details suppressed');
} finally {
  if (pointerFd !== undefined) fs.closeSync(pointerFd);
}

async function launch() {
  const dirs = Object.fromEntries(['home', 'appData', 'userData', 'sessionData', 'logs', 'crashDumps', 'tmp'].map(name => {
    const dir = path.join(run, name); fs.mkdirSync(dir, { recursive: true, mode: 0o700 }); return [name, dir];
  }));
  const appConfig = path.join(dirs.home, '.coding-professional-agent-dev');
  fs.mkdirSync(appConfig, { mode: 0o700 });
  const password = crypto.randomBytes(32).toString('hex');
  const server = net.createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  function privateJson(name, value) { fs.writeFileSync(name, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 }); }
  privateJson(path.join(appConfig, 'settings.json'), { 'app-state': { version: 2, currentSessionId: null, settings: {
    cliProxyApi: { baseUrl: config.baseUrl, apiKey: config.apiKey, tunnelEnabled: false },
    modelId: config.modelId, reasoningLevel: '', speed: 'standard',
    resumeUnfinishedConversations: false, localMemoryEnabled: false, toolAssistedMemoryEnabled: false,
    showInMenuBar: false, preventSleep: false, autoCheckUpdates: false,
    modelSettings: { enableAll: true, models: {}, cacheWarming: { mode: 'off', maxWarmingTime: 3600 } },
    webServer: { enabled: true, host: '127.0.0.1', port, password },
  } } });
  // Preseed all ordinary stores: never fall back to legacy personal settings.
  for (const [name, value] of Object.entries({ 'ui.json': {}, 'projects.json': [], 'shortcuts.json': {}, 'schedule.json': [] })) {
    privateJson(path.join(appConfig, name), value);
  }
  privateJson(path.join(appConfig, 'cached_models.json'), [{ id: config.modelId, label: 'Dedicated test model',
    supportsFast: false, reasoningLevels: [], input: ['text'], contextWindow: 32768, maxTokens: 4096 }]);
  privateJson(path.join(run, 'package.json'), { name: 'cpa-android-isolated-model-test', version: '1.0.0', type: 'module', main: 'main.mjs' });
  const entry = path.join(desktop, 'dist-electron/src/main/index.js');
  fs.writeFileSync(path.join(run, 'main.mjs'), `import { app } from 'electron';
import os from 'node:os';
import { pathToFileURL } from 'node:url';
const dirs = ${JSON.stringify(dirs)};
if (os.homedir() !== dirs.home || process.env.HOME !== dirs.home) throw Error('HOME isolation failed');
for (const name of ['home', 'appData', 'userData', 'sessionData', 'crashDumps']) app.setPath(name, dirs[name]);
app.setAppLogsPath(dirs.logs);
const requestLock = app.requestSingleInstanceLock.bind(app);
app.requestSingleInstanceLock = (...args) => {
  if (app.getPath('userData') !== dirs.userData || app.getPath('home') !== dirs.home) throw Error('Path isolation failed before lock');
  console.log('ISOLATED_PATHS_VERIFIED_BEFORE_LOCK');
  return requestLock(...args);
};
await import(pathToFileURL(${JSON.stringify(entry)}).href);
`, { mode: 0o600 });
  const electron = path.join(desktop, 'node_modules/electron/dist/Electron.app/Contents/MacOS/Electron');
  log = fs.createWriteStream(path.join(run, 'desktop.log'), { mode: 0o600 });
  await once(log, 'open');
  child = spawn(electron, [run, '--headless', '--host', '127.0.0.1', '--port', String(port),
    `--user-data-dir=${dirs.userData}`, `--crash-dumps-dir=${dirs.crashDumps}`], {
    cwd: run, env: { PATH: process.env.PATH, HOME: dirs.home, CPA_HOME: appConfig, SHELL: '/bin/sh',
      ZDOTDIR: dirs.home, XDG_CONFIG_HOME: dirs.appData, TMPDIR: dirs.tmp + '/', LANG: 'en_US.UTF-8',
      NODE_ENV: 'development' }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  await once(child, 'spawn');
  // Buffer complete lines, so chunk boundaries cannot split a credential around the redactor.
  const secrets = [config.apiKey, encodeURIComponent(config.apiKey), JSON.stringify(config.apiKey).slice(1, -1), password];
  function capture(stream) {
    stream.setEncoding('utf8');
    let pending = '';
    const redact = line => secrets.reduce((s, secret) => s.split(secret).join('[REDACTED]'), line);
    stream.on('data', bytes => {
      pending += bytes.toString();
      const lines = pending.split('\n'); pending = lines.pop();
      for (const line of lines) {
        // Never retain verbose renderer/model payloads. Keep startup and failure diagnostics only.
        if (/ISOLATED_PATHS_|Web server listening|(?:Error|Failed|failed|ERROR)/.test(line)) log.write(redact(line) + '\n');
      }
    });
    stream.on('end', () => { if (pending && /ISOLATED_PATHS_|Error|Failed|failed|ERROR/.test(pending)) log.write(redact(pending) + '\n'); });
  }
  capture(child.stdout); capture(child.stderr);
  privateJson(path.join(run, 'control.json'), { pid: child.pid, launcherPid: process.pid, port, revision, appConfig, password });
  // Pointer was reserved exclusively before any child process or copied credential was created.
  console.log('Isolated desktop launched; private runtime pointer: .local/model-e2e-current.json');
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill('SIGTERM'));
  child.on('close', code => { log.end(); console.log(`Isolated desktop exited (${code ?? 'signal'})`); process.exitCode = code ?? 1; });
}
