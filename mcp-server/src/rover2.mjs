// Rover v2: "look, then step" 3D pathing. Candidates are probed column-by-column (block_query),
// reachable ones are walked to with a short path job, and every move is checked for progress.
// Escalation: pillar up when the target is above, walk-off when below, re-probe when stuck.
const D = 'http://127.0.0.1:57611';
const TARGET = (process.env.TARGET || '1888,219,1416').split(',').map(Number);
const MAX_ITER = Number(process.env.MAX_ITER || 60);
const STEP_MIN = 4, STEP_MAX = 8;
const PROBE_DOWN = 6, PROBE_UP = 4;

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
const badBlock = (id) => /water|lava|magma|cactus|powder_snow|leaves/.test(id || '');

async function probeColumns(px, py, pz, candidates) {
  // one block_query for all candidate columns
  const positions = [];
  const meta = [];
  for (const c of candidates) {
    const start = positions.length;
    for (let y = py - PROBE_DOWN; y <= py + PROBE_UP; y++) positions.push([c.x, y, c.z]);
    meta.push({ ...c, from: start, to: positions.length });
  }
  // block_query accepts at most 64 positions per call: query in chunks and keep the order.
  const blocks = new Array(positions.length).fill(null);
  for (let i = 0; i < positions.length; i += 60) {
    const chunk = positions.slice(i, i + 60);
    const r = await call('block_query', { positions: chunk, timeoutSeconds: 30 });
    const bs = r.json?.blocks || [];
    for (let k = 0; k < bs.length && k < chunk.length; k++) {
      blocks[i + k] = bs[k];
    }
  }
  const out = [];
  for (const m of meta) {
    const col = blocks.slice(m.from, m.to);
    let surface = null;
    let fluid = false;
    for (const b of col) {
      if (b.air) continue;
      if (badBlock(b.id)) { fluid = true; continue; }
      if (!surface || b.pos[1] > surface.pos[1]) surface = b;
    }
    if (!surface || fluid) { out.push({ ...m, surface: null }); continue; }
    // clearance: the two cells above the surface must be air (approx from the probe)
    const sy = surface.pos[1];
    const above1 = col.find((b) => b.pos[1] === sy + 1);
    const above2 = col.find((b) => b.pos[1] === sy + 2);
    const clear = (!above1 || above1.air) && (!above2 || above2.air);
    out.push({ ...m, surface, clear, y: sy + 1 });
  }
  return out;
}

const log = { startedAt: new Date().toISOString(), target: TARGET, steps: [], result: null };
let p = await P();
console.log(`rover2 start ${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)} -> ${TARGET.join(',')} (${dist3(p).toFixed(1)})`);
let lastDist = dist3(p);
let stuck = 0;
let sideFlip = 1;

for (let iter = 0; iter < MAX_ITER; iter++) {
  p = await P();
  const d = dist3(p);
  if (d <= 3) { log.result = { ok: true, pos: [p.x, p.y, p.z], dist: d, iter }; break; }
  const px = Math.floor(p.x), py = Math.floor(p.y), pz = Math.floor(p.z);
  const baseAngle = Math.atan2(-(TARGET[0] - p.x), (TARGET[2] - p.z));
  const candidates = [];
  for (const offDeg of [0, 25 * sideFlip, -25 * sideFlip, 50, -50, 75, -75]) {
    const a = baseAngle + (offDeg * Math.PI) / 180;
    for (const step of [STEP_MIN, STEP_MIN + 2, STEP_MAX]) {
      candidates.push({
        x: px + Math.round(-Math.sin(a) * step),
        z: pz + Math.round(Math.cos(a) * step),
        offDeg, step,
      });
    }
  }
  const probed = await probeColumns(px, py, pz, candidates);
  const usable = probed.filter((c) => c.surface && c.clear);
  let best = null, bestScore = Infinity;
  for (const c of usable) {
    const rise = c.y - py;
    if (rise > 1 || rise < -3) continue; // too high to jump / drop limited to 3 for safety
    const score = Math.hypot(c.x - TARGET[0], c.z - TARGET[2]) + Math.abs(c.y - TARGET[1]) * 0.4 + Math.abs(rise) * 0.3;
    if (score < bestScore) { bestScore = score; best = c; }
  }
  if (!best) {
    // no step: escalate
    const above = TARGET[1] >= py;
    log.steps.push({ iter, action: above ? 'tower' : 'walk_off', pos: [px, py, pz], dist: d, probed: probed.length, usable: usable.length });
    if (above) {
      const tw = await call('tower', { blocks: Math.min(TARGET[1] - py + 2, 8), item: 'minecraft:dirt', timeoutSeconds: 120 }, 30000);
      const twj = await waitJob(tw.json.jobId, 130000);
      log.steps.push({ iter, action: 'tower_result', state: twj.job.state, reason: twj.job.reason || null, placed: twj.job.metrics?.placed });
      console.log(`iter ${iter}: no step -> tower ${twj.job.state} placed=${twj.job.metrics?.placed}`);
    } else {
      // Controlled descent (never walk off a ledge blindly - that killed the character earlier).
      const dg = await call('dig_shaft', { level: py - 4, mode: 'down', timeoutSeconds: 90 }, 30000);
      const dgj = await waitJob(dg.json.jobId, 100000);
      const p2 = await P();
      log.steps.push({ iter, action: 'descend_result', state: dgj.job.state, reason: dgj.job.reason || null, pos: [p2.x, p2.y, p2.z] });
      console.log(`iter ${iter}: no step -> descend ${dgj.job.state} ${dgj.job.reason || ''}`);
      if (dgj.job.state !== 'done') {
        const walk = await call('walk', { yaw: p.yaw, ticks: 4, timeoutSeconds: 15 }, 15000);
        await waitJob(walk.json.jobId, 20000);
      }
    }
    sideFlip = -sideFlip;
    continue;
  }
  const wp = [best.x, best.y, best.z];
  const pt = await call('path_to', { x: wp[0], y: wp[1], z: wp[2], range: 1, timeoutSeconds: 30 }, 30000);
  const pj = await waitJob(pt.json.jobId, 40000);
  const p2 = await P();
  const moved = Math.hypot(p2.x - p.x, p2.z - p.z) + Math.abs(p2.y - p.y);
  const d2 = dist3(p2);
  log.steps.push({
    iter, waypoint: wp, offDeg: best.offDeg, step: best.step, rise: best.y - py,
    state: pj.job.state, reason: pj.job.reason || null,
    moved: Math.round(moved * 10) / 10, dist: Math.round(d2 * 10) / 10,
  });
  console.log(`iter ${iter}: wp=${wp.join(',')} rise=${best.y - py} ${pj.job.state} moved=${moved.toFixed(1)} dist=${d2.toFixed(1)}`);
  if (moved < 0.8) {
    stuck++;
    sideFlip = -sideFlip;
    if (stuck >= 4) {
      log.steps.push({ iter, action: 'stuck_reset', pos: [p2.x, p2.y, p2.z] });
      stuck = 0;
    }
  } else {
    stuck = 0;
  }
  lastDist = d2;
}
if (!log.result) {
  const fin = await P();
  log.result = { ok: dist3(fin) <= 3, pos: [fin.x, fin.y, fin.z], dist: dist3(fin), reason: 'max_iter' };
}
log.finishedAt = new Date().toISOString();
console.log('RESULT ' + JSON.stringify(log.result));
const fs = await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/rover2-log.json', JSON.stringify(log, null, 2));


