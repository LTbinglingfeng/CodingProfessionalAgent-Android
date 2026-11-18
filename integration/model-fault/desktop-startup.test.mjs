import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

// Run the actual launcher in a disposable tree, with no real repository, Electron, or keys.
for (const scenario of ['missing Electron', 'control file write failure', 'existing pointer']) {
  test(`isolated startup: ${scenario}`, t => {
    const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'cpa-launch-test-'));
    t.after(() => fs.rmSync(temp, { recursive: true, force: true }));
    const scripts = path.join(temp, 'android/scripts');
    const local = path.join(temp, 'android/.local');
    const desktop = path.join(temp, 'CodingProfessionalAgent');
    for (const dir of [scripts, local, desktop]) fs.mkdirSync(dir, { recursive: true });
    const launcher = path.join(scripts, 'start.mjs');
    fs.copyFileSync(new URL('../../scripts/start-real-model-desktop.mjs', import.meta.url), launcher);
    fs.writeFileSync(path.join(temp, 'git'), '#!/bin/sh\nexit 0\n', { mode: 0o700 });
    fs.writeFileSync(path.join(local, 'test-model.json'), JSON.stringify({
      apiKey: 'secret-canary', modelId: 'unused', baseUrl: 'https://unused.invalid',
    }));
    const pointer = path.join(local, 'model-e2e-current.json');
    const options = [];
    if (scenario === 'existing pointer') fs.writeFileSync(pointer, 'other owner');
    if (scenario === 'control file write failure') {
      const electron = path.join(desktop, 'node_modules/electron/dist/Electron.app/Contents/MacOS/Electron');
      fs.mkdirSync(path.dirname(electron), { recursive: true });
      fs.writeFileSync(electron, '#!/bin/sh\nexec /bin/sleep 10\n', { mode: 0o700 });
      const hook = path.join(temp, 'fault.cjs');
      fs.writeFileSync(hook, `const fs = require('node:fs'); const write = fs.writeFileSync;
        fs.writeFileSync = function(file, value, ...rest) {
          if (String(file).endsWith('/control.json')) {
            write(${JSON.stringify(path.join(temp, 'pid'))}, String(JSON.parse(value).pid));
            throw Error('secret-canary');
          }
          return write(file, value, ...rest);
        };`);
      options.push('--require', hook);
    }
    const result = spawnSync(process.execPath, [...options, launcher], { encoding: 'utf8', timeout: 5000,
      env: { PATH: temp } });
    assert.ifError(result.error);
    assert.equal(result.status, 1);
    assert.doesNotMatch(result.stdout + result.stderr, /secret-canary|Isolated desktop launched/);
    if (scenario === 'control file write failure') {
      const pid = Number(fs.readFileSync(path.join(temp, 'pid'), 'utf8'));
      assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' });
    }
    if (scenario === 'existing pointer') assert.equal(fs.readFileSync(pointer, 'utf8'), 'other owner');
    assert.deepEqual(fs.readdirSync(local).sort(), scenario === 'existing pointer'
      ? ['model-e2e-current.json', 'test-model.json'] : ['test-model.json']);
  });
}
