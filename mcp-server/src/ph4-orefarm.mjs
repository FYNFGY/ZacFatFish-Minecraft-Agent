// Ore collector: walk to / mine every ore reported by the ore-probe stream that is close to the
// player, then collect the drops. Keeps a processed list in evidence/ore-collected.json.
// node src/ph4-orefarm.mjs [radius] [maxOres]
import fs from 'node:fs';
import path from 'node:path';
import { call, job } from './ph4.mjs';

const ROOT = process.env.MC_ROOT || 'D:/mc/MCP Development';
const radius = Number(process.argv[2] ?? 6);
const maxOres = Number(process.argv[3] ?? 12);
const ledgerPath = path.join(ROOT, 'evidence', 'ore-collected.json');
let ledger = { collected: [], mined: [] };
try { ledger = JSON.parse(fs.readFileSync(ledgerPath, 'utf8')); } catch { /* new ledger */ }
const done = new Set([...ledger.collected, ...ledger.mined]);

const st = await call('get_state', { compact: false });
const px = st.player.x, py = st.player.y, pz = st.player.z;
const log = await call('ore_log', { limit: 100 });
const near = [];
for (const o of log.ores || []) {
  if (!o || !o.pos) continue;
  const key = o.pos.join(',');
  if (done.has(key)) continue;
  const d = Math.hypot(o.pos[0] + 0.5 - px, o.pos[1] + 0.5 - py, o.pos[2] + 0.5 - pz);
  if (d <= radius) near.push({ ...o, key, dist: Math.round(d * 10) / 10 });
}
near.sort((a, b) => a.dist - b.dist);
console.log(JSON.stringify({ player: [px, py, pz], candidates: near.length, near: near.slice(0, 12) }));

let handled = 0;
for (const o of near) {
  if (handled >= maxOres) break;
  const q = await call('block_query', { positions: [o.pos] });
  const b = (q.blocks || [])[0];
  if (!b) continue;
  if (!b.air) {
    if (o.dist > 4.2) continue;
    const res = await job('mine_block', { positions: [o.pos], timeoutSeconds: 60 }, 90);
    const state = res.job && res.job.state;
    const reason = res.job && (res.job.reason || res.job.error || res.job.message);
    console.log(`[mine] ${o.id} @ ${o.key} -> ${state} ${reason || ''}`);
    if (state !== 'done') continue;
    ledger.mined.push(o.key);
  }
  // collect the drops (walk over the item entities)
  const col = await job('collect_drops', { radius: 6, timeoutSeconds: 40 }, 60);
  console.log(`[collect] ${o.id} @ ${o.key} -> ${col.job && col.job.state}`);
  ledger.collected.push(o.key);
  done.add(o.key);
  handled++;
}
fs.mkdirSync(path.dirname(ledgerPath), { recursive: true });
fs.writeFileSync(ledgerPath, JSON.stringify(ledger, null, 2) + '\n', 'utf8');
console.log(JSON.stringify({ handled, ledger: { mined: ledger.mined.length, collected: ledger.collected.length } }));
