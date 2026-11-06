// Round-3 test round 2: dig fix, light map, torch, branch mine, container, resync, stop-under-load.
import fs from 'node:fs';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const url = process.env.MCP_URL || 'http://127.0.0.1:57610/mcp';
const root = process.env.ROOT || 'D:\\mc\\MCP Development';
const client = new Client({ name: 'r3-tests2', version: '0.0.1' }, { capabilities: {} });
await client.connect(new StreamableHTTPClientTransport(new URL(url)));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const report = { startedAt: new Date().toISOString(), tests: [] };

async function call(name, args = {}, timeoutMs = 180000) {
  const t0 = Date.now();
  const res = await client.callTool({ name, arguments: args }, undefined, { timeout: timeoutMs });
  const ms = Date.now() - t0;
  const text = (res.content || []).filter((c) => c.type === 'text').map((c) => c.text).join('\n');
  let json = null;
  try { json = JSON.parse(text); } catch { json = { raw: text }; }
  return { ms, json, isError: !!res.isError };
}
async function waitJob(jobId, maxMs = 180000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < maxMs) {
    await sleep(150);
    const r = await call('job_status', { jobId }, 15000);
    last = r.json;
    if (last && ['done', 'failed', 'cancelled'].includes(last.state)) return { job: last, ms: Date.now() - t0 };
  }
  return { job: last, ms: Date.now() - t0, timeout: true };
}
function rec(name, data) {
  report.tests.push({ name, at: new Date().toISOString(), ...data });
  console.log(`[${name}] ${JSON.stringify(data).slice(0, 400)}`);
}

const st = await call('get_state', {}, 20000);
const p = st.json?.player;
if (!p) { console.error('no player'); process.exit(2); }
rec('state', { ms: st.ms, pos: [p.x, p.y, p.z], y: p.y, light: [p.blockLight, p.skyLight], fluid: p.fluidAtFeet });
const hasTorch = (p.inventory?.items || []).some((i) => i.id === 'minecraft:torch');
rec('inventory', { used: p.inventory?.usedSlots, free: p.inventory?.freeSlots, hasTorch, items: (p.inventory?.items || []).slice(0, 8) });

// 1. dig 8 blocks down with the fixed staircase
const targetY = Math.floor(p.y) - 8;
const dig = await call('dig_shaft', { level: targetY, mode: 'stair', yaw: p.yaw, timeoutSeconds: 400 }, 20000);
const digJob = await waitJob(dig.json?.jobId, 180000);
const afterDig = await call('get_state', {}, 20000);
rec('dig_shaft_8', { targetY, job: digJob.job, ms: digJob.ms, endY: afterDig.json?.player?.y });

// 2. light map at the new position
const pos2 = afterDig.json?.player;
const lm = await call('light_map', { radius: 6 }, 20000);
rec('light_map', { ms: lm.ms, darkCells: lm.json?.darkCells, minLight: lm.json?.minBlockLight, safe: lm.json?.safe, samples: lm.json?.samples });

// 3. torch at the first dark cell (if the player carries torches)
const dark = lm.json?.dark?.[0];
if (hasTorch && dark) {
  const ta = await call('torch_at', { x: dark.pos[0], y: dark.pos[1], z: dark.pos[2] }, 20000);
  await sleep(500);
  const lm2 = await call('light_map', { radius: 6 }, 20000);
  rec('torch_at', { target: dark.pos, result: ta.json, darkAfter: lm2.json?.darkCells, minAfter: lm2.json?.minBlockLight });
} else {
  rec('torch_at', { skipped: hasTorch ? 'no dark cell' : 'no torch in inventory' });
}

// 4. branch mine a small fishbone at the new depth
const bm = await call('branch_mine', { corridorLength: 16, spacing: 3, branchLength: 8, branches: 2, yaw: pos2.yaw, maxBlocks: 400, timeoutSeconds: 400 }, 20000);
const bmJob = await waitJob(bm.json?.jobId, 240000);
const lm3 = await call('light_map', { radius: 8 }, 20000);
rec('branch_mine', { job: bmJob.job, ms: bmJob.ms, darkAfter: lm3.json?.darkCells });

// 5. stop_all interrupting a branch mine
const bm2 = await call('branch_mine', { corridorLength: 64, spacing: 3, branchLength: 16, branches: 6, yaw: pos2.yaw, maxBlocks: 4000, timeoutSeconds: 600 }, 20000);
await sleep(1500);
const stop = await call('stop_all', { reason: 'test_interrupt' }, 15000);
const st1 = await call('get_state', {}, 15000);
await sleep(1200);
const st2 = await call('get_state', {}, 15000);
const jj = bm2.json?.jobId ? await call('job_status', { jobId: bm2.json.jobId }, 15000) : { json: null };
rec('stop_all_branch', {
  stopMs: stop.ms, stopResult: stop.json,
  jobState: jj.json?.state, jobReason: jj.json?.reason,
  movedAfterStop: st1.json?.player && st2.json?.player
    ? Math.hypot(st2.json.player.x - st1.json.player.x, st2.json.player.z - st1.json.player.z) : null,
});

// 6. container: find a chest nearby, open, read, close
const scan = await call('scan_area', { center: [Math.floor(pos2.x), Math.floor(pos2.y), Math.floor(pos2.z)], radius: 24, filter: 'chest', includeAir: false }, 30000);
const chest = (scan.json?.blocks || []).find((b) => b.id && b.id.includes('chest'));
if (chest) {
  const open = await call('container_open', { x: chest.pos[0], y: chest.pos[1], z: chest.pos[2] }, 20000);
  const slots = open.json?.slots?.length ?? null;
  const close = await call('container_close', {}, 15000);
  rec('container', { chest: chest.pos, openOk: !!open.json?.ok, slots, close: close.json });
} else {
  rec('container', { skipped: 'no chest within 24 blocks' });
}

// 7. resync
const rs = await call('resync', {}, 20000);
rec('resync', { ms: rs.ms, items: rs.json?.items, cancelledJobs: rs.json?.cancelledJobs, menuOpen: rs.json?.menuOpen });

report.finishedAt = new Date().toISOString();
fs.mkdirSync(path.join(root, 'evidence'), { recursive: true });
const out = path.join(root, 'evidence', 'round3-tests2.json');
fs.writeFileSync(out, JSON.stringify(report, null, 2), 'utf8');
console.log('wrote ' + out);
await client.close();
