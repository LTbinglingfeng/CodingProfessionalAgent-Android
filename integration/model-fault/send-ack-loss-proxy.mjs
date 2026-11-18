import http from 'node:http';
import crypto from 'node:crypto';

/** Test-only TCP/WebSocket fault boundary. All business bytes come from the real desktop.
 * Drops exactly one successful delegateRun reply AFTER forwarding the request once.
 * Existing read-only observer sockets survive; new connections fail until explicit release.
 * Never logs frames, bodies, cookies, prompts, passwords, or model credentials.
 */
export async function createSendAckLossProxy({ targetOrigin, token, WebSocket, WebSocketServer }) {
  if (!/^http:\/\/127\.0\.0\.1:[0-9]{1,5}$/.test(targetOrigin)) throw Error('Expected isolated loopback target');
  const target = new URL(targetOrigin);
  if (!target.port || typeof token !== 'string' || !/^[a-f0-9]{64}$/.test(token)) throw Error('Invalid fault proxy configuration');
  const expectedToken = Buffer.from(token);
  const state = { forwardedDelegateRuns: 0, accepted: false, faultApplied: false, droppedRpcReply: false,
    offline: false, blockedConnections: 0 };
  const status = () => ({ ...state });
  let armed = true;
  let stopping = false;
  const sockets = new Set();
  const upgrades = new Set();
  const upstreams = new Set();
  const httpUpstreams = new Set();
  const bridge = new WebSocketServer({ noServer: true, maxPayload: 4 * 1024 * 1024 });
  const authorized = req => {
    const value = req.headers['x-cpa-test-token'];
    if (typeof value !== 'string') return false;
    const candidate = Buffer.from(value);
    return candidate.length === expectedToken.length && crypto.timingSafeEqual(candidate, expectedToken);
  };
  function json(res, code, body) {
    res.writeHead(code, { 'content-type': 'application/json', 'cache-control': 'no-store', connection: 'close' });
    res.end(JSON.stringify(body));
  }
  const server = http.createServer((req, res) => {
    if (req.url?.startsWith('/__fault/')) {
      if (!authorized(req)) { req.resume(); json(res, 403, { error: 'forbidden' }); return; }
      if (req.url === '/__fault/status' && req.method === 'GET') { json(res, 200, status()); return; }
      if (req.url === '/__fault/release' && req.method === 'POST') {
        req.resume(); state.offline = false; json(res, 200, status()); return;
      }
      req.resume(); json(res, 404, { error: 'unknown test control' }); return;
    }
    if (stopping || state.offline) {
      state.blockedConnections++; req.resume(); json(res, 503, { error: 'test link offline' }); return;
    }
    // Forward origin/cookie unchanged. Only Host and hop-by-hop connection are rewritten.
    const headers = { ...req.headers, host: target.host };
    delete headers.connection;
    const upstream = http.request({ hostname: '127.0.0.1', port: Number(target.port), path: req.url,
      method: req.method, headers, timeout: 10_000 }, response => {
      response.on('error', () => res.destroy());
      res.writeHead(response.statusCode, response.headers); res.flushHeaders(); response.pipe(res);
    });
    httpUpstreams.add(upstream);
    upstream.on('close', () => httpUpstreams.delete(upstream));
    res.on('close', () => { if (!res.writableFinished) upstream.destroy(); });
    upstream.on('timeout', () => upstream.destroy());
    upstream.on('error', () => { if (!res.headersSent) json(res, 502, { error: 'test upstream unavailable' }); else res.destroy(); });
    req.on('aborted', () => upstream.destroy());
    req.pipe(upstream);
  });
  server.on('connection', socket => {
    sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket));
  });
  function rejectUpgrade(socket, code) {
    if (!socket.destroyed && !socket.writableEnded) socket.end(`HTTP/1.1 ${code} Test proxy\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  }
  function closePeer(peer, code, reason) {
    if (peer.readyState !== WebSocket.OPEN && peer.readyState !== WebSocket.CONNECTING) return;
    if (code === 1006 || code === 1005 || peer.readyState === WebSocket.CONNECTING) peer.terminate();
    else peer.close(code, reason);
  }
  server.on('upgrade', (req, socket, head) => {
    if (stopping || state.offline) { state.blockedConnections++; rejectUpgrade(socket, 503); return; }
    if (req.url !== '/api/ws') { rejectUpgrade(socket, 404); return; }
    const headers = {};
    for (const key of ['cookie', 'origin', 'user-agent']) if (req.headers[key]) headers[key] = req.headers[key];
    const upstream = new WebSocket(`ws://${target.host}/api/ws`, { headers, handshakeTimeout: 10_000,
      maxPayload: 4 * 1024 * 1024, perMessageDeflate: false });
    upstreams.add(upstream); upgrades.add(socket);
    let downstream;
    let waitingId;
    const fail = () => { if (downstream) downstream.terminate(); else rejectUpgrade(socket, 502); };
    upstream.on('error', fail);
    upstream.on('unexpected-response', (_request, response) => {
      response.resume(); rejectUpgrade(socket, response.statusCode === 401 ? 401 : 502); upstream.terminate();
    });
    socket.on('close', () => { upgrades.delete(socket); closePeer(upstream, 1006); });
    upstream.on('close', (code, reason) => { upstreams.delete(upstream); if (downstream) closePeer(downstream, code, reason); });
    upstream.on('open', () => {
      if (socket.destroyed || stopping || state.offline) { upstream.terminate(); rejectUpgrade(socket, 503); return; }
      bridge.handleUpgrade(req, socket, head, client => {
        downstream = client; upgrades.delete(socket);
        client.on('error', () => closePeer(upstream, 1006));
        client.on('close', (code, reason) => closePeer(upstream, code, reason));
        client.on('message', (data, binary) => {
          if (upstream.readyState !== WebSocket.OPEN) { client.terminate(); return; }
          if (!binary) {
            let message; try { message = JSON.parse(data.toString()); } catch {}
            if (message?.type === 'rpc' && message.method === 'session:delegateRun') {
              state.forwardedDelegateRuns++;
              if (armed && typeof message.id === 'string') { armed = false; waitingId = message.id; }
            }
          }
          upstream.send(data, { binary });
        });
        upstream.on('message', (data, binary) => {
          if (!binary && waitingId !== undefined) {
            let message; try { message = JSON.parse(data.toString()); } catch {}
            if (message?.type === 'rpc_result' && message.id === waitingId) {
              waitingId = undefined;
              state.accepted = true; state.faultApplied = true; state.droppedRpcReply = true; state.offline = true;
              // No success reply is fabricated or delivered; client sees a real socket failure.
              client.terminate(); upstream.terminate(); return;
            }
          }
          if (client.readyState === WebSocket.OPEN) client.send(data, { binary });
        });
      });
    });
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return {
    origin: `http://127.0.0.1:${server.address().port}`, status,
    async close() {
      stopping = true;
      for (const socket of upgrades) socket.destroy();
      for (const socket of bridge.clients) socket.terminate();
      for (const socket of upstreams) socket.terminate();
      for (const request of httpUpstreams) request.destroy();
      for (const socket of sockets) socket.destroy();
      await Promise.all([new Promise(resolve => bridge.close(resolve)), new Promise(resolve => server.close(resolve))]);
    },
  };
}
