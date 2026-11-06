# Ph4 Phase 0 - stabilisation report

Date: 2026-10-31 (session 1)
Build: `mcpbridge-0.1.0.jar`, 77 classes, all Java sources UTF-8 without BOM.

## What was changed

| File | Change |
|---|---|
| `Ghosts.java` (new) | Ghost/desync detector: `position_jump` (server pushed the player back > 1 block/tick), `mined_revert` (a broken block reappeared), `invisible_wall` (blocked while the 3x3x3 bubble is air and no entity touches the player). Events go to the job log, `get_state`, the `ghosts` tool and `wait_events`. |
| `ProgressWatch.java` (new) | Movement watchdog: < 1 block in 60 ticks (3 s) without a completed work unit -> `job_stalled` event + job fails with `stalled`. |
| `OreProbe.java` (new) | After every broken block the 6 neighbours are probed; ores are recorded and pushed as `ore_found` events; the last probe is a job metric; `ore_log` exposes the stream. |
| `DeleteItemJob.java` (new) | Server-synced delete: menu SWAP to a hotbar slot, `DROP_ALL_ITEMS`, wait for the server-confirmed empty slot, run away; reports `repicked` if the items were picked up again. No client-side `ItemStack` edit anywhere. |
| `BlockMiner.java` | Only `startDestroyBlock`/`continueDestroyBlock` (prediction-tracked). A block counts as broken only after the world state changed **and stayed changed for 3 ticks**; a revert fails with `ghost_block`. `tool_low` is checked **before** the first hit. |
| `WalkToJob.java` | Plant clearing no longer calls `gameMode.destroyBlock` (that call sends **no packet** - pure client-side removal, the root cause of the invisible colliders). Plants are cleared through `BlockMiner`, only when the walker is stuck >= 8 ticks and only for real vegetation (never `*_block` -> `grass_block` is protected). Watchdog + ghost check added. |
| `PathJob.java` | Same: no `destroyBlock`, no more clearing every cell containing "grass"/"grass_block"; clear only when stuck >= 10 ticks, then restart Baritone. Watchdog + ghost check added. |
| `DigJob.java` | Per-block ore probe, ores metric, watchdog + ghost check in the walking phase. |
| `MineExecutor.java`, `BranchMineJob.java` | Per-block ore probe (`lastProbe`), ores metric. |
| `GameState.java` | `toolStatus` in `get_state`: main hand + every damageable stack with slot, type, tier, damage/maxDamage/remaining/low, `bestPickaxe`, `lowTools`. `ghostEvents`/`ghostCount`. |
| `ScanSurface.java` | Doors / fence gates / trapdoors no longer cut the connected region (P1-1 contract). |
| `Tools.java`, `server.js` | New tools: `delete_item`, `ore_log`, `ghosts`, `scan_connected`, `eye_throw`; `scan_area` documented as debug/X-ray (`xray` field). `get_state` compact now carries `toolStatus` and the ghost counters. |

### Why `mc.gameMode.destroyBlock` was the ghost block

`javap -c` on `net.minecraft.client.multiplayer.MultiPlayerGameMode` (1.21.1, `vendor/cp/minecraft-srg.jar`):
`destroyBlock(BlockPos)` only calls `Level.setBlock(pos, fluid, 11)` locally and `Block.destroy(...)`. The
packets are built in the `startPrediction` lambdas (`lambda$continueDestroyBlock$2/$3`), i.e. only the
vanilla mining loop is server-tracked. A bare `destroyBlock(...)` therefore removes the block **client
side only**: the client renders air, the server keeps the collider, refuses the player's movement and
pushes them back - "stuck on an invisible object".

## Acceptance results

| Test | Result | Evidence |
|---|---|---|
| P0-1 ghost-free tunnel (40-block stone corridor, walk back/forth 3x) | **PASS** - 6/6 legs done, 157-167 ticks each, `ghostCount` 0, no `stalled`/`ghost_block` | `evidence/ph4-p0-ghost-walk.json`, `evidence/ph4-p0-tunnel.json` |
| P0-1 plant clearing through the prediction-tracked miner | **PASS** - leaves at `1420,61,-518` cleared by `BlockMiner`, walk finished in 98 ticks, cells are air | `evidence/ph4-p0-plant-clear.json` |
| P1-8 watchdog (walk into a wall) | **PASS** - failed with `stalled` after 3.74 s; `bestDistanceInWindow` 0.2 in 60 ticks | `evidence/ph4-p0-stalled.json` |
| P1-5 tool_low (iron ore + iron pickaxe with 5 uses) | **PASS** - failed in 450 ms with `tool_low`, block still `minecraft:iron_ore` afterwards | `evidence/ph4-p0-tool-low.json` |
| P1-6 sync delete (64 cobblestone) | **PASS** - job done, slot freed, `deleted` 64, `stable` across two snapshots 2 s apart (`usedSlots` 13 -> 12) | `evidence/ph4-p0-delete-item.json` |

Setup commands used for these tests: `/fill` the stone box, `/tp`, `/give`, `/clear`, `/setblock`. The
tests themselves ran without commands.

## Known limitations / follow-ups

1. `Ghosts.position_jump` also fires for `/tp` (setup) and respawns. Events > 8 blocks are tagged
   `teleport: true`; the test runs above only contain those.
2. The `invisible_wall` signature needs the client bubble to be all air; a desync that keeps a solid
   block client-side is caught by `mined_revert` instead. Rubber-banding inside a tunnel is caught by
   `position_jump`, which is the strong signal.
3. `scan_area` is still an X-ray and remains available - only for debug/creative; gameplay uses
   `scan_connected`.

## Next

Phase 1 validation (`scan_connected` two-room/door/wall test, ore probe behind a wall, `eye_throw`),
then Phase 2 (stair climb, `dig_tunnel`) and Phase 3 (CubeTest, TestB v2).
