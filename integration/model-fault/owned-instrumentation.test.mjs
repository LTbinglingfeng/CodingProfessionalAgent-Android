import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runOwnedInstrumentation } from './owned-instrumentation.mjs';

const failed = async () => { throw Error('SECRET_FAKE_FAILURE_PAYLOAD'); };
test('successful instrumentation never force-stops the app', async () => {
  const result = await runOwnedInstrumentation(async () => 'safe output', () => { throw Error('Unexpected cleanup'); });
  assert.equal(result, 'safe output');
});
test('adb timeout force-stops only the owned target and verifies absence without leaking failure details', async () => {
  const calls = [];
  await assert.rejects(runOwnedInstrumentation(failed, (args) => {
    calls.push(args);
    return args.includes('force-stop') ? '' : 'CPA_APP_PID_STATUS:1';
  }), error => error.message.includes('force-stopped') && !error.message.includes('SECRET') && error.cause === undefined);
  assert.deepEqual(calls[0], ['shell', 'am', 'force-stop', 'dev.cpa.android']);
  assert.equal(calls.length, 2);
});
test('waits for the target to actually exit, rather than trusting force-stop return alone', async () => {
  let probes = 0, clock = 0;
  await assert.rejects(runOwnedInstrumentation(failed, (args) => {
    if (args.includes('force-stop')) return '';
    return ++probes < 3 ? 'CPA_APP_PID_STATUS:0' : 'CPA_APP_PID_STATUS:1';
  }, { now: () => clock, wait: async ms => { clock += ms; } }), /force-stopped/);
  assert.equal(probes, 3);
  assert.equal(clock, 200);
});
test('unknown device output or transport errors cannot certify termination', async () => {
  for (const mode of ['invalid-status', 'transport-error', 'force-stop-failed']) {
    await assert.rejects(runOwnedInstrumentation(failed, (args) => {
      if (args.includes('force-stop') && mode !== 'force-stop-failed') return '';
      if (mode === 'invalid-status') return 'unexpected';
      throw Error('SECRET_DEVICE_ERROR');
    }), error => error.message.includes('unconfirmed') && !error.message.includes('SECRET'));
  }
});
test('a still-running target reaches a bounded unconfirmed outcome', async () => {
  let clock = 0, probes = 0;
  await assert.rejects(runOwnedInstrumentation(failed, (args) => {
    if (args.includes('force-stop')) return '';
    probes++; return 'CPA_APP_PID_STATUS:0';
  }, { now: () => clock, wait: async ms => { clock += ms; } }), /unconfirmed/);
  assert.equal(clock, 3_000);
  assert.equal(probes, 30);
});

test('force-stop and verification share the recovery timeout', async () => {
  let clock = 0;
  const budgets = [];
  await assert.rejects(runOwnedInstrumentation(failed, (args, timeout) => {
    budgets.push(timeout);
    clock += timeout - 1;
    return args.includes('force-stop') ? '' : 'CPA_APP_PID_STATUS:1';
  }, { now: () => clock }), /force-stopped/);
  assert.deepEqual(budgets, [3000, 1]);
});
