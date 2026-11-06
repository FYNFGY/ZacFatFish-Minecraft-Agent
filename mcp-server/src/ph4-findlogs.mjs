// Find tree trunks around the player with targeted block queries (no X-ray: only a 17x17x8 bubble).
// node src/ph4-findlogs.mjs [radiusXZ] [heightAbove] [heightBelow]
import { call } from './ph4.mjs';

const rx = Number(process.argv[2] ?? 8);
const above = Number(process.argv[3] ?? 6);
const below = Number(process.argv[4] ?? 1);
const st = await call('get_state', { compact: false });
const px = Math.floor(st.player.x);
const py = Math.floor(st.player.y);
const pz = Math.floor(st.player.z);
const positions = [];
for (let dx = -rx; dx <= rx; dx++) {
  for (let dz = -rx; dz <= rx; dz++) {
    for (let dy = -below; dy <= above; dy++) {
      positions.push([px + dx, py + dy, pz + dz]);
    }
  }
}
const logs = [];
const others = {};
for (let i = 0; i < positions.length; i += 64) {
  const chunk = positions.slice(i, i + 64);
  const r = await call('block_query', { positions: chunk });
  for (const b of r.blocks || []) {
    if (String(b.id).includes('log')) {
      logs.push({ pos: b.pos, id: b.id });
    } else if (!b.air) {
      others[b.id] = (others[b.id] || 0) + 1;
    }
  }
}
logs.sort((a, b) => {
  const da = Math.hypot(a.pos[0] - px, a.pos[2] - pz);
  const db = Math.hypot(b.pos[0] - px, b.pos[2] - pz);
  return da - db;
});
console.log(JSON.stringify({ player: [px, py, pz], checked: positions.length, logs: logs.slice(0, 40), blocks: others }, null, 2));
