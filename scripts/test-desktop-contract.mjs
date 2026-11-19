#!/usr/bin/env node
// No Electron bootstrap, settings service, plugin loading, or model runtime.
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash, randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
const android = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const desktop = path.resolve(process.env.CPA_DESKTOP_SOURCE || path.join(android, '../CodingProfessionalAgent'));
const requireDesktop = createRequire(path.join(desktop, 'package.json'));
const ts = requireDesktop('typescript');
const lock = JSON.parse(await readFile(path.join(android, 'integration/desktop-contract/source-lock.json'), 'utf8'));
const temp = await mkdtemp(path.join(tmpdir(), 'cpa-desktop-contract-'));
let service, child;
const oldHome = process.env.HOME;
const oldCwd = process.cwd();
try {
  await mkdir(path.join(temp, 'home'));
  process.chdir(temp);
  process.env.HOME = path.join(temp, 'home');
  process.env.USERPROFILE = process.env.HOME;
  process.env.XDG_CONFIG_HOME = path.join(temp, 'home');
  await writeFile(path.join(temp, 'package.json'), '{"type":"module"}');
  // Only pinned, reviewed service modules can enter the temporary runtime.
  for (const [name, hash] of Object.entries(lock.sha256)) {
    const source = await readFile(path.join(desktop, 'src/main/services', name), 'utf8');
    if (createHash('sha256').update(source).digest('hex') !== hash) throw Error(`Source lock mismatch: ${name}`);
    let output = ts.transpileModule(source, { compilerOptions: {
      target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022,
    }}).outputText;
    output = output.replace(/from 'ws'/g, `from '${pathToFileURL(path.join(path.dirname(requireDesktop.resolve('ws')), 'wrapper.mjs')).href}'`);
    // Refuse unexpected runtime dependencies instead of loading personal configuration.
    for (const match of output.matchAll(/(?:from\s*|import\s*)['"]([^'"]+)['"]/g)) {
      const spec = match[1];
      if (spec.startsWith('node:') || spec === pathToFileURL(path.join(path.dirname(requireDesktop.resolve('ws')), 'wrapper.mjs')).href) continue;
      const relative = path.posix.normalize(path.posix.join(path.posix.dirname(name), spec)).replace(/\.js$/, '.ts');
      if (!spec.startsWith('.') || !(relative in lock.sha256)) throw Error(`Unreviewed runtime import: ${spec}`);
    }
    const dest = path.join(temp, name.replace(/\.ts$/, '.js'));
    await mkdir(path.dirname(dest), { recursive: true });
    await writeFile(dest, output);
  }
  const { WebServerService } = await import(pathToFileURL(path.join(temp, 'webServerService.js')));
  service = new WebServerService({ distDir: temp });
  const password = randomBytes(32).toString('hex');
  // Explicit fixture registry: NO real business/plugin/Agent dispatcher.
  const registry = new Map([
    ['fixture.echo', async (args, context) => ({ args, context, fixture: true })],
    ['fixture.fail', async () => { throw Error('fixture failure'); }],
    ['fixture.event', async () => {
      service.broadcastEvent({ kind: 'fixture.contract', data: JSON.stringify({ text: '跨端 ✓', nested: { count: 2 }, fixture: true }) });
      return { emitted: true };
    }],
    ['fixture.rotate', async () => {
      setTimeout(() => service.configure({ password: randomBytes(32).toString('hex') }), 100);
      return { rotating: true };
    }],
  ]);
  service.setRpcDispatcher(async (method, args, context) => {
    const handler = registry.get(method);
    if (!handler) throw Error('Unknown fixture method');
    return handler(args, context);
  });
  const status = await service.start({ host: '127.0.0.1', port: 0, password });
  if (!status.running || !status.port) throw Error('Service failed to start');
  console.log(`Desktop source ${lock.revision}: real WebServerService + explicit business fixture`);
  const command = process.argv.slice(2);
  if (!command.length) command.push('./scripts/gradle-local', ':app:testDebugUnitTest', '--tests',
    'dev.cpa.android.integration.DesktopServiceContractTest', '--rerun');
  child = spawn(command[0], command.slice(1), {
    cwd: android, stdio: 'inherit', env: { ...process.env, CPA_CONTRACT_URL: status.url, CPA_CONTRACT_PASSWORD: password, CPA_CONTRACT_FIXTURE: 'source-service' },
  });
  const terminate = () => child?.kill('SIGTERM');
  process.once('SIGINT', terminate); process.once('SIGTERM', terminate);
  const timer = setTimeout(() => child?.kill('SIGKILL'), 180_000);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', (code) => resolve(code)); });
    if (code !== 0) throw Error('Contract test child failed');
  } finally { clearTimeout(timer); }
} finally {
  await service?.stop();
  process.env.HOME = oldHome;
  process.chdir(oldCwd);
  await rm(temp, { recursive: true, force: true });
}
