// Hiker: the strategy the human asked for. Short, terrain-following legs:
//   * pick a waypoint ~18 blocks toward the target at (roughly) the current height  -> contour/ridge
//   * let Baritone walk it (short path, no digging, no towers)
//   * if that leg fails, rotate the waypoint +/-45 degrees (go around the obstacle)
//   * if three rotations fail, take a small vertical step (<=3 blocks) toward the target height
//   * stop when within 3 blocks; every leg is logged and checked for progress
const D = 'http://127.0.0.1:57611';
const TARGET = (process.env.TARGET || '1888,219,1416').split(',').map(Number);
const MAX_LEGS = Number(process.env.MAX_LEGS || 40);
const LEG = Number(process.env.LEG || 18);

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
const dist3 = (p) => Math.hypot(p.x - TARGET[0], p.y - TARGET[1], p.z - TARGET[2]);

const log = { startedAt: new Date().toISOString(), target: TARGET, legs: [], result: null };
let p = await P();
console.log(`hiker start ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} -> ${TARGET.join(',')} (${dist3(p).toFixed(1)})`);
let stuck = 0;

for (let leg = 0; leg < MAX_LEGS; leg++) {
  p = await P();
  const d = dist3(p);
  if (d <= 3) { log.result = { ok: true, pos: [p.x, p.y, p.z], dist: d, leg }; break; }
  const px = Math.floor(p.x);
  const py = Math.floor(p.y);
  const pz = Math.floor(p.z);
  const dx = TARGET[0] - px;
  const dz = TARGET[2] - pz;
  const horiz = Math.hypot(dx, dz) || 1;
  const base = Math.atan2(-dx / horiz, dz / horiz);
  const step = Math.min(LEG, Math.max(6, Math.round(horiz)));
  let ok = false;
  const rotations = [0, 45, -45, 90, -90];
  for (const rot of rotations) {
    const a = base + (rot * Math.PI) / 180;
    const wx = Math.round(px + -Math.sin(a) * step);
    const wz = Math.round(pz + Math.cos(a) * step);
    // scan the waypoint column first: the goal Y must be the real surface height there
    const colPos = [];
    for (let y = py - 34; y <= py + 8; y++) colPos.push([wx, y, wz]);
    const col = await call('block_query', { positions: colPos, timeoutSeconds: 20 });
    let surfY = null;
    let bad = false;
    for (const b of (col.json?.blocks || [])) {
      if (b.air) continue;
      if (/water|lava/.test(b.id || '')) { bad = true; continue; }
      if (/leaves|bush/.test(b.id || '')) continue;
      if (surfY === null || b.pos[1] > surfY) surfY = b.pos[1];
    }
    if (bad || surfY === null) { continue; }
    const pt = await call('path_to', { x: wx, y: surfY + 1, z: wz, range: 2, timeoutSeconds: 40 }, 45000);
    const pj = await waitJob(pt.json.jobId, 50000);
    const p2 = await P();
    const moved = Math.hypot(p2.x - p.x, p2.z - p.z) + Math.abs(p2.y - p.y);
    const d2 = dist3(p2);
    log.legs.push({ leg, rot, waypoint: [wx, py, wz], state: pj.job.state, reason: pj.job.reason || null, moved: Math.round(moved * 10) / 10, dist: Math.round(d2 * 10) / 10 });
    console.log(`leg ${leg} rot=${rot} wp=${wx},${py},${wz} ${pj.job.state} ${pj.job.reason || ''} moved=${moved.toFixed(1)} dist=${d2.toFixed(1)}`);
    if (moved > 2) { ok = true; break; }
  }
  if (!ok) {
    stuck++;
    // small vertical correction only
    const needUp = TARGET[1] > py + 1;
    if (needUp) {
      const tw = await call('tower', { blocks: 3, item: 'minecraft:dirt', timeoutSeconds: 90 }, 30000);
      const twj = await waitJob(tw.json.jobId, 100000);
      log.legs.push({ leg, action: 'small_tower', state: twj.job.state, placed: twj.job.metrics?.placed });
      console.log(`leg ${leg}: stuck -> small tower ${twj.job.state} placed=${twj.job.metrics?.placed}`);
    } else {
      const dg = await call('dig_shaft', { level: py - 3, mode: 'down', timeoutSeconds: 90 }, 30000);
      const dgj = await waitJob(dg.json.jobId, 100000);
      log.legs.push({ leg, action: 'descend', state: dgj.job.state, reason: dgj.job.reason || null });
      console.log(`leg ${leg}: stuck -> descend ${dgj.job.state} ${dgj.job.reason || ''}`);
      if (dgj.job.state !== 'done') {
        const walk = await call('walk', { yaw: p.yaw, ticks: 6, timeoutSeconds: 15 }, 15000);
        await waitJob(walk.json.jobId, 20000);
        log.legs.push({ leg, action: 'walk_off_small' });
      }
    }
    if (stuck >= 4) {
      log.result = { ok: false, pos: [p.x, p.y, p.z], dist: d, reason: 'stuck' };
      break;
    }
  } else {
    stuck = 0;
  }
}
if (!log.result) {
  const fin = await P();
  log.result = { ok: dist3(fin) <= 3, pos: [fin.x, fin.y, fin.z], dist: dist3(fin), reason: 'max_legs' };
}
log.finishedAt = new Date().toISOString();
console.log('RESULT ' + JSON.stringify(log.result));
const fs = await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/hiker-log.json', JSON.stringify(log, null, 2));



