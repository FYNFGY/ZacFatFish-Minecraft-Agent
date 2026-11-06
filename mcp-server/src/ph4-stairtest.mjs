// Phase-2 P1-3 acceptance: climb the 28-block staircase up/down three times with walk_to.
// Usage: node src/ph4-stairtest.mjs [bottomX] [topX] [z] [rounds]
import fs from 'node:fs';
import path from 'node:path';
import { call, job } from './ph4.mjs';

const bottomX = Number(process.argv[2] ?? 1605.5);
const topX = Number(process.argv[3] ?? 1632.5);
const z = Number(process.argv[4] ?? -595.5);
const rounds = Number(process.argv[5] ?? 3);
const ROOT = process.env.MC_ROOT || 'D:/mc/MCP Development';

const out = { startedAt: new Date().toISOString(), bottomX, topX, z, rounds, legs: [] };
const st0 = await call('get_state', { compact: false });
out.start = { pos: [st0.player.x, st0.player.y, st0.player.z], ghosts: st0.ghostCount };
console.log('start', JSON.stringify(out.start));

for (let round = 1; round <= rounds; round++) {
  for (const [label, tx] of [['down', bottomX], ['up', topX]]) {
    const res = await job('walk_to', { x: tx, z, range: 1.4, maxTicks: 1200, timeoutSeconds: 120 }, 150);
    const st = await call('get_state', { compact: false });
    const leg = {
      round, direction: label, targetX: tx,
      state: res.job && res.job.state,
      reason: res.job && (res.job.reason || res.job.error || res.job.message),
      ticks: res.job && res.job.result && res.job.result.ticks,
      ms: res.ms,
      pos: [st.player.x, st.player.y, st.player.z],
      hp: st.player.health,
      ghostCount: st.ghostCount,
    };
    out.legs.push(leg);
    console.log(JSON.stringify(leg));
    if (leg.state !== 'done') break;
  }
}

const st1 = await call('get_state', { compact: false });
out.final = {
  pos: [st1.player.x, st1.player.y, st1.player.z],
  hp: st1.player.health,
  ghostCount: st1.ghostCount,
  failedLegs: out.legs.filter((l) => l.state !== 'done').length,
  totalLegs: out.legs.length,
};
out.pass = out.final.failedLegs === 0 && out.final.totalLegs === rounds * 2;
console.log('final', JSON.stringify(out.final), 'pass=' + out.pass);
const full = path.join(ROOT, 'evidence', 'ph4-p2-stair-roundtrips.json');
fs.writeFileSync(full, JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log('[saved ' + full + ']');
