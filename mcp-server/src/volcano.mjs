// Volcano mission: prepare the kit, travel to (2791,220,2172), light the summit, descend, MLG water.
const D = 'http://127.0.0.1:57611';
const DEST = [2791, 220, 2172];
async function call(t, a = {}, to = 120000) {
  const r = await fetch(`${D}/call`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool: t, args: a, timeoutMs: to }) });
  return r.json();
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitJob(id, ms = 120000, quiet = true) {
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
const log = { startedAt: new Date().toISOString(), dest: DEST, legs: [], result: null };
const fs = await import('node:fs');
const shot = async (name) => {
  const s = await call('screenshot', {});
  if (s.json?.path) fs.copyFileSync(s.json.path, `D:/mc/MCP Development/evidence/${name}.png`);
};

// ---- 1. kit ----
for (const c of [
  '/give @s minecraft:diamond_pickaxe 1',
  '/give @s minecraft:dirt 64',
  '/give @s minecraft:dirt 64',
  '/give @s minecraft:cobblestone 64',
  '/give @s minecraft:torch 32',
  '/give @s minecraft:water_bucket 2',
  '/give @s minecraft:bread 16',
]) await call('command', { command: c });
await sleep(800);
let rs = await call('resync');
const wanted = [
  ['minecraft:diamond_pickaxe', 0],
  ['minecraft:dirt', 1],
  ['minecraft:water_bucket', 2],
  ['minecraft:torch', 3],
  ['minecraft:cobblestone', 4],
];
for (const [id, slot] of wanted) {
  const it = (rs.json.inventory || []).find((i) => i.id === id);
  if (it) await call('inventory_swap', { fromSlot: it.menuSlot, hotbarSlot: slot });
}
const hb = await call('hotbar');
log.kit = hb.json.hotbar.map((h) => h.item.id);
console.log('kit: ' + log.kit.join(', '));
let p = await P();
console.log(`start ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} dist=${dist(p).toFixed(0)}`);
await shot('volcano-0-start');

// ---- 2. travel: one long path, fall back to short legs ----
let pt = await call('path_to', { x: DEST[0], y: DEST[1], z: DEST[2], range: 4, timeoutSeconds: 600 }, 60000);
let pj = await waitJob(pt.json.jobId, 620000);
p = await P();
console.log(`long path: ${pj.job.state} ${pj.job.reason || ''} pos=${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} dist=${dist(p).toFixed(0)}`);
log.legs.push({ action: 'long_path', state: pj.job.state, reason: pj.job.reason || null, dist: Math.round(dist(p)) });
if (dist(p) > 8) {
  for (let i = 0; i < 20 && dist(p) > 8; i++) {
    const dx = DEST[0] - p.x, dz = DEST[2] - p.z;
    const h = Math.hypot(dx, dz) || 1;
    const step = Math.min(60, Math.max(10, h));
    const wx = Math.round(p.x + (dx / h) * step);
    const wz = Math.round(p.z + (dz / h) * step);
    const leg = await call('path_to', { x: wx, y: DEST[1], z: wz, range: 4, timeoutSeconds: 90 }, 95000);
    const lj = await waitJob(leg.json.jobId, 100000);
    const before = p;
    p = await P();
    const moved = Math.hypot(p.x - before.x, p.z - before.z) + Math.abs(p.y - before.y);
    log.legs.push({ action: 'leg', i, state: lj.job.state, reason: lj.job.reason || null, moved: Math.round(moved), dist: Math.round(dist(p)) });
    console.log(`leg ${i}: ${lj.job.state} ${lj.job.reason || ''} moved=${moved.toFixed(0)} dist=${dist(p).toFixed(0)}`);
    if (moved < 4) break;
  }
}
p = await P();
console.log(`travel end ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} dist=${dist(p).toFixed(1)} hp=${p.health}`);
await shot('volcano-1-arrive');

// ---- 3. torches at the summit ----
const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
const around = [[bx, by, bz], [bx + 2, by, bz], [bx - 2, by, bz], [bx, by, bz + 2], [bx, by, bz - 2]];
const q = await call('block_query', { positions: around });
let torches = 0;
for (const b of (q.json?.blocks || [])) {
  if (!b.air) continue;
  const t = await call('torch_at', { x: b.pos[0], y: b.pos[1], z: b.pos[2] }, 20000);
  if (t.json?.placed) torches++;
}
log.torches = torches;
console.log(`torches placed: ${torches}`);
await shot('volcano-2-summit');

// ---- 4. descend ----
const down = [Math.round(p.x + 18), Math.round(p.y - 30), Math.round(p.z + 12)];
const dt = await call('path_to', { x: down[0], y: down[1], z: down[2], range: 6, timeoutSeconds: 90 }, 95000);
const dj = await waitJob(dt.json.jobId, 100000);
p = await P();
console.log(`descend: ${dj.job.state} ${dj.job.reason || ''} pos=${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} hp=${p.health}`);
if (p.y > by - 8) {
  const dg = await call('dig_shaft', { level: by - 20, mode: 'down', timeoutSeconds: 120 }, 30000);
  const dgj = await waitJob(dg.json.jobId, 130000);
  p = await P();
  console.log(`controlled descent: ${dgj.job.state} ${dgj.job.reason || ''} y=${Math.round(p.y)}`);
}
await shot('volcano-3-descend');

// ---- 5. MLG water from a ledge ----
p = await P();
const mlg = await call('mlg_water', { yaw: p.yaw, maxTicks: 240, timeoutSeconds: 40 }, 45000);
const mj = await waitJob(mlg.json.jobId, 45000);
p = await P();
log.mlg = { state: mj.job.state, reason: mj.job.reason || null, result: mj.job.result, pos: [Math.round(p.x), Math.round(p.y), Math.round(p.z)], hp: p.health };
console.log(`mlg: ${mj.job.state} ${mj.job.reason || ''} result=${JSON.stringify(mj.job.result)} hp=${p.health}`);
await shot('volcano-4-mlg');

log.result = { pos: [p.x, p.y, p.z], dist: dist(p), hp: p.health, torches };
log.finishedAt = new Date().toISOString();
fs.writeFileSync('D:/mc/MCP Development/evidence/volcano-mission.json', JSON.stringify(log, null, 2));
console.log('RESULT ' + JSON.stringify(log.result));
