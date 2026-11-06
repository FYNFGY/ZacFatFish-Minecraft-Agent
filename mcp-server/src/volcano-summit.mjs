// Volcano part 2: climb to the summit (short legs + small pillars), light it with torches.
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
const shot = async (name) => {
  const s = await call('screenshot', {});
  if (s.json?.path) fs.copyFileSync(s.json.path, `D:/mc/MCP Development/evidence/${name}.png`);
};

let p = await P();
console.log(`start ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} dist=${dist(p).toFixed(0)}`);
for (let i = 0; i < 20; i++) {
  p = await P();
  if (p.y >= DEST[1] - 2 && dist(p) < 8) break;
  const pt = await call('path_to', { x: DEST[0], y: DEST[1], z: DEST[2], range: 3, timeoutSeconds: 30 }, 35000);
  const pj = await waitJob(pt.json.jobId, 38000);
  const p2 = await P();
  const moved = Math.hypot(p2.x - p.x, p2.z - p.z) + Math.abs(p2.y - p.y);
  console.log(`climb ${i}: walk ${pj.job.state} ${pj.job.reason || ''} moved=${moved.toFixed(0)} y=${Math.round(p2.y)} dist=${dist(p2).toFixed(0)}`);
  if (moved < 2.5) {
    const tw = await call('tower', { blocks: 4, item: 'minecraft:dirt', timeoutSeconds: 120 }, 30000);
    const twj = await waitJob(tw.json.jobId, 130000);
    const p3 = await P();
    console.log(`climb ${i}: pillar ${twj.job.state} ${twj.job.reason || ''} y=${Math.round(p3.y)}`);
  }
}
p = await P();
console.log(`summit: ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} dist=${dist(p).toFixed(1)} hp=${p.health}`);
await shot('volcano-5-summit');

// torches around the summit
const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
const cells = [[bx, by, bz], [bx + 1, by, bz], [bx - 1, by, bz], [bx, by, bz + 1], [bx, by, bz - 1], [bx + 2, by, bz], [bx, by, bz + 2]];
const q = await call('block_query', { positions: cells });
let torches = 0;
for (const b of (q.json?.blocks || [])) {
  if (!b.air) continue;
  const t = await call('torch_at', { x: b.pos[0], y: b.pos[1], z: b.pos[2] }, 20000);
  if (t.json?.placed) torches++;
}
console.log(`torches: ${torches}`);
await sleep(500);
const lm = await call('light_map', { radius: 6 }, 20000);
console.log(`light after torches: dark=${lm.json?.darkCells} min=${lm.json?.minBlockLight}`);
await shot('volcano-6-summit-lit');
const fs2 = await import('node:fs');
fs2.writeFileSync('D:/mc/MCP Development/evidence/volcano-summit.json', JSON.stringify({ pos: [p.x, p.y, p.z], torches, light: { dark: lm.json?.darkCells, min: lm.json?.minBlockLight } }, null, 2));
