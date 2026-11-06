// Phase-3 CubeTest: solid 24^3 cube, a target inside, dig_tunnel from the entrance to it, walk in,
// walk back out. Acceptance: reach + return, zero ghost reports, path length < 3x the straight line.
// The cube area is force-loaded before the fill, because /fill silently fails on unloaded chunks.
// node src/ph4-cubetest.mjs
import fs from 'node:fs';
import path from 'node:path';
import { call, job } from './ph4.mjs';

const ROOT = process.env.MC_ROOT || 'D:/mc/MCP Development';
const CUBE = { x0: 1700, y0: 98, z0: -700, x1: 1723, y1: 121, z1: -677 };
const ENTRANCE = [1697, 99, -690];
const TARGET = [1712, 99, -690];
const entranceXZ = [ENTRANCE[0] + 0.5, ENTRANCE[2] + 0.5];

const cmd = (c) => call('command', { command: c });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const t0 = Date.now();
await cmd('/forceload add 1690 -710 1735 -665');
await sleep(1200);
await cmd(`/fill ${CUBE.x0} ${CUBE.y0} ${CUBE.z0} ${CUBE.x1} ${CUBE.y1} ${CUBE.z1} minecraft:stone`);
await sleep(400);
await cmd('/fill 1695 98 -693 1699 98 -687 minecraft:stone');
await sleep(300);
await cmd('/fill 1695 99 -693 1699 101 -687 minecraft:air');
await sleep(300);
await cmd('/tp @p 1697.5 99 -690.5 -90 0');
await sleep(800);

// Verify the cube really exists before digging: a failed /fill would make the test meaningless.
const check = await call('block_query', {
  positions: [[CUBE.x0, 110, -690], [CUBE.x1, 110, -690], [1710, 110, -690], [TARGET[0], TARGET[1], TARGET[2]]],
});
const cubeOk = (check.blocks || []).slice(0, 3).every((b) => b.id === 'minecraft:stone');
const setupMs = Date.now() - t0;
const st0 = await call('get_state', { compact: false });
const out = {
  startedAt: new Date().toISOString(),
  cube: CUBE, entrance: ENTRANCE, target: TARGET,
  setupMs, cubeOk, cubeCheck: check.blocks,
  startPos: [st0.player.x, st0.player.y, st0.player.z],
  ghostsBefore: st0.ghostCount,
};
if (!cubeOk) {
  out.pass = false;
  out.error = 'the cube fill failed (chunk not loaded) - rerun after forceload';
  console.log(JSON.stringify(out, null, 2));
  process.exit(1);
}

const straight = Math.hypot(TARGET[0] - ENTRANCE[0], TARGET[2] - ENTRANCE[2]);

const tunnel = await job('dig_tunnel', {
  from: ENTRANCE, to: TARGET, torches: true, returnTrip: true, maxBlocks: 200, timeoutSeconds: 300,
}, 400);
out.tunnel = {
  state: tunnel.job && tunnel.job.state,
  reason: tunnel.job && (tunnel.job.reason || tunnel.job.error || tunnel.job.message),
  ms: tunnel.ms,
  result: tunnel.job && tunnel.job.result,
  metrics: tunnel.job && tunnel.job.metrics,
};
console.log('tunnel', JSON.stringify({ state: out.tunnel.state, ms: out.tunnel.ms, result: out.tunnel.result }));

const walkIn = await job('walk_to', { x: TARGET[0] + 0.5, z: TARGET[2] + 0.5, range: 0.6, maxTicks: 600, timeoutSeconds: 90 }, 120);
const st1 = await call('get_state', { compact: false });
out.walkIn = {
  state: walkIn.job && walkIn.job.state,
  reason: walkIn.job && (walkIn.job.reason || walkIn.job.error || walkIn.job.message),
  ticks: walkIn.job && walkIn.job.result && walkIn.job.result.ticks,
  ms: walkIn.ms,
  pos: [st1.player.x, st1.player.y, st1.player.z],
};
const walkOut = await job('walk_to', { x: entranceXZ[0], z: entranceXZ[1], range: 0.9, maxTicks: 600, timeoutSeconds: 90 }, 120);
const st2 = await call('get_state', { compact: false });
out.walkOut = {
  state: walkOut.job && walkOut.job.state,
  reason: walkOut.job && (walkOut.job.reason || walkOut.job.error || walkOut.job.message),
  ticks: walkOut.job && walkOut.job.result && walkOut.job.result.ticks,
  ms: walkOut.ms,
  pos: [st2.player.x, st2.player.y, st2.player.z],
};
const ghosts = await call('ghosts', { limit: 50, check: true });
out.ghostsAfter = { total: ghosts.total, events: ghosts.events, stuckInAir: ghosts.stuckInAir };
out.path = {
  straightLine: Math.round(straight * 10) / 10,
  tunnelLength: out.tunnel.result ? out.tunnel.result.length : null,
  ratio: out.tunnel.result ? Math.round((out.tunnel.result.length / straight) * 100) / 100 : null,
};
out.pass = out.tunnel.state === 'done'
  && out.tunnel.result && out.tunnel.result.returnedToStart === true
  && out.tunnel.result.blocksMined > 0
  && out.walkIn.state === 'done'
  && out.walkOut.state === 'done'
  && out.ghostsAfter.total === st0.ghostCount
  && out.path.ratio !== null && out.path.ratio < 3;
console.log(JSON.stringify({ path: out.path, ghosts: out.ghostsAfter.total, pass: out.pass }, null, 2));
const full = path.join(ROOT, 'evidence', 'ph4-p3-cubetest.json');
fs.writeFileSync(full, JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log('[saved ' + full + ']');
