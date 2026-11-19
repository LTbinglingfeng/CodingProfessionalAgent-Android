#!/usr/bin/env node
// Real source-locked gateway + desktop forwarding; business RPC is an explicit fixture.
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir, mkdtemp, rm, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash, randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
const android = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const desktop = path.resolve(process.env.CPA_DESKTOP_SOURCE || path.join(android, '../CodingProfessionalAgent'));
const gateway = path.resolve(process.env.CPA_GATEWAY_SOURCE || path.join(android, '../CLIProxyAPI'));
const lock = JSON.parse(await readFile(path.join(android, 'integration/tunnel-contract/source-lock.json'), 'utf8'));
const temp = await mkdtemp(path.join(tmpdir(), 'cpa-tunnel-contract-'));
const requireDesktop = createRequire(path.join(desktop, 'package.json'));
const ts = requireDesktop('typescript');
const wsModule = pathToFileURL(path.join(path.dirname(requireDesktop.resolve('ws')), 'wrapper.mjs')).href;
const hash = data => createHash('sha256').update(data).digest('hex');
const main = await readFile(path.join(android, 'integration/tunnel-contract/main.go'));
const buildId = hash(JSON.stringify(lock.gateway) + hash(main));
const build = path.join(android, '.local/tunnel-contract', buildId);
let hub, child, service, tunnel;
let fixtureControlFailure = false;
async function run(command, args, options = {}) {
  const proc = spawn(command, args, options);
  const code = await new Promise((resolve, reject) => { proc.once('error', reject); proc.once('exit', resolve); });
  if (code !== 0) throw Error('Fixture subprocess failed');
}
try {
  await mkdir(build, { recursive: true });
  for (const [name, expected] of Object.entries(lock.gateway)) {
    const bytes = await readFile(path.join(gateway, name));
    if (hash(bytes) !== expected) throw Error(`Gateway source lock mismatch: ${name}`);
    const output = path.join(build, name);
    await mkdir(path.dirname(output), { recursive: true }); await writeFile(output, bytes);
  }
  await writeFile(path.join(build, 'main.go'), main);
  const executable = path.join(build, 'gateway-fixture');
  const built = await access(executable).then(() => true, () => false);
  if (!built) {
    console.log('Building isolated production tunnel Hub');
    await run('go', ['build', '-mod=readonly', '-o', executable, '.'], { cwd: build, stdio: 'inherit',
      env: { ...process.env, GOWORK: 'off', GOCACHE: path.join(android, '.local/go-build-cache') } });
  }
  await writeFile(path.join(temp, 'package.json'), '{"type":"module"}');
  for (const [name, expected] of Object.entries(lock.desktop)) {
    const source = await readFile(path.join(desktop, 'src/main/services', name), 'utf8');
    if (hash(source) !== expected) throw Error(`Desktop source lock mismatch: ${name}`);
    let output = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
    output = output.replace(/from 'ws'/g, `from '${wsModule}'`);
    for (const match of output.matchAll(/(?:from\s*|import\s*)['"]([^'"]+)['"]/g)) {
      const spec = match[1];
      if (spec.startsWith('node:') || spec === wsModule) continue;
      const relative = path.posix.normalize(path.posix.join(path.posix.dirname(name), spec)).replace(/\.js$/, '.ts');
      if (!spec.startsWith('.') || !(relative in lock.desktop)) throw Error(`Unreviewed runtime import: ${spec}`);
    }
    const dest = path.join(temp, name.replace(/\.ts$/, '.js'));
    await mkdir(path.dirname(dest), { recursive: true }); await writeFile(dest, output);
  }
  const key = randomBytes(32).toString('hex');
  const password = randomBytes(32).toString('hex');
  hub = spawn(executable, [], { cwd: temp, stdio: ['pipe', 'pipe', 'inherit'], env: { ...process.env, CPA_TUNNEL_FIXTURE_KEY: key } });
  const lines = createInterface({ input: hub.stdout });
  const url = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error('Gateway startup timeout')), 10_000);
    lines.once('line', line => { clearTimeout(timer); resolve(line); });
    hub.once('error', error => { clearTimeout(timer); reject(error); });
    hub.once('exit', () => { clearTimeout(timer); reject(Error('Gateway exited')); });
  });
  if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(url)) throw Error('Invalid fixture listener');
  const { WebServerService } = await import(pathToFileURL(path.join(temp, 'webServerService.js')));
  const { CliProxyTunnelServerService } = await import(pathToFileURL(path.join(temp, 'cliProxyTunnelService.js')));
  service = new WebServerService({ distDir: temp });
  service.setRpcDispatcher(async (method, args, context) => {
    if (method === 'fixture.echo') return { args, context, fixture: true };
    if (method === 'fixture.event') {
      service.broadcastEvent({ kind: 'fixture.contract', data: JSON.stringify({ text: '隧道 ✓', fixture: true }) });
      return { emitted: true };
    }
    if (method === 'fixture.bounce') {
      setTimeout(async () => {
        try {
          await tunnel.stop();
          await new Promise(resolve => setTimeout(resolve, 300));
          await tunnel.syncConfig({ enabled: true, baseUrl: url, apiKey: key, tunnelUuid: 'android-contract-desktop', webPassword: password });
        } catch { fixtureControlFailure = true; }
      }, 100);
      return { bouncing: true };
    }
    if (method === 'fixture.revoke') { setTimeout(() => hub?.stdin.write('revoke\n'), 100); return { revoking: true }; }
    throw Error('Unknown fixture method');
  });
  tunnel = new CliProxyTunnelServerService({ webServerService: service, ensureServerUuid: () => 'android-contract-desktop', getHostname: () => 'Fixture Desktop' });
  await tunnel.syncConfig({ enabled: true, baseUrl: url, apiKey: key, tunnelUuid: 'android-contract-desktop', webPassword: password });
  const deadline = Date.now() + 15_000;
  while (tunnel.getStatus().phase !== 'connected') {
    if (Date.now() >= deadline) throw Error('Desktop tunnel startup timeout');
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  console.log('Real CLIProxyAPI Hub + desktop CliProxyTunnelServerService/WebServerService; explicit business fixture');
  const command = process.argv.slice(2);
  child = spawn(command[0] || './scripts/gradle-local', command.length ? command.slice(1) : [':app:testDebugUnitTest', '--tests', 'dev.cpa.android.core.network.tunnel.RealTunnelContractTest'], {
    cwd: android, stdio: 'inherit', env: { ...process.env, CPA_TUNNEL_URL: url, CPA_TUNNEL_KEY: key, CPA_TUNNEL_PASSWORD: password,
      CPA_TUNNEL_TARGET: 'android-contract-desktop', CPA_TUNNEL_FIXTURE: 'source-services' },
  });
  const terminate = () => { child?.kill('SIGTERM'); hub?.stdin.end(); };
  process.once('SIGINT', terminate); process.once('SIGTERM', terminate);
  const timer = setTimeout(() => child?.kill('SIGKILL'), 180_000);
  try {
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', resolve); });
    if (code !== 0 || fixtureControlFailure) throw Error('Tunnel contract failed');
  } finally { clearTimeout(timer); }
} finally {
  await tunnel?.dispose(); await service?.stop();
  if (hub && hub.exitCode === null && hub.signalCode === null) {
    hub.stdin.end();
    const timer = setTimeout(() => hub.kill('SIGKILL'), 2000);
    await new Promise(resolve => hub.once('exit', resolve)); clearTimeout(timer);
  }
  await rm(temp, { recursive: true, force: true });
}
