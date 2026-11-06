# Ph4 - Issue register (Ph3 findings + user report)

Severity: **P0** blocks everything, **P1** must be fixed before the next test run, **P2** quality.

## Status after Phase 0 (2026-10-31)

| Issue | Status | Where |
|---|---|---|
| P0-1 ghost blocks | **FIXED + verified** - no direct `destroyBlock`, 3-tick confirmation, ghost detector | `PHASE0-REPORT.md` |
| P1-5 tool durability | **FIXED + verified** (`tool_status`, `tool_low`) | idem |
| P1-6 inventory fake drop | **FIXED + verified** (`delete_item`, server-synced, no local edits) | idem |
| P1-8 progress watchdog | **FIXED + verified** (`stalled` in 3.7 s) | idem |
| P1-1 connected scan / no X-ray | `scan_connected` implemented (doors/gates/trapdoors passable), needs the room/door/wall acceptance run | Phase 1 |
| P1-2 ore awareness | ore probe stream + `ore_log` implemented, needs the behind-a-wall acceptance run | Phase 1 |
| P1-7 ender-eye protocol | `eye_throw` implemented (4-tick capture, tracking, pickup), needs the triangulation run | Phase 1 |
| P1-3 / P1-4 stairs | not yet | Phase 2 |
| P1-9 / P1-10 / P2-1 tunnel | P1-9 done in the path jobs (only stuck + real vegetation); others Phase 2 | Phase 2 |
| P1-11 user abort | **done** - MCP server tracks started jobs; a cancelled request (`notifications/cancelled`) or a closed session (SSE stream gone for 2 s) cancels them; new `cancel_active` tool; tested with `test/p1-11-abort.mjs` (`evidence/p1-11-abort.json`) | Phase 4 |
| Test A stronghold | **做不到 (not feasible) in this world** - the eyes of ender place the stronghold at (0,0), but it is under the ocean floor (water y62->34, seabed y32 at (0,0)); reaching it needs underwater work this tool set does not have yet. See `TESTA-RESULT.md` | Ph4 end |


## P0-1 Ghost blocks after digging (invisible collider)

**Symptom (user)**: after auto-pathing digs a block, the block looks like air, but the player (also when
the human controls the character) gets stuck on an invisible object where the block used to be.

**Evidence**: happened after long tunnel/stair runs that used `WalkToJob`/`PathJob` vegetation clearing
(`mc.gameMode.destroyBlock(...)`) and after `BlockMiner` runs. Restarting the client clears it.

**Root-cause hypothesis**: the client removes the block locally (destroyBlock / continueDestroyBlock)
without a server-confirmed update, or a client-side destroy is issued for a block the server keeps
(rejected). Client and server then disagree: the client renders air while collision still comes from the
server's block, or vice versa.

**Proposed fix**:
1. Never call `mc.gameMode.destroyBlock` for solid blocks. Only plants (hardness 0, no collision).
2. Treat a block as broken only when the **world block state changes** and stays changed for >= 3 ticks;
   if it reverts, mark it as a ghost and re-sync the chunk (e.g. `level.getChunk(...).setBlockState` is a
   client-side hack - prefer asking the server: place a block adjacent / use `ServerboundPlayerActionPacket`
   STOP_DESTROY_BLOCK, or simply re-enter the chunk by moving).
3. Add a **ghost detector**: after each job, sample the 3x3 blocks under the player; if
   `state.isAir()` but the player is `horizontalCollision` with no visible block, report
   `ghost_block` with coordinates and freeze the job.
4. Only path-fit mining through `BlockMiner` (never raw destroyBlock) for anything with hardness > 0.

## P1-1 `scan_area` is an X-ray and misleads the agent

**Request (user)**: `scan_area` must not see through walls. It should only report the **surface blocks of
the connected space** around the player (radius-bounded). **Passable blocks do not cut the region:** two
rooms joined by a door / fence gate / trapdoor count as one connected space, and the agent may see the
surface blocks of **both** rooms (that is the point - you should know the whole room you can walk into).
Solid walls still hide what is behind them. For ore detection: after each dug block, probe its
**6 neighbours** and report them to a subagent; the subagent reports ore coordinates; the agent paths to a
cell **next to the ore** and performs one `mine_block`.

**Proposed design**:
- `scan_connected {radius}`: BFS flood fill from the player's cell through **passable** cells - air,
  plants, water, **doors, fence gates, trapdoors** (open or closed: they are traversable) - and stop at
  solid blocks. Return (a) the reachable air cells and (b) every solid block that touches a reachable
  cell (the surface of the connected space, i.e. all rooms reachable through doors). Never return a block
  that is only reachable through a solid wall. The radius may be raised (e.g. 48-64) because the result
  is bounded by the connected space, not by the sphere.
- Ore probe (event-driven): every `BlockMiner` completion reports the 6 neighbours of the broken block
  (`ore_probe` event: position, id, whether it is an ore). A lightweight subagent consumes the stream,
  filters `_ore` ids and returns coordinates.
- Keep the old `scan_area` only for debug/creative use, and mark it `xray: true` in the schema so the
  agent cannot use it accidentally.

## P1-2 No ore awareness while tunnelling

The branch/stair jobs report `ores`, but nothing watches it in real time and the drops are not collected.
**Fix**: see P1-1 (event stream) + auto-`collect_drops` after each ore + a `ore_found` job event.

## P1-3 Staircase-up is broken ("could not climb onto the step")

`DigJob.Mode.UP` mines the step and hops, but the hop lands short or the plan has no valid step.
**Fix**: rework the climb into a deterministic loop:
1. stand exactly at the step face (dist <= 0.42),
2. clear `front+1` and `front+2`,
3. single `jumpFromGround()` + forward delta for 8 ticks,
4. verify `y == startY + 1` and `onGround`; else retry <= 3 then report.
Also add a `step_up` primitive (jump onto a 1-block rise) reused by `walk_to`.

## P1-4 Descent quality / "jump two blocks, dig one" motion

`DigJob.Mode.STAIR` mines 3 cells per step (front y-1, y, y+1) and hops with a 0.14-0.26 drift; visually
this is "hop, dig, hop, dig". It also stalls on caves/water.
**Fix**: rewrite the stair as a clean 1-step state machine (dig -> stand -> drop one block -> repeat),
with an explicit cave/water check and dynamic re-planning (new endpoint when blocked). Replace
`Mode.DESCEND`/`down` entirely with it.

## P1-5 Tool durability policy

`tooWorn` now only skips truly broken tools, but there is no decision logic.
**Fix**: before mining a block whose hardness > threshold, check the tool's remaining durability:
- remaining < 10 -> report `tool_low` and stop the job (agent decides: use it up, swap, or craft/repair);
- expose `tool_status` (id, damage, max, material tier) in `get_state`.

## P1-6 Inventory: remove fake-drop logic, add a sync delete

The old `drop_junk` mutated stacks client-side -> fake empty slots. `dump_junk` (drop + run) is
server-synced but leaves entities on the ground.
**Request**: remove the drop logic and provide a **synchronised delete**.
**Options** (pick one, document it):
1. Server-synced THROW click (menu slot, button 1) repeated with a short run-away - what `dump_junk`
   does now; rename to `drop_stack`, keep for gameplay.
2. True delete: place a chest, move the stack into it, break the chest (or leave the chest). This is
   server-synced and leaves no loose items.
3. Container-based delete: put items into a container and destroy it with lava/fire.
Never edit `ItemStack` directly again.

## P1-7 Ender-eye protocol

**Request**: do not throw repeatedly from the same spot; after throwing, pick the dropped eye back up
(unless it broke).
**Fix**:
1. Throw once, capture the projectile **within the first 4 ticks** (`entity_query` every 50 ms for 300 ms).
2. Track it until it despawns or lands; record the landing position (item entity if the eye survived).
3. Walk >= 40 blocks perpendicular, throw again, **triangulate** the two bearings.
4. When close (eye flight < 6 blocks and steep descent), dig down at the intersection.
5. Pick up the dropped eye item before moving on; count eyes and abort the protocol if < 6 remain.

## P1-8 Pathing needs a progress watchdog

**Request**: while a path job runs, if the position changes < 1 block in 3 s, report it; the agent decides
stuck vs slow.
**Fix**: every path/walk job emits a `progress` metric and fails with `stalled` after 60 ticks without
>= 1 block of movement (configurable). The MCP layer forwards it as an event so the agent can react
immediately instead of waiting for job completion.

## P1-9 Pathing destroys plants along the whole route

`WalkToJob`/`PathJob` clear plants on every tick they collide. Baritone paths through grass and the
clear function fires for plants that do not actually block movement.
**Fix**: only clear a plant if **all** of these hold: it occupies the cell the player is moving into,
its collision shape is not empty OR it is a berry bush (bush slows movement), and the player has not
moved for >= 10 ticks. Otherwise leave it.

## P1-10 Break/place must be optional during pathing

**Request**: some areas must not be modified by the pathfinder; breaking/placing should be a toggle that
can be switched mid-path.
**Fix**: add `allowBreak` / `allowPlace` flags to `path_to` / `walk_to` / dig jobs (default false for
`walk_to`, true for dig jobs), persist them in the job and expose `set_pathing_policy {break, place}`.

## P1-11 User abort must cancel the current MCP task (plugin-side)

**Request**: when the user ends the assistant's output, MCP should cancel the running task and stay
connected (implemented by the plugin).
**Fix (done, Phase 4)**: the MCP server keeps the session open and tracks every job it started
(`BridgeStore.activeJobs`). A cancelled request (`extra.signal` / `notifications/cancelled`) or a
closed session (SSE stream gone for 2 s, since some clients never send DELETE) calls
`cancelActiveJobs()` and cancels them through the bridge (`/jobs/<id>/cancel`). `stop_all` also uses
that path and reports `cancelledViaMcp`; `cancel_active` is available for an explicit cleanup.
Evidence: `evidence/p1-11-abort.json`, `mcp-server/test/p1-11-abort.mjs`, report
`docs/Ph4/PHASE4-REPORT.md`.

## P2-1 Dig-pathing through solid rock (A -> B tunnel, returnable)

**Request**: given A and B in solid rock, dig a tunnel from A to B and allow the player to walk back from
B to A (3D pathing). This replaces the descent logic. The process must be dynamic: when blocked, pick a
new endpoint.
**Fix**: implement `dig_tunnel {from, to, size 1x2}`:
1. A* over a coarse 3D grid (solid = diggable, air = free) with a cost on elevation change,
2. dig the corridor with `BlockMiner` (2 high), collect drops, place torches every 8 blocks,
3. verify walkability by walking back to the start at the end,
4. on repeated `blocked`/water, re-route to a new endpoint.

## P2-2 Duplicate clients / modpack freeze

- The launcher sometimes starts two `java.exe` for the same profile; the world then behaves oddly.
  `clean-start.ps1` kills old processes first - keep using it.
- After very long sessions the client freezes movement (walk_to moves ~1 block / 80 ticks) while the
  bridge still answers. Restarting the client fixes it. Treat "movement much slower than 0.2 blocks/tick"
  as a client fault and restart (not a logic failure).

## P2-3 Watchdog / relaunch

Fixed: `clean-start.ps1` does not start the watchdog unless `-Watchdog`; a `runtime/watchdog.stop`
file stops it; `stop-all.ps1` kills node + java and writes the marker. Keep this behaviour.

## P2-4 Tooling gotchas (for the next agent)

- Java files must be **UTF-8 without BOM** (`javac` fails on `\ufeff`).
- `block_query` takes at most **64 positions** per call; `scan_area` `maxBlocks` <= 6000.
- `mine_block.positions` must be arrays of exactly 3 ints.
- `take_screenshot` paths; screenshots are 2560x1600.
- The world save is `...\versions\假期增援计划\saves\Nouveau monde (1)` ("Smoke Test").
