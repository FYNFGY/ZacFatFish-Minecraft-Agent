// Round-3 smoke + timing harness. Connects to the MCP server, runs a short sequence of timed checks
// and writes evidence/round3-smoke.json. Safe by design: it only touches blocks near the player.
import fs from 'node:fs';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const url = process.env.MCP_URL || 'http://127.0.0.1:57610/mcp';
const root = process.env.ROOT || 'D:\\mc\\MCP Development';
const client = new Client({ name: 'r3-tests', version: '0.0.1' }, { capabilities: {} });
await client.connect(new StreamableHTTPClientTransport(new URL(url)));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const report = { startedAt: new Date().toISOString(), tests: [] };

async function call(name, args = {}, timeoutMs = 120000) {
  const t0 = Date.now();
  const res = await client.callTool({ name, arguments: args }, undefined, { timeout: timeoutMs });
  const ms = Date.now() - t0;
  const text = (res.content || []).filter((c) => c.type === 'text').map((c) => c.text).join('\n');
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = { raw: text };
  }
  return { ms, json, isError: !!res.isError };
}

async function waitJob(jobId, maxMs = 60000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < maxMs) {
    await sleep(150);
    const r = await call('job_status', { jobId }, 10000);
    last = r.json;
    if (last && ['done', 'failed', 'cancelled'].includes(last.state)) {
      return { job: last, ms: Date.now() - t0 };
    }
  }
  return { job: last, ms: Date.now() - t0, timeout: true };
}

function record(name, data) {
  report.tests.push({ name, at: new Date().toISOString(), ...data });
  console.log(`[${name}] ${JSON.stringify(data).slice(0, 300)}`);
}

// 0. state
const state0 = await call('get_state', {}, 15000);
const p = state0.json?.player;
if (!p) {
  console.error('no player state: ' + JSON.stringify(state0.json).slice(0, 300));
  await client.close();
  process.exit(2);
}
record('state', { ms: state0.ms, player: p.name, pos: [p.x, p.y, p.z], hp: p.health });

// 1. state latency x5
const lat = [];
for (let i = 0; i < 5; i++) {
  const r = await call('get_state', {}, 15000);
  lat.push(r.ms);
}
lat.sort((a, b) => a - b);
record('state_latency', { samples: lat, median: lat[Math.floor(lat.length / 2)] });

// 2. stop_all while idle
const idleStop = await call('stop_all', { reason: 'smoke_idle' }, 10000);
record('stop_all_idle', { ms: idleStop.ms, result: idleStop.json });

// 3. stop_all under load: start a long walk, then stop after 300 ms
const walk = await call('walk', { yaw: 0, ticks: 300, timeoutSeconds: 120 }, 10000);
const walkJob = walk.json?.jobId;
await sleep(300);
const stopLoad = await call('stop_all', { reason: 'smoke_load' }, 10000);
const stopT0 = Date.now();
let cancelled = null;
for (let i = 0; i < 20; i++) {
  const j = await call('job_status', { jobId: walkJob }, 8000);
  if (j.json && ['cancelled', 'done', 'failed'].includes(j.json.state)) {
    cancelled = { state: j.json.state, reason: j.json.reason, ms: Date.now() - stopT0 };
    break;
  }
  await sleep(100);
}
record('stop_all_under_load', { stopAllMs: stopLoad.ms, stopResult: stopLoad.json, walkCancelled: cancelled });

// 4. mine one nearby block (block next to the player's feet, one down)
const bx = Math.floor(p.x);
const by = Math.floor(p.y);
const bz = Math.floor(p.z);
const candidates = [
  [bx + 1, by - 1, bz],
  [bx - 1, by - 1, bz],
  [bx, by - 1, bz + 1],
  [bx, by - 1, bz - 1],
];
const bq = await call('block_query', { positions: candidates }, 15000);
const solid = (bq.json?.blocks || []).find((b) => !b.air && b.hardness >= 0 && b.hardness < 100);
if (solid) {
  const mine = await call('mine_block', { x: solid.pos[0], y: solid.pos[1], z: solid.pos[2] }, 90000);
  const job = await waitJob(mine.json?.jobId, 60000);
  const after = await call('block_query', { positions: [solid.pos] }, 15000);
  record('mine_one', {
    target: solid.pos,
    block: solid.id,
    job: job.job,
    verify: after.json?.blocks?.[0]?.id,
  });
} else {
  record('mine_one', { skipped: 'no suitable block next to the player' });
}

// 5. small staircase dig (4 blocks down)
const dig = await call('dig_shaft', { level: Math.floor(p.y) - 4, mode: 'stair', yaw: p.yaw, timeoutSeconds: 300 }, 15000);
const digJob = await waitJob(dig.json?.jobId, 120000);
const afterDig = await call('get_state', {}, 15000);
record('dig_shaft_small', {
  job: digJob.job,
  ms: digJob.ms,
  startY: p.y,
  endY: afterDig.json?.player?.y,
});

// 6. tiny branch mine (only when the player is underground enough to be safe)
const st = afterDig.json?.player;
if (st && st.y < 70) {
  const bm = await call('branch_mine', {
    corridorLength: 10, spacing: 4, branchLength: 6, branches: 1,
    yaw: st.yaw, maxBlocks: 200, timeoutSeconds: 300,
  }, 15000);
  const bmJob = await waitJob(bm.json?.jobId, 120000);
  record('branch_mine_small', { job: bmJob.job, ms: bmJob.ms });
} else {
  record('branch_mine_small', { skipped: 'player not in a safe place (y>=70)' });
}

report.finishedAt = new Date().toISOString();
fs.mkdirSync(path.join(root, 'evidence'), { recursive: true });
const out = path.join(root, 'evidence', 'round3-smoke.json');
fs.writeFileSync(out, JSON.stringify(report, null, 2), 'utf8');
console.log('wrote ' + out);
await client.close();
