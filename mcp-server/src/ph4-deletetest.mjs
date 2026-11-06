// Phase-0 acceptance P1-6: delete a stack server-side and prove the inventory stays in sync.
// node src/ph4-deletetest.mjs
import fs from 'node:fs';
import path from 'node:path';
import { call, job } from './ph4.mjs';

const ROOT = process.env.MC_ROOT || 'D:/mc/MCP Development';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const counts = (st) => {
  const m = {};
  for (const it of (st.player.inventory.items || [])) m[it.id] = it.count;
  return m;
};

const before = await call('get_state', { compact: false });
const beforeCounts = counts(before);
const usedBefore = before.player.inventory.usedSlots;

const res = await job('delete_item', { item: 'minecraft:cobblestone', run: 6, timeoutSeconds: 60 }, 90);
await sleep(2000);
const after1 = await call('get_state', { compact: false });
await sleep(2000);
const after2 = await call('get_state', { compact: false });

const out = {
  startedAt: new Date().toISOString(),
  job: res.job,
  ms: res.ms,
  before: { usedSlots: usedBefore, cobblestone: beforeCounts['minecraft:cobblestone'] || 0, counts: beforeCounts },
  after1: { usedSlots: after1.player.inventory.usedSlots, cobblestone: counts(after1)['minecraft:cobblestone'] || 0, counts: counts(after1) },
  after2: { usedSlots: after2.player.inventory.usedSlots, cobblestone: counts(after2)['minecraft:cobblestone'] || 0, counts: counts(after2) },
};
out.stable = out.after1.usedSlots === out.after2.usedSlots
  && out.after1.cobblestone === out.after2.cobblestone;
out.freed = usedBefore - out.after2.usedSlots;
out.deleted = out.before.cobblestone - out.after2.cobblestone;
out.pass = out.job && out.job.state === 'done' && out.stable && out.deleted >= 64;
console.log(JSON.stringify(out, null, 2));
const full = path.join(ROOT, 'evidence', 'ph4-p0-delete-item.json');
fs.writeFileSync(full, JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log('[saved ' + full + '] pass=' + out.pass);
