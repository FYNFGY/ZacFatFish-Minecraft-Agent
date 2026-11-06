// Mountain climb: gather buildable blocks within reach, move to a solid-floor cell, pillar up 36,
// then walk the last blocks to the target. Fixes the reach filter (eye height) and collects drops.
const D = 'http://127.0.0.1:57611';
const TARGET = [1888, 219, 1416];
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
const P = async () => (await call('get_state')).json.player;
const countOf = async (ids) => {
  const st = await call('get_state');
  let n = 0;
  for (const it of (st.json.player.inventory.items || [])) if (ids.includes(it.id)) n += it.count;
  return n;
};

const p0 = await P();
console.log(`start ${Math.round(p0.x)},${Math.round(p0.y)},${Math.round(p0.z)} target ${TARGET.join(',')}`);
const ids = ['minecraft:dirt', 'minecraft:coarse_dirt', 'minecraft:gravel'];
let have = await countOf(ids);
console.log(`blocks before: ${have}`);

for (let round = 0; round < 12 && have < 40; round++) {
  const p = await P();
  const eye = { x: p.x, y: p.y + 1.62, z: p.z };
  const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
  let blocks = [];
  for (const filter of ['dirt', 'gravel']) {
    const scan = await call('scan_area', { center: [bx, by, bz], radius: 6, filter, includeAir: false });
    for (const b of scan.json.blocks || []) {
      const d = Math.hypot(b[0] + 0.5 - eye.x, b[1] + 0.5 - eye.y, b[2] + 0.5 - eye.z);
      if (d <= 4.0) blocks.push({ b: [b[0], b[1], b[2]], d });
    }
  }
  blocks.sort((a, b) => a.d - b.d);
  if (!blocks.length) {
    // walk toward the nearest dirt/gravel to get in reach
    const scan = await call('scan_area', { center: [bx, by, bz], radius: 12, filter: 'dirt', includeAir: false });
    const near = (scan.json.blocks || [])[0];
    if (!near) { console.log('no more blocks'); break; }
    const pt = await call('path_to', { x: near[0], y: near[1], z: near[2], range: 2, timeoutSeconds: 60 }, 25000);
    const pj = await waitJob(pt.json.jobId, 70000);
    console.log(`walk to blocks: ${pj.job.state}`);
    continue;
  }
  const picks = blocks.slice(0, 3).map((x) => x.b);
  const m = await call('mine_block', { positions: picks });
  const j = await waitJob(m.json.jobId, 60000);
  const c = await call('collect_drops', { radius: 6 });
  const cj = await waitJob(c.json.jobId, 40000);
  have = await countOf(ids);
  console.log(`round ${round}: mined=${j.job.metrics?.blocksMined} (${j.job.state}) collected=${cj.job.metrics?.collected} blocks=${have}`);
}

// find a cell with a solid floor and free feet
const p = await P();
const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
const cells = [];
for (let dx = -4; dx <= 4; dx++) for (let dz = -4; dz <= 4; dz++) cells.push([bx + dx, by, bz + dz]);
const below = await call('block_query', { positions: cells.map((c) => [c[0], c[1] - 1, c[2]]) });
const here = await call('block_query', { positions: cells });
let spot = null;
for (let i = 0; i < cells.length; i++) {
  const b = below.json.blocks?.[i];
  const h = here.json.blocks?.[i];
  if (b && !b.air && b.collision && b.collision.length && h && h.air) { spot = cells[i]; break; }
}
console.log(`tower spot: ${spot ? spot.join(',') : 'none (will try current cell)'}`);
if (spot) {
  const pt = await call('path_to', { x: spot[0], y: spot[1], z: spot[2], range: 1, timeoutSeconds: 90 }, 25000);
  const pj = await waitJob(pt.json.jobId, 100000);
  console.log(`walk to spot: ${pj.job.state}`);
}
const rs = await call('resync');
const stack = (rs.json.inventory || []).find((i) => ids.includes(i.id) && i.count >= 5);
console.log(`stack: ${stack ? stack.id + ' x' + stack.count + ' menuSlot=' + stack.menuSlot : 'none'}`);
if (stack) await call('inventory_swap', { fromSlot: stack.menuSlot, hotbarSlot: 0 });
const cur = await P();
const need = Math.max(1, TARGET[1] - Math.floor(cur.y) + 2);
console.log(`towering ${need} from y=${cur.y}`);
const tw = await call('tower', { blocks: Math.min(need, 45), item: stack ? stack.id : 'minecraft:dirt', timeoutSeconds: 300 }, 40000);
const twj = await waitJob(tw.json.jobId, 320000);
const afterTower = await P();
console.log(`tower: ${twj.job.state} ${twj.job.reason || ''} y=${Math.round(afterTower.y)}`);
const pt = await call('path_to', { x: TARGET[0], y: TARGET[1], z: TARGET[2], range: 2, timeoutSeconds: 120 }, 30000);
const ptj = await waitJob(pt.json.jobId, 130000);
const fin = await P();
const dist = Math.hypot(fin.x - TARGET[0], fin.y - TARGET[1], fin.z - TARGET[2]);
console.log(`final: ${ptj.job.state} ${ptj.job.reason || ''} pos=${Math.round(fin.x)},${Math.round(fin.y)},${Math.round(fin.z)} dist=${dist.toFixed(1)}`);
const fs = await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/path-cliff.json', JSON.stringify({
  start: [p0.x, p0.y, p0.z], blocksGathered: await countOf(ids), tower: twj.job,
  final: { state: ptj.job.state, pos: [fin.x, fin.y, fin.z], dist },
}, null, 2));
