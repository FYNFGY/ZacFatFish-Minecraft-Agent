// Volcano mission part 3: climb to the summit, light it with torches, then MLG-water off the edge.
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
    await sleep(120);
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
const log = { startedAt: new Date().toISOString(), steps: [] };

let p = await P();
console.log(`start ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} hp=${p.health} dist=${dist(p).toFixed(0)}`);
for (let i = 0; i < 16; i++) {
  p = await P();
  if (p.y >= DEST[1] - 1 && dist(p) < 8) break;
  const pt = await call('path_to', { x: DEST[0], y: DEST[1], z: DEST[2], range: 3, timeoutSeconds: 30 }, 35000);
  const pj = await waitJob(pt.json.jobId, 38000);
  const p2 = await P();
  const moved = Math.hypot(p2.x - p.x, p2.z - p.z) + Math.abs(p2.y - p.y);
  log.steps.push({ i, action: 'walk', state: pj.job.state, reason: pj.job.reason || null, moved: Math.round(moved), y: Math.round(p2.y), dist: Math.round(dist(p2)) });
  console.log(`climb ${i}: walk ${pj.job.state} moved=${moved.toFixed(0)} y=${Math.round(p2.y)} dist=${dist(p2).toFixed(0)}`);
  if (moved < 2.5) {
    const tw = await call('tower', { blocks: 4, item: 'minecraft:dirt', timeoutSeconds: 120 }, 30000);
    const twj = await waitJob(tw.json.jobId, 130000);
    const p3 = await P();
    log.steps.push({ i, action: 'pillar', state: twj.job.state, reason: twj.job.reason || null, y: Math.round(p3.y) });
    console.log(`climb ${i}: pillar ${twj.job.state} ${twj.job.reason || ''} y=${Math.round(p3.y)}`);
  }
}
p = await P();
const summit = [Math.round(p.x), Math.round(p.y), Math.round(p.z)];
console.log(`summit ${summit.join(',')} dist=${dist(p).toFixed(1)} hp=${p.health}`);
await shot('volcano-8-summit');

// torches
const bx = summit[0], by = summit[1], bz = summit[2];
const cells = [];
for (const [dx, dz] of [[0, 0], [1, 0], [-1, 0], [0, 1], [0, -1], [2, 0], [0, 2], [-2, 0], [0, -2]]) cells.push([bx + dx, by, bz + dz]);
const q = await call('block_query', { positions: cells });
let torches = 0;
for (const b of (q.json?.blocks || [])) {
  if (!b.air) continue;
  const t = await call('torch_at', { x: b.pos[0], y: b.pos[1], z: b.pos[2] }, 20000);
  if (t.json?.placed) torches++;
}
log.torches = torches;
const lm = await call('light_map', { radius: 6 }, 20000);
console.log(`torches=${torches} dark=${lm.json?.darkCells} minLight=${lm.json?.minBlockLight}`);
await shot('volcano-9-summit-lit');

// find an edge for the MLG: a neighbour column whose surface is >= 8 blocks lower
const probe = [];
for (let dx = -5; dx <= 5; dx++) for (let dz = -5; dz <= 5; dz++) {
  if (dx === 0 && dz === 0) continue;
  for (let y = by - 20; y <= by + 1; y++) probe.push([bx + dx, y, bz + dz, dx, dz]);
}
const byKey = new Map();
const blocks = [];
for (let i = 0; i < probe.length; i += 60) {
  const chunk = probe.slice(i, i + 60).map((x) => [x[0], x[1], x[2]]);
  const r = await call('block_query', { positions: chunk });
  const bs = r.json?.blocks || [];
  for (let k = 0; k < bs.length && k < chunk.length; k++) blocks.push({ b: bs[k], dx: probe[i + k][3], dz: probe[i + k][4] });
}
for (const { b, dx, dz } of blocks) {
  if (b.air) continue;
  const key = `${dx},${dz}`;
  const cur = byKey.get(key);
  if (!cur || b.pos[1] > cur) byKey.set(key, b.pos[1]);
}
let edge = null;
for (const [key, surf] of byKey.entries()) {
  const [dx, dz] = key.split(',').map(Number);
  if (Math.abs(dx) > 4 || Math.abs(dz) > 4) continue;
  if (surf < by - 8) edge = { dx, dz, drop: by - surf };
}
console.log('edge:', edge ? `${edge.dx},${edge.dz} drop=${edge.drop}` : 'none nearby');
if (edge) {
  const yaw = Math.toDegrees(Math.atan2(-edge.dx, edge.dz));
  log.edge = edge;
  const mlg = await call('mlg_water', { yaw, maxTicks: 260, timeoutSeconds: 45 }, 50000);
  const mj = await waitJob(mlg.json.jobId, 50000);
  const a = await P();
  log.mlg = { state: mj.job.state, reason: mj.job.reason || null, result: mj.job.result };
  console.log(`mlg: ${mj.job.state} ${mj.job.reason || ''} result=${JSON.stringify(mj.job.result)} hp=${a.health}`);
  await shot('volcano-10-mlg');
  log.after = [Math.round(a.x), Math.round(a.y), Math.round(a.z), a.health];
}
log.summit = summit;
log.finishedAt = new Date().toISOString();
fs.writeFileSync('D:/mc/MCP Development/evidence/volcano-mission2.json', JSON.stringify(log, null, 2));
console.log('DONE ' + JSON.stringify({ summit, torches, mlg: log.mlg, after: log.after }));
