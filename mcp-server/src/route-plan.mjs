// Height-map route planner: scan the surface, BFS over walkable cells (|dy| <= 1), print waypoints.
const D = 'http://127.0.0.1:57611';
async function call(t, a = {}, to = 60000) {
  const r = await fetch(`${D}/call`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool: t, args: a, timeoutMs: to }) });
  return r.json();
}
const st = await call('get_state');
const p = st.json.player;
const px = Math.floor(p.x), py = Math.floor(p.y), pz = Math.floor(p.z);
const target = [1888, 219, 1416];
console.log(`player ${px},${py},${pz} -> target ${target.join(',')}`);

const scan = await call('scan_surface', {
  center: [px, py, pz], radius: 40, mode: 'connected', heightRadius: 32,
  maxSurface: 8000, timeBudgetMs: 4000,
}, 60000);
const cells = scan.json.heightMap || [];
console.log(`heightMap cells: ${cells.length}`);
const map = new Map();
for (const c of cells) map.set(`${c[0]},${c[1]}`, c[2]);
const startKey = `${px},${pz}`;
const goalKey = `${target[0]},${target[2]}`;
const keys = [...map.keys()];
function nearest(x, z) {
  let best = null, bestD = Infinity;
  for (const k of keys) {
    const [kx, kz] = k.split(',').map(Number);
    const d = Math.abs(kx - x) + Math.abs(kz - z);
    if (d < bestD) { bestD = d; best = k; }
  }
  return { key: best, d: bestD };
}
const startNear = nearest(px, pz);
const goalNear = nearest(target[0], target[2]);
console.log(`start cell ${startNear.key} (d=${startNear.d}), goal cell ${goalNear.key} (d=${goalNear.d})`);

// BFS 8-neighbour, allow |dy| <= 1
const q = [startNear.key];
const prev = new Map([[startNear.key, null]]);
const dirs = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [1, -1], [-1, 1], [-1, -1]];
while (q.length) {
  const cur = q.shift();
  if (cur === goalNear.key) break;
  const [cx, cz] = cur.split(',').map(Number);
  const cy = map.get(cur);
  for (const [dx, dz] of dirs) {
    const nk = `${cx + dx},${cz + dz}`;
    if (!map.has(nk) || prev.has(nk)) continue;
    const ny = map.get(nk);
    if (Math.abs(ny - cy) > 1) continue;
    prev.set(nk, cur);
    q.push(nk);
  }
}
if (!prev.has(goalNear.key)) {
  console.log('no walkable route found at |dy|<=1');
  const heights = cells.map((c) => c[2]); if (!heights.length) { console.log('empty'); process.exit(0); }
  console.log(`height range: ${Math.min(...heights)}..${Math.max(...heights)}`);
} else {
  const path = [];
  let cur = goalNear.key;
  while (cur) { path.unshift(cur); cur = prev.get(cur); }
  console.log(`path cells: ${path.length}`);
  const waypoints = [];
  for (let i = 0; i < path.length; i += 8) {
    const [wx, wz] = path[i].split(',').map(Number);
    waypoints.push([wx, map.get(path[i]), wz]);
  }
  const [gx, gz] = goalNear.key.split(',').map(Number);
  waypoints.push([gx, map.get(goalNear.key), gz]);
  console.log('waypoints: ' + waypoints.map((w) => w.join(',')).join(' | '));
  const fs = await import('node:fs');
  fs.writeFileSync('D:/mc/MCP Development/evidence/path-plan.json', JSON.stringify({ player: [px, py, pz], target, waypoints }, null, 2));
}

