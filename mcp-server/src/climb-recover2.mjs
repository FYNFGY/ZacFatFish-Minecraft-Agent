// Mountain recovery: gather dirt (reachable only), find a full-block floor, pillar up 28, walk to target.
const D = 'http://127.0.0.1:57611';
async function call(t, a = {}, to = 180000) {
  const r = await fetch(`${D}/call`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool: t, args: a, timeoutMs: to }) });
  return r.json();
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitJob(id, ms = 180000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < ms) {
    await sleep(100);
    const r = await call('job_status', { jobId: id }, 15000);
    last = r.json;
    if (last && ['done', 'failed', 'cancelled'].includes(last.state)) return { job: last, ms: Date.now() - t0 };
  }
  return { job: last, ms: Date.now() - t0, timeout: true };
}
const pos = async () => (await call('get_state')).json.player;
const dirtCount = async () => {
  const st = await call('get_state');
  const it = (st.json.player.inventory.items || []).find((i) => i.id === 'minecraft:dirt');
  return it ? it.count : 0;
};

const p0 = await pos();
console.log(`start ${Math.round(p0.x)},${Math.round(p0.y)},${Math.round(p0.z)}`);

// 1. gather reachable dirt
for (let round = 0; round < 10 && (await dirtCount()) < 30; round++) {
  const p = await pos();
  const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
  const scan = await call('scan_area', { center: [bx, by, bz], radius: 7, filter: 'dirt', includeAir: false });
  const all = (scan.json.blocks || []).map((b) => [b[0], b[1], b[2]]);
  const reach = all.filter((b) => Math.hypot(b[0] + 0.5 - p.x, b[1] + 0.5 - p.y, b[2] + 0.5 - p.z) <= 4.2);
  if (reach.length === 0) {
    if (all.length === 0) { console.log('no dirt nearby'); break; }
    const t = all[0];
    const pt = await call('path_to', { x: t[0], y: t[1], z: t[2], range: 2, timeoutSeconds: 60 }, 25000);
    const pj = await waitJob(pt.json.jobId, 70000);
    console.log(`walk to dirt ${t.join(',')}: ${pj.job.state} ${pj.job.reason || ''}`);
    continue;
  }
  const m = await call('mine_block', { positions: reach.slice(0, 5) });
  const j = await waitJob(m.json.jobId, 60000);
  const c = await call('collect_drops', { radius: 6 });
  const cj = await waitJob(c.json.jobId, 40000);
  console.log(`dirt round ${round}: mine=${j.job.state}/${j.job.metrics?.blocksMined} collect=${cj.job.state}/${cj.job.metrics?.collected} total=${await dirtCount()}`);
}

// 2. find a cell with a full-block floor nearby
const p = await pos();
const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
const cells = [];
for (let dx = -4; dx <= 4; dx++) for (let dz = -4; dz <= 4; dz++) cells.push([bx + dx, by, bz + dz]);
const below = await call('block_query', { positions: cells.map((c) => [c[0], c[1] - 1, c[2]]), timeoutSeconds: 30 });
const above = await call('block_query', { positions: cells.map((c) => [c[0], c[1] + 1, c[2]]), timeoutSeconds: 30 });
let spot = null;
for (let i = 0; i < cells.length; i++) {
  const b = below.json.blocks?.[i];
  const a = above.json.blocks?.[i];
  if (b && !b.air && b.collision && b.collision.length && a && a.air) { spot = cells[i]; break; }
}
console.log(`tower spot: ${spot ? spot.join(',') : 'none'}`);
if (spot) {
  const pt = await call('path_to', { x: spot[0], y: spot[1], z: spot[2], range: 1, timeoutSeconds: 60 }, 25000);
  const pj = await waitJob(pt.json.jobId, 70000);
  console.log(`walk to spot: ${pj.job.state}`);
}
// 3. hotbar swap + tower
const rs = await call('resync');
const dirt = (rs.json.inventory || []).find((i) => i.id === 'minecraft:dirt');
console.log(`dirt stack: count=${dirt ? dirt.count : 0} menuSlot=${dirt ? dirt.menuSlot : 'none'}`);
if (dirt) await call('inventory_swap', { fromSlot: dirt.menuSlot, hotbarSlot: 0 });
const need = Math.max(1, 219 - Math.round((await pos()).y) + 1);
const tw = await call('tower', { blocks: Math.min(need, 40), item: 'minecraft:dirt', timeoutSeconds: 300 }, 40000);
const twj = await waitJob(tw.json.jobId, 320000);
const after = await pos();
console.log(`tower ${Math.min(need, 40)}: ${twj.job.state} ${twj.job.reason || ''} y=${Math.round(after.y)}`);
// 4. final walk
const pt = await call('path_to', { x: 1888, y: 219, z: 1416, range: 2, timeoutSeconds: 180 }, 30000);
const ptj = await waitJob(pt.json.jobId, 190000);
const fin = await pos();
const dist = Math.hypot(fin.x - 1888, fin.y - 219, fin.z - 1416);
console.log(`final: ${ptj.job.state} ${ptj.job.reason || ''} pos=${Math.round(fin.x)},${Math.round(fin.y)},${Math.round(fin.z)} dist=${dist.toFixed(1)}`);
const fs = await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/path-mountain.json', JSON.stringify({
  start: [p0.x, p0.y, p0.z], towerSpot: spot, tower: twj.job,
  final: { state: ptj.job.state, pos: [fin.x, fin.y, fin.z], dist },
}, null, 2));
