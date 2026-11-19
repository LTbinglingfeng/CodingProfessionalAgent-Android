import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

for (const kind of ['desktop', 'tunnel']) {
  for (const mode of ['success', 'failed-status', 'timeout', 'cancel', 'stuck-cleanup']) {
    test(`${kind}: ${mode}`, { timeout: 8000 }, async t => {
      const dir = mkdtempSync(join(tmpdir(), 'device-contract-'));
      t.after(() => rmSync(dir, { recursive: true, force: true }));
      mkdirSync(join(dir, 'platform-tools'));
      const secret = 'ab'.repeat(32);
      writeFileSync(join(dir, 'platform-tools/adb'), `#!/bin/sh
set -eu
[ "$1:$2" = '-s:emulator-5580' ]
shift 2
case "$*" in
  get-state) echo device;;
  'shell getprop ro.kernel.qemu') echo 1;;
  'shell getprop ro.build.version.sdk') echo 37;;
  'shell pm list instrumentation') echo instrumentation:dev.cpa.android.test/dev.cpa.android.LocalizedTestRunner;;
  'shell pm grant '*) :;;
  'shell am force-stop dev.cpa.android') echo stopped >> "$ANDROID_HOME/log";;
  'reverse tcp:0 tcp:1234') echo 4321;;
  'reverse --remove tcp:4321') echo removed >> "$ANDROID_HOME/log"; [ "$MODE" != stuck-cleanup ] || exec sleep 20;;
  'shell am instrument '*)
    echo "$CPA_CONTRACT_PASSWORD"
    case "$MODE" in
      timeout) exec sleep 20;;
      cancel) kill -TERM "$RUNNER_PID"; exec sleep 20;;
      failed-status) echo 'INSTRUMENTATION_STATUS_CODE: -2';;
    esac
    echo 'OK (1 test)'; echo 'INSTRUMENTATION_CODE: -1';;
  *) exit 2;;
esac\n`, { mode: 0o700 });
      // Exercise the real timeout utility, shortening only the slow paths.
      writeFileSync(join(dir, 'timeout'), `#!/bin/sh
set -eu
[ "$1 $2 $3" = '--foreground -s KILL' ]
seconds=$4; shift 4
case "$MODE:$seconds" in timeout:120|stuck-cleanup:3) seconds=1;; esac
PATH="$ORIGINAL_PATH" exec timeout --foreground -s KILL "$seconds" "$@"\n`, { mode: 0o700 });
      const child = spawn('bash', ['-c', 'export RUNNER_PID=$$; exec bash "$1"', '_', `scripts/test-${kind}-device-contract.sh`], {
        cwd: new URL('../', import.meta.url), env: { ...process.env, MODE: mode, ANDROID_HOME: dir,
          ORIGINAL_PATH: process.env.PATH, PATH: `${dir}:${process.env.PATH}`, ANDROID_SERIAL: 'emulator-5580',
          CPA_CONTRACT_FIXTURE: 'source-service', CPA_TUNNEL_FIXTURE: 'source-services', CPA_TUNNEL_TARGET: 'android-contract-desktop',
          CPA_CONTRACT_URL: 'http://127.0.0.1:1234', CPA_TUNNEL_URL: 'http://127.0.0.1:1234',
          CPA_CONTRACT_PASSWORD: secret, CPA_TUNNEL_PASSWORD: secret, CPA_TUNNEL_KEY: secret },
      });
      let output = '';
      for (const stream of [child.stdout, child.stderr]) stream.on('data', s => { output += s; });
      const status = await new Promise((resolve, reject) => { child.on('error', reject); child.on('close', resolve); });
      assert.equal(output.includes(secret), false);
      assert.equal(status, mode === 'success' ? 0 : mode === 'cancel' ? 143 : 1, output);
      assert.equal(readFileSync(join(dir, 'log'), 'utf8'), ['timeout', 'cancel'].includes(mode) ? 'stopped\nremoved\n' : 'removed\n');
    });
  }
}
