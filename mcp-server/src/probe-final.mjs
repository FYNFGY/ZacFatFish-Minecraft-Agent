// Final regression pass after the watchdog work: state, stop_all, mining, branch mine, resync, light.
const D = 'http://127.0.0.1:57611';
async function call(t, a = {}, to = 120000) {
  const r = await fetch(`${D}/call`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool: t, args: a, timeoutMs: to }) });
  return r.json();
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitJob(id, maxMs = 180000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < maxMs) {
    await sleep(80);
    const r = await call('job_status', { jobId: id }, 15000);
    last = r.json;
    if (last && ['done', 'failed', 'cancelled'].includes(last.state)) return { job: last, ms: Date.now() - t0 };
  }
  return { job: last, ms: Date.now() - t0, timeout: true };
}
const out = { at: new Date().toISOString(), checks: {} };

const st = await call('get_state');
const p = st.json.player;
out.checks.state = { ms: st.ms, player: p.name, pos: [p.x, p.y, p.z], light: [p.blockLight, p.skyLight] };

// latency
const lats = [];
for (let i = 0; i < 5; i++) lats.push((await call('get_state')).ms);
lats.sort((a, b) => a - b);
out.checks.latency = { samples: lats, median: lats[2] };

// stop_all
const stop = await call('stop_all', { reason: 'final_regression' });
out.checks.stop_all = { ms: stop.ms, result: stop.json };

// mine one solid block nearby
const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
const grid = [];
for (let dx = -3; dx <= 3; dx++) for (let dz = -3; dz <= 3; dz++) if (dx || dz) grid.push([bx + dx, by, bz + dz]);
const bq = await call('block_query', { positions: grid });
const solid = (bq.json.blocks || []).find((b) => !b.air && b.hardness >= 0 && b.hardness < 50);
if (solid) {
  const m = await call('mine_block', { x: solid.pos[0], y: solid.pos[1], z: solid.pos[2] });
  const j = await waitJob(m.json.jobId, 60000);
  const chk = await call('block_query', { positions: [solid.pos] });
  out.checks.mine = { block: solid.id, state: j.job.state, ms: j.ms, ticks: j.job.metrics?.results?.[0]?.ticks, after: chk.json.blocks?.[0]?.id };
}

// short branch mine (8 corridor, 1 branch of 4)
const bm = await call('branch_mine', { corridorLength: 8, spacing: 4, branchLength: 4, branches: 1, yaw: p.yaw, maxBlocks: 120 }, 60000);
const bj = await waitJob(bm.json.jobId, 120000);
out.checks.branch_mine = { state: bj.job.state, ms: bj.ms, blocks: bj.job.metrics?.blocksMined, branches: bj.job.metrics?.branchesDone, steps: bj.job.metrics?.steps };

// light map + resync
const lm = await call('light_map', { radius: 6 });
out.checks.light_map = { darkCells: lm.json.darkCells, min: lm.json.minBlockLight, samples: lm.json.samples };
const rs = await call('resync');
out.checks.resync = { ms: rs.ms, items: rs.json.items, cancelled: rs.json.cancelledJobs };

console.log(JSON.stringify(out, null, 2));
const fs = await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/round3-final.json', JSON.stringify(out, null, 2));
