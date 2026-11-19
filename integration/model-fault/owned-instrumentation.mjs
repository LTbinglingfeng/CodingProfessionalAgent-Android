// Only the runner that has verified/claimed the designated emulator may call this helper.
// A timed-out host adb child does not prove the Android instrumentation has terminated.
export async function runOwnedInstrumentation(execute, call, { now = () => Math.floor(performance.now()),
  wait = ms => new Promise(resolve => setTimeout(resolve, ms)) } = {}) {
  try { return await execute(); }
  catch {
    let stopped = false;
    try {
      const deadline = now() + 3_000;
      call(['shell', 'am', 'force-stop', 'dev.cpa.android'], 3_000);
      let remaining;
      while ((remaining = deadline - now()) > 0) {
        const state = call(['shell', 'pidof dev.cpa.android >/dev/null; echo CPA_APP_PID_STATUS:$?'], remaining);
        if (state === 'CPA_APP_PID_STATUS:1') { stopped = true; break; }
        if (state !== 'CPA_APP_PID_STATUS:0') throw Error('Device process state unknown');
        await wait(Math.max(0, Math.min(100, deadline - now())));
      }
    } catch {}
    // Never include the original adb/instrumentation output, cause or exception message.
    throw Error(stopped ? 'Instrumentation could not complete; owned emulator app force-stopped, raw output suppressed' :
      'Instrumentation could not complete; app termination unconfirmed, investigate designated emulator');
  }
}
