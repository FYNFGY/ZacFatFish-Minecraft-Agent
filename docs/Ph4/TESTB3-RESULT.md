# TestB v2 - attempt 3 result (2026-10-31, round 3)

Setup (commands, before the run): `/difficulty peaceful`, `/time set 1000`, `/weather clear`,
`/clear @p`, `/tp 1548.5 75 -470.5`, `/save-all`.
**Zero `/` commands were used from the start of the run.**

## What was achieved (survival, no commands)

| Step | Evidence |
|---|---|
| 5 spruce logs hand-mined, planks/sticks/table/wooden pickaxe | `evidence/testb3-wood2.json`, transcript craft jobs |
| stair down 11 + 12 steps for 42 cobblestone, 4 stone pickaxes + furnace crafted | `testb3-stone1/2.json` |
| 4 more stone pickaxes + a second table placed at the mine site (y=40) | `testb3-slots5.json` |
| tunnel B extended to z=-500 (30 blocks, 60 mined) | `testb3-tunnel2.json` |
| **ore probe stream live in chat**: every new ore announced as `发现新矿石：<name> (<id>) @ x,y,z` | chat history (`ph4.mjs` ore watch) |
| every ore within reach mined and collected (ledgered) | `evidence/ore-collected.json` (26 mined / 55 collected) |
| 6 raw iron collected -> 7 ingots smelted in a placed furnace (coal fuel) | `testb3-furnace4.json` |
| **2 iron pickaxes crafted** (plus 4 stone pickaxes) | craft job `j-7p-2964db` |
| main tunnel B reused: stair extended down from its far end to Y=-59 | `testb3-descent2/5.json` |
| **DIAMOND**: `minecraft:deepslate_diamond_ore` at `1552,-17,-453`, mined at 28 ticks, diamond picked up | `testb3-diamond1.json`, `testb3-slots23.json` (`minecraft:diamond x1`) |

## Partial failure: the return trip

The player is at `(1550.6, -11, -459.6)` - trapped in the cave system that the stair broke into at y=3.
The return walk failed because:

1. **The walker cannot climb a 1-block step when it is horizontally aligned with it**
   (`tickWalk` only jumps while `horizontalCollision` is set and the horizontal distance is > 0, so a
   pure vertical step gives "distance 0.02" and no jump). That is a real mod bug and the next fix.
2. The cave under the stair has no floor in front of the player, so `dig_shaft mode=up` refuses
   ("no floor below 1553,2,-460 to build a step on") - the stairs-up primitive needs a support block
   fallback for cave floors.
3. Baritone computed a 6358-tick path through the caves but then stopped (`hasPath=false`), so it
   cannot be used as the escape route either.

## Verdict

- **Diamond acceptance: PASS** (survival, zero commands, iron pickaxe, diamond in the inventory).
- **Return-trip acceptance: NOT COMPLETE** in this attempt. The player is underground at y=-11 with
  a diamond in the bag.

## Fixes queued before the next attempt

1. `TunnelJob.tickWalk` / `WalkToJob`: jump when the target is one block above and the player is on
   the ground, even with zero horizontal distance (stand-at-step + jump).
2. `DigJob.UP`: place a support block when the front column has no floor (cave floor fallback).
3. `CollectTask`: throttle jumps (max one per 10 ticks) and give up after 40 stuck ticks instead of
   80, so the client never looks like it is "jumping in place".
4. Keep the ore watch (chat announcement) and the ore-collect ledger - both worked well.
5. Reuse policy (user request): all further descents start at an existing tunnel end; Baritone is
   used to walk the existing corridors instead of digging parallel shafts.
