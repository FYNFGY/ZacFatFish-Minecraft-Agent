// Focused mining probe: success case, out-of-reach case, unbreakable case. Uses the mc-daemon.
const DAEMON = 'http://127.0.0.1:57611';
async function call(tool, args = {}, timeoutMs = 60000) {
  const r = await fetch(`${DAEMON}/call`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tool, args, timeoutMs }),
  });
  const j = await r.json();
  return j;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitJob(id, maxMs = 60000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < maxMs) {
    await sleep(100);
    const r = await call('job_status', { jobId: id }, 10000);
    last = r.json;
    if (last && ['done', 'failed', 'cancelled'].includes(last.state)) return last;
  }
  return last;
}

const st = await call('get_state');
const p = st.json.player;
const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
console.log(`player ${bx},${by},${bz} yaw=${p.yaw}`);

// case 1: find a solid block within 4 blocks (feet level)
const grid = [];
for (let dx = -3; dx <= 3; dx++) for (let dz = -3; dz <= 3; dz++) if (dx || dz) grid.push([bx + dx, by, bz + dz]);
const bq = await call('block_query', { positions: grid });
const solid = (bq.json.blocks || []).find((b) => !b.air && b.hardness >= 0 && b.hardness < 50);
if (!solid) {
  console.log('no solid block found at feet level');
} else {
  const m = await call('mine_block', { x: solid.pos[0], y: solid.pos[1], z: solid.pos[2] });
  const j = await waitJob(m.json.jobId);
  console.log(`case1 block=${solid.id} hardness=${solid.hardness} state=${j.state} reason=${j.reason || '-'} ` +
    `ticks=${j.metrics?.results?.[0]?.ticks} mined=${j.metrics?.blocksMined} jobMs=${Date.now() - Date.now()}`);
}

// case 2: out of reach (10 blocks)
const far = [bx + 10, by, bz];
const m2 = await call('mine_block', { x: far[0], y: far[1], z: far[2] });
const j2 = await waitJob(m2.json.jobId, 15000);
console.log(`case2 state=${j2.state} reason=${j2.reason} msg=${j2.message}`);

// case 3: block under the feet (bedrock at the world bottom)
const under = [bx, by - 1, bz];
const bq3 = await call('block_query', { positions: [under] });
const b3 = bq3.json.blocks?.[0];
const m3 = await call('mine_block', { x: under[0], y: under[1], z: under[2] });
const j3 = await waitJob(m3.json.jobId, 15000);
console.log(`case3 block=${b3?.id} hardness=${b3?.hardness} state=${j3.state} reason=${j3.reason} msg=${j3.message}`);

// case 4: place a block into an air cell with a solid neighbour
const airCells = (bq.json.blocks || []).filter((b) => b.air).slice(0, 6);
const hb = await call('hotbar');
const chestSlot = hb.json.hotbar.findIndex((h) => h.item.id === 'minecraft:chest');
console.log(`hotbar chestSlot=${chestSlot} items=${hb.json.hotbar.map((h) => h.item.id).join(',')}`);
for (const c of airCells) {
  const pl = await call('place_block', { x: c.pos[0], y: c.pos[1], z: c.pos[2], item: 'minecraft:chest' }, 30000);
  const pj = await waitJob(pl.json.jobId, 20000);
  console.log(`place at ${c.pos}: ${pj.state} ${pj.reason || ''} ${pj.message || ''}`);
  if (pj.state === 'done') break;
}
