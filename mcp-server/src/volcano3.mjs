// Volcano finale: climb the last stretch to (2791,220,2172), light the summit, then MLG-water down.
const D = 'http://127.0.0.1:57611';
const DEST = [2791, 220, 2172];
async function call(t, a = {}, to = 120000) {
  const r = await fetch(`${D}/call`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool: t, args: a, timeoutMs: to }) });
  return r.json();
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitJob(id, ms = 120000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < ms) {
    await sleep(110);
    const r = await call('job_status', { jobId: id }, 15000);
    last = r.json;
    if (last && ['done', 'failed', 'cancelled'].includes(last.state)) return { job: last, ms: Date.now() - t0 };
  }
  return { job: last, ms: Date.now() - t0, timeout: true };
}
const P = async () => (await call('get_state')).json.player;
const dist = (p) => Math.hypot(p.x - DEST[0], p.y - DEST[1], p.z - DEST[2]);
const fs = await import('node:fs');
const shot = async (n) => {
  const s = await call('screenshot', {});
  if (s.json?.path) fs.copyFileSync(s.json.path, `D:/mc/MCP Development/evidence/${n}.png`);
};
const log = { steps: [] };

let p = await P();
console.log(`start ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} hp=${p.health} dist=${dist(p).toFixed(0)}`);
for (let i = 0; i < 14; i++) {
  p = await P();
  if (p.y >= DEST[1] - 1 && dist(p) < 6) break;
  const dx = DEST[0] - p.x, dz = DEST[2] - p.z;
  const h = Math.hypot(dx, dz) || 1;
  const step = Math.min(5, h);
  const w = await call('walk_to', { x: p.x + (dx / h) * step, z: p.z + (dz / h) * step, range: 1.0, maxTicks: 120, timeoutSeconds: 25 }, 30000);
  const wj = await waitJob(w.json.jobId, 30000);
  const p2 = await P();
  const moved = Math.hypot(p2.x - p.x, p2.z - p.z) + Math.abs(p2.y - p.y);
  console.log(`leg ${i}: walk_to ${wj.job.state} ${wj.job.reason || ''} moved=${moved.toFixed(1)} y=${Math.round(p2.y)} dist=${dist(p2).toFixed(0)}`);
  log.steps.push({ i, action: 'walk_to', state: wj.job.state, moved: Math.round(moved), y: Math.round(p2.y) });
  if (moved < 1.2) {
    const tw = await call('tower', { blocks: 4, item: 'minecraft:dirt', timeoutSeconds: 120 }, 30000);
    const twj = await waitJob(tw.json.jobId, 130000);
    const p3 = await P();
    console.log(`leg ${i}: pillar ${twj.job.state} ${twj.job.reason || ''} y=${Math.round(p3.y)}`);
    log.steps.push({ i, action: 'pillar', state: twj.job.state, y: Math.round(p3.y) });
  }
}
p = await P();
console.log(`at summit: ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} dist=${dist(p).toFixed(1)} hp=${p.health}`);
log.summit = [p.x, p.y, p.z];
await shot('volcano-11-summit');

// torches
const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
const cells = [];
for (const [dx, dz] of [[0, 0], [1, 0], [-1, 0], [0, 1], [0, -1], [2, 0], [0, 2]]) cells.push([bx + dx, by, bz + dz]);
const q = await call('block_query', { positions: cells });
let torches = 0;
for (const b of (q.json?.blocks || [])) {
  if (!b.air) continue;
  const t = await call('torch_at', { x: b.pos[0], y: b.pos[1], z: b.pos[2] }, 20000);
  if (t.json?.placed) torches++;
}
const lm = await call('light_map', { radius: 6 }, 20000);
console.log(`torches=${torches} dark=${lm.json?.darkCells} min=${lm.json?.minBlockLight}`);
log.torches = torches;
await shot('volcano-12-lit');

// MLG down from the summit: use the direction with the biggest drop found by a quick probe
let best = null;
const probes = [];
for (let r = 1; r <= 3; r++) for (const [dx, dz] of [[0, 1], [1, 0], [0, -1], [-1, 0], [1, 1], [-1, 1], [1, -1], [-1, -1]]) probes.push([bx + dx * r, by + 1, bz + dz * r, dx, dz]);
const qb = [];
for (let i = 0; i < probes.length; i += 60) {
  const chunk = probes.slice(i, i + 60).map((x) => [x[0], x[1], x[2]]);
  const r = await call('block_query', { positions: chunk });
  const bs = r.json?.blocks || [];
  for (let k = 0; k < bs.length && k < chunk.length; k++) qb.push({ b: bs[k], dx: probes[i + k][3], dz: probes[i + k][4] });
}
for (const e of qb) if (e.b.air) { best = { dx: e.dx, dz: e.dz }; break; }
if (best) {
  const yaw = Math.toDegrees(Math.atan2(-best.dx, best.dz));
  console.log('jumping toward', best.dx, best.dz);
  const mlg = await call('mlg_water', { yaw, maxTicks: 260, timeoutSeconds: 45 }, 50000);
  const mj = await waitJob(mlg.json.jobId, 50000);
  const a = await P();
  log.mlg = { state: mj.job.state, reason: mj.job.reason || null, result: mj.job.result };
  console.log(`mlg: ${mj.job.state} ${mj.job.reason || ''} result=${JSON.stringify(mj.job.result)} hp=${a.health}`);
  log.after = [a.x, a.y, a.z, a.health];
  await shot('volcano-13-mlg');
}
fs.writeFileSync('D:/mc/MCP Development/evidence/volcano-final.json', JSON.stringify(log, null, 2));
console.log('DONE ' + JSON.stringify({ summit: log.summit, torches, mlg: log.mlg, after: log.after }));
