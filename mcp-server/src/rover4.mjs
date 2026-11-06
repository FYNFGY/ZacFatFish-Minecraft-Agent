// Rover v4: dense local height map + Dijkstra to the best reachable cell toward the target,
// then follow a few cells with the short walk_to primitive. No Baritone, no tall towers.
const D = 'http://127.0.0.1:57611';
const TARGET = (process.env.TARGET || '1888,219,1416').split(',').map(Number);
const MAX_ITER = Number(process.env.MAX_ITER || 40);
const GRID_R = Number(process.env.GRID_R || 16);
const GRID_STEP = Number(process.env.GRID_STEP || 2);
const Y_DOWN = Number(process.env.Y_DOWN || 10);
const Y_UP = Number(process.env.Y_UP || 6);

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
const bad = (id) => /water|lava|magma|cactus|powder_snow|leaves|bush|kelp/.test(id || '');

async function buildGrid(px, py, pz, R) {
  const positions = [];
  const meta = [];
  for (let dx = -R; dx <= R; dx += GRID_STEP) {
    for (let dz = -R; dz <= R; dz += GRID_STEP) {
      const from = positions.length;
      for (let y = py - Y_DOWN; y <= py + Y_UP; y++) positions.push([px + dx, y, pz + dz]);
      meta.push({ x: px + dx, z: pz + dz, from, to: positions.length });
    }
  }
  const blocks = new Array(positions.length).fill(null);
  for (let i = 0; i < positions.length; i += 60) {
    const chunk = positions.slice(i, i + 60);
    const r = await call('block_query', { positions: chunk, timeoutSeconds: 30 });
    const bs = r.json?.blocks || [];
    for (let k = 0; k < bs.length && k < chunk.length; k++) blocks[i + k] = bs[k];
  }
  const grid = new Map();
  for (const m of meta) {
    const col = blocks.slice(m.from, m.to).filter(Boolean);
    let surface = null;
    let blocked = false;
    for (const b of col) {
      if (b.air) continue;
      if (bad(b.id)) { blocked = true; continue; }
      if (!surface || b.pos[1] > surface.pos[1]) surface = b;
    }
    if (!surface || blocked) continue;
    const sy = surface.pos[1];
    const a1 = col.find((b) => b.pos[1] === sy + 1);
    const a2 = col.find((b) => b.pos[1] === sy + 2);
    if ((a1 && !a1.air) || (a2 && !a2.air)) continue;
    grid.set(`${m.x},${m.z}`, { x: m.x, z: m.z, y: sy + 1, id: surface.id });
  }
  return grid;
}

function planRoute(grid, startKey, goal) {
  const start = grid.get(startKey);
  if (!start) return null;
  const dirs = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [1, -1], [-1, 1], [-1, -1]];
  const dist = new Map([[startKey, 0]]);
  const came = new Map([[startKey, null]]);
  const queue = [startKey];
  while (queue.length) {
    let bi = 0;
    for (let i = 1; i < queue.length; i++) {
      if ((dist.get(queue[i]) ?? Infinity) < (dist.get(queue[bi]) ?? Infinity)) bi = i;
    }
    const cur = queue.splice(bi, 1)[0];
    const cc = grid.get(cur);
    const parts = cur.split(',');
    const cx = Number(parts[0]);
    const cz = Number(parts[1]);
    for (const d of dirs) {
      const nk = `${cx + d[0]},${cz + d[1]}`;
      const nc = grid.get(nk);
      if (!nc) continue;
      const dy = nc.y - cc.y;
      if (dy > 1 || dy < -3) continue;
      const stepCost = GRID_STEP + Math.abs(dy) * 1.5 + (dy > 0 ? 0.4 : 0);
      const nd = (dist.get(cur) ?? Infinity) + stepCost;
      if (nd < (dist.get(nk) ?? Infinity)) {
        dist.set(nk, nd);
        came.set(nk, cur);
        queue.push(nk);
      }
    }
  }
  let best = null;
  let bestScore = Infinity;
  for (const k of dist.keys()) {
    const c = grid.get(k);
    if (!c) continue;
    if (Math.abs(c.x - start.x) + Math.abs(c.z - start.z) < 3) continue;
    const score = Math.hypot(c.x - goal[0], c.z - goal[2]) + Math.abs(c.y - goal[1]) * 0.5;
    if (score < bestScore) { bestScore = score; best = k; }
  }
  if (!best) return null;
  const path = [];
  let k = best;
  while (k) { path.unshift(grid.get(k)); k = came.get(k); }
  return path;
}

const log = { startedAt: new Date().toISOString(), target: TARGET, steps: [], result: null };
let p = await P();
console.log(`rover4 start ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} -> ${TARGET.join(',')} (${dist3(p).toFixed(1)})`);
let path = null;
let pathIndex = 0;
let stuck = 0;

for (let iter = 0; iter < MAX_ITER; iter++) {
  p = await P();
  const d = dist3(p);
  if (d <= 3) { log.result = { ok: true, pos: [p.x, p.y, p.z], dist: d, iter }; break; }
  const px = Math.floor(p.x);
  const py = Math.floor(p.y);
  const pz = Math.floor(p.z);

  if (!path || pathIndex >= path.length - 1) {
    const grid = await buildGrid(px, py, pz, GRID_R);
    path = planRoute(grid, `${px},${pz}`, TARGET);
    pathIndex = 0;
    log.steps.push({ iter, action: 'plan', cells: grid.size, pathLen: path ? path.length : 0 });
    console.log(`iter ${iter}: plan cells=${grid.size} path=${path ? path.length : 'none'}`);
  }
  if (!path) {
    const tw = await call('tower', { blocks: 3, item: 'minecraft:dirt', timeoutSeconds: 90 }, 30000);
    const twj = await waitJob(tw.json.jobId, 100000);
    log.steps.push({ iter, action: 'no_path_small_tower', state: twj.job.state, placed: twj.job.metrics?.placed });
    console.log(`iter ${iter}: no path -> small tower ${twj.job.state} placed=${twj.job.metrics?.placed}`);
    continue;
  }
  let idx = Math.min(path.length - 1, pathIndex + 3);
  while (idx > 0 && Math.hypot(path[idx].x - p.x, path[idx].z - p.z) < 1.5) idx--;
  const wp = path[idx];
  if (!wp) { path = null; continue; }
  const wr = await call('walk_to', { x: wp.x, z: wp.z, range: 0.9, maxTicks: 240, timeoutSeconds: 40 }, 45000);
  const wj = await waitJob(wr.json.jobId, 45000);
  const p2 = await P();
  const moved = Math.hypot(p2.x - p.x, p2.z - p.z) + Math.abs(p2.y - p.y);
  log.steps.push({ iter, waypoint: [wp.x, wp.y, wp.z], state: wj.job.state, reason: wj.job.reason || null, moved: Math.round(moved * 10) / 10, dist: Math.round(dist3(p2) * 10) / 10 });
  console.log(`iter ${iter}: wp=${wp.x},${wp.y},${wp.z} ${wj.job.state} ${wj.job.reason || ''} moved=${moved.toFixed(1)} dist=${dist3(p2).toFixed(1)}`);
  if (moved < 0.7) {
    stuck++;
    path = null;
    if (stuck >= 3) {
      const tw = await call('tower', { blocks: 2, item: 'minecraft:dirt', timeoutSeconds: 90 }, 30000);
      await waitJob(tw.json.jobId, 100000);
      stuck = 0;
    }
  } else {
    stuck = 0;
    pathIndex = idx;
  }
}
if (!log.result) {
  const fin = await P();
  log.result = { ok: dist3(fin) <= 3, pos: [fin.x, fin.y, fin.z], dist: dist3(fin), reason: 'max_iter' };
}
log.finishedAt = new Date().toISOString();
console.log('RESULT ' + JSON.stringify(log.result));
const fs = await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/rover4-log.json', JSON.stringify(log, null, 2));

