// P1-11 test: a client that starts a long job and then drops the MCP session must not leave the
// game working. The server cancels every job it started when the request is aborted or the session
// closes.
//
//   node test/p1-11-abort.mjs [url]
//
// Exit code 0 = pass, 1 = fail. Writes evidence/p1-11-abort.json.
import fs from 'node:fs';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const URL_BASE = process.argv[2] || 'http://127.0.0.1:57610/mcp';
const OUT = path.resolve(process.env.MC_ROOT || 'D:/mc/MCP Development', 'evidence', 'p1-11-abort.json');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function connect() {
  const client = new Client({ name: 'p1-11-abort-test', version: '1.0.0' });
  const transport = new StreamableHTTPClientTransport(new URL(URL_BASE));
  await client.connect(transport);
  return client;
}

const textOf = (res) => {
  const item = (res.content || []).find((c) => c.type === 'text');
  try { return JSON.parse(item.text); } catch { return { raw: item && item.text }; }
};

// --- raw JSON-RPC helpers (needed to deliver notifications/cancelled while a call is in flight) ---
const MCP_URL = new URL(URL_BASE);
async function rawPost(body, sessionId) {
  return fetch(MCP_URL, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      ...(sessionId ? { 'mcp-session-id': sessionId } : {}),
    },
    body: JSON.stringify(body),
  });
}
async function rawInitialize() {
  const res = await rawPost({
    jsonrpc: '2.0', id: 1, method: 'initialize',
    params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'p1-11-raw', version: '1.0.0' } },
  });
  const sid = res.headers.get('mcp-session-id');
  await res.text();
  if (!sid) throw new Error('initialize did not return mcp-session-id');
  const init = await rawPost({ jsonrpc: '2.0', method: 'notifications/initialized' }, sid);
  await init.text();
  return sid;
}
async function rawCall(sid, name, args, id, timeoutMs = 3000) {
  const res = await fetch(MCP_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream', 'mcp-session-id': sid },
    body: JSON.stringify({ jsonrpc: '2.0', id, method: 'tools/call', params: { name, arguments: args } }),
    signal: AbortSignal.timeout(timeoutMs),
  });
  return res.text();
}
async function rawNotify(sid, msg) {
  const res = await rawPost(msg, sid);
  try { await res.text(); } catch { /* ignore */ }
}

const started = Date.now();
const report = { test: 'P1-11 user abort cancels the running job', at: new Date().toISOString() };
let failed = false;
try {
  const client = await connect();
  report.server = (await client.getServerVersion()) || null;

  // A long, harmless job: walk in place for 10 s.
  const call = await client.callTool({ name: 'walk', arguments: { yaw: 0, ticks: 200 } });
  const body = textOf(call);
  report.jobStarted = body;
  const jobId = body.jobId;
  if (!jobId) throw new Error('walk did not return a jobId');
  await sleep(1200);

  // Drop the session exactly like a user pressing stop: the transport close must cancel the job.
  await client.close();
  report.sessionClosedAt = Date.now();
  // the server treats "SSE stream closed + 2 s without new requests" as the end of the session
  await sleep(4000);

  const checker = await connect();
  const st = textOf(await checker.callTool({ name: 'job_status', arguments: { jobId } }));
  await checker.close();
  report.jobAfterAbort = { state: st.state, reason: st.reason, message: st.message, metrics: st.metrics };
  if (st.state !== 'cancelled') {
    failed = true;
    report.error = `job state is ${st.state}, expected cancelled`;
  }
  report.scenarios = { sessionClose: { pass: st.state === 'cancelled', jobId, state: st.state } };

  // Scenario B: the user stops while a tool call is in flight. Use raw JSON-RPC so the exact
  // `notifications/cancelled` message a client sends on abort can be delivered while the call runs.
  const c2 = await connect();
  const walk = textOf(await c2.callTool({ name: 'walk', arguments: { yaw: 90, ticks: 240 } }));
  await c2.close();
  const session = await rawInitialize();
  const inFlight = rawCall(session, 'scan_area', { radius: 32 }, 42);
  await sleep(20);
  await rawNotify(session, { jsonrpc: '2.0', method: 'notifications/cancelled', params: { requestId: 42, reason: 'user stop' } });
  const aborted = await inFlight.then(() => 'completed').catch((e) => (e && e.name) || 'error');  await sleep(1500);
  const checker2 = await connect();
  const stB = textOf(await checker2.callTool({ name: 'job_status', arguments: { jobId: walk.jobId } }));
  await checker2.close();
  report.scenarios.requestAbort = { jobId: walk.jobId, abortResult: aborted, state: stB.state, pass: stB.state === 'cancelled' };
  if (stB.state !== 'cancelled') {
    failed = true;
    report.error = `scenario B: job state is ${stB.state}, expected cancelled`;
  }
  report.jobAfterAbortB = { state: stB.state, reason: stB.reason };
} catch (e) {
  failed = true;
  report.error = e.message;
}
report.ms = Date.now() - started;
report.pass = !failed;
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(report, null, 2) + '\n', 'utf8');
console.log(JSON.stringify({ pass: report.pass, jobId: report.jobStarted && report.jobStarted.jobId, state: report.jobAfterAbort && report.jobAfterAbort.state, error: report.error }));
process.exit(failed ? 1 : 0);
