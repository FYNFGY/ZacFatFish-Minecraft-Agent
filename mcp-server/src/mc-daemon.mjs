// Persistent MCP client daemon. Keeps one MCP session open and exposes a tiny local HTTP API so the
// agent can call tools with ~10 ms overhead instead of starting a new MCP session per call.
//   POST /call   {"tool":"get_state","args":{},"timeoutMs":15000} -> {"ok":true,"ms":12,"isError":false,"json":{...},"text":"..."}
//   GET  /health -> {"ok":true,"session":"connected","upMs":...}
// Env: MCP_URL (default http://127.0.0.1:57610/mcp), PORT (default 57611)
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const mcpUrl = process.env.MCP_URL || 'http://127.0.0.1:57610/mcp';
const port = Number(process.env.PORT || 57611);
// Ph6 §2.8.1: chat events pushed by the mod are forwarded here, to the MCP server's ingest endpoint.
const internalPort = Number(process.env.MC_INTERNAL_PORT || 57612);
const workspace = path.resolve(process.env.MC_WORKSPACE || process.cwd());
const startedAt = Date.now();

let client = null;
let connecting = null;
const watches = new Map();

async function connect() {
  if (client) return client;
  if (connecting) return connecting;
  connecting = (async () => {
    const c = new Client({ name: 'mc-daemon', version: '0.1.0' }, { capabilities: {} });
    const transport = new StreamableHTTPClientTransport(new URL(mcpUrl));
    await c.connect(transport);
    client = c;
    console.log(`[daemon] connected to ${mcpUrl}`);
    return c;
  })();
  try {
    return await connecting;
  } finally {
    connecting = null;
  }
}

async function callTool(tool, args, timeoutMs) {
  const t0 = Date.now();
  let lastError = null;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const c = await connect();
      const res = await c.callTool({ name: tool, arguments: args || {} }, undefined, { timeout: timeoutMs || 60000 });
      const text = (res.content || []).filter((x) => x.type === 'text').map((x) => x.text).join('\n');
      let json = null;
      try { json = JSON.parse(text); } catch { /* keep null */ }
      return { ok: true, ms: Date.now() - t0, isError: !!res.isError, json, text };
    } catch (e) {
      lastError = e;
      try { if (client) await client.close(); } catch { /* ignore */ }
      client = null;
      if (attempt === 0) await new Promise((r) => setTimeout(r, 250));
    }
  }
  return { ok: false, ms: Date.now() - t0, error: String(lastError && lastError.message ? lastError.message : lastError) };
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => { data += chunk; if (data.length > 4_000_000) reject(new Error('body too large')); });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

// ---------------------------------------------------------------- Ph6 §2.8 chat push

function instancesDir() {
  if (process.env.MC_STATE_DIR) return path.resolve(process.env.MC_STATE_DIR, 'instances');
  const local = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
  return path.join(local, 'state', 'headlessmc-mcp', 'instances');
}

/** The bearer token the mod was given at startup; the push must prove it is the same client. */
function liveTokens() {
  const out = new Set();
  try {
    for (const name of fs.readdirSync(instancesDir())) {
      if (!name.endsWith('.json')) continue;
      try {
        const raw = JSON.parse(fs.readFileSync(path.join(instancesDir(), name), 'utf8'));
        if (raw && typeof raw.token === 'string' && raw.token) out.add(raw.token);
      } catch { /* a half-written registration file is not fatal */ }
    }
  } catch { /* no client has ever registered */ }
  return out;
}

function internalToken() {
  try {
    return fs.readFileSync(path.join(workspace, 'runtime', 'mcp-internal-token'), 'utf8').trim();
  } catch {
    return '';
  }
}

async function forwardInternal(endpoint, rawBody) {
  const token = internalToken();
  if (!token) return { ok: false, error: 'no_internal_token' };
  const res = await fetch(`http://127.0.0.1:${internalPort}${endpoint}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-zff-internal': token },
    body: rawBody,
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = null; }
  return { ok: res.ok, status: res.status, json };
}

async function forwardChat(rawBody) {
  return forwardInternal('/internal/chat_event', rawBody);
}

// 2026-11-01 需求 3/6：MCP server 没了就别当孤儿。每 10 s 探一次 /internal/health，
// 连续 3 次失败（≈30 s）就自己退出；插件 stop 时也会直接 taskkill 整棵进程树。
let deadTicks = 0;
const watchdog = setInterval(async () => {
  try {
    const token = internalToken();
    const res = await fetch(`http://127.0.0.1:${internalPort}/internal/health`, {
      headers: token ? { 'x-zff-internal': token } : {},
      signal: AbortSignal.timeout(2000),
    });
    deadTicks = res.ok ? 0 : deadTicks + 1;
  } catch {
    deadTicks += 1;
  }
  if (deadTicks >= 3) {
    console.error('[daemon] MCP server 连续 3 次探活失败，守护进程退出');
    process.exit(0);
  }
}, 10_000);
watchdog.unref?.();

const server = http.createServer(async (req, res) => {
  const send = (code, obj) => {
    const body = JSON.stringify(obj);
    res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
    res.end(body);
  };
  try {
    if (req.method === 'GET' && req.url === '/health') {
      return send(200, { ok: true, session: client ? 'connected' : 'idle', upMs: Date.now() - startedAt });
    }
    // Ph6 §2.8.1: the mod pushes every chat line here (loopback + the bridge bearer token); we forward
    // it to the MCP server's ingest endpoint, which is where matching / queueing / the flag happen.
    if (req.method === 'POST' && req.url === '/chat_event') {
      const auth = String(req.headers.authorization || '');
      const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
      const known = liveTokens();
      if (!token || (known.size > 0 && !known.has(token))) {
        return send(401, { ok: false, error: 'bad_token' });
      }
      const body = await readBody(req);
      try {
        const forwarded = await forwardChat(body);
        return send(forwarded.ok ? 200 : 502, { ok: forwarded.ok, ingest: forwarded.json || null, status: forwarded.status });
      } catch (e) {
        return send(503, { ok: false, error: 'ingest_unreachable', message: String(e && e.message ? e.message : e) });
      }
    }
    // Ph7 M1-3: mod-side edge events (fall / damage / hunger / inventory_full) use the same loopback
    // bearer token as chat, but a separate endpoint so the two queues never block each other.
    if (req.method === 'POST' && req.url === '/event') {
      const auth = String(req.headers.authorization || '');
      const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
      const known = liveTokens();
      if (!token || (known.size > 0 && !known.has(token))) {
        return send(401, { ok: false, error: 'bad_token' });
      }
      const body = await readBody(req);
      try {
        const forwarded = await forwardInternal('/internal/event', body);
        return send(forwarded.ok ? 200 : 502, { ok: forwarded.ok, ingest: forwarded.json || null, status: forwarded.status });
      } catch (e) {
        return send(503, { ok: false, error: 'ingest_unreachable', message: String(e && e.message ? e.message : e) });
      }
    }
    if (req.method === 'GET' && req.url === '/interrupts') {
      try {
        const token = internalToken();
        const res = await fetch(`http://127.0.0.1:${internalPort}/internal/interrupts`, { headers: { 'x-zff-internal': token } });
        return send(res.status, await res.json());
      } catch (e) {
        return send(503, { ok: false, error: String(e && e.message ? e.message : e) });
      }
    }
    // The DSH plugin reads the flag/env state through this instead of opening its own MCP session.
    if (req.method === 'GET' && req.url === '/chat_state') {
      try {
        const token = internalToken();
        const res = await fetch(`http://127.0.0.1:${internalPort}/internal/state`, { headers: { 'x-zff-internal': token } });
        return send(res.status, await res.json());
      } catch (e) {
        return send(503, { ok: false, error: String(e && e.message ? e.message : e) });
      }
    }
    if (req.method === 'POST' && req.url === '/call') {      const body = await readBody(req);
      const { tool, args, timeoutMs } = JSON.parse(body);
      if (!tool) return send(400, { ok: false, error: 'tool is required' });
      // If the caller goes away mid-call (the agent aborted, the script was killed), cancel the job
      // it was polling/starting: user abort must stop the game work, not leave it running.
      const local = { sent: false, jobId: (args && args.jobId) || null };
      res.on('close', () => {
        if (local.sent || !local.jobId) return;
        console.log(`[daemon] caller left during ${tool}; cancelling ${local.jobId}`);
        callTool('job_cancel', { jobId: local.jobId }, 10_000).catch(() => { /* best effort */ });
      });
      const result = await callTool(tool, args, timeoutMs);
      if (result && result.json && result.json.jobId) local.jobId = result.json.jobId;
      local.sent = true;
      return send(200, result);
    }
    if (req.method === 'POST' && req.url === '/watch') {
      // The agent re-arms this on every job_status poll. If the agent dies (DSH kills the script
      // between polls) the TTL expires and the job is cancelled - no orphan digging/walking.
      const body = await readBody(req);
      const { jobId, ttlMs } = JSON.parse(body);
      if (!jobId) return send(400, { ok: false, error: 'jobId is required' });
      const ttl = Math.max(1500, Math.min(60_000, Number(ttlMs) || 3500));
      const old = watches.get(jobId);
      if (old) clearTimeout(old);
      const timer = setTimeout(() => {
        watches.delete(jobId);
        console.log(`[daemon] watch expired for ${jobId}; cancelling (caller gone)`);
        callTool('job_cancel', { jobId }, 10_000).catch(() => { /* best effort */ });
      }, ttl);
      watches.set(jobId, timer);
      return send(200, { ok: true, jobId, ttlMs: ttl, watching: watches.size });
    }
    if (req.method === 'POST' && req.url === '/batch') {
      const body = await readBody(req);
      const calls = JSON.parse(body);
      const out = [];
      for (const c of calls) out.push(await callTool(c.tool, c.args, c.timeoutMs));
      return send(200, { ok: true, results: out });
    }
    send(404, { ok: false, error: 'unknown endpoint' });
  } catch (e) {
    send(500, { ok: false, error: String(e && e.message ? e.message : e) });
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`[daemon] listening on http://127.0.0.1:${port} (mcp=${mcpUrl})`);
});
