// Torch round-trip probe: mine existing torches, collect them, then place one in a dark cell and verify.
const DAEMON = 'http://127.0.0.1:57611';
async function call(tool, args = {}, timeoutMs = 60000) {
  const r = await fetch(`${DAEMON}/call`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool, args, timeoutMs }) });
  return r.json();
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitJob(id, maxMs = 60000) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < maxMs) {
    await sleep(80);
    const r = await call('job_status', { jobId: id }, 10000);
    last = r.json;
    if (last && ['done', 'failed', 'cancelled'].includes(last.state)) return { job: last, ms: Date.now() - t0 };
  }
  return { job: last, ms: Date.now() - t0, timeout: true };
}
async function torchCount() {
  const st = await call('get_state');
  const item = (st.json.player.inventory.items || []).find((i) => i.id === 'minecraft:torch');
  return item ? item.count : 0;
}

// wait for the world (up to 4 min)
let p = null;
for (let i = 0; i < 40; i++) {
  const r = await call('get_state');
  if (r.ok && r.json && r.json.player) { p = r.json.player; break; }
  await sleep(6000);
}
if (!p) { console.log('NOT IN WORLD'); process.exit(2); }
const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
console.log(`player ${bx},${by},${bz}`);

const out = { player: [bx, by, bz], before: await torchCount(), steps: [] };

// 1. find torches nearby and mine them
const scan = await call('scan_area', { center: [bx, by, bz], radius: 24, filter: 'torch', includeAir: false }, 30000);
const torches = (scan.json.blocks || []).slice(0, 3);
out.steps.push({ scanTorches: torches });
for (const t of torches) {
  const m = await call('mine_block', { x: t[0], y: t[1], z: t[2] });
  const j = await waitJob(m.json.jobId, 30000);
  out.steps.push({ mine: t, state: j.job.state, reason: j.job.reason || null });
  const c = await call('collect_drops', { radius: 8 }, 40000);
  const cj = await waitJob(c.json.jobId, 45000);
  out.steps.push({ collect: cj.job.state, collected: cj.job.metrics?.collected, reason: cj.job.reason || cj.job.result?.reason });
}
out.afterMine = await torchCount();

// 2. light map and place a torch in the darkest cell
const lm = await call('light_map', { radius: 8 }, 30000);
const dark = (lm.json.dark || [])[0];
out.lightBefore = { darkCells: lm.json.darkCells, min: lm.json.minBlockLight, sample: dark };
if (dark && out.afterMine > 0) {
  const ta = await call('torch_at', { x: dark.pos[0], y: dark.pos[1], z: dark.pos[2] }, 20000);
  await sleep(800);
  const bq = await call('block_query', { positions: [dark.pos] }, 15000);
  const lm2 = await call('light_map', { radius: 8 }, 30000);
  out.torchPlaced = { result: ta.json, blockAfter: bq.json.blocks?.[0]?.id, lightAfter: { darkCells: lm2.json.darkCells, min: lm2.json.minBlockLight } };
} else {
  out.torchPlaced = { skipped: 'no torch item or no dark cell' };
}

console.log(JSON.stringify(out, null, 2));
const fs = await import('node:fs');
fs.mkdirSync('D:/mc/MCP Development/evidence', { recursive: true });
fs.writeFileSync('D:/mc/MCP Development/evidence/round3-torch.json', JSON.stringify(out, null, 2));
