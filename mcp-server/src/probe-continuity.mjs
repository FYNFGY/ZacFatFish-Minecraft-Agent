// Continuity benchmark: compare one-job-per-block vs one batched mine job, plus daemon latencies.
const DAEMON = 'http://127.0.0.1:57611';
async function call(tool, args = {}, timeoutMs = 120000) {
  const r = await fetch(`${DAEMON}/call`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool, args, timeoutMs }) });
  return r.json();
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitJob(id, maxMs = 120000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < maxMs) {
    await sleep(60);
    const r = await call('job_status', { jobId: id }, 10000);
    last = r.json;
    if (last && ['done', 'failed', 'cancelled'].includes(last.state)) return { job: last, ms: Date.now() - t0 };
  }
  return { job: last, ms: Date.now() - t0, timeout: true };
}

const st = await call('get_state');
const p = st.json.player;
const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
const out = { player: [bx, by, bz], rounds: {} };

// find 6 mineable blocks (dirt/stone) at feet level within 4 blocks
const grid = [];
for (let dx = -3; dx <= 3; dx++) for (let dz = -3; dz <= 3; dz++) if (dx || dz) grid.push([bx + dx, by, bz + dz]);
const bq = await call('block_query', { positions: grid });
const targets = (bq.json.blocks || []).filter((b) => !b.air && b.hardness >= 0 && b.hardness < 50).slice(0, 6);
out.targets = targets.map((t) => [t.pos, t.id, t.hardness]);
console.log(`targets: ${targets.map((t) => t.id).join(',')}`);

// round A: one job per block
const tA = Date.now();
const perBlock = [];
for (const t of targets) {
  const m = await call('mine_block', { x: t.pos[0], y: t.pos[1], z: t.pos[2] });
  const j = await waitJob(m.json.jobId);
  perBlock.push({ block: t.id, ms: j.ms, state: j.job.state, ticks: j.job.metrics?.results?.[0]?.ticks });
}
const totalA = Date.now() - tA;
out.rounds.singleJobs = { totalMs: totalA, perBlock };

// round B: one batched job with all positions
const bq2 = await call('block_query', { positions: grid });
const targets2 = (bq2.json.blocks || []).filter((b) => !b.air && b.hardness >= 0 && b.hardness < 50).slice(0, 6);
const positions = targets2.map((t) => t.pos);
if (positions.length === 0) {
  out.rounds.batch = { skipped: 'no targets left' };
} else {
  const tB = Date.now();
  const m = await call('mine_block', { positions });
  const j = await waitJob(m.json.jobId);
  out.rounds.batch = {
    totalMs: Date.now() - tB,
    state: j.job.state,
    ticks: (j.job.metrics?.results || []).map((r) => r.ticks),
    blocks: j.job.metrics?.blocksMined,
  };
}

// round C: daemon latency for get_state and stop_all
const lats = [];
for (let i = 0; i < 8; i++) {
  const r = await call('get_state');
  lats.push(r.ms);
}
lats.sort((a, b) => a - b);
const stop = await call('stop_all', { reason: 'bench' }, 10000);
out.rounds.latency = { stateMs: lats, stateMedian: lats[Math.floor(lats.length / 2)], stopAllMs: stop.ms, stopResult: stop.json };

console.log(JSON.stringify(out, null, 2));
const fs = await import('node:fs');
fs.mkdirSync('D:/mc/MCP Development/evidence', { recursive: true });
fs.writeFileSync('D:/mc/MCP Development/evidence/round3-continuity.json', JSON.stringify(out, null, 2));

