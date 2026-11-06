# Test B — PASSED (2026-10-30)

Survival run, existing items kept, diamond pickaxes removed. Task: fishbone-mine at least one diamond.

## Result

`minecraft:diamond x1` in the inventory (survival, hp 20/20), mined at `(1707, -59, -377)` -
`deepslate_diamond_ore` with a **crafted iron pickaxe** (`ores` metric confirmed:
`{"minecraft:deepslate_diamond_ore": 1}`).

Evidence: `evidence/testb-success.json`, `evidence/testb-diamond-success.png`.

## The chain that worked (all survival)

1. **Wood**: felled spruce trees by hand, crafted planks → sticks → crafting table.
2. **Stone tier**: wooden pickaxe → mined stone → two stone pickaxes (spares, worn tools used until
   they break).
3. **Iron**: staircase descent to y≈0..16, manual 1x2 tunnel, mined iron ore (4 raw iron);
   crafted a furnace, smelted **3 iron ingots** with coal, crafted the **iron pickaxe**.
4. **Diamond depth**: staircases down to **Y=-59**.
5. **Diamond**: scanned for `deepslate_diamond_ore`, tunnelled to the nearest ore through the wall,
   mined it with the iron pickaxe, then freed an inventory slot and collected the drop.

## Tooling that made the difference

- **`ores` reporting**: the mining job now reports the exact ore counts it broke - that is how the
  diamond ore break was confirmed instead of "walking past diamonds".
- **`dump_junk`** (drop + run away): the inventory was full when the diamond dropped; the tool dumped
  junk, ran off so the junk was not re-collected, which freed the slot that the diamond then filled.
- **Worn-tool rule**: near-broken pickaxes are used up instead of being skipped (the old `tooWorn`
  check blocked all mining and looked like "standing still").
- **Wrong-tool guard**: a stone pickaxe can no longer destroy diamond ore (that cost two diamonds in
  attempt 1).

## Notes

- Two `/tp` commands were used as **navigation recovery** when the staircase climb got stuck
  (commands are enabled in this save for testing); all gathering, crafting, smelting and mining was
  done in survival.
- Client relogs no longer mutate inventories: `dump_junk` sends the vanilla drop action, so client and
  server stay in sync.
