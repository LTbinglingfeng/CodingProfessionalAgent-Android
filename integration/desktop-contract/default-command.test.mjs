import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

test('default fixture child selects the live Gradle test without invoking a build', t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cpa-default-command-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  for (const dir of ['scripts', 'integration/desktop-contract']) fs.mkdirSync(path.join(root, dir), { recursive: true });
  const harness = path.join(root, 'scripts/test-desktop-contract.mjs');
  fs.copyFileSync(new URL('../../scripts/test-desktop-contract.mjs', import.meta.url), harness);
  fs.copyFileSync(new URL('./source-lock.json', import.meta.url), path.join(root, 'integration/desktop-contract/source-lock.json'));
  fs.writeFileSync(path.join(root, 'scripts/gradle-local'), `#!/bin/sh
set -eu
test "$CPA_CONTRACT_FIXTURE" = source-service
test -n "$CPA_CONTRACT_PASSWORD"
printf '%s\\n' "$@" > child-args
`, { mode: 0o700 });
  const result = spawnSync(process.execPath, [harness], { encoding: 'utf8', timeout: 15000,
    env: { ...process.env, CPA_DESKTOP_SOURCE: process.env.CPA_DESKTOP_SOURCE ||
      fileURLToPath(new URL('../../../CodingProfessionalAgent', import.meta.url)) } });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(fs.readFileSync(path.join(root, 'child-args'), 'utf8').trim().split('\n'),
    [':app:testDebugUnitTest', '--tests', 'dev.cpa.android.integration.DesktopServiceContractTest', '--rerun']);
});
