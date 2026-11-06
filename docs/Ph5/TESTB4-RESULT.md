# TestB v2 - FINAL result (2026-10-31, rounds 3-4)

## Verdict: PASS (with two environment notes)

| Acceptance | Result |
|---|---|
| `minecraft:diamond` in the inventory | **PASS** - 1 diamond (deepslate diamond ore at `1552,-17,-453`, iron pickaxe) |
| Zero `/` commands during the run | **PASS** - only tools (mine/craft/place/path/tower...); all `/` commands were pre-run setup |
| Return trip completed | **PASS** - back on the surface at `1547, 75, -472`, hp 20/20, next to the start point `1548.5, 75, -470.5` |

Evidence: `evidence/testb3-diamond1.json` (ore break), `testb3-slots23.json` (diamond picked up),
`testb3-final-state.json` + `testb3-final-inv.json` (surface, hp 20, diamond 1, ghost 0),
`testb3-furnace4.json` (7 iron ingots), `testb3-descent5.json` (stair to Y=-59).

## The full chain (survival, no commands)

wood (5 spruce logs by hand) -> planks/sticks/table -> wooden pickaxe -> 11+12 stair steps for 42
cobblestone -> 4 stone pickaxes + furnace -> tunnel B extended to z=-500 (60 blocks) -> 6 raw iron
mined (ore probe stream found 9 veins) -> 7 iron ingots smelted with coal -> **2 iron pickaxes** ->
stair extended down from the end of the existing tunnel B to Y=-59 -> diamond ore mined.

## Fixes made during the return (all in the mod)

| Bug | Fix |
|---|---|
| A 1-block step with zero horizontal distance could not be climbed (`distance 0.02`, no jump). | `TunnelJob.tickWalk` jumps when the target is up to 1 block above and < 1.5 blocks away; `WalkToJob` hops after 12 stuck ticks even without `horizontalCollision`. |
| `tower` refused to pillar up through a cave ceiling ("ceiling too low"). | `TowerJob` now **mines stone/deepslate ceilings** (hardness <= 4) and continues; obsidian/bedrock still stop it. |
| `dig_shaft mode=up` refused when the next step had no floor. | `DigJob.UP` builds a floor block under the step from the nearest solid neighbour (`builtFloor` metric). |
| `collect_drops` jumped every tick when items were unreachable (looked like "jumping in place"). | Jump at most once per 10 ticks, give up after 35 stuck ticks. |

The escape used the fixed tower: **64 blocks** (cave y=-11 -> y=53) + **26 blocks** (y=53 -> y=79),
mining the ceilings on the way, then walking off the tree canopy to the ground.

## Environment notes (honest caveats)

1. The world is the shared test world (`Nouveau monde (1)`), not a brand-new save: the run started in
   an untouched forest area `(1548, 75, -470)` with `/clear`, but other test structures from earlier
   rounds exist elsewhere in the same world.
2. The return climbed a pillar instead of walking the tunnels: the stair that was dug down broke into
   a cave and its ladder was unreachable from the cave pocket. Reusing tunnel B worked for every
   other leg (Baritone walked the existing corridors in 2-7 s each).
