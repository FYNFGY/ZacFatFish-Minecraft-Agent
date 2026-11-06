// Phase-0 acceptance: walk back and forth through the dug tunnel three times and count ghost events.
// Usage: node src/ph4-ghosttest.mjs <entranceX> <z> <farX> [rounds]
import fs from 'node:fs';
import path from 'node:path';
import { call, job } from './ph4.mjs';

const entranceX = Number(process.argv[2] ?? 1401.5);
const z = Number(process.argv[3] ?? -517.5);
const farX = Number(process.argv[4] ?? 1439.5);
const rounds = Number(process.argv[5] ?? 3);
const ROOT = process.env.MC_ROOT || 'D:/mc/MCP Development';
const out = { startedAt: new Date().toISOString(), entranceX, z, farX, rounds, legs: [], ghosts: [] };

const ghostSnapshot = async (label) => {
  const g = await call('ghosts', { limit: 50, check: true });
  out.ghosts.push({ label, total: g.total, stuckInAir: g.stuckInAir, events: g.events });
  return g;
};

const state = await call('get_state', { compact: false });
out.start = { pos: [state.player.x, state.player.y, state.player.z], hp: state.player.health, ghosts: state.ghostCount };
console.log('start', JSON.stringify(out.start));

for (let round = 1; round <= rounds; round++) {
  for (const [label, tx] of [['back', entranceX], ['forth', farX]]) {
    const t0 = Date.now();
    const res = await job('walk_to', { x: tx, z, range: 0.9, maxTicks: 900, timeoutSeconds: 90 }, 120, 'walk_to');
    const st = await call('get_state', { compact: false });
    const leg = {
      round, direction: label, targetX: tx,
      state: res.job && res.job.state,
      reason: res.job && (res.job.reason || res.job.error || res.job.message),
      ms: res.ms, ticks: res.job && res.job.result && res.job.result.ticks,
      pos: [st.player.x, st.player.y, st.player.z],
      hp: st.player.health,
      ghostCount: st.ghostCount,
    };
    out.legs.push(leg);
    console.log(JSON.stringify(leg));
    if (leg.state !== 'done') break;
    await ghostSnapshot(`round${round}-${label}`);
  }
}

const finalState = await call('get_state', { compact: false });
out.final = {
  pos: [finalState.player.x, finalState.player.y, finalState.player.z],
  hp: finalState.player.health,
  ghostCount: finalState.ghostCount,
  failedLegs: out.legs.filter((l) => l.state !== 'done').length,
  totalLegs: out.legs.length,
};
console.log('final', JSON.stringify(out.final));
const full = path.join(ROOT, 'evidence', 'ph4-p0-ghost-walk.json');
fs.writeFileSync(full, JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log('[saved ' + full + ']');
