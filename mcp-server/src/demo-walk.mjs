// Live demo: climb the peak near (1888,219,1416) from the base, then come back down.
// Strategy: short walking legs toward the target; when a leg makes no progress, a small 3-block pillar
// (never a tall tower). Descent: walk down the natural slope; if blocked, controlled dig-down.
const D = 'http://127.0.0.1:57611';
const PEAK = [1888, 219, 1416];
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
const dist3 = (p) => Math.hypot(p.x - PEAK[0], p.y - PEAK[1], p.z - PEAK[2]);
async function shot(name) {
  const s = await call('screenshot', {});
  if (s.json?.path) {
    const fs = await import('node:fs');
    fs.copyFileSync(s.json.path, `D:/mc/MCP Development/evidence/${name}.png`);
    return s.json.path;
  }
  return null;
}

const log = { startedAt: new Date().toISOString(), climb: [], descent: [], result: null };
let p = await P();
console.log(`demo start ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} hp=${p.health} peak=${PEAK.join(',')} dist=${dist3(p).toFixed(1)}`);
await shot('demo-0-base');

for (let i = 0; i < 14; i++) {
  p = await P();
  if (p.y >= PEAK[1] - 1 || dist3(p) <= 3) break;
  const pt = await call('path_to', { x: PEAK[0], y: PEAK[1], z: PEAK[2], range: 2, timeoutSeconds: 25 }, 30000);
  const pj = await waitJob(pt.json.jobId, 35000);
  const p2 = await P();
  const moved = Math.hypot(p2.x - p.x, p2.z - p.z) + Math.abs(p2.y - p.y);
  log.climb.push({ i, action: 'walk', state: pj.job.state, moved: Math.round(moved * 10) / 10, y: Math.round(p2.y), dist: Math.round(dist3(p2) * 10) / 10 });
  console.log(`climb ${i}: walk ${pj.job.state} moved=${moved.toFixed(1)} y=${Math.round(p2.y)} dist=${dist3(p2).toFixed(1)}`);
  if (moved < 2.5) {
    const need = Math.min(4, Math.max(1, PEAK[1] - Math.round(p2.y)));
    const tw = await call('tower', { blocks: need, item: 'minecraft:dirt', timeoutSeconds: 120 }, 30000);
    const twj = await waitJob(tw.json.jobId, 130000);
    const p3 = await P();
    log.climb.push({ i, action: 'pillar', blocks: need, state: twj.job.state, reason: twj.job.reason || null, y: Math.round(p3.y) });
    console.log(`climb ${i}: pillar ${need} -> ${twj.job.state} ${twj.job.reason || ''} y=${Math.round(p3.y)}`);
    if (twj.job.state !== 'done') break;
  }
}
p = await P();
console.log(`at top: ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} dist=${dist3(p).toFixed(1)}`);
log.climbTop = [p.x, p.y, p.z];
await shot('demo-1-top');

// descent: walk down a natural slope away from the peak
const down = [Math.round(p.x + 25), 0, Math.round(p.z + 15)];
const pt2 = await call('path_to', { x: down[0], y: Math.round(p.y - 20), z: down[2], range: 2, timeoutSeconds: 40 }, 45000);
const dj = await waitJob(pt2.json.jobId, 50000);
let d = await P();
log.descent.push({ action: 'walk_down', state: dj.job.state, reason: dj.job.reason || null, pos: [Math.round(d.x), Math.round(d.y), Math.round(d.z)] });
console.log(`descent walk: ${dj.job.state} ${dj.job.reason || ''} pos=${Math.round(d.x)},${Math.round(d.y)},${Math.round(d.z)}`);
if (d.y > p.y - 6) {
  // blocked on the slope: controlled dig-down (safe, probes the landing)
  const dg = await call('dig_shaft', { level: Math.round(p.y) - 12, mode: 'down', timeoutSeconds: 120 }, 30000);
  const dgj = await waitJob(dg.json.jobId, 130000);
  d = await P();
  log.descent.push({ action: 'dig_down', state: dgj.job.state, reason: dgj.job.reason || null, steps: dgj.job.metrics?.steps, pos: [Math.round(d.x), Math.round(d.y), Math.round(d.z)] });
  console.log(`descent dig: ${dgj.job.state} ${dgj.job.reason || ''} y=${Math.round(d.y)}`);
}
await shot('demo-2-down');

const fin = await P();
log.result = { top: log.climbTop, bottom: [fin.x, fin.y, fin.z], hp: fin.health };
console.log('RESULT ' + JSON.stringify(log.result));
log.finishedAt = new Date().toISOString();
const fs = await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/demo-walk.json', JSON.stringify(log, null, 2));
