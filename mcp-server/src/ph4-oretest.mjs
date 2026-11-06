// Phase-1 P1-2 acceptance: a diamond ore hidden behind the tunnel wall must be found by the ore
// probe stream (not by an X-ray scan) and mined.
// node src/ph4-oretest.mjs
import fs from 'node:fs';
import path from 'node:path';
import { call, job } from './ph4.mjs';

const ROOT = process.env.MC_ROOT || 'D:/mc/MCP Development';
const WALL1 = [1435, 61, -517];
const WALL2 = [1435, 61, -516];
const ORE = [1435, 61, -515];

const cmd = (c) => call('command', { command: c });
await cmd('/clear @p minecraft:iron_pickaxe');
await cmd('/give @p minecraft:iron_pickaxe');
await cmd('/setblock 1435 61 -515 minecraft:diamond_ore');
await cmd('/tp @p 1435.5 61 -518.5 180 0');
await new Promise((r) => setTimeout(r, 500));

const scan = await call('scan_connected', { radius: 10, includeEntities: false });
const surfaceHas = (r, x, y, z) => (r.surface || []).some((s) => s[0] === x && s[1] === y && s[2] === z);
const out = {
  startedAt: new Date().toISOString(),
  setup: ['/clear @p iron_pickaxe', '/give @p iron_pickaxe', '/setblock 1435 61 -515 diamond_ore', '/tp'],
  scanBefore: {
    openCells: scan.openCells, surfaceCount: scan.surfaceCount,
    oreVisibleInScan: surfaceHas(scan, ...ORE),
    oreOnSurfaceList: !!(scan.surface || []).find((s) => s[3] === 'minecraft:diamond_ore'),
  },
  steps: [],
};

const mine = async (pos, label) => {
  const res = await job('mine_block', { positions: [pos], timeoutSeconds: 60 }, 90);
  const log = await call('ore_log', { limit: 20 });
  const step = {
    label, pos, state: res.job && res.job.state,
    reason: res.job && (res.job.reason || res.job.error || res.job.message),
    blocksMined: res.job && res.job.metrics && res.job.metrics.blocksMined,
    lastProbe: res.job && res.job.metrics && res.job.metrics.lastProbe,
    oreLogTotal: log.total,
    ores: (log.ores || []).map((o) => ({ pos: o.pos, id: o.id, from: o.from })),
    ms: res.ms,
  };
  out.steps.push(step);
  console.log(JSON.stringify(step));
  return step;
};

await mine(WALL1, 'wall-1 (z=-517)');
const step2 = await mine(WALL2, 'wall-2 (z=-516)');
await mine(ORE, 'ore (z=-515)');
const collect = await job('collect_drops', { radius: 6, timeoutSeconds: 60 }, 90);
const state = await call('get_state', { compact: false });
const counts = {};
for (const it of state.player.inventory.items || []) counts[it.id] = it.count;
out.collect = { state: collect.job && collect.job.state, ms: collect.ms };
out.inventory = { diamonds: counts['minecraft:diamond'] || 0, counts };
out.oreFoundByProbe = !!(step2.ores || []).find((o) => o.pos && o.pos[0] === ORE[0] && o.pos[1] === ORE[1] && o.pos[2] === ORE[2]);
out.pass = out.scanBefore.oreVisibleInScan === false
  && (out.steps[0].oreLogTotal === 0)
  && out.oreFoundByProbe
  && out.steps[2].state === 'done'
  && out.inventory.diamonds >= 1;
console.log('PASS=' + out.pass);
const full = path.join(ROOT, 'evidence', 'ph4-p1-ore-probe.json');
fs.writeFileSync(full, JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log('[saved ' + full + ']');
