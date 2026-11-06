# TestB v2 - progress log (attempt 1, 2026-10-31)

Setup (commands, before the run): `/difficulty peaceful`, `/time set 1000`, `/weather clear`,
`/clear @p`, `/tp 1746.5 95 -447.5` (forest at the coast, untouched area), `/save-all`.
**From that point the run used no `/` command.**

## What worked (survival, no commands)

| Step | Evidence |
|---|---|
| 3-4 spruce logs hand-mined (61-66 ticks each, `mine_block`) | `evidence/testb2-wood2.json` |
| planks 12, sticks 4, crafting table, wooden pickaxe | craft job results in the transcript |
| stair down 19 steps (y 92 -> 72) for stone | `evidence/testb2-stone-b.json` |
| 31 cobblestone collected, stone pickaxes + furnace crafted | `testb2-slots2.json` |
| iron found by the ore probe stream: 9 `iron_ore` in a 48-block corridor at y=30 | `evidence/testb2-iron3.json` |
| 9 iron ingots smelted in a placed furnace (raw iron 9 + coal 2 -> ingots 9) | `evidence/testb2-furnace.json` |
| **iron pickaxe crafted** (3 ingots + 2 sticks at the surface table) | craft job `j-1f-9481dd` |
| stair down 89 steps in one job (y 88 -> 60 -> 30 -> 8), 3 blocks/step | `testb2-down-e/f/g` |

## Where it went wrong

1. **Stair entry/exit**: climbing out of the 1-wide stair near the surface needed a pillar (`tower`),
   and the pillar then blocked the stair on the next descent. Each round trip needed manual digging
   of the pillar. A 1-wide 45-degree stair is fine to *dig* (the 60-block acceptance test passed) but
   the surface entry is fragile: the walker cannot reliably drop into it and cannot climb the 2-block
   lip at the collapse point.
2. **Tool budget on long descents**: 150 blocks of stair at 3 blocks/step = 450 pickaxe uses; one
   iron pickaxe (250) is not enough, and `tool_low` correctly stops on ores when the tool is nearly
   dead. The healthy-spare logic works but needs spares *in the bag*.
3. Placement of blocks (furnace/table) sometimes "was not confirmed after 3 ticks" and the item was
   lost; `use_block` against a support block worked reliably instead.

## State at the end of the attempt

Player on the surface above the stair at `(1735, 90, -451)`, survival, hp 20, no commands used,
inventory: 1 fresh iron pickaxe, 1 stone pickaxe, 3 iron ingots, cobble/coal, no diamond yet.

## Final outcome: attempt 1 failed (tool dead end)

The stair ride (using `dig_shaft` as a walker over an existing stair) worked and reached **y=8**
(`evidence/testb2-descend-ride1.json`), but it *dug* 243 fresh blocks on the way down (80 steps,
3 blocks/step). The `up` mode also digs 3 blocks/step (`testb2-upride-test.json`), so the return
climb costs as much as the descent. With 143 pickaxe uses left for a 201-block descent the run ran
out of tools at y=28 with no table, no furnace and no way to craft more - a hard dead end.

**Verdict: TestB v2 attempt 1 = FAILED.** No diamond, no return. Zero commands were used, so the
failure is a real capability gap: the tool and travel budget was not planned for a 130-block descent.

## Next attempt (short list) - the numbers that matter

- Descent surface -> Y=-59 = ~130 steps x 3 blocks = **390 pickaxe uses** (the stair digs 3 blocks
  per step, the walk-down over an existing stair is free).
- Tool plan: 4 stone pickaxes (524 uses) for the stone/deepslate descent + 2 iron pickaxes (500) for
  the ores and the diamond branch mine. That needs **6 iron ingots** (2 iron pickaxes) smelted
  *before* descending, plus ~20 cobble for the stone picks.
- Carry a crafting table + furnace down (as items) and place them at the mine mouth; craft spares
  on site instead of walking back to the surface.
- Start on **flat ground** so the stair entry is a clean hole; never pillar out of the stair.
- Mine the whole descent in **one or two `dig_shaft` jobs**, then walk back up the same stair
  (walking is free and the 28-step round-trip acceptance test passed 3x).
