// Rover v3: follow the mountain side. Each step probes two rings of columns, prefers a walkable
// neighbour at a similar height (contour) or a +1 slope toward the target, and NEVER builds a tall
// tower - only small 1-block adjustments when the terrain demands it.
const D = 'http://127.0.0.1:57611';
const TARGET = (process.env.TARGET || '1888,219,1416').split(',').map(Number);
const MAX_ITER = Number(process.env.MAX_ITER || 40);
const PROBE_DOWN = 10, PROBE_UP = 6;

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
const bad = (id) => /water|lava|magma|cactus|powder_snow|leaves|bush/.test(id || '');

/** Probe surfaces for a list of (x,z) columns using chunked block_query calls. */
async function probeColumns(px, py, pz, columns) {
  const positions = [];
  const meta = [];
  for (const c of columns) {
    const from = positions.length;
    for (let y = py - PROBE_DOWN; y <= py + PROBE_UP; y++) positions.push([c.x, y, c.z]);
    meta.push({ ...c, from, to: positions.length });
  }
  const blocks = new Array(positions.length).fill(null);
  for (let i = 0; i < positions.length; i += 60) {
    const chunk = positions.slice(i, i + 60);
    const r = await call('block_query', { positions: chunk, timeoutSeconds: 30 });
    const bs = r.json?.blocks || [];
    for (let k = 0; k < bs.length && k < chunk.length; k++) blocks[i + k] = bs[k];
  }
  const out = [];
  for (const m of meta) {
    const col = blocks.slice(m.from, m.to).filter(Boolean);
    let surface = null, fluid = false;
    for (const b of col) {
      if (b.air) continue;
      if (bad(b.id)) { fluid = true; continue; }
      if (!surface || b.pos[1] > surface.pos[1]) surface = b;
    }
    if (!surface || fluid) continue;
    const sy = surface.pos[1];
    const a1 = col.find((b) => b.pos[1] === sy + 1);
    const a2 = col.find((b) => b.pos[1] === sy + 2);
    const clear = (!a1 || a1.air) && (!a2 || a2.air);
    out.push({ x: m.x, z: m.z, id: surface.id, sy, feetY: sy + 1, clear, dist: m.dist, angle: m.angle });
  }
  return out;
}

const log = { startedAt: new Date().toISOString(), target: TARGET, steps: [], result: null };
let p = await P();
console.log(`rover3 start ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} -> ${TARGET.join(',')} (${dist3(p).toFixed(1)})`);
let lastDist = dist3(p);
let stuck = 0;

for (let iter = 0; iter < MAX_ITER; iter++) {
  p = await P();
  const d = dist3(p);
  if (d <= 3) { log.result = { ok: true, pos: [p.x, p.y, p.z], dist: d, iter }; break; }
  const px = Math.floor(p.x), py = Math.floor(p.y), pz = Math.floor(p.z);
  const baseAngle = Math.atan2(-(TARGET[0] - p.x), (TARGET[2] - p.z));

  let best = null, bestScore = Infinity, probed = 0;
  for (const rings of [[5, 9], [9, 14], [14, 20]]) {
    const columns = [];
    for (const radius of rings) {
      for (let k = 0; k < 16; k++) {
        const a = k * Math.PI / 8;
        const x = px + Math.round(-Math.sin(a) * radius);
        const z = pz + Math.round(Math.cos(a) * radius);
        if (x === px && z === pz) continue;
        columns.push({ x, z, dist: radius, angle: a });
      }
    }
    const surfaces = await probeColumns(px, py, pz, columns);
    probed += surfaces.length;
    for (const s of surfaces) {
      if (!s.clear) continue;
      const dy = s.feetY - py;
      if (dy > 1 || dy < -3) continue; // can step up 1, drop at most 3
      const distToTarget = Math.hypot(s.x - TARGET[0], s.z - TARGET[2]);
      const dyPenalty = Math.abs(dy) * 1.2;
      const slopeBonus = (dy > 0 && TARGET[1] > py + 2) ? -1.5 : 0;
      const score = distToTarget + dyPenalty + slopeBonus;
      if (score < bestScore) { bestScore = score; best = s; }
    }
    if (best) break;
  }

  if (!best) {
    // no walkable neighbour: small local correction only (never a tall tower)
    const above = TARGET[1] > py + 1;
    log.steps.push({ iter, action: above ? 'small_tower' : 'no_step', pos: [px, py, pz], dist: d, probed });
    console.log(`iter ${iter}: no step (probed ${probed}) -> ${above ? 'small tower' : 'sidestep'}`);
    if (above) {
      const tw = await call('tower', { blocks: 3, item: 'minecraft:dirt', timeoutSeconds: 90 }, 30000);
      const twj = await waitJob(tw.json.jobId, 100000);
      log.steps.push({ iter, action: 'small_tower_result', state: twj.job.state, placed: twj.job.metrics?.placed });
    } else {
      // sidestep along the contour toward the target
      const yaw = p.yaw + (iter % 2 === 0 ? 60 : -60);
      const walk = await call('walk', { yaw, ticks: 10, timeoutSeconds: 20 }, 15000);
      await waitJob(walk.json.jobId, 25000);
      log.steps.push({ iter, action: 'sidestep', yaw });
    }
    continue;
  }

  const wp = [best.x, best.feetY, best.z];
  const pt = await call('walk_to', { x: wp[0], z: wp[2], range: 1.2, maxTicks: 240, timeoutSeconds: 40 }, 45000);
  const pj = await waitJob(pt.json.jobId, 45000);
  const p2 = await P();
  const moved = Math.hypot(p2.x - p.x, p2.z - p.z) + Math.abs(p2.y - p.y);
  const d2 = dist3(p2);
  log.steps.push({
    iter, waypoint: wp, dy: best.feetY - py, state: pj.job.state, reason: pj.job.reason || null,
    moved: Math.round(moved * 10) / 10, dist: Math.round(d2 * 10) / 10,
  });
  console.log(`iter ${iter}: wp=${wp.join(',')} dy=${best.feetY - py} ${pj.job.state} moved=${moved.toFixed(1)} dist=${d2.toFixed(1)}`);
  if (moved < 0.8) { stuck++; } else { stuck = 0; }
  lastDist = d2;
  if (stuck >= 3) {
    log.steps.push({ iter, action: 'stuck_rotate', pos: [p2.x, p2.y, p2.z] });
    stuck = 0;
  }
}
if (!log.result) {
  const fin = await P();
  log.result = { ok: dist3(fin) <= 3, pos: [fin.x, fin.y, fin.z], dist: dist3(fin), reason: 'max_iter' };
}
log.finishedAt = new Date().toISOString();
console.log('RESULT ' + JSON.stringify(log.result));
const fs = await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/rover3-log.json', JSON.stringify(log, null, 2));

