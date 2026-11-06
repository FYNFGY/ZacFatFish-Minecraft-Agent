// Endurance probe: mine 20 blocks in batches of 5, verify each one, report timing and failures.
const D = 'http://127.0.0.1:57611';
async function call(t, a = {}, to = 180000) {
  const r = await fetch(`${D}/call`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool: t, args: a, timeoutMs: to }) });
  return r.json();
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitJob(id, maxMs = 180000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < maxMs) {
    await sleep(60);
    const r = await call('job_status', { jobId: id }, 15000);
    last = r.json;
    if (last && ['done', 'failed', 'cancelled'].includes(last.state)) return { job: last, ms: Date.now() - t0 };
  }
  return { job: last, ms: Date.now() - t0, timeout: true };
}

const st = await call('get_state');
const p = st.json.player;
const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
const out = { player: [bx, by, bz], batches: [], totalMs: 0, blocksMined: 0, failures: 0 };

// collect up to 20 mineable cells: feet level ring + floor ring
const positions = [];
for (let r = 1; r <= 4 && positions.length < 20; r++) {
  for (let dx = -r; dx <= r && positions.length < 20; dx++) {
    for (let dz = -r; dz <= r && positions.length < 20; dz++) {
      if (Math.max(Math.abs(dx), Math.abs(dz)) !== r) continue;
      for (const dy of [0, -1]) {
        positions.push([bx + dx, by + dy, bz + dz]);
      }
    }
  }
}
const bq = await call('block_query', { positions });
const mineable = (bq.json.blocks || []).filter((b) => !b.air && b.hardness >= 0 && b.hardness < 50).slice(0, 20);
out.candidates = mineable.length;
console.log(`mineable candidates: ${mineable.length}`);

const t0 = Date.now();
for (let i = 0; i < mineable.length; i += 5) {
  const group = mineable.slice(i, i + 5);
  const started = Date.now();
  const m = await call('mine_block', { positions: group.map((g) => g.pos) });
  const j = await waitJob(m.json.jobId, 120000);
  const verify = await call('block_query', { positions: group.map((g) => g.pos) });
  const left = (verify.json.blocks || []).filter((b) => !b.air).length;
  out.batches.push({
    n: group.length,
    state: j.job.state,
    ms: Date.now() - started,
    ticks: (j.job.metrics?.results || []).map((r) => r.ticks),
    remaining: left,
  });
  out.blocksMined += (j.job.metrics?.blocksMined || 0);
  if (j.job.state !== 'done' || left > 0) out.failures++;
}
out.totalMs = Date.now() - t0;
out.avgPerBlockMs = out.blocksMined ? Math.round(out.totalMs / out.blocksMined) : null;
console.log(JSON.stringify(out, null, 2));
const fs = await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/round3-endurance.json', JSON.stringify(out, null, 2));
