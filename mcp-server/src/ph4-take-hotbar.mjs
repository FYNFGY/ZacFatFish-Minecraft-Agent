// Find an item in the main inventory and move it to a hotbar slot (server-synced SWAP clicks).
// node src/ph4-take-hotbar.mjs <itemId> [hotbarSlot]
import { call } from './ph4.mjs';

const target = process.argv[2];
const scratch = Number(process.argv[3] ?? 7);
if (!target) {
  console.error('usage: ph4-take-hotbar.mjs <itemId> [hotbarSlot]');
  process.exit(2);
}
const hb = await call('hotbar', {});
if (hb.hotbar[scratch].item.id === target) {
  console.log(JSON.stringify({ found: true, already: true, hotbarSlot: scratch }));
  process.exit(0);
}
for (let i = 9; i <= 35; i++) {
  const r = await call('inventory_swap', { fromSlot: i, hotbarSlot: scratch });
  const now = await call('hotbar', {});
  const id = now.hotbar[scratch].item.id;
  if (id === target) {
    console.log(JSON.stringify({ found: true, fromSlot: i, hotbarSlot: scratch, after: now.hotbar[scratch].item }));
    process.exit(0);
  }
  if (id !== 'minecraft:air') {
    await call('inventory_swap', { fromSlot: i, hotbarSlot: scratch });
  }
}
console.log(JSON.stringify({ found: false, item: target }));
process.exit(1);
