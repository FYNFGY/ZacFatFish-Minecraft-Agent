// Zero-screenshot acceptance run: drives a complete chat / path / container / build-verification
// workflow through the MCP server and asserts that no screenshot is ever used.
// Usage: node src/no-screenshot-run.mjs [--out <dir>]
import fs from 'node:fs';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const url = process.env.MCP_URL || 'http://127.0.0.1:57610/mcp';
const outIdx = process.argv.indexOf('--out');
const outDir = outIdx >= 0 ? process.argv[outIdx + 1] : 'D:/mc/MCP Development/evidence';
fs.mkdirSync(outDir, { recursive: true });

const client = new Client({ name: 'acceptance-no-screenshot', version: '1.0.0' }, { capabilities: {} });
await client.connect(new StreamableHTTPClientTransport(new URL(url)));

const trace = [];
let screenshotCalls = 0;
const log = (line) => console.log(line);

async function call(name, args = {}, timeout = 120000) {
  if (name === 'screenshot') screenshotCalls++;
  const t0 = Date.now();
  const res = await client.callTool({ name, arguments: args }, undefined, { timeout });
  const text = (res.content || []).filter((c) => c.type === 'text').map((c) => c.text).join('\n');
  let json = null;
  try { json = JSON.parse(text); } catch { /* non-json payload */ }
  trace.push({ at: new Date().toISOString(), tool: name, args, ms: Date.now() - t0, isError: !!res.isError, ok: json ? json.ok : null });
  if (res.isError) throw new Error(`${name} returned an error: ${text.slice(0, 400)}`);
  return json ?? text;
}

function ok(condition, message) {
  if (!condition) throw new Error('ASSERT FAILED: ' + message);
  log('  OK  ' + message);
}

async function waitJob(jobId, maxMs = 180000) {
  const deadline = Date.now() + maxMs;
  let last = null;
  while (Date.now() < deadline) {
    last = await call('job_status', { jobId });
    if (['done', 'failed', 'cancelled'].includes(last.state)) return last;
    await new Promise((r) => setTimeout(r, 500));
  }
  return last;
}

const started = Date.now();
try {
  log('phase 1: state (structured only)');
  const state = await call('get_state');
  ok(state.player && state.player.name, `player=${state.player.name}`);
  ok(state.screen && state.screen.inWorld === true, 'client is in a world');
  ok(state.screen.open === false, 'no screen blocks input');

  log('phase 2: chat send + read-back');
  const message = 'no-screenshot acceptance ' + new Date().toISOString().slice(0, 19);
  const sent = await call('chat', { message });
  ok(sent.sent === true, 'chat reported sent');
  await new Promise((r) => setTimeout(r, 1200));
  const history = await call('chat_history', { limit: 8 });
  const texts = (history.messages || []).map((m) => m.text || '');
  ok(texts.some((t) => t.includes('no-screenshot acceptance')), 'chat_history contains the message');

  log('phase 3: real pathing to the test rig');
  const pathJob = await call('path_to', { x: 1, y: 100, z: -3, range: 2, timeoutSeconds: 180 });
  ok(!!pathJob.jobId, `path job ${pathJob.jobId}`);
  let st = await waitJob(pathJob.jobId);
  ok(st.state === 'done', `path finished: ${st.state}, progress ${st.progress}`);
  const after = await call('get_state');
  const dist = Math.hypot(after.player.x - 1, after.player.z + 3);
  ok(dist < 4.0, `player is within ${dist.toFixed(2)} blocks of the rig`);

  log('phase 4: container open / read / close');
  const opened = await call('container_open', { x: 2, y: 100, z: -6 });
  ok(String(opened.menuClass).includes('ChestMenu'), `menu=${opened.menuClass}`);
  const read = await call('container_read');
  ok(Array.isArray(read.slots) && read.slots.length === 63, `slots=${read.slots.length}`);
  ok(read.carried && read.carried.id === 'minecraft:air', 'cursor is empty');
  const closed = await call('container_close');
  ok(closed.wasOpen === true, 'container closed again');

  log('phase 5: build verification by block_query');
  const q = await call('block_query', { positions: [[7, 100, 0], [8, 100, 0], [9, 100, 0], [7, 101, 0], [8, 101, 0], [9, 101, 0], [7, 102, 0], [8, 102, 0], [9, 102, 0]] });
  const id = (p) => (q.blocks.find((b) => b.pos[0] === p[0] && b.pos[1] === p[1] && b.pos[2] === p[2]) || {}).id;
  ok(id([7, 100, 0]) === 'minecraft:diamond_block' && id([8, 100, 0]) === 'minecraft:air' && id([9, 100, 0]) === 'minecraft:diamond_block', 'bottom row pattern');
  ok(id([7, 101, 0]) === 'minecraft:air' && id([8, 101, 0]) === 'minecraft:diamond_block' && id([9, 101, 0]) === 'minecraft:air', 'middle row pattern');
  ok(id([7, 102, 0]) === 'minecraft:air' && id([8, 102, 0]) === 'minecraft:diamond_block' && id([9, 102, 0]) === 'minecraft:air', 'top row pattern');

  log('phase 6: no-screenshot audit');
  ok(screenshotCalls === 0, `screenshot calls = ${screenshotCalls}`);
  const tools = await client.listTools();
  ok(tools.tools.length === 31, `tool surface intact: ${tools.tools.length}`);

  const summary = {
    result: 'PASS',
    startedAt: new Date(started).toISOString(),
    elapsedMs: Date.now() - started,
    screenshotCalls,
    toolCalls: trace.length,
    player: state.player.name,
    dimension: state.player.dimension,
    finalPosition: [after.player.x, after.player.y, after.player.z],
    calls: trace,
  };
  fs.writeFileSync(path.join(outDir, 'no-screenshot-run.json'), JSON.stringify(summary, null, 2));
  log(`PASS in ${((Date.now() - started) / 1000).toFixed(1)}s with ${trace.length} tool calls and 0 screenshots`);
} catch (error) {
  const summary = {
    result: 'FAIL',
    error: String(error && error.message ? error.message : error),
    screenshotCalls,
    calls: trace,
  };
  fs.writeFileSync(path.join(outDir, 'no-screenshot-run.json'), JSON.stringify(summary, null, 2));
  console.error('FAIL: ' + summary.error);
  process.exitCode = 1;
} finally {
  await client.close();
}
