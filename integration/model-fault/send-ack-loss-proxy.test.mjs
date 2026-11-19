import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { createSendAckLossProxy } from './send-ack-loss-proxy.mjs';

// Protocol fixtures only for unit-testing the fault boundary, NOT model acceptance evidence.
const require = createRequire(new URL('../../../CodingProfessionalAgent/package.json', import.meta.url));
const { WebSocket, WebSocketServer } = require('ws');
const token = 'a'.repeat(64);
const deps = { WebSocket, WebSocketServer, token };
async function until(predicate) {
  const deadline = Date.now() + 3_000;
  while (Date.now() < deadline) { if (predicate()) return; await new Promise(r => setTimeout(r, 5)); }
  throw Error('Fixture condition timed out');
}
async function backend(t, { rejectDelegate = false } = {}) {
  let delegates = 0, requests = 0;
  const cookies = [];
  const hanging = new Set();
  const server = http.createServer((req, res) => {
    requests++; cookies.push(req.headers.cookie);
    res.setHeader('content-type', 'application/json');
    if (req.url === '/fixture/hang') {
      req.resume(); hanging.add(res); res.on('close', () => hanging.delete(res));
      res.flushHeaders(); return;
    }
    if (req.url === '/api/auth/login') res.setHeader('set-cookie', 'test_session=one; HttpOnly; Path=/');
    req.resume(); res.end(JSON.stringify({ required: true, authenticated: Boolean(req.headers.cookie) }));
  });
  const wss = new WebSocketServer({ noServer: true });
  server.on('upgrade', (req, socket, head) => {
    if (!req.headers.cookie) { socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'); return; }
    cookies.push(req.headers.cookie);
    wss.handleUpgrade(req, socket, head, ws => {
      ws.send(JSON.stringify({ type: 'hello', clientId: 'fixture-only' }));
      ws.on('message', (data, binary) => {
        if (binary) { ws.send(data, { binary: true }); return; }
        const message = JSON.parse(data.toString());
        if (message.method === 'session:delegateRun') {
          delegates++;
          if (rejectDelegate) { ws.send(JSON.stringify({ type: 'rpc_error', id: message.id, error: 'fixture rejection' })); return; }
          // Emit a genuine fixture event first: proxy must not fake/count it as an ACK.
          ws.send(JSON.stringify({ type: 'event', event: { kind: 'fixture.user-entry' } }));
          setTimeout(() => {
            if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'rpc_result', id: message.id, result: 'fixture-session' }));
          }, 20);
        } else if (message.method === 'fixture.fail') ws.send(JSON.stringify({ type: 'rpc_error', id: message.id, error: 'fixture rejected' }));
        else if (message.method === 'fixture.close') ws.close(4401, 'fixture auth expired');
        else ws.send(JSON.stringify({ type: 'rpc_result', id: message.id, result: 'fixture read' }));
      });
    });
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => {
    for (const client of wss.clients) client.terminate();
    server.closeAllConnections();
    await Promise.all([new Promise(r => wss.close(r)), new Promise(r => server.close(r))]);
  });
  return { origin: `http://127.0.0.1:${server.address().port}`, delegates: () => delegates, requests: () => requests,
    hanging: () => hanging.size, cookies };
}
async function connect(t, origin, cookie = 'test_session=one') {
  const socket = new WebSocket(origin.replace('http:', 'ws:') + '/api/ws', { headers: { cookie } });
  const messages = [];
  let closed = false, closeCode;
  socket.on('message', (data, binary) => messages.push(binary ? { binary: [...data] } : JSON.parse(data.toString())));
  socket.on('error', () => {});
  socket.on('close', code => { closed = true; closeCode = code; });
  t.after(() => socket.terminate());
  await once(socket, 'open');
  await until(() => messages.some(m => m.type === 'hello'));
  return { socket, messages, closed: () => closed, closeCode: () => closeCode,
    call: (method, id = 'read') => socket.send(JSON.stringify({ type: 'rpc', method, id, args: [] })) };
}
async function startProxy(t, target) {
  const proxy = await createSendAckLossProxy({ ...deps, targetOrigin: target.origin });
  t.after(() => proxy.close());
  return proxy;
}
const control = (proxy, action, method = 'GET', provided = token) => fetch(proxy.origin + '/__fault/' + action,
  { method, headers: { 'X-CPA-Test-Token': provided } });

test('forwards HTTP authentication and distinct cookies without retaining bodies', async t => {
  const target = await backend(t), proxy = await startProxy(t, target);
  const login = await fetch(proxy.origin + '/api/auth/login', { method: 'POST', body: '{"password":"fixture"}' });
  assert.equal(login.status, 200);
  assert.match(login.headers.get('set-cookie'), /^test_session=one;/);
  await fetch(proxy.origin + '/api/auth/status', { headers: { cookie: 'test_session=two' } });
  assert.deepEqual(target.cookies, [undefined, 'test_session=two']);
  assert.equal(proxy.status().forwardedDelegateRuns, 0);
});

test('drops only a server-accepted ACK, preserves observer, rejects reconnect, releases without replay', async t => {
  const target = await backend(t), proxy = await startProxy(t, target);
  const observer = await connect(t, proxy.origin, 'test_session=observer');
  const sender = await connect(t, proxy.origin);
  sender.call('session:delegateRun', 'mutating-1');
  await until(() => sender.closed());
  assert.equal(target.delegates(), 1);
  assert.equal(proxy.status().forwardedDelegateRuns, 1);
  assert.equal(proxy.status().accepted, true);
  assert.equal(proxy.status().droppedRpcReply, true);
  assert.equal(proxy.status().offline, true);
  assert.equal(sender.messages.filter(m => m.type === 'rpc_result').length, 0);
  assert.equal(sender.messages.filter(m => m.type === 'event').length, 1);
  assert.equal(observer.closed(), false);
  observer.call('session:get', 'authoritative-read');
  await until(() => observer.messages.some(m => m.id === 'authoritative-read'));
  assert.equal((await fetch(proxy.origin + '/api/auth/status')).status, 503);
  const denied = new WebSocket(proxy.origin.replace('http:', 'ws:') + '/api/ws', { headers: { cookie: 'test_session=one' } });
  denied.on('error', () => {});
  const status = await new Promise(resolve => denied.on('unexpected-response', (_req, res) => {
    res.resume(); resolve(res.statusCode); denied.terminate();
  }));
  assert.equal(status, 503);
  const release = await control(proxy, 'release', 'POST');
  assert.equal(release.status, 200);
  const recovered = await connect(t, proxy.origin);
  recovered.call('session:get', 'recovered-read');
  await until(() => recovered.messages.some(m => m.id === 'recovered-read'));
  assert.equal(target.delegates(), 1);
  assert.equal(proxy.status().forwardedDelegateRuns, 1);
  assert.equal(proxy.status().offline, false);
});

test('rejected delegate is not fabricated into an accepted unknown outcome', async t => {
  const target = await backend(t, { rejectDelegate: true }), proxy = await startProxy(t, target);
  const client = await connect(t, proxy.origin);
  client.call('session:delegateRun', 'rejected-mutation');
  await until(() => client.messages.some(m => m.id === 'rejected-mutation'));
  assert.equal(client.messages.find(m => m.id === 'rejected-mutation').type, 'rpc_error');
  assert.equal(proxy.status().accepted, false);
  assert.equal(proxy.status().faultApplied, false);
  assert.equal(proxy.status().offline, false);
  assert.equal(proxy.status().forwardedDelegateRuns, 1);
});

test('HTTP upstreams are cancelled on downstream abort and on proxy shutdown', async t => {
  const target = await backend(t), proxy = await startProxy(t, target);
  const abort = new AbortController();
  const first = await fetch(proxy.origin + '/fixture/hang', { signal: abort.signal });
  const firstBody = first.text().catch(() => null);
  assert.equal(target.hanging(), 1);
  abort.abort(); await firstBody;
  await until(() => target.hanging() === 0);
  const secondBody = fetch(proxy.origin + '/fixture/hang').then(r => r.text()).catch(() => null);
  await until(() => target.hanging() === 1);
  await proxy.close(); await secondBody;
  await until(() => target.hanging() === 0);
});

test('control requires a per-run token and never leaks to upstream', async t => {
  const target = await backend(t), proxy = await startProxy(t, target);
  assert.equal((await control(proxy, 'status', 'GET', 'wrong')).status, 403);
  assert.equal((await control(proxy, 'release', 'POST', '')).status, 403);
  assert.equal((await control(proxy, 'missing')).status, 404);
  assert.equal(target.requests(), 0);
  assert.equal((await (await control(proxy, 'status')).json()).faultApplied, false);
});

test('read errors, binary frames and 4401 close retain their transport meaning', async t => {
  const target = await backend(t), proxy = await startProxy(t, target);
  const client = await connect(t, proxy.origin);
  client.call('fixture.fail', 'bad-read');
  await until(() => client.messages.some(m => m.id === 'bad-read'));
  assert.equal(client.messages.find(m => m.id === 'bad-read').type, 'rpc_error');
  client.socket.send(Buffer.from([1, 2, 3]));
  await until(() => client.messages.some(m => m.binary));
  assert.deepEqual(client.messages.find(m => m.binary).binary, [1, 2, 3]);
  client.call('fixture.close'); await until(() => client.closed());
  assert.equal(client.closeCode(), 4401);
  assert.equal(proxy.status().faultApplied, false);
});

test('unauthenticated upgrade stays 401 and cannot arm the fault', async t => {
  const target = await backend(t), proxy = await startProxy(t, target);
  const socket = new WebSocket(proxy.origin.replace('http:', 'ws:') + '/api/ws');
  socket.on('error', () => {});
  const status = await new Promise(resolve => socket.on('unexpected-response', (_req, res) => {
    res.resume(); resolve(res.statusCode); socket.terminate();
  }));
  assert.equal(status, 401);
  assert.equal(proxy.status().forwardedDelegateRuns, 0);
});

test('refuses non-loopback upstreams and missing control secret', async () => {
  await assert.rejects(createSendAckLossProxy({ ...deps, targetOrigin: 'https://example.invalid' }));
  await assert.rejects(createSendAckLossProxy({ ...deps, targetOrigin: 'http://127.0.0.1:1234', token: '' }));
});
