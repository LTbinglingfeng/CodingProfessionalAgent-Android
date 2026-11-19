import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

test('partial credential write triggers bounded cleanup even if removing the config fails', t => {
  const root = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'cpa-device-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const run = path.join(root, '.local/model-e2e-fixture');
  fs.mkdirSync(run, { recursive: true });
  fs.writeFileSync(path.join(root, '.local/model-e2e-current.json'), JSON.stringify({ run }));
  fs.writeFileSync(path.join(run, 'control.json'), JSON.stringify({ password: 'fake', pid: 123, port: 12345 }));
  fs.mkdirSync(path.join(root, 'scripts'));
  fs.copyFileSync(new URL('../../scripts/test-real-model-device.mjs', import.meta.url), path.join(root, 'scripts/runner.mjs'));
  fs.symlinkSync(new URL('../../integration', import.meta.url), path.join(root, 'integration'));
  // Stub only the machine boundaries; no device, real-model host, or credentials needed.
  const hook = path.join(root, 'fixture.cjs');
  fs.writeFileSync(hook, `
    const fs = require('node:fs');
    const run = ${JSON.stringify(run)};
    global.fetch = async () => ({ ok: true, headers: new Map([['set-cookie','fixture']]), json: async () => ({
      required: true, result: { homeDir: run+'/home', userConfigDir: run+'/config' }
    }) });
    require('node:child_process').execFileSync = (cmd, args, options) => {
      if (cmd === 'ps') return '/Electron.app/Contents/MacOS/Electron ' + run;
      if (cmd.endsWith('/lsof')) return 'n127.0.0.1:12345';
      if (!cmd.endsWith('/adb')) throw Error('Unexpected command');
      fs.appendFileSync(run+'/calls', JSON.stringify([args, options.timeout, options.killSignal])+'\\n');
      if (args.includes('ro.kernel.qemu')) return '1';
      if (args.includes('ro.build.version.sdk')) return '37';
      if (args.includes('pm')) return 'dev.cpa.android.test/dev.cpa.android.LocalizedTestRunner';
      if (args.includes('tcp:0')) return '4321';
      if (args.includes('dd') || args.includes('files/real-model-test.json')) throw Error('secret-canary');
      return '';
    };
    require('node:module').syncBuiltinESMExports();`);
  const result = spawnSync(process.execPath, ['--require', hook, path.join(root, 'scripts/runner.mjs')],
    { encoding: 'utf8', timeout: 5000 });
  assert.ifError(result.error);
  assert.equal(result.status, 1);
  assert.doesNotMatch(result.stderr, /secret-canary/);
  assert.match(result.stderr, /cleanup unconfirmed/);
  const calls = fs.readFileSync(path.join(run, 'calls'), 'utf8').trim().split('\n').map(line => JSON.parse(line));
  for (const [args, timeout, signal] of calls) {
    assert.deepEqual(args.slice(0, 2), ['-s', 'emulator-5580']);
    assert.ok(timeout > 0 && timeout <= 5000);
    assert.equal(signal, 'SIGKILL');
  }
  assert.deepEqual(calls.slice(-3).map(([args]) => args.slice(2)), [
    ['shell', 'run-as', 'dev.cpa.android', 'dd', 'of=files/real-model-test.json'],
    ['shell', 'run-as', 'dev.cpa.android', 'rm', '-f', 'files/real-model-test.json'],
    ['reverse', '--remove', 'tcp:4321'],
  ]);
  assert.ok(!fs.existsSync(path.join(root, '.local/model-device-test.lock')));
});
