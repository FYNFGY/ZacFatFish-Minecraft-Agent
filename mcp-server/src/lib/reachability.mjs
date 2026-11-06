/**
 * Ph8 local reachability synthesis.
 *
 * Input contract:
 *   center: [px, feetY, pz]
 *   layers: feetY-1 .. feetY+2 (4 layers); d/t look one extra layer.
 *   blockAt(x,y,z) -> { id, air, full, nonFull, loaded }
 *
 * The algorithm is deliberately local: 15x15, 4 layers + one extra for pits/shafts. It does not
 * pathfind outside this box, and it never treats a non-full block (torch/button/trapdoor) as air.
 */

const HALF = 7;
const SIZE = 15;

const key = (x, y, z) => `${x},${y},${z}`;

function passable (b) {
  return !!(b && b.loaded !== false && b.air === true);
}

function support (b) {
  return !!(b && b.loaded !== false && !b.air && (b.full || b.nonFull));
}

function solid (b) {
  return !!(b && b.loaded !== false && !b.air && b.full);
}

export function synthesize ({ px, pz, feetY, blockAt }) {
  const at = (x, y, z) => blockAt(x, y, z) || { id: 'minecraft:unloaded', air: false, full: true, nonFull: false, loaded: false };
  const info = new Map();

  // Column profiles.
  for (let dz = -HALF; dz <= HALF; dz++) {
    for (let dx = -HALF; dx <= HALF; dx++) {
      const x = px + dx;
      const z = pz + dz;
      const feet = at(x, feetY, z);
      const head = at(x, feetY + 1, z);
      const above = at(x, feetY + 2, z);
      const above3 = at(x, feetY + 3, z);
      const below = at(x, feetY - 1, z);
      const below2 = at(x, feetY - 2, z);
      const bodyBlocked = !passable(feet) || !passable(head);
      const headBlocked = !passable(head) && passable(feet);
      const feetNonFull = !!feet.nonFull;
      const nonFullProbe = !!feet.nonFull || !!head.nonFull || !!above.nonFull || !!above3.nonFull;
      const deepPit = !support(below) && !support(below2) && passable(feet) && passable(head);
      const shaft = !deepPit && passable(feet) && passable(head) && passable(above) && passable(above3)
        && support(below);
      info.set(key(x, z), { x, z, feet, head, above, above3, below, below2, bodyBlocked,
        headBlocked, feetNonFull, nonFullProbe, deepPit, shaft });
    }
  }

  // 4-neighbour BFS over (x,z,level).
  const start = { x: px, z: pz, y: feetY };
  const visited = new Set([`${px},${pz},${feetY}`]);
  const queue = [start];
  const canStep = (x, z, y) => {
    if (y < feetY - 1 || y > feetY + 2) return false;
    const f = at(x, y, z);
    const h = at(x, y + 1, z);
    return passable(f) && passable(h) && support(at(x, y - 1, z));
  };
  while (queue.length) {
    const cur = queue.shift();
    for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const nx = cur.x + dx;
      const nz = cur.z + dz;
      if (Math.abs(nx - px) > HALF || Math.abs(nz - pz) > HALF) continue;
      const candidates = [];
      if (canStep(nx, nz, cur.y)) candidates.push(cur.y);
      if (canStep(nx, nz, cur.y + 1)) {
        // A one-block rise requires a full block at the old feet height.
        const step = at(nx, cur.y, nz);
        if (solid(step)) candidates.push(cur.y + 1);
      }
      if (canStep(nx, nz, cur.y - 1)) candidates.push(cur.y - 1);
      for (const ny of candidates) {
        const k = `${nx},${nz},${ny}`;
        if (visited.has(k)) continue;
        visited.add(k);
        queue.push({ x: nx, z: nz, y: ny });
      }
    }
  }

  const reachableAt = (x, z, y) => visited.has(`${x},${z},${y}`);
  const rows = [];
  for (let dz = -HALF; dz <= HALF; dz++) {
    let row = '';
    for (let dx = -HALF; dx <= HALF; dx++) {
      const x = px + dx;
      const z = pz + dz;
      const c = info.get(key(x, z));
      const isCenter = x === px && z === pz;
      const o = reachableAt(x, z, feetY);
      const up = reachableAt(x, z, feetY + 1);
      const down = reachableAt(x, z, feetY - 1);
      let mark;
      if (c.nonFullProbe && !c.feetNonFull) mark = '&';
      else if (c.feetNonFull) mark = 'l';
      else if (c.headBlocked) mark = 'u';
      else if (c.bodyBlocked) mark = up ? '+' : '*';
      else if (c.deepPit) mark = 'd';
      else if (up) mark = '+';
      else if (down && !o) mark = '-';
      else if (o) {
        const transition = [[1, 0], [-1, 0], [0, 1], [0, -1]].some(([ax, az]) =>
          reachableAt(x + ax, z + az, feetY + 1) || reachableAt(x + ax, z + az, feetY - 1));
        mark = transition ? '=' : 'o';
      } else if (c.shaft) mark = 't';
      else mark = '*';

      if (isCenter) {
        let prefix = '%';
        if (mark === '=') prefix += '=';
        else if (mark === '&') prefix += '&';
        mark = prefix;
      }
      row += mark;
    }
    rows.push(row);
  }
  return {
    ok: true,
    center: [px, feetY, pz],
    layers: [feetY - 1, feetY, feetY + 1, feetY + 2],
    origin: [px - HALF, pz - HALF],
    rows: SIZE,
    cols: SIZE,
    grid: rows,
    legend: {
      o: 'reachable, landing same height',
      '+': 'reachable, landing one higher',
      '=': 'reachable same-height vertical transition',
      '-': 'reachable, landing one lower',
      '*': 'unreachable, solid in player body',
      l: 'unreachable, block catches feet',
      u: 'unreachable, block at head',
      d: 'deep pit',
      t: 'upward shaft',
      '%': 'player position',
      '&': 'non-full block in probe area',
    },
  };
}
