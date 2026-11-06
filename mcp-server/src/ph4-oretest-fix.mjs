// Follow-up to ph4-oretest.mjs: widen the 1-wide niche (collect_drops cannot centre itself into a
// 1-wide hole) and collect the diamond, then patch the evidence file.
import fs from 'node:fs';
import path from 'node:path';
import { call, job } from './ph4.mjs';

const ROOT = process.env.MC_ROOT || 'D:/mc/MCP Development';
const full = path.join(ROOT, 'evidence', 'ph4-p1-ore-probe.json');
const out = JSON.parse(fs.readFileSync(full, 'utf8'));

const widen = await job('mine_block', { positions: [[1436, 61, -516]], timeoutSeconds: 60 }, 90);
const collect = await job('collect_drops', { radius: 6, timeoutSeconds: 60 }, 90);
const state = await call('get_state', { compact: false });
const counts = {};
for (const it of state.player.inventory.items || []) counts[it.id] = it.count;
out.widenNiche = { state: widen.job && widen.job.state, ms: widen.ms };
out.collect2 = { state: collect.job && collect.job.state, result: collect.job && collect.job.result, ms: collect.ms };
out.inventory = { diamonds: counts['minecraft:diamond'] || 0, counts };
out.pass = out.scanBefore.oreVisibleInScan === false
  && out.steps[0].oreLogTotal === 0
  && out.oreFoundByProbe
  && out.steps[2].state === 'done'
  && out.inventory.diamonds >= 1;
fs.writeFileSync(full, JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log(JSON.stringify({ widen: out.widenNiche, collect: out.collect2, diamonds: out.inventory.diamonds, pass: out.pass }, null, 2));
