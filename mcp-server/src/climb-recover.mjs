// Mountain-climb recovery: gather dirt, move it to the hotbar, pillar up to the summit, walk to target.
const D = 'http://127.0.0.1:57611';
async function call(t, a = {}, to = 240000) {
  const r = await fetch(`${D}/call`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool: t, args: a, timeoutMs: to }) });
  return r.json();
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitJob(id, maxMs = 240000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < maxMs) {
    await sleep(100);
    const r = await call('job_status', { jobId: id }, 15000);
    last = r.json;
    if (last && ['done', 'failed', 'cancelled'].includes(last.state)) return { job: last, ms: Date.now() - t0 };
  }
  return { job: last, ms: Date.now() - t0, timeout: true };
}

const st = await call('get_state');
const p = st.json.player;
const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
console.log(`start ${bx},${by},${bz} (target 1888,219,1416; need +${219 - by})`);

// 1. gather dirt
const scan = await call('scan_area', { center: [bx, by, bz], radius: 12, filter: 'dirt', includeAir: false });
const dirts = (scan.json.blocks || []).slice(0, 30).map((b) => [b[0], b[1], b[2]]);
console.log(`dirt candidates: ${dirts.length}`);
for (let i = 0; i < dirts.length; i += 6) {
  const group = dirts.slice(i, i + 6);
  const m = await call('mine_block', { positions: group });
  const j = await waitJob(m.json.jobId, 120000);
  console.log(`mine batch ${i / 6 + 1}: ${j.job.state} mined=${j.job.metrics?.blocksMined} ms=${j.ms}`);
}
const st2 = await call('get_state');
const dirtItem = (st2.json.player.inventory.items || []).find((it) => it.id === 'minecraft:dirt');
console.log(`dirt in inventory: ${dirtItem ? dirtItem.count : 0}`);

// 2. move dirt into hotbar slot 0
const rs = await call('resync');
const dirtSlot = (rs.json.inventory || []).find((it) => it.id === 'minecraft:dirt');
console.log(`dirt menuSlot: ${dirtSlot ? dirtSlot.menuSlot : 'none'} count=${dirtSlot ? dirtSlot.count : 0}`);
if (dirtSlot) {
  const sw = await call('inventory_swap', { fromSlot: dirtSlot.menuSlot, hotbarSlot: 0 });
  console.log(`swap: hotbar0=${sw.json?.afterHotbar?.id}`);
}

// 3. pillar up to the summit
const need = Math.max(1, 219 - by + 1);
console.log(`towering ${need} blocks`);
const tw = await call('tower', { blocks: Math.min(need, 32), item: 'minecraft:dirt', timeoutSeconds: 300 }, 40000);
const twj = await waitJob(tw.json.jobId, 320000);
const after = (await call('get_state')).json.player;
console.log(`tower: ${twj.job.state} ${twj.job.reason || ''} y=${after.y} (target 219)`);

// 4. walk the last few blocks
const pt = await call('path_to', { x: 1888, y: 219, z: 1416, range: 2, timeoutSeconds: 120 }, 30000);
const ptj = await waitJob(pt.json.jobId, 130000);
const fin = (await call('get_state')).json.player;
const dist = Math.hypot(fin.x - 1888, fin.y - 219, fin.z - 1416);
console.log(`final path: ${ptj.job.state} ${ptj.job.reason || ''} pos=${Math.round(fin.x)},${Math.round(fin.y)},${Math.round(fin.z)} dist=${dist.toFixed(1)}`);
const fs = await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/path-mountain.json', JSON.stringify({
  start: [bx, by, bz], dirtGathered: dirtItem ? dirtItem.count : 0,
  tower: twj.job, final: { state: ptj.job.state, pos: [fin.x, fin.y, fin.z], dist },
}, null, 2));

