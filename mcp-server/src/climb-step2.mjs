// Step 2 of the mountain climb: gather dirt at the slope, then pillar up and walk to the target.
const D = 'http://127.0.0.1:57611';
async function call(t, a = {}, to = 120000) {
  const r = await fetch(`${D}/call`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool: t, args: a, timeoutMs: to }) });
  return r.json();
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitJob(id, ms = 120000) {
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
const getP = async () => (await call('get_state')).json.player;
const dirtN = async () => {
  const st = await call('get_state');
  const it = (st.json.player.inventory.items || []).find((i) => i.id === 'minecraft:dirt' || i.id === 'minecraft:coarse_dirt');
  return it ? it.count : 0;
};

console.log(`start ${JSON.stringify((await getP()).x)}`);
for (let round = 0; round < 8; round++) {
  const p = await getP();
  if (p.y > 200) break;
  const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
  const scan = await call('scan_area', { center: [bx, by, bz], radius: 5, filter: 'dirt', includeAir: false });
  const blocks = (scan.json.blocks || []).map((b) => [b[0], b[1], b[2]]).filter((b) => {
    const d = Math.hypot(b[0] + 0.5 - p.x, b[1] + 0.5 - p.y, b[2] + 0.5 - p.z);
    return d <= 4.2;
  });
  if (!blocks.length) { console.log(`round ${round}: no reachable dirt`); break; }
  const m = await call('mine_block', { positions: blocks.slice(0, 5) });
  const j = await waitJob(m.json.jobId, 60000);
  const c = await call('collect_drops', { radius: 6 });
  const cj = await waitJob(c.json.jobId, 40000);
  console.log(`round ${round}: mined=${j.job.metrics?.blocksMined} (${j.job.state}) collected=${cj.job.metrics?.collected} dirt=${await dirtN()}`);
  if ((await dirtN()) >= 30) break;
}
const total = await dirtN();
console.log(`dirt total: ${total}`);
const rs = await call('resync');
const stack = (rs.json.inventory || []).find((i) => i.id === 'minecraft:dirt' || i.id === 'minecraft:coarse_dirt');
console.log(`stack: ${stack ? stack.id + ' x' + stack.count + ' slot=' + stack.menuSlot : 'none'}`);
if (stack) await call('inventory_swap', { fromSlot: stack.menuSlot, hotbarSlot: 0 });
const p = await getP();
const need = Math.max(1, 219 - Math.floor(p.y) + 1);
console.log(`towering ${need} from y=${p.y}`);
const tw = await call('tower', { blocks: Math.min(need, 40), item: stack ? stack.id : 'minecraft:dirt', timeoutSeconds: 300 }, 40000);
const twj = await waitJob(tw.json.jobId, 320000);
const after = await getP();
console.log(`tower: ${twj.job.state} ${twj.job.reason || ''} y=${Math.round(after.y)}`);
const pt = await call('path_to', { x: 1888, y: 219, z: 1416, range: 2, timeoutSeconds: 180 }, 30000);
const ptj = await waitJob(pt.json.jobId, 190000);
const fin = await getP();
console.log(`final: ${ptj.job.state} ${ptj.job.reason || ''} pos=${Math.round(fin.x)},${Math.round(fin.y)},${Math.round(fin.z)}`);
const fs = await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/path-mountain-2.json', JSON.stringify({
  dirt: total, tower: twj.job, final: { state: ptj.job.state, pos: [fin.x, fin.y, fin.z] },
}, null, 2));
