// Rover: step-by-step 3D pathfinder. Short waypoints (<= scan radius), local height-map BFS,
// progress checks after every move, and escalation (pillar up / stair down) when stuck.
// Usage: TARGET=x,y,z node rover.mjs [--max-iter 60]
const D = 'http://127.0.0.1:57611';
const TARGET = (process.env.TARGET || '1888,219,1416').split(',').map(Number);
const MAX_ITER = Number(process.env.MAX_ITER || 60);
const SCAN_R = Number(process.env.SCAN_R || 8);
const WAYPOINT_DIST = Number(process.env.WAYPOINT_DIST || 7);

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
const isFluid = (id) => /water|lava/.test(id || '');
const isBad = (id) => /leaves|water|lava|magma|cactus|powder_snow/.test(id || '');

async function scanSurface(center, radius) {
  const r = await call('scan_area', { center, radius, includeAir: false, maxBlocks: 5000 }, 30000);
  const blocks = r.json?.blocks || [];
  const cols = new Map();
  for (const b of blocks) {
    const key = `${b[0]},${b[2]}`;
    const fluid = isFluid(b[3]);
    const cur = cols.get(key);
    if (!cur || b[1] > cur.y) cols.set(key, { x: b[0], y: b[1], z: b[2], id: b[3], fluid });
  }
  return cols;
}

function bfs(cols, startKey, goal, radius) {
  const start = cols.get(startKey);
  if (!start) return null;
  const q = [startKey];
  const prev = new Map([[startKey, null]]);
  const dirs = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [1, -1], [-1, 1], [-1, -1]];
  while (q.length) {
    const cur = q.shift();
    const [cx, cz] = cur.split(',').map(Number);
    const cc = cols.get(cur);
    if (!cc) continue;
    for (const [dx, dz] of dirs) {
      const nk = `${cx + dx},${cz + dz}`;
      if (prev.has(nk)) continue;
      const nc = cols.get(nk);
      if (!nc || nc.fluid || isBad(nc.id)) continue;
      const dy = nc.y - cc.y;
      // can jump up 1, can safely drop up to 5
      if (dy > 1 || dy < -5) continue;
      prev.set(nk, cur);
      q.push(nk);
    }
  }
  // pick the reachable cell with the best progress toward the goal, 3..WAYPOINT_DIST away
  let best = null, bestScore = Infinity;
  for (const k of prev.keys()) {
    const c = cols.get(k);
    const dStart = Math.hypot(c.x - start.x, c.z - start.z);
    if (dStart < 3 || dStart > WAYPOINT_DIST) continue;
    const dGoal = Math.hypot(c.x - goal[0], c.z - goal[2]) + Math.abs(c.y - goal[1]) * 0.3;
    if (dGoal < bestScore) { bestScore = dGoal; best = c; }
  }
  return best;
}

const log = { startedAt: new Date().toISOString(), target: TARGET, steps: [], result: null };
let p = await P();
console.log(`rover start ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} -> ${TARGET.join(',')} (${dist3(p).toFixed(1)})`);
let stuck = 0;
let lastDist = dist3(p);

for (let iter = 0; iter < MAX_ITER; iter++) {
  p = await P();
  const d = dist3(p);
  if (d <= 3) { log.result = { ok: true, pos: [p.x, p.y, p.z], dist: d, iter }; break; }
  const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
  const cols = await scanSurface([bx, by, bz], SCAN_R);
  const startKey = `${bx},${bz}`;
  let goal = bfs(cols, startKey, TARGET, SCAN_R);
  if (!goal) {
    // no walkable neighbour: escalate vertically
    log.steps.push({ iter, action: 'no_route', pos: [bx, by, bz], dist: d });
    if (TARGET[1] > by) {
      const positions = [];
      for (let dy = 2; dy <= 4; dy++) positions.push([bx, by + dy, bz]);
      await call('mine_block', { positions });
      const tw = await call('tower', { blocks: Math.min(TARGET[1] - by + 1, 6), item: 'minecraft:dirt', timeoutSeconds: 120 }, 30000);
      const twj = await waitJob(tw.json.jobId, 130000);
      log.steps.push({ iter, action: 'tower', state: twj.job.state, reason: twj.job.reason || null, placed: twj.job.metrics?.placed });
    } else {
      const dg = await call('dig_shaft', { level: Math.max(TARGET[1], by - 4), mode: 'stair', yaw: p.yaw, timeoutSeconds: 120 }, 30000);
      const dgj = await waitJob(dg.json.jobId, 130000);
      log.steps.push({ iter, action: 'dig_down', state: dgj.job.state, reason: dgj.job.reason || null });
    }
    continue;
  }
  const wp = [Math.floor(goal.x), Math.floor(goal.y), Math.floor(goal.z)];
  const pt = await call('path_to', { x: wp[0], y: wp[1], z: wp[2], range: 1, timeoutSeconds: 30 }, 30000);
  const pj = await waitJob(pt.json.jobId, 40000);
  const p2 = await P();
  const moved = Math.hypot(p2.x - p.x, p2.z - p.z) + Math.abs(p2.y - p.y);
  const d2 = dist3(p2);
  const progress = lastDist - d2;
  log.steps.push({ iter, waypoint: wp, state: pj.job.state, reason: pj.job.reason || null, moved: Math.round(moved * 10) / 10, dist: Math.round(d2 * 10) / 10 });
  console.log(`iter ${iter}: wp=${wp.join(',')} ${pj.job.state} moved=${moved.toFixed(1)} dist=${d2.toFixed(1)}`);
  if (moved < 0.8 || progress < 0.5) {
    stuck++;
    if (stuck >= 3) {
      // escalate: vertical move toward the target
      if (TARGET[1] > p2.y + 1) {
        const b2 = [Math.floor(p2.x), Math.floor(p2.y), Math.floor(p2.z)];
        const positions = [];
        for (let dy = 2; dy <= 4; dy++) positions.push([b2[0], b2[1] + dy, b2[2]]);
        await call('mine_block', { positions });
        const tw = await call('tower', { blocks: Math.min(TARGET[1] - b2[1] + 1, 8), item: 'minecraft:dirt', timeoutSeconds: 120 }, 30000);
        const twj = await waitJob(tw.json.jobId, 130000);
        log.steps.push({ iter, action: 'escalate_tower', state: twj.job.state, placed: twj.job.metrics?.placed });
        console.log(`escalate tower: ${twj.job.state} placed=${twj.job.metrics?.placed}`);
      } else {
        const dg = await call('dig_shaft', { level: Math.max(TARGET[1], Math.floor(p2.y) - 4), mode: 'stair', yaw: p2.yaw, timeoutSeconds: 120 }, 30000);
        const dgj = await waitJob(dg.json.jobId, 130000);
        log.steps.push({ iter, action: 'escalate_dig', state: dgj.job.state, reason: dgj.job.reason || null });
        console.log(`escalate dig: ${dgj.job.state} ${dgj.job.reason || ''}`);
      }
      stuck = 0;
    }
  } else {
    stuck = 0;
  }
  lastDist = d2;
  if (iter === MAX_ITER - 1) log.result = { ok: false, pos: [p2.x, p2.y, p2.z], dist: d2, reason: 'max_iter' };
}
if (!log.result) {
  const fin = await P();
  log.result = { ok: dist3(fin) <= 3, pos: [fin.x, fin.y, fin.z], dist: dist3(fin) };
}
log.finishedAt = new Date().toISOString();
console.log('RESULT ' + JSON.stringify(log.result));
const fs = await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/rover-log.json', JSON.stringify(log, null, 2));

