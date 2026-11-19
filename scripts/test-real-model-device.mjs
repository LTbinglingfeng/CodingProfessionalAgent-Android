#!/usr/bin/env node
// Runs only against a host made by start-real-model-desktop.mjs and the designated emulator.
// Desktop login password is transferred over stdin to app-private files, never model API key.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { createSendAckLossProxy } from '../integration/model-fault/send-ack-loss-proxy.mjs';
import { runOwnedInstrumentation } from '../integration/model-fault/owned-instrumentation.mjs';
const args = process.argv.slice(2);
if (args.length > 1 || (args.length === 1 && !['--send-disconnect', '--new-thread', '--ask', '--resume', '--file', '--edit', '--git', '--fork', '--fork-boundary', '--worktree', '--subagent', '--queue', '--steer', '--workspace', '--unread'].includes(args[0]))) throw Error('Supported options: --send-disconnect or --new-thread or --ask or --resume or --file or --edit or --git or --fork or --fork-boundary or --worktree or --subagent or --queue or --steer or --workspace or --unread');
const unreadMode = args[0] === '--unread';
const workspaceMode = args[0] === '--workspace';
const subagentMode = args[0] === '--subagent';
const worktreeMode = args[0] === '--worktree';
const boundaryMode = args[0] === '--fork-boundary';
const forkMode = args[0] === '--fork' || boundaryMode || worktreeMode;
const gitMode = args[0] === '--git';
const editMode = args[0] === '--edit';
const fileMode = args[0] === '--file';
const resumeMode = args[0] === '--resume';
const steerMode = args[0] === '--steer';
const queueMode = args[0] === '--queue' || steerMode;
const askMode = args[0] === '--ask' || queueMode;
const newThreadMode = args[0] === '--new-thread';
const faultMode = args[0] === '--send-disconnect';
const configFile = faultMode ? 'real-model-fault-test.json' : 'real-model-test.json';
const evidenceFile = faultMode ? 'real-model-fault-evidence.json' : 'real-model-test-evidence.json';
const testClass = faultMode ? 'RealModelSendDisconnectTest' : 'RealModelSessionTest';
const guardArgs = faultMode ? ['CPA_REAL_MODEL_FAULT', 'send-accepted-drop'] : ['CPA_REAL_MODEL', 'isolated'];
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const { run } = JSON.parse(fs.readFileSync(path.join(root, '.local/model-e2e-current.json')));
if (path.dirname(run) !== path.join(root, '.local') || !path.basename(run).startsWith('model-e2e-')) {
  throw Error('Invalid isolated run directory');
}
const control = JSON.parse(fs.readFileSync(path.join(run, 'control.json')));
const adb = path.join(root, '.local/android-sdk/platform-tools/adb');
const device = ['-s', 'emulator-5580'];
const app = 'dev.cpa.android';
const boundedAdb = (args, timeout = 5_000, input) => {
  try {
    return execFileSync(adb, [...device, ...args], { encoding: 'utf8', stdio: 'pipe', input,
      timeout, killSignal: 'SIGKILL' }).trim();
  } catch { throw Error('Designated emulator ADB command failed; output suppressed'); }
};
const call = (...args) => boundedAdb(args);
if (call('shell', 'getprop', 'ro.kernel.qemu') !== '1' || call('shell', 'getprop', 'ro.build.version.sdk') !== '37') {
  throw Error('Only designated API37 emulator is allowed');
}
if (!call('shell', 'pm', 'list', 'instrumentation').includes('dev.cpa.android.test/dev.cpa.android.LocalizedTestRunner')) {
  throw Error('Install app and test APKs before running');
}
// Bind the endpoint to the exact managed Electron PID BEFORE sending its test password.
// A stale pointer plus port reuse must never target another desktop instance.
let command, listeners;
try {
  command = execFileSync('ps', ['-p', String(control.pid), '-o', 'command='], { encoding: 'utf8' });
  listeners = execFileSync('/usr/sbin/lsof', ['-nP', '-a', '-p', String(control.pid), `-iTCP:${control.port}`,
    '-sTCP:LISTEN', '-Fn'], { encoding: 'utf8' });
} catch { throw Error('Managed isolated host PID/listener is unavailable'); }
if (!command.includes('/Electron.app/Contents/MacOS/Electron') || !command.includes(run) ||
    !listeners.split('\n').includes(`n127.0.0.1:${control.port}`)) throw Error('Isolated host identity mismatch');
const origin = `http://127.0.0.1:${control.port}`;
const auth = await fetch(origin + '/api/auth/status').then(r => r.json());
if (auth.required !== true) throw Error('Expected password-protected isolated host');
const login = await fetch(origin + '/api/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ password: control.password }) });
if (!login.ok) throw Error('Isolated host authentication failed');
const cookie = login.headers.get('set-cookie')?.split(';')[0];
if (!cookie) throw Error('Missing isolated login cookie');
const response = await fetch(origin + '/api/rpc', { method: 'POST', headers: { 'content-type': 'application/json', cookie },
  body: JSON.stringify({ id: 'verify-isolated-paths', method: 'native:runtimeInfo', args: [] }) }).then(r => r.json());
if (response.result?.homeDir !== path.join(run, 'home') ||
    !response.result?.userConfigDir?.startsWith(run + '/')) throw Error('Authenticated runtime paths are not isolated');
const deviceLock = path.join(root, '.local/model-device-test.lock');
let ownsDeviceLock = false;
let configWriteAttempted = false;
let reverse;
let faultProxy;
try {
  // Never let a second runner's timeout cleanup terminate another owned test.
  try { fs.writeFileSync(deviceLock, JSON.stringify({ pid: process.pid }) + '\n', { flag: 'wx', mode: 0o600 }); ownsDeviceLock = true; }
  catch { throw Error('Designated emulator is claimed by another model runner; inspect stale locks explicitly'); }
  if (call('shell', 'dumpsys', 'activity', 'instrumentation').includes('dev.cpa.android')) {
    throw Error('Target app already has active instrumentation; refusing to interfere');
  }
  call('shell', 'pm', 'grant', app, 'android.permission.ACCESS_LOCAL_NETWORK');
  const faultToken = faultMode ? crypto.randomBytes(32).toString('hex') : undefined;
  if (faultMode) {
    const require = createRequire(path.resolve(root, '../CodingProfessionalAgent/package.json'));
    const { WebSocket, WebSocketServer } = require('ws');
    faultProxy = await createSendAckLossProxy({ targetOrigin: origin, token: faultToken, WebSocket, WebSocketServer });
  }
  const devicePort = faultProxy ? new URL(faultProxy.origin).port : control.port;
  reverse = call('reverse', 'tcp:0', `tcp:${devicePort}`);
  if (!/^\d+$/.test(reverse)) throw Error('Invalid reverse allocation');
  call('shell', 'run-as', app, 'mkdir', '-p', 'files');
  call('shell', 'run-as', app, 'rm', '-f', `files/${evidenceFile}`);
  let askProjectId;
  if (askMode || fileMode || gitMode || worktreeMode || subagentMode || workspaceMode) {
    askProjectId = `ask-test-${crypto.randomBytes(6).toString('hex')}`;
    const workspace = path.join(run, 'home', askProjectId);
    fs.mkdirSync(workspace, { mode: 0o700 });
    if (workspaceMode) {
      fs.mkdirSync(path.join(workspace, 'source-folder'));
      fs.mkdirSync(path.join(workspace, '.hidden-folder'));
      fs.writeFileSync(path.join(workspace, 'keep.txt'), 'workspace preservation sentinel\n');
    }
    if (gitMode || worktreeMode) {
      const git = (...args) => execFileSync('git', args, { cwd: workspace, stdio: 'pipe', env: { ...process.env, GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1' } });
      git('init', '-b', 'main');
      git('config', 'user.name', 'Android verification');
      git('config', 'user.email', 'android-test@example.invalid');
      fs.writeFileSync(path.join(workspace, 'tracked.txt'), 'base line\n');
      fs.mkdirSync(path.join(workspace, 'nested'));
      fs.writeFileSync(path.join(workspace, 'nested', 'nested.txt'), 'nested preview content\n');
      fs.writeFileSync(path.join(workspace, 'old name.txt'), 'rename content\n');
      git('add', '.'); git('commit', '-m', 'test base');
      git('update-ref', 'refs/remotes/origin/main', 'HEAD');
      git('symbolic-ref', 'refs/remotes/origin/HEAD', 'refs/remotes/origin/main');
      git('checkout', '-b', 'feature');
      fs.writeFileSync(path.join(workspace, 'branch.txt'), 'branch addition\n');
      git('add', '.'); git('commit', '-m', 'test branch');
      fs.writeFileSync(path.join(workspace, 'tracked.txt'), 'base line\nworking addition\n');
      git('mv', 'old name.txt', 'renamed name.txt');
      fs.writeFileSync(path.join(workspace, '未跟踪.txt'), 'untracked addition\n');
    }
    const rpc = async (method, args) => {
      const r = await fetch(origin + '/api/rpc', { method: 'POST', headers: { 'content-type': 'application/json', cookie },
        body: JSON.stringify({ id: 'ask-project-setup', method, args }) });
      const value = await r.json(); if (!r.ok || value.error) throw Error('Isolated Ask project setup failed');
      return value.result;
    };
    const projects = await rpc('kvstore:get', ['projects']);
    if (projects != null && !Array.isArray(projects)) throw Error('Unexpected isolated project store');
    await rpc('kvstore:set', ['projects', [...(projects || []), { id: askProjectId, name: 'Isolated Ask verification', path: workspace }]]);
  }
  const testConfig = { ...(askProjectId ? { projectId: askProjectId } : {}), origin: `http://127.0.0.1:${reverse}`, password: control.password,
    runId: `android-real-${crypto.randomBytes(6).toString('hex')}`, ...(faultMode ? { faultToken } : {}) };
  configWriteAttempted = true; // A failed or timed-out dd may still have written credentials.
  boundedAdb(['shell', 'run-as', app, 'dd', `of=files/${configFile}`], 5_000, JSON.stringify(testConfig));
  call('shell', 'run-as', app, 'chmod', '600', `files/${configFile}`);
  // Async child execution keeps the loopback fault proxy/control endpoints responsive.
  // On child timeout/failure, also stop and verify the owned Android instrumentation target.
  const output = await runOwnedInstrumentation(async () => {
    const { stdout } = await promisify(execFile)(adb, [...device, 'shell', 'am', 'instrument', '-w', '-r',
      '-e', 'class', `dev.cpa.android.integration.${testClass}`, '-e', ...guardArgs,
      ...(newThreadMode ? ['-e', 'CPA_REAL_MODEL_NEW_THREAD', 'true'] : []),
      ...(askMode ? ['-e', 'CPA_REAL_MODEL_ASK', 'true'] : []),
      ...(queueMode ? ['-e', 'CPA_REAL_MODEL_QUEUE', 'true'] : []),
      ...(steerMode ? ['-e', 'CPA_REAL_MODEL_STEER', 'true'] : []),
      ...(resumeMode ? ['-e', 'CPA_REAL_MODEL_RESUME', 'true'] : []),
      ...(fileMode ? ['-e', 'CPA_REAL_MODEL_FILE', 'true'] : []),
      ...(editMode ? ['-e', 'CPA_REAL_MODEL_EDIT', 'true'] : []),
      ...(gitMode ? ['-e', 'CPA_REAL_MODEL_GIT', 'true'] : []),
      ...(forkMode ? ['-e', 'CPA_REAL_MODEL_FORK', 'true'] : []),
      ...(boundaryMode ? ['-e', 'CPA_REAL_MODEL_FORK_BOUNDARY', 'true'] : []),
      ...(worktreeMode ? ['-e', 'CPA_REAL_MODEL_WORKTREE', 'true'] : []),
      ...(unreadMode ? ['-e', 'CPA_REAL_MODEL_UNREAD', 'true'] : []),
      ...(workspaceMode ? ['-e', 'CPA_REAL_MODEL_WORKSPACE', 'true'] : []),
      ...(subagentMode ? ['-e', 'CPA_REAL_MODEL_SUBAGENT', 'true'] : []),
      'dev.cpa.android.test/dev.cpa.android.LocalizedTestRunner'], { encoding: 'utf8', timeout: 240_000, killSignal: 'SIGKILL',
      maxBuffer: 1024 * 1024 });
    return stdout;
  }, boundedAdb);
  const success = /OK \(1 test\)/.test(output) && /INSTRUMENTATION_CODE: -1/.test(output) &&
    !/FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[1234]/.test(output);
  // Preserve only app-authored redacted evidence, not instrumentation exception payloads.
  let evidenceOk = false;
  try {
    const evidence = JSON.parse(call('exec-out', 'run-as', app, 'head', '-c', '8192', `files/${evidenceFile}`));
    if (faultProxy) evidence.proxy = faultProxy.status();
    const json = JSON.stringify(evidence, null, 2) + '\n';
    const prefix = unreadMode ? 'unread-device-evidence' : workspaceMode ? 'workspace-device-evidence' : steerMode ? 'steer-device-evidence' : queueMode ? 'queue-device-evidence' : subagentMode ? 'subagent-device-evidence' : faultMode ? 'fault-device-evidence' : newThreadMode ? 'new-thread-device-evidence' : askMode ? 'ask-device-evidence' : resumeMode ? 'resume-device-evidence' : fileMode ? 'file-device-evidence' : editMode ? 'edit-device-evidence' : gitMode ? 'git-device-evidence' : worktreeMode ? 'worktree-device-evidence' : boundaryMode ? 'fork-boundary-device-evidence' : forkMode ? 'fork-device-evidence' : 'device-evidence';
    fs.writeFileSync(path.join(run, `${prefix}-${testConfig.runId}.json`), json, { mode: 0o600, flag: 'wx' });
    fs.writeFileSync(path.join(run, `${prefix}.json`), json, { mode: 0o600 });
    const fault = faultProxy?.status();
    evidenceOk = evidence.passed === true && (!unreadMode || evidence.unreadConfirmed && evidence.sendClicks === 2 && evidence.finalUserCount === 2 && evidence.observedRunCount === 2) && (!workspaceMode || evidence.workspaceConfirmed && evidence.sendClicks === 0 && evidence.observedRunCount === 0) && (!queueMode || (steerMode ? evidence.steerConfirmed : evidence.queueConfirmed) && evidence.sendClicks === 2 && evidence.finalUserCount === 2 && evidence.observedRunCount === (steerMode ? 1 : 2)) && (!subagentMode || evidence.subagentConfirmed && evidence.sendClicks === 1 && evidence.finalUserCount === 1 && evidence.observedRunCount === 1) && (!worktreeMode || evidence.worktreeConfirmed) && (!boundaryMode || evidence.forkBoundaryConfirmed) && (!forkMode || evidence.forkConfirmed && evidence.forkSourceUnchanged && evidence.finalUserCount === 2 && evidence.observedRunCount === 2) && (!gitMode || evidence.gitWorkingConfirmed && evidence.gitBranchConfirmed && evidence.gitPreviewConfirmed && evidence.gitTreeConfirmed && evidence.sendClicks === 0 && evidence.observedRunCount === 0) && (!editMode || evidence.editConfirmed && evidence.editClicks === 1 && evidence.finalUserCount === 2 && evidence.observedRunCount === 3) && (!fileMode || evidence.fileBytesConfirmed && evidence.fileModelConfirmed) && (!resumeMode || evidence.resumeConfirmed && evidence.resumeClicks === 1 && evidence.finalUserCount === 2 && evidence.observedRunCount === 3) && (!askMode || evidence.askPresented && evidence.askCountdownCancelled && evidence.askCustomConfirmed) && (!newThreadMode || evidence.newThreadFlow === true && evidence.newThreadBackClean === true) && (!fault || (fault.forwardedDelegateRuns === 1 && fault.accepted &&
      fault.faultApplied && fault.droppedRpcReply && !fault.offline));
  } catch { /* Missing evidence is never treated as success. */ }
  if (!success || !evidenceOk) {
    const codes = output.match(/INSTRUMENTATION_(?:STATUS_)?CODE: -?\d+/g) ?? [];
    const types = [...output.matchAll(/(?:java\.lang\.|androidx\.[\w.]+\.)(\w*(?:Exception|Error))/g)].map(m => m[1]);
    const flags = { codes, types: [...new Set(types)], crashed: output.includes('Process crashed'),
      classMissing: /ClassNotFoundException|Could not find class/.test(output),
      guardFailure: /Real-model (?:E2E|fault test) failed/.test(output),
      evidenceCaptured: fs.existsSync(path.join(run, unreadMode ? 'unread-device-evidence.json' : workspaceMode ? 'workspace-device-evidence.json' : steerMode ? 'steer-device-evidence.json' : queueMode ? 'queue-device-evidence.json' : subagentMode ? 'subagent-device-evidence.json' : faultMode ? 'fault-device-evidence.json' : newThreadMode ? 'new-thread-device-evidence.json' : askMode ? 'ask-device-evidence.json' : resumeMode ? 'resume-device-evidence.json' : fileMode ? 'file-device-evidence.json' : editMode ? 'edit-device-evidence.json' : gitMode ? 'git-device-evidence.json' : worktreeMode ? 'worktree-device-evidence.json' : boundaryMode ? 'fork-boundary-device-evidence.json' : forkMode ? 'fork-device-evidence.json' : 'device-evidence.json')) };
    console.error('Sanitized instrumentation diagnostics:', JSON.stringify(flags));
    throw Error('Real model test FAILED; inspect sanitized device evidence (no raw credentials/model output)');
  }
  console.log(`Real model ${unreadMode ? 'unread' : workspaceMode ? 'workspace' : steerMode ? 'steer' : queueMode ? 'queue' : subagentMode ? 'subagent' : faultMode ? 'send-disconnect' : newThreadMode ? 'new-thread' : askMode ? 'ask' : resumeMode ? 'resume' : fileMode ? 'file' : editMode ? 'edit' : gitMode ? 'git' : worktreeMode ? 'worktree' : boundaryMode ? 'fork-boundary' : forkMode ? 'fork' : 'session'} device test: OK (1 test). See isolated run evidence for verified steps.`);
} finally {
  let cleanupFailed = false;
  if (configWriteAttempted) { try { call('shell', 'run-as', app, 'rm', '-f', `files/${configFile}`); } catch { cleanupFailed = true; } }
  if (reverse && /^\d+$/.test(reverse)) { try { call('reverse', '--remove', `tcp:${reverse}`); } catch { cleanupFailed = true; } }
  if (cleanupFailed) {
    console.error('Device cleanup unconfirmed; inspect the designated emulator config and reverse mapping.');
    process.exitCode = 1;
  }
  try { if (faultProxy) await faultProxy.close(); }
  finally { if (ownsDeviceLock) fs.rmSync(deviceLock, { force: true }); }
}
